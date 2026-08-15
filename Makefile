.PHONY: up down db api pipeline web

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
