# OSPChat Shared — Makefile

GRADLE  ?= gradle
VERSION    := $(shell cat VERSION)
KTLINT  ?= ktlint

.DEFAULT_GOAL := help
.PHONY: help build test ktlint ktlint-format publish-local clean

help: ## Show this help
	@echo "OSPChat Shared (Kotlin Multiplatform) — common library"
	@echo ""
	@echo "Targets:"
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  %-18s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

build: ## Compile both targets + run tests
	$(GRADLE) build

test: ## Run the desktop tests (covers most of the shared logic)
	$(GRADLE) desktopTest

ktlint: ## Run ktlint over src/. Non-zero exit on any violation.
	$(KTLINT) --relative 'src/**/*.kt'

ktlint-format: ## Run ktlint --format to auto-fix what it can.
	$(KTLINT) --format --relative 'src/**/*.kt'

publish-local: ## Publish to ~/.m2/repository so ospchat-desktop can consume.
	$(GRADLE) publishToMavenLocal

clean: ## gradle clean
	$(GRADLE) clean
