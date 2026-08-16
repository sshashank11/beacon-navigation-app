.PHONY: up down db api pipeline web osm check check-api check-pipeline check-web

up:
	docker compose up -d

down:
	docker compose down

db:
	docker compose exec postgres psql -U beacon -d beacon

api:
	cd api && ./gradlew bootRun

pipeline:
	cd pipeline && uv run python -m beacon_pipeline

web:
	cd web && npm run dev

osm:
	cd pipeline && uv run beacon-pipeline prepare-osm

check: check-api check-pipeline check-web

check-api:
	cd api && ./gradlew test

check-pipeline:
	cd pipeline && uv run --with pytest pytest

check-web:
	cd web && npm run lint
	cd web && npm run build
