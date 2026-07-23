resource "aws_security_group" "load_balancer" {
  name        = "${local.name}-alb"
  description = "Public HTTPS ingress for EventLedger"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name}-alb"
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.load_balancer.id
  description       = "Redirect public HTTP to HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.load_balancer.id
  description       = "Public HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_security_group" "application" {
  name        = "${local.name}-app"
  description = "EventLedger ECS tasks"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name}-app"
  }
}

resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.application.id
  description                  = "Application traffic from the load balancer"
  referenced_security_group_id = aws_security_group.load_balancer.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.load_balancer.id
  description                  = "Forward requests to EventLedger"
  referenced_security_group_id = aws_security_group.application.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "vpc_endpoints" {
  name        = "${local.name}-vpc-endpoints"
  description = "Private AWS API endpoints used by EventLedger"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name}-vpc-endpoints"
  }
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_from_app" {
  security_group_id            = aws_security_group.vpc_endpoints.id
  description                  = "HTTPS from EventLedger tasks"
  referenced_security_group_id = aws_security_group.application.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "app_to_endpoints" {
  security_group_id            = aws_security_group.application.id
  description                  = "Private ECR, Secrets Manager, CloudWatch Logs, and X-Ray APIs"
  referenced_security_group_id = aws_security_group.vpc_endpoints.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "app_to_s3" {
  security_group_id = aws_security_group.application.id
  description       = "Regional S3 gateway endpoint for private ECR image layers"
  prefix_list_id    = aws_vpc_endpoint.application_s3.prefix_list_id
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "app_dns_udp" {
  security_group_id = aws_security_group.application.id
  description       = "DNS through the VPC resolver"
  cidr_ipv4         = "${cidrhost(var.vpc_cidr, 2)}/32"
  from_port         = 53
  to_port           = 53
  ip_protocol       = "udp"
}

resource "aws_vpc_security_group_egress_rule" "app_dns_tcp" {
  security_group_id = aws_security_group.application.id
  description       = "Large DNS responses through the VPC resolver"
  cidr_ipv4         = "${cidrhost(var.vpc_cidr, 2)}/32"
  from_port         = 53
  to_port           = 53
  ip_protocol       = "tcp"
}

resource "aws_security_group" "database" {
  name        = "${local.name}-postgres"
  description = "PostgreSQL access from EventLedger only"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name}-postgres"
  }
}

resource "aws_vpc_security_group_ingress_rule" "database_from_app" {
  security_group_id            = aws_security_group.database.id
  description                  = "PostgreSQL from ECS tasks"
  referenced_security_group_id = aws_security_group.application.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "app_to_database" {
  security_group_id            = aws_security_group.application.id
  description                  = "PostgreSQL"
  referenced_security_group_id = aws_security_group.database.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "kafka" {
  name        = "${local.name}-kafka"
  description = "MSK SASL/SCRAM access from EventLedger only"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name}-kafka"
  }
}

resource "aws_vpc_security_group_ingress_rule" "kafka_from_app" {
  security_group_id            = aws_security_group.kafka.id
  description                  = "SASL/SCRAM Kafka from ECS tasks"
  referenced_security_group_id = aws_security_group.application.id
  from_port                    = 9096
  to_port                      = 9096
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "kafka_interbroker" {
  security_group_id            = aws_security_group.kafka.id
  description                  = "Broker replication and cluster coordination"
  referenced_security_group_id = aws_security_group.kafka.id
  ip_protocol                  = "-1"
}

resource "aws_vpc_security_group_egress_rule" "kafka_interbroker" {
  security_group_id            = aws_security_group.kafka.id
  description                  = "Broker replication and cluster coordination"
  referenced_security_group_id = aws_security_group.kafka.id
  ip_protocol                  = "-1"
}

resource "aws_vpc_security_group_egress_rule" "app_to_kafka" {
  security_group_id            = aws_security_group.application.id
  description                  = "SASL/SCRAM Kafka"
  referenced_security_group_id = aws_security_group.kafka.id
  from_port                    = 9096
  to_port                      = 9096
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "Encrypted Redis access from EventLedger only"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name}-redis"
  }
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_app" {
  security_group_id            = aws_security_group.redis.id
  description                  = "Redis from ECS tasks"
  referenced_security_group_id = aws_security_group.application.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "redis_replication" {
  security_group_id            = aws_security_group.redis.id
  description                  = "Redis replication and failover"
  referenced_security_group_id = aws_security_group.redis.id
  ip_protocol                  = "-1"
}

resource "aws_vpc_security_group_egress_rule" "redis_replication" {
  security_group_id            = aws_security_group.redis.id
  description                  = "Redis replication and failover"
  referenced_security_group_id = aws_security_group.redis.id
  ip_protocol                  = "-1"
}

resource "aws_vpc_security_group_egress_rule" "app_to_redis" {
  security_group_id            = aws_security_group.application.id
  description                  = "Redis"
  referenced_security_group_id = aws_security_group.redis.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}
