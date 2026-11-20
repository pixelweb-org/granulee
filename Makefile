# Granulee, local development. `make` lists the targets.
#
#   make dev                 backend and frontend dev server; Ctrl+C stops both
#   make clickhouse seed     optional: a local ClickHouse with sample data to point Granulee at
#   make source              registers that ClickHouse in the running Granulee
#
# Needs a JDK (17 or newer runs Gradle; it fetches the JDK 21 toolchain itself if missing) and
# Node 22 or newer. Docker only for the ClickHouse targets.

CH_CONTAINER ?= granulee-test-ch
CH_IMAGE     ?= clickhouse/clickhouse-server:25.8
CH_PORT      ?= 18123
CH_USER      ?= granulee_test
CH_PASSWORD  ?= granulee_test
CH_URL        = http://localhost:$(CH_PORT)

export CH_URL CH_USER CH_PASSWORD

.DEFAULT_GOAL := help
.PHONY: help dev backend frontend clickhouse seed source install test build image stop clean

help: ## List the targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "  make %-11s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

dev: install ## Start backend and frontend; Ctrl+C stops both
	@dev/dev.sh

backend: ## Only the backend, on http://localhost:8080
	cd backend && ./gradlew bootRun

frontend: install ## Only the frontend dev server, on http://localhost:5173
	cd frontend && npm run dev

clickhouse: ## Start a local ClickHouse container to try Granulee on (created on first use)
	@if [ -z "$$(docker ps -aq -f name='^$(CH_CONTAINER)$$')" ]; then \
		echo "Creating $(CH_CONTAINER) from $(CH_IMAGE)"; \
		docker run -d --name $(CH_CONTAINER) -p 127.0.0.1:$(CH_PORT):8123 \
			-e CLICKHOUSE_USER=$(CH_USER) -e CLICKHOUSE_PASSWORD=$(CH_PASSWORD) \
			-e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 $(CH_IMAGE) >/dev/null; \
	elif [ -z "$$(docker ps -q -f name='^$(CH_CONTAINER)$$')" ]; then \
		docker start $(CH_CONTAINER) >/dev/null; \
	fi
	@printf 'Waiting for ClickHouse on $(CH_URL) '; \
	n=0; until curl -sf '$(CH_URL)/ping' >/dev/null; do \
		n=$$((n+1)); if [ $$n -gt 60 ]; then echo ' no answer after 60 s'; exit 1; fi; printf '.'; sleep 1; \
	done; echo ' up'

seed: clickhouse ## Fill that ClickHouse with a sample schema and a query-log workload (drops and recreates it)
	dev/seed.sh

source: ## Register that ClickHouse in the running Granulee, if it has no source yet
	@dev/add-source.sh

install: ## Install the frontend's packages when they are missing or outdated
	@if [ ! -d frontend/node_modules ] || [ frontend/package-lock.json -nt frontend/node_modules ]; then cd frontend && npm install; fi

test: ## Backend tests, then the frontend type check and build
	cd backend && ./gradlew test
	cd frontend && npm run build

build: ## One jar with the frontend inside, in backend/build/libs
	cd backend && ./gradlew bootJar -PwithFrontend

image: ## The container image as granulee:latest in the local Docker daemon
	cd backend && ./gradlew jibDockerBuild -PwithFrontend

stop: ## Stop the ClickHouse container (its data stays)
	-docker stop $(CH_CONTAINER)

clean: ## Remove the ClickHouse container and Granulee's local state (sources, scans, trials)
	-docker rm -f $(CH_CONTAINER)
	rm -r -f ./backend/data
