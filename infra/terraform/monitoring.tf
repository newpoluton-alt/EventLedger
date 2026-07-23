resource "aws_cloudwatch_metric_alarm" "alb_unhealthy_targets" {
  alarm_name          = "${local.name}-unhealthy-targets"
  alarm_description   = "One or more EventLedger targets are unhealthy."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 2
  period              = 60
  statistic           = "Maximum"
  treat_missing_data  = "breaching"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    LoadBalancer = aws_lb.this.arn_suffix
    TargetGroup  = aws_lb_target_group.application.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "alb_server_errors" {
  alarm_name          = "${local.name}-alb-5xx"
  alarm_description   = "The load balancer is returning server errors."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 5
  evaluation_periods  = 2
  period              = 300
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    LoadBalancer = aws_lb.this.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "target_server_errors" {
  alarm_name          = "${local.name}-target-5xx"
  alarm_description   = "EventLedger targets are returning server errors."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 5
  evaluation_periods  = 2
  period              = 300
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    LoadBalancer = aws_lb.this.arn_suffix
    TargetGroup  = aws_lb_target_group.application.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "outbox_backlog" {
  alarm_name          = "${local.name}-outbox-backlog"
  alarm_description   = "The transactional outbox has more than 1,000 pending events."
  namespace           = "EventLedger"
  metric_name         = "eventledger_outbox_pending"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 1000
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  period              = 300
  statistic           = "Maximum"
  treat_missing_data  = "breaching"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    Environment = var.environment
  }
}

resource "aws_cloudwatch_metric_alarm" "reconciliation_mismatch" {
  alarm_name          = "${local.name}-reconciliation-mismatch"
  alarm_description   = "Reconciliation detected at least one accounting mismatch."
  namespace           = "EventLedger"
  metric_name         = "eventledger_reconciliation_mismatches_total"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  period              = 300
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    Environment = var.environment
  }
}

resource "aws_cloudwatch_metric_alarm" "outbox_dead_event" {
  alarm_name          = "${local.name}-outbox-dead-event"
  alarm_description   = "At least one outbox event exhausted all publication retries."
  namespace           = "EventLedger"
  metric_name         = "eventledger_outbox_events_total"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  period              = 300
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    Environment = var.environment
    result      = "dead"
  }
}

resource "aws_cloudwatch_metric_alarm" "secret_rotation_errors" {
  alarm_name          = "${local.name}-secret-rotation-errors"
  alarm_description   = "The secret-rotation redeployment function is failing."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  period              = 300
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    FunctionName = aws_lambda_function.secret_rotation_redeploy.function_name
  }
}

resource "aws_cloudwatch_metric_alarm" "secret_rotation_dead_letters" {
  alarm_name          = "${local.name}-secret-rotation-dead-letters"
  alarm_description   = "A runtime-secret event could not trigger an EventLedger redeployment."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  period              = 300
  statistic           = "Maximum"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    QueueName = aws_sqs_queue.secret_rotation_dead_letter.name
  }
}

resource "aws_cloudwatch_metric_alarm" "database_cpu" {
  alarm_name          = "${local.name}-database-cpu"
  alarm_description   = "PostgreSQL CPU utilization is high."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  evaluation_periods  = 3
  period              = 300
  statistic           = "Average"
  treat_missing_data  = "missing"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.this.identifier
  }
}

resource "aws_cloudwatch_metric_alarm" "database_storage" {
  alarm_name          = "${local.name}-database-storage"
  alarm_description   = "PostgreSQL has less than 20 GiB free storage."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  comparison_operator = "LessThanThreshold"
  threshold           = 21474836480
  evaluation_periods  = 2
  period              = 300
  statistic           = "Minimum"
  treat_missing_data  = "missing"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.this.identifier
  }
}

resource "aws_cloudwatch_metric_alarm" "redis_memory" {
  alarm_name          = "${local.name}-redis-memory"
  alarm_description   = "Redis database memory usage is high."
  namespace           = "AWS/ElastiCache"
  metric_name         = "DatabaseMemoryUsageCountedForEvictPercentage"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  evaluation_periods  = 3
  period              = 300
  statistic           = "Average"
  treat_missing_data  = "missing"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    ReplicationGroupId = aws_elasticache_replication_group.this.replication_group_id
  }
}

resource "aws_cloudwatch_metric_alarm" "ecs_cpu" {
  alarm_name          = "${local.name}-ecs-cpu"
  alarm_description   = "The ECS service remains CPU constrained after autoscaling."
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 85
  evaluation_periods  = 3
  period              = 300
  statistic           = "Average"
  treat_missing_data  = "breaching"
  alarm_actions       = var.alarm_notification_arns

  dimensions = {
    ClusterName = aws_ecs_cluster.this.name
    ServiceName = aws_ecs_service.application.name
  }
}
