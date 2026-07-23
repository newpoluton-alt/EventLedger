resource "aws_ecr_repository" "application" {
  name                 = var.project_name
  image_tag_mutability = "IMMUTABLE"

  encryption_configuration {
    encryption_type = "AES256"
  }

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "application" {
  repository = aws_ecr_repository.application.name
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Retain the 50 most recent release images"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["sha-"]
          countType     = "imageCountMoreThan"
          countNumber   = 50
        }
        action = {
          type = "expire"
        }
      },
      {
        rulePriority = 2
        description  = "Remove untagged images after seven days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

resource "aws_ecs_cluster" "this" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "application" {
  name              = "/ecs/${local.name}/application"
  retention_in_days = 30
}

resource "aws_cloudwatch_log_group" "otel" {
  name              = "/ecs/${local.name}/otel"
  retention_in_days = 30
}

resource "aws_cloudwatch_log_group" "metrics" {
  name              = "/ecs/${local.name}/metrics"
  retention_in_days = 30
}

resource "random_password" "api_key" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "api_key" {
  name                    = "${local.name}/api-key"
  description             = "API key required by EventLedger application endpoints"
  recovery_window_in_days = 30
}

resource "aws_secretsmanager_secret_version" "api_key" {
  secret_id = aws_secretsmanager_secret.api_key.id
  secret_string = jsonencode({
    token = random_password.api_key.result
  })
}

resource "aws_iam_role" "ecs_execution" {
  name = "${local.name}-ecs-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
      Action = "sts:AssumeRole"
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = data.aws_caller_identity.current.account_id
        }
        ArnLike = {
          "aws:SourceArn" = "arn:aws:ecs:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:*"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name = "read-runtime-secrets"
  role = aws_iam_role.ecs_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadRuntimeSecrets"
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = [
          var.database_app_secret_arn,
          var.msk_scram_secret_arn,
          aws_secretsmanager_secret.api_key.arn,
          aws_secretsmanager_secret.redis.arn
        ]
      },
      {
        Sid    = "DecryptRuntimeSecrets"
        Effect = "Allow"
        Action = "kms:Decrypt"
        Resource = [
          var.database_app_kms_key_arn,
          var.msk_scram_kms_key_arn
        ]
        Condition = {
          StringEquals = {
            "kms:ViaService" = "secretsmanager.${data.aws_region.current.name}.amazonaws.com"
          }
        }
      }
    ]
  })
}

resource "aws_iam_role" "ecs_task" {
  name = "${local.name}-ecs-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
      Action = "sts:AssumeRole"
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = data.aws_caller_identity.current.account_id
        }
        ArnLike = {
          "aws:SourceArn" = "arn:aws:ecs:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:*"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "ecs_task_telemetry" {
  name = "publish-telemetry"
  role = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "WriteXRay"
        Effect = "Allow"
        Action = [
          "xray:PutTraceSegments",
          "xray:PutTelemetryRecords",
          "xray:GetSamplingRules",
          "xray:GetSamplingTargets",
          "xray:GetSamplingStatisticSummaries"
        ]
        Resource = "*"
      },
      {
        Sid      = "WriteMetrics"
        Effect   = "Allow"
        Action   = "cloudwatch:PutMetricData"
        Resource = "*"
        Condition = {
          StringEquals = {
            "cloudwatch:namespace" = "EventLedger"
          }
        }
      },
      {
        Sid    = "WriteEmbeddedMetricLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:DescribeLogStreams",
          "logs:PutLogEvents"
        ]
        Resource = "${aws_cloudwatch_log_group.metrics.arn}:*"
      },
      {
        Sid      = "DiscoverMetricLogGroup"
        Effect   = "Allow"
        Action   = "logs:DescribeLogGroups"
        Resource = "*"
      }
    ]
  })
}

