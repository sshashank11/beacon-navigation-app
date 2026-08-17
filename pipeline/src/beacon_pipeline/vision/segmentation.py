from __future__ import annotations

import json
import re
from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Any, Protocol

import httpx
import numpy as np
import psycopg
from PIL import Image

from beacon_pipeline.http import get_with_retry

MODEL_ID ="nvidia/segformer-b0-finetuned-cityscapes-1024-1024"
DEFAULT_SAMPLE_LIMIT = 20
DOWNLOAD_ATTEMPTS = 4

CITYSCAPES_COLORS = {
    "road": (128, 64, 128),
    "sidewalk": (244, 35, 232),
    "building": (70, 70, 70),
    "wall": (102, 102, 156),
    "fence": (190, 153, 153),
    "pole": (153, 153, 153),
    "traffic light": (250, 170, 30),
    "traffic sign": (220, 220, 0),
    "vegetation": (107, 142, 35),
    "terrain": (152, 251, 152),
    "sky": (70, 130, 180),
    "person": (220, 20, 60),
    "rider": (255, 0, 0),
    "car": (0, 0, 142),
    "truck": (0, 0, 70),
    "bus": (0, 60, 100),
    "train": (0, 80, 100),
    "motorcycle": (0, 0, 230),
    "bicycle": (119, 11, 32),
}


@dataclass(frozen=True)
class ImageReference:
    mapillary_id: str
    thumb_url: str


@dataclass(frozen=True)
class PreviewResult:
    sample_count: int
    output_dir: Path
    manifest_path: Path
    model_id: str
    device: str
    detected_classes: tuple[str, ...]


class SemanticSegmenter(Protocol):
    model_id: str
    device: str
    labels: dict[int, str]

    def predict(self, images: Sequence[Image.Image]) -> list[np.ndarray]: ...


class SegformerSegmenter:
    def __init__(
        self,
        processor: Any,
        model: Any,
        torch_module: Any,
        device: str,
        model_id: str,
    ) -> None:
        self._processor = processor
        self._model = model
        self._torch = torch_module
        self.device = device
        self.model_id = model_id
        self.labels = {
            int(class_id): str(label)
            for class_id, label in model.config.id2label.items()
        }

    @classmethod
    def load(
        cls,
        model_id: str = MODEL_ID,
        device: str | None = None,
    ) -> SegformerSegmenter:
        try:
            import torch
            from transformers import (
                AutoImageProcessor,
                SegformerForSemanticSegmentation,
            )

            selected_device = device or ("cuda" if torch.cuda.is_available() else "cpu")
            processor = AutoImageProcessor.from_pretrained(model_id)
            model = SegformerForSemanticSegmentation.from_pretrained(model_id)
        except ImportError as exc:
            raise RuntimeError(
                "Install the model environment with `uv sync --extra vision`"
            ) from exc

        model.to(selected_device)
        model.eval()
        return cls(processor, model, torch, selected_device, model_id)

    def predict(self, images: Sequence[Image.Image]) -> list[np.ndarray]:
        encoded = self._processor(images=list(images), return_tensors="pt")
        inputs = {name: tensor.to(self.device) for name, tensor in encoded.items()}
        with self._torch.inference_mode():
            outputs = self._model(**inputs)
        target_sizes = [(image.height, image.width) for image in images]
        masks = self._processor.post_process_semantic_segmentation(
            outputs,
            target_sizes=target_sizes,
        )
        return [mask.to("cpu").numpy() for mask in masks]


def preview_segmentations(
    database_url: str,
    output_dir: Path,
    sample_limit: int = DEFAULT_SAMPLE_LIMIT,
    batch_size: int = 4,
    device: str | None = None,
    segmenter: SemanticSegmenter | None = None,
    client: httpx.Client | None = None,
) -> PreviewResult:
    if sample_limit <= 0:
        raise ValueError("sample_limit must be positive")
    if batch_size <= 0:
        raise ValueError("batch_size must be positive")

    references = load_image_references(database_url, sample_limit)
    if len(references) < sample_limit:
        raise RuntimeError(
            f"Need {sample_limit} snapped street images; found {len(references)}. "
            "Run harvest-mapillary first."
        )

    owns_client = client is None
    image_client = client or httpx.Client(
        timeout=30.0,
        follow_redirects=True,
        headers={"User-Agent": "BeaconNavigationApp/0.1"},
    )
    try:
        samples = [
            (reference, download_image(image_client, reference.thumb_url))
            for reference in references
        ]
    finally:
        if owns_client:
            image_client.close()

    active_segmenter = segmenter or SegformerSegmenter.load(device=device)
    masks: list[np.ndarray] = []
    for offset in range(0, len(samples), batch_size):
        batch = samples[offset : offset + batch_size]
        masks.extend(active_segmenter.predict([image for _, image in batch]))

    return render_segmentation_previews(
        samples,
        masks,
        active_segmenter.labels,
        output_dir,
        active_segmenter.model_id,
        active_segmenter.device,
    )


