from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


NYC_BBOX = (-74.25909, 40.477399, -73.700272, 40.917577)


@dataclass(frozen=True)
class Settings:
    database_url: str
    redis_url: str
    openaq_api_key: str | None
    airnow_api_key: str | None
    google_maps_key: str | None
    nws_user_agent: str
    pollen_daily_call_budget: int
    nyccas_raster_dir: Path
    elevation_raster_dir: Path
    nyc_open_data_app_token: str | None


def load_settings() -> Settings:
    return Settings(
        database_url=_pipeline_database_url(),
        redis_url=os.getenv("REDIS_URL", "redis://localhost:6379"),
        openaq_api_key=os.getenv("OPENAQ_API_KEY") or None,
        airnow_api_key=os.getenv("AIRNOW_API_KEY") or None,
        google_maps_key=os.getenv("GOOGLE_MAPS_KEY") or None,
        nws_user_agent=os.getenv(
            "NWS_USER_AGENT",
            "BeaconNavigationApp/0.1 (contact@example.com)",
        ),
        pollen_daily_call_budget=int(os.getenv("POLLEN_DAILY_CALL_BUDGET", "60")),
        nyccas_raster_dir=Path(os.getenv("NYCCAS_RASTER_DIR", "../data/nyccas")),
        elevation_raster_dir=Path(
            os.getenv("ELEVATION_RASTER_DIR", "../data/elevation")
        ),
        nyc_open_data_app_token=os.getenv("NYC_OPEN_DATA_APP_TOKEN") or None,
    )


def _pipeline_database_url() -> str:
    explicit = os.getenv("PIPELINE_DATABASE_URL")
    if explicit:
        return explicit

    jdbc = os.getenv("DATABASE_URL")
    if jdbc and jdbc.startswith("jdbc:postgresql://"):
        return "postgresql://" + jdbc.removeprefix("jdbc:postgresql://")

    user = os.getenv("POSTGRES_USER", "beacon")
    password = os.getenv("POSTGRES_PASSWORD", "beacon")
    database = os.getenv("POSTGRES_DB", "beacon")
    host = os.getenv("POSTGRES_HOST", "localhost")
    port = os.getenv("POSTGRES_PORT", "5432")
    return f"postgresql://{user}:{password}@{host}:{port}/{database}"
