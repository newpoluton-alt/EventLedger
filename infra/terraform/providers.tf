provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Application = var.project_name
      Environment = var.environment
      ManagedBy   = "Terraform"
      Repository  = "EventLedger"
    }
  }
}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}
