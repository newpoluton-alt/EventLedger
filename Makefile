.DEFAULT_GOAL := help

.PHONY: help up down clean logs ps test check image compose-check terraform-fmt terraform-validate

help: ## Show available targets
	@awk 'BEGIN {FS = ":.*## "; printf "Usage: make <target>\n\n"} /^[a-zA-Z_-]+:.*## / {printf "  %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

up: ## Build and start the complete local stack
	docker compose up --build -d

down: ## Stop the local stack without deleting data
	docker compose down

clean: ## Stop the local stack and delete its named volumes
	docker compose down --volumes --remove-orphans

logs: ## Follow application and infrastructure logs
	docker compose logs --follow --tail=200

ps: ## Show local service health
	docker compose ps

test: ## Run the unit and integration tests
	./gradlew test

check: ## Run formatting, tests, and static verification
	./gradlew check

image: ## Build the production container image
	docker build --target runtime --tag eventledger:local .

compose-check: ## Validate the Compose model
	docker compose config --quiet

terraform-fmt: ## Format the AWS Terraform configuration
	terraform -chdir=infra/terraform fmt -recursive

terraform-validate: ## Initialize and validate Terraform without a remote backend
	terraform -chdir=infra/terraform init -backend=false
	terraform -chdir=infra/terraform validate
