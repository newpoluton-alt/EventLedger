resource "aws_cloudwatch_log_group" "msk" {
  name              = "/aws/msk/${local.name}"
  retention_in_days = 30
}

resource "aws_msk_configuration" "this" {
  name              = "${local.name}-kafka"
  kafka_versions    = [var.kafka_version]
  server_properties = <<-PROPERTIES
    auto.create.topics.enable=false
    allow.everyone.if.no.acl.found=${var.kafka_acl_bootstrap_complete ? "false" : "true"}
    default.replication.factor=3
    log.retention.hours=168
    min.insync.replicas=2
    num.partitions=6
    transaction.state.log.min.isr=2
    transaction.state.log.replication.factor=3
    unclean.leader.election.enable=false
  PROPERTIES
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${local.name}-kafka"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = local.az_count

  broker_node_group_info {
    client_subnets  = aws_subnet.data[*].id
    instance_type   = var.kafka_instance_type
    security_groups = [aws_security_group.kafka.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.kafka_volume_size
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  client_authentication {
    sasl {
      scram = true
    }
    unauthenticated = false
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  enhanced_monitoring = "PER_TOPIC_PER_BROKER"

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }

  tags = {
    Name = "${local.name}-kafka"
  }
}

resource "aws_msk_scram_secret_association" "this" {
  cluster_arn     = aws_msk_cluster.this.arn
  secret_arn_list = [var.msk_scram_secret_arn]
}

resource "aws_appautoscaling_target" "msk_storage" {
  max_capacity       = 1000
  min_capacity       = 1
  resource_id        = aws_msk_cluster.this.arn
  scalable_dimension = "kafka:broker-storage:VolumeSize"
  service_namespace  = "kafka"
}

resource "aws_appautoscaling_policy" "msk_storage" {
  name               = "${local.name}-kafka-storage"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.msk_storage.resource_id
  scalable_dimension = aws_appautoscaling_target.msk_storage.scalable_dimension
  service_namespace  = aws_appautoscaling_target.msk_storage.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "KafkaBrokerStorageUtilization"
    }
    target_value = 60
  }
}
