# FleetPulse — Makefile
# Usage:
#   make up         → start core infra (Kafka, Postgres, ClickHouse, Redis, Mongo, MinIO)
#   make up-app     → start infra + application services
#   make up-sim     → start infra + app + simulator (1K vehicles)
#   make up-obs     → start observability stack (Prometheus + Grafana)
#   make down       → stop everything
#   make seed       → load 100K-vehicle seed data into Postgres
#   make test       → run unit + integration tests
#   make lint       → run all linters
#   make load       → run k6 load test
#   make chaos      → run chaos scenarios

.PHONY: up up-app up-sim up-obs down seed test lint load chaos logs help

COMPOSE = docker compose
PROFILES_CORE = --profile core
PROFILES_APP  = --profile core --profile app
PROFILES_SIM  = --profile core --profile app --profile sim
PROFILES_OBS  = --profile obs

## ============================================================
## Infrastructure
## ============================================================

up: ## Start core infrastructure only
	@echo "🚀 Starting FleetPulse core infrastructure..."
	$(COMPOSE) $(PROFILES_CORE) up -d --wait
	@echo ""
	@echo "✅ Core infrastructure ready:"
	@echo "   Kafka:           localhost:9092"
	@echo "   Schema Registry: http://localhost:8081"
	@echo "   PostgreSQL:      localhost:5432 (fleetpulse/fleetpulse_dev)"
	@echo "   ClickHouse:      http://localhost:8123"
	@echo "   Redis:           localhost:6379"
	@echo "   MongoDB:         localhost:27017"
	@echo "   MinIO:           http://localhost:9001 (fleetpulse/fleetpulse_dev)"

up-app: ## Start infra + application services
	@echo "🚀 Starting FleetPulse app services..."
	$(COMPOSE) $(PROFILES_APP) up -d --wait
	@echo ""
	@echo "✅ Application ready:"
	@echo "   Gateway:    http://localhost:8080"
	@echo "   Normalizer: http://localhost:8082"
	@echo "   Orderer:    http://localhost:8083"
	@echo "   API:        http://localhost:8000/docs"

up-sim: ## Start everything including simulator (1K vehicles)
	@echo "🚀 Starting FleetPulse with simulator (1K vehicles)..."
	$(COMPOSE) $(PROFILES_SIM) up -d --wait
	@echo ""
	@echo "✅ Simulator running at http://localhost:8090"
	@echo "   Control: GET http://localhost:8090/control/stats"

up-obs: ## Start observability stack
	$(COMPOSE) $(PROFILES_OBS) up -d --wait
	@echo "✅ Observability:"
	@echo "   Prometheus: http://localhost:9090"
	@echo "   Grafana:    http://localhost:3001 (admin/admin)"

down: ## Stop all services
	$(COMPOSE) --profile core --profile app --profile sim --profile obs down

down-clean: ## Stop all services and remove volumes
	$(COMPOSE) --profile core --profile app --profile sim --profile obs down -v

## ============================================================
## Seed data
## ============================================================

seed: ## Load seed data into Postgres (run after 'make up')
	@echo "🌱 Loading seed data..."
	docker exec fp-postgres psql -U fleetpulse -d fleetpulse -f /docker-entrypoint-initdb.d/005_seed.sql
	@echo "✅ Seed data loaded"

seed-python: ## Generate and load seed via Python simulator
	@echo "🌱 Generating 1K-vehicle seed..."
	cd services/simulator && python -m simulator seed --preset dev --seed 42 --output ../../data/seed/
	@echo "✅ Seed generated in data/seed/"

## ============================================================
## Testing
## ============================================================

test: ## Run all tests (unit + integration)
	@echo "🧪 Running Python tests (fpcore + simulator + api)..."
	cd libs/py/fpcore && python -m pytest tests/ -v --tb=short
	cd services/simulator && python -m pytest tests/ -v --tb=short
	cd services/api && python -m pytest tests/ -v --tb=short
	@echo "🧪 Running Java tests (gateway + normalizer + orderer)..."
	cd services/gateway && ./mvnw test -q
	cd services/normalizer && ./mvnw test -q
	cd services/orderer && ./mvnw test -q

test-fpcore: ## Run fpcore algorithm library tests only
	cd libs/py/fpcore && python -m pytest tests/ -v --tb=short --cov=fpcore --cov-report=term-missing

test-java: ## Run all Java service tests
	for svc in gateway normalizer orderer stream-engine telemetry-sink insight-sink; do \
		echo "Testing $$svc..."; \
		cd services/$$svc && ./mvnw test -q && cd ../..; \
	done

test-integration: ## Run integration tests against live datastores
	cd tests && python -m pytest integration/ -v --tb=short

## ============================================================
## Code quality
## ============================================================

lint: ## Run all linters
	@echo "🔍 Linting Python..."
	cd libs/py/fpcore && python -m ruff check . && python -m mypy fpcore/
	cd services/simulator && python -m ruff check . && python -m mypy simulator/
	cd services/api && python -m ruff check . && python -m mypy app/
	@echo "🔍 Linting Java..."
	for svc in gateway normalizer orderer stream-engine telemetry-sink insight-sink; do \
		cd services/$$svc && ./mvnw checkstyle:check -q && cd ../..; \
	done

format: ## Auto-format Python code
	cd libs/py/fpcore && python -m ruff format .
	cd services/simulator && python -m ruff format .
	cd services/api && python -m ruff format .

## ============================================================
## Load testing
## ============================================================

load: ## Run k6 load test (requires up-app + up-sim)
	@echo "📊 Running load test (100 events/s ramp to 1K)..."
	k6 run tests/load/load_test.js

## ============================================================
## Chaos
## ============================================================

chaos: ## Run chaos scenarios
	@echo "💥 Running chaos tests..."
	python tests/chaos/run_chaos.py

## ============================================================
## Logs & monitoring
## ============================================================

logs: ## Stream logs from all services
	$(COMPOSE) --profile core --profile app --profile sim logs -f

logs-kafka: ## Stream Kafka logs
	docker logs fp-kafka -f

logs-api: ## Stream API logs
	docker logs fp-api -f

logs-gateway: ## Stream gateway logs
	docker logs fp-gateway -f

## ============================================================
## Utility
## ============================================================

ps: ## Show running containers
	$(COMPOSE) --profile core --profile app --profile sim ps

topics: ## List Kafka topics
	docker exec fp-kafka kafka-topics --bootstrap-server localhost:9092 --list

kafka-lag: ## Show Kafka consumer group lag
	docker exec fp-kafka kafka-consumer-groups --bootstrap-server localhost:9092 --all-groups --describe

pg-shell: ## Open PostgreSQL shell
	docker exec -it fp-postgres psql -U fleetpulse -d fleetpulse

ch-shell: ## Open ClickHouse shell
	docker exec -it fp-clickhouse clickhouse-client --user fleetpulse --password fleetpulse_dev --database fleetpulse

redis-shell: ## Open Redis shell
	docker exec -it fp-redis redis-cli

mongo-shell: ## Open MongoDB shell
	docker exec -it fp-mongo mongosh --username fleetpulse --password fleetpulse_dev --authenticationDatabase admin fleetpulse

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'