resource "aws_ecs_task_definition" "application" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  volume {
    name = "runtime-tmp"
  }

  container_definitions = jsonencode([
    {
      name      = "otel-collector"
      image     = "public.ecr.aws/aws-observability/aws-otel-collector:v0.48.0"
      essential = true
      cpu       = 256
      memory    = 512
      command   = ["--config=env:AOT_CONFIG_CONTENT"]
      environment = [
        {
          name  = "AWS_REGION"
          value = data.aws_region.current.name
        },
        {
          name = "AOT_CONFIG_CONTENT"
          value = templatefile("${path.module}/adot-config.yml.tftpl", {
            environment       = var.environment
            metrics_log_group = aws_cloudwatch_log_group.metrics.name
            region            = data.aws_region.current.name
          })
        }
      ]
      healthCheck = {
        command     = ["CMD", "/healthcheck"]
        interval    = 10
        timeout     = 5
        retries     = 3
        startPeriod = 10
      }
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.otel.name
          awslogs-region        = data.aws_region.current.name
          awslogs-stream-prefix = "otel"
        }
      }
    },
    {
      name                   = "application"
      image                  = "${aws_ecr_repository.application.repository_url}:${var.image_tag}"
      essential              = true
      cpu                    = 768
      memory                 = 1536
      user                   = "10001"
      readonlyRootFilesystem = true
      stopTimeout            = 60
      dependsOn = [
        {
          containerName = "otel-collector"
          condition     = "HEALTHY"
        }
      ]
      portMappings = [
        {
          name          = "http"
          containerPort = 8080
          hostPort      = 8080
          protocol      = "tcp"
          appProtocol   = "http"
        }
      ]
      mountPoints = [
        {
          sourceVolume  = "runtime-tmp"
          containerPath = "/tmp"
          readOnly      = false
        }
      ]
      environment = [
        {
          name  = "DATABASE_URL"
          value = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${var.db_name}?sslmode=verify-full&sslrootcert=/app/certs/global-bundle.pem"
        },
        {
          name  = "SPRING_FLYWAY_LOCATIONS"
          value = "classpath:db/migration"
        },
        {
          name  = "KAFKA_BOOTSTRAP_SERVERS"
          value = aws_msk_cluster.this.bootstrap_brokers_sasl_scram
        },
        {
          name  = "KAFKA_SECURITY_PROTOCOL"
          value = "SASL_SSL"
        },
        {
          name  = "KAFKA_SASL_MECHANISM"
          value = "SCRAM-SHA-512"
        },
        {
          name  = "KAFKA_TOPIC_REPLICATION_FACTOR"
          value = "3"
        },
        {
          name  = "KAFKA_TOPIC_MIN_ISR"
          value = "2"
        },
        {
          name  = "REDIS_HOST"
          value = aws_elasticache_replication_group.this.primary_endpoint_address
        },
        {
          name  = "REDIS_PORT"
          value = tostring(aws_elasticache_replication_group.this.port)
        },
        {
          name  = "SPRING_DATA_REDIS_SSL_ENABLED"
          value = "true"
        },
        {
          name  = "EVENTLEDGER_API_KEY_VERSION"
          value = aws_secretsmanager_secret_version.api_key.version_id
        },
        {
          name  = "REDIS_SECRET_VERSION"
          value = aws_secretsmanager_secret_version.redis.version_id
        },
        {
          name  = "OTEL_EXPORTER_OTLP_ENDPOINT"
          value = "http://127.0.0.1:4318/v1/traces"
        },
        {
          name  = "OTEL_SERVICE_NAME"
          value = var.project_name
        },
        {
          name  = "OTEL_RESOURCE_ATTRIBUTES"
          value = "deployment.environment=${var.environment},service.namespace=payments"
        },
        {
          name  = "TRACING_SAMPLE_RATE"
          value = var.environment == "prod" ? "0.1" : "1.0"
        },
        {
          name  = "MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS"
          value = "never"
        },
        {
          name  = "SERVER_FORWARD_HEADERS_STRATEGY"
          value = "framework"
        }
      ]
      secrets = [
        {
          name      = "EVENTLEDGER_API_KEY"
          valueFrom = "${aws_secretsmanager_secret.api_key.arn}:token::"
        },
        {
          name      = "DATABASE_USERNAME"
          valueFrom = "${var.database_app_secret_arn}:username::"
        },
        {
          name      = "DATABASE_PASSWORD"
          valueFrom = "${var.database_app_secret_arn}:password::"
        },
        {
          name      = "KAFKA_USERNAME"
          valueFrom = "${var.msk_scram_secret_arn}:username::"
        },
        {
          name      = "KAFKA_PASSWORD"
          valueFrom = "${var.msk_scram_secret_arn}:password::"
        },
        {
          name      = "SPRING_DATA_REDIS_PASSWORD"
          valueFrom = "${aws_secretsmanager_secret.redis.arn}:token::"
        }
      ]
      healthCheck = {
        command     = ["CMD-SHELL", "curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness >/dev/null || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 45
      }
      linuxParameters = {
        initProcessEnabled = true
      }
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.application.name
          awslogs-region        = data.aws_region.current.name
          awslogs-stream-prefix = "app"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "application" {
  name            = var.project_name
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.application.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  platform_version                   = "1.4.0"
  health_check_grace_period_seconds  = 90
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_execute_command             = false

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = aws_subnet.application[*].id
    security_groups  = [aws_security_group.application.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.application.arn
    container_name   = "application"
    container_port   = 8080
  }

  depends_on = [
    aws_lb_listener.https,
    aws_iam_role_policy.ecs_execution_secrets,
    aws_msk_scram_secret_association.this,
    aws_secretsmanager_secret_version.api_key,
    aws_secretsmanager_secret_version.redis
  ]

  lifecycle {
    ignore_changes = [desired_count]
  }
}

resource "aws_appautoscaling_target" "ecs" {
  max_capacity       = max(var.desired_count * 4, 2)
  min_capacity       = var.desired_count
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.application.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "ecs_cpu" {
  name               = "${local.name}-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}

resource "aws_appautoscaling_policy" "ecs_memory" {
  name               = "${local.name}-memory"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageMemoryUtilization"
    }
    target_value       = 70
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}
