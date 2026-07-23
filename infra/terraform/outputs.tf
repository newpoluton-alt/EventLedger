output "application_url" {
  description = "Public HTTPS endpoint covered by the configured ACM certificate."
  value       = "https://${trimsuffix(aws_route53_record.application.fqdn, ".")}"
}

output "load_balancer_dns_name" {
  description = "DNS name of the public application load balancer."
  value       = aws_lb.this.dns_name
}

output "ecr_repository_url" {
  description = "ECR repository accepting immutable EventLedger images."
  value       = aws_ecr_repository.application.repository_url
}

output "adot_ecr_repository_url" {
  description = "Private ECR repository for the pinned ADOT sidecar mirror."
  value       = aws_ecr_repository.adot.repository_url
}

output "ecs_cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "ecs_service_name" {
  description = "ECS service name."
  value       = aws_ecs_service.application.name
}

output "application_log_group" {
  description = "CloudWatch log group for application logs."
  value       = aws_cloudwatch_log_group.application.name
}

output "api_key_secret_arn" {
  description = "Secrets Manager ARN holding the API key; the secret value is not exposed."
  value       = aws_secretsmanager_secret.api_key.arn
}

output "database_instance_id" {
  description = "RDS instance identifier used by recovery runbooks."
  value       = aws_db_instance.this.identifier
}

output "database_master_secret_arn" {
  description = "RDS-managed administrator secret ARN for the audited database bootstrap path."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "database_endpoint" {
  description = "Private PostgreSQL endpoint."
  value       = aws_db_instance.this.endpoint
}

output "application_subnet_ids" {
  description = "Private subnet IDs available to approved one-off application or administration tasks."
  value       = aws_subnet.application[*].id
}

output "application_security_group_id" {
  description = "Security group ID that permits the application path to stateful dependencies."
  value       = aws_security_group.application.id
}

output "kafka_bootstrap_brokers" {
  description = "Private SASL/SCRAM bootstrap brokers."
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_scram
}

output "kafka_cluster_arn" {
  description = "MSK cluster ARN used by recovery runbooks."
  value       = aws_msk_cluster.this.arn
}

output "redis_primary_endpoint" {
  description = "Private encrypted Redis primary endpoint."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}
