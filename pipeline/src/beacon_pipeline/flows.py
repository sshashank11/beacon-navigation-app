from __future__ import annotations

from prefect import flow, serve, task
from prefect.client.schemas.schedules import CronSchedule

from beacon_pipeline.config import load_settings
from beacon_pipeline.ingest.airnow import ingest_airnow
from beacon_pipeline.ingest.dob_permits import ingest_dob_permits
from beacon_pipeline.ingest.nws import ingest_nws
from beacon_pipeline.ingest.openaq import ingest_openaq
from beacon_pipeline.ingest.pollen import ingest_pollen
from beacon_pipeline.model.hazard_fields import build_hazard_fields, refresh_construction_scores


@task(retries=2, retry_delay_seconds=30)
def run_job(job_name: str) -> int:
    settings = load_settings()
    jobs = {
        "openaq": lambda: ingest_openaq(settings),
        "airnow": lambda: ingest_airnow(settings),
        "nws": lambda: ingest_nws(settings),
        "pollen": lambda: ingest_pollen(settings),
        "dob-permits": lambda: ingest_dob_permits(settings),
        "construction-scores": lambda: refresh_construction_scores(settings.database_url),
        "hazard-fields": lambda: build_hazard_fields(settings),
    }
    return jobs[job_name]()


@flow(name="ingest-openaq")
def openaq_flow() -> int:
    return run_job("openaq")


@flow(name="ingest-airnow")
def airnow_flow() -> int:
    return run_job("airnow")


@flow(name="ingest-nws")
def nws_flow() -> int:
    return run_job("nws")


@flow(name="ingest-pollen")
def pollen_flow() -> int:
    return run_job("pollen")


@flow(name="refresh-construction")
def construction_flow() -> int:
    permits = run_job("dob-permits")
    run_job("construction-scores")
    return permits


@flow(name="build-hazard-fields")
def hazard_fields_flow() -> int:
    return run_job("hazard-fields")


def serve_flows() -> None:
    timezone = "America/New_York"
    serve(
        openaq_flow.to_deployment(
            name="every-15-minutes",
            schedule=CronSchedule(cron="*/15 * * * *", timezone=timezone),
        ),
        airnow_flow.to_deployment(
            name="hourly",
            schedule=CronSchedule(cron="5 * * * *", timezone=timezone),
        ),
        nws_flow.to_deployment(
            name="hourly",
            schedule=CronSchedule(cron="10 * * * *", timezone=timezone),
        ),
        pollen_flow.to_deployment(
            name="daily",
            schedule=CronSchedule(cron="0 4 * * *", timezone=timezone),
        ),
        construction_flow.to_deployment(
            name="nightly",
            schedule=CronSchedule(cron="0 2 * * *", timezone=timezone),
        ),
        hazard_fields_flow.to_deployment(
            name="every-15-minutes",
            schedule=CronSchedule(cron="12,27,42,57 * * * *", timezone=timezone),
        ),
    )
