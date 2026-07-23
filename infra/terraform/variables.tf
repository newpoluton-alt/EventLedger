variable "project_name" {
  description = "Short name used to prefix AWS resources."
  type        = string
  default     = "eventledger"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,22}$", var.project_name))
    error_message = "project_name must be 3-23 lowercase alphanumeric or hyphen characters."
  }
}

variable "environment" {
  description = "Deployment environment name."
  type        = string
  default     = "prod"

  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment must be staging or prod."
  }
}

variable "aws_region" {
  description = "AWS region for the deployment."
  type        = string
  default     = "eu-central-1"
}

variable "availability_zones" {
  description = "Three distinct availability zones used for the highly available topology."
  type        = list(string)
  default     = ["eu-central-1a", "eu-central-1b", "eu-central-1c"]

  validation {
    condition     = length(var.availability_zones) == 3 && length(distinct(var.availability_zones)) == 3
    error_message = "Exactly three distinct availability zones are required."
  }
}

variable "vpc_cidr" {
  description = "CIDR block for the EventLedger VPC."
  type        = string
  default     = "10.42.0.0/16"
}

variable "certificate_arn" {
  description = "ACM certificate ARN used by the public HTTPS listener."
  type        = string
}

variable "domain_name" {
  description = "Public DNS name covered by certificate_arn."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$", var.domain_name))
    error_message = "domain_name must be a valid lowercase DNS hostname."
  }
}

variable "hosted_zone_id" {
  description = "Route53 public hosted-zone ID for domain_name."
  type        = string
}

variable "image_tag" {
  description = "Immutable ECR image tag deployed by ECS."
  type        = string
  default     = "sha-0000000"

  validation {
    condition     = can(regex("^sha-[0-9a-f]{7,64}$", var.image_tag))
    error_message = "image_tag must be a sha- tag with 7-64 lowercase hexadecimal characters."
  }
}

variable "desired_count" {
  description = "Normal ECS task count; zero is reserved for the initial database-identity bootstrap."
  type        = number
  default     = 3

  validation {
    condition     = var.desired_count == 0 || var.desired_count >= 2
    error_message = "desired_count must be zero for bootstrap or at least two for availability."
  }
}

variable "db_name" {
  description = "PostgreSQL database name."
  type        = string
  default     = "eventledger"
}

variable "db_username" {
  description = "PostgreSQL administrator username. The password is managed by RDS."
  type        = string
  default     = "eventledger_admin"
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.medium"
}

variable "db_allocated_storage" {
  description = "Initial RDS gp3 storage in GiB."
  type        = number
  default     = 100
}

variable "db_max_allocated_storage" {
  description = "Maximum storage autoscaling limit in GiB."
  type        = number
  default     = 500
}

variable "db_final_snapshot_identifier" {
  description = "Unique final snapshot name for an approved RDS replacement or deletion."
  type        = string
}

variable "db_deletion_protection" {
  description = "Protect the database from accidental deletion."
  type        = bool
  default     = true
}

variable "database_app_secret_arn" {
  description = "Secrets Manager ARN containing username/password for a least-privilege EventLedger schema owner."
  type        = string
}

variable "database_app_kms_key_arn" {
  description = "Customer-managed KMS key ARN encrypting the application database secret."
  type        = string
}

variable "kafka_version" {
  description = "Amazon MSK Kafka version."
  type        = string
  default     = "3.9.x"
}

variable "kafka_instance_type" {
  description = "Amazon MSK broker instance type."
  type        = string
  default     = "kafka.m5.large"
}

variable "kafka_volume_size" {
  description = "Initial EBS volume size per MSK broker in GiB."
  type        = number
  default     = 250
}

variable "msk_scram_secret_arn" {
  description = "ARN of a Secrets Manager secret named AmazonMSK_* with username and password JSON keys."
  type        = string
}

variable "msk_scram_kms_key_arn" {
  description = "Customer-managed KMS key ARN encrypting the MSK SCRAM secret."
  type        = string
}

variable "kafka_username" {
  description = "Kafka principal in the associated SCRAM secret and explicit bootstrap ACLs."
  type        = string
  default     = "eventledger"
}

variable "kafka_acl_bootstrap_complete" {
  description = "Set true only after explicit ACLs for kafka_username exist; this changes the broker default to deny."
  type        = bool
  default     = false
}

variable "redis_node_type" {
  description = "ElastiCache Redis node type."
  type        = string
  default     = "cache.t4g.small"
}

variable "redis_auth_token" {
  description = "Current Redis AUTH token. Supply through TF_VAR_redis_auth_token and use encrypted remote state."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[A-Za-z0-9!&#$^<>-]{32,128}$", var.redis_auth_token))
    error_message = "redis_auth_token must be 32-128 characters using alphanumerics or !&#$^<>-."
  }
}

variable "redis_next_auth_token" {
  description = "Optional next Redis AUTH token used only during the ROTATE phase of a two-phase credential change."
  type        = string
  sensitive   = true
  default     = null
  nullable    = true

  validation {
    condition = (
      var.redis_next_auth_token == null ||
      can(regex("^[A-Za-z0-9!&#$^<>-]{32,128}$", var.redis_next_auth_token))
    )
    error_message = "redis_next_auth_token must be null or 32-128 characters using alphanumerics or !&#$^<>-."
  }
}

variable "redis_final_snapshot_identifier" {
  description = "Unique final snapshot name for an approved Redis replacement or deletion."
  type        = string
}

variable "alarm_notification_arns" {
  description = "SNS topic ARNs notified by CloudWatch alarms."
  type        = list(string)

  validation {
    condition     = length(var.alarm_notification_arns) > 0
    error_message = "Provide at least one SNS topic ARN so production alarms are actionable."
  }
}
