# SegFormer baseline decision

## Decision

Use `nvidia/segformer-b0-finetuned-cityscapes-1024-1024` without fine-tuning for
the first end-to-end imagery scoring pipeline. Revisit fine-tuning only after
the baseline has produced segment scores that can be evaluated in routing.

## Rationale

The checkpoint's Cityscapes labels directly cover the first route-relevant
signals: vegetation, terrain, sky, road, sidewalk, people, bicycles, and motor
vehicles. That is enough to validate image acquisition, semantic segmentation,
aggregation, freshness, and route integration before adding training work.

Cityscapes has no construction class. The baseline must therefore leave
construction detection unsupported rather than infer it from unrelated labels.
The existing permit-based construction score remains the source of truth while
the imagery pipeline is validated.

## Revisit criteria

Consider fine-tuning on a labeled Mapillary Vistas subset after all of these are
true:

- the 20-image preview review confirms sensible masks for the supported classes;
- image analysis and segment aggregation run successfully end to end;
- routing evaluation shows that construction imagery would add useful signal;
- a representative NYC sample can be labeled and held out for validation.

Any fine-tuned model must improve a construction-specific validation metric
without materially regressing vegetation, sky, road, sidewalk, person, or
vehicle performance. Store its immutable model identifier with every analysis
row so baseline and fine-tuned results can coexist.
