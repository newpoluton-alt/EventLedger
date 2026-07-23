locals {
  redis_runtime_auth_token = coalesce(var.redis_next_auth_token, var.redis_auth_token)
}

resource "aws_elasticache_subnet_group" "this" {
  name       = "${local.name}-redis"
  subnet_ids = aws_subnet.data[*].id
}

resource "aws_elasticache_parameter_group" "this" {
  name   = "${local.name}-redis7"
  family = "redis7"

  parameter {
    name  = "maxmemory-policy"
    value = "volatile-lru"
  }
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${local.name}-redis"
  description          = "EventLedger idempotency and response cache"

  engine               = "redis"
  engine_version       = "7.1"
  node_type            = var.redis_node_type
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.this.name
  subnet_group_name    = aws_elasticache_subnet_group.this.name
  security_group_ids   = [aws_security_group.redis.id]

  num_cache_clusters         = 3
  automatic_failover_enabled = true
  multi_az_enabled           = true

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = local.redis_runtime_auth_token
  auth_token_update_strategy = var.redis_next_auth_token == null ? "SET" : "ROTATE"

  snapshot_retention_limit   = 7
  snapshot_window            = "01:00-02:00"
  maintenance_window         = "sun:04:30-sun:05:30"
  auto_minor_version_upgrade = true
  final_snapshot_identifier  = var.redis_final_snapshot_identifier

  apply_immediately = true

  lifecycle {
    precondition {
      condition     = var.redis_next_auth_token == null || var.redis_next_auth_token != var.redis_auth_token
      error_message = "redis_next_auth_token must differ from the currently active redis_auth_token."
    }
  }
}

resource "aws_secretsmanager_secret" "redis" {
  name                    = "${local.name}/redis"
  description             = "Redis credentials injected into EventLedger tasks"
  recovery_window_in_days = 30
}

resource "aws_secretsmanager_secret_version" "redis" {
  secret_id = aws_secretsmanager_secret.redis.id
  secret_string = jsonencode({
    token = local.redis_runtime_auth_token
  })

  depends_on = [aws_elasticache_replication_group.this]
}