def load_image_references(database_url: str, limit: int) -> list[ImageReference]:
    with psycopg.connect(database_url) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT mapillary_id, thumb_url
            FROM street_image
            WHERE nearest_segment_id IS NOT NULL
            ORDER BY captured_at DESC, mapillary_id
            LIMIT %s
            """,
            (limit,),
        )
        return [ImageReference(str(row[0]), str(row[1])) for row in cursor.fetchall()]


def render_segmentation_previews(
    samples: Sequence[tuple[ImageReference, Image.Image]],
    masks: Sequence[np.ndarray],
    labels: dict[int, str],
    output_dir: Path,
    model_id: str = MODEL_ID,
    device: str = "test",
) -> PreviewResult:
    if len(samples) != len(masks):
        raise ValueError("each sample must have exactly one segmentation mask")

    output_dir.mkdir(parents=True, exist_ok=True)
    detected = Counter[str]()
    image_entries: list[dict[str, object]] = []
    for (reference, image), raw_mask in zip(samples, masks, strict=True):
        mask = np.asarray(raw_mask)
        if mask.ndim != 2 or mask.shape != (image.height, image.width):
            raise ValueError(
                f"mask for {reference.mapillary_id} does not match its image dimensions"
            )

        histogram = class_histogram(mask, labels)
        detected.update(histogram)
        filename = f"{_safe_filename(reference.mapillary_id)}.png"
        _render_side_by_side(image, mask, labels).save(output_dir / filename)
        image_entries.append(
            {
                "mapillary_id": reference.mapillary_id,
                "source_url": reference.thumb_url,
                "preview_file": filename,
                "class_fractions": histogram,
            }
        )

    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(
            {
                "model_id": model_id,
                "device": device,
                "sample_count": len(samples),
                "detected_classes": sorted(detected),
                "images": image_entries,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    return PreviewResult(
        sample_count=len(samples),
        output_dir=output_dir,
        manifest_path=manifest_path,
        model_id=model_id,
        device=device,
        detected_classes=tuple(sorted(detected)),
    )


def download_image(client: httpx.Client, url: str) -> Image.Image:
    response = get_with_retry(client, url, attempts=DOWNLOAD_ATTEMPTS)
    response.raise_for_status()
    with Image.open(BytesIO(response.content)) as source:
        return source.convert("RGB")


def class_histogram(mask: np.ndarray, labels: dict[int, str]) -> dict[str, float]:
    class_ids, counts = np.unique(mask, return_counts=True)
    pixel_count = int(mask.size)
    return {
        labels.get(int(class_id), f"class_{int(class_id)}"): round(
            int(count) / pixel_count,
            6,
        )
        for class_id, count in zip(class_ids, counts, strict=True)
    }


def _render_side_by_side(
    image: Image.Image,
    mask: np.ndarray,
    labels: dict[int, str],
) -> Image.Image:
    colors = np.zeros((image.height, image.width, 3), dtype=np.uint8)
    for class_id in np.unique(mask):
        label = labels.get(int(class_id), f"class_{int(class_id)}")
        colors[mask == class_id] = _class_color(label, int(class_id))

    preview = Image.new("RGB", (image.width * 2, image.height))
    preview.paste(image.convert("RGB"), (0, 0))
    preview.paste(Image.fromarray(colors, mode="RGB"), (image.width, 0))
    return preview


def _class_color(label: str, class_id: int) -> tuple[int, int, int]:
    if label in CITYSCAPES_COLORS:
        return CITYSCAPES_COLORS[label]
    return (
        (class_id * 67 + 31) % 256,
        (class_id * 97 + 59) % 256,
        (class_id * 131 + 83) % 256,
    )


def _safe_filename(mapillary_id: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]", "_", mapillary_id)
