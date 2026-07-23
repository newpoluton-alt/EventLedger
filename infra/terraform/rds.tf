resource "aws_db_subnet_group" "this" {
  name       = "${local.name}-postgres"
  subnet_ids = aws_subnet.data[*].id

  tags = {
    Name = "${local.name}-postgres"
  }
}

resource "aws_db_parameter_group" "this" {
  name   = "${local.name}-postgres16"
  family = "postgres16"

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

resource "aws_db_instance" "this" {
  identifier = "${local.name}-postgres"

  engine                      = "postgres"
  engine_version              = "16.14"
  instance_class              = var.db_instance_class
  db_name                     = var.db_name
  username                    = var.db_username
  manage_master_user_password = true
  port                        = 5432

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true

  multi_az               = true
  publicly_accessible    = false
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.database.id]
  parameter_group_name   = aws_db_parameter_group.this.name

  backup_retention_period    = 14
  backup_window              = "02:00-03:00"
  maintenance_window         = "sun:03:30-sun:04:30"
  auto_minor_version_upgrade = true
  copy_tags_to_snapshot      = true
  enabled_cloudwatch_logs_exports = [
    "postgresql",
    "upgrade"
  ]

  performance_insights_enabled          = true
  performance_insights_retention_period = 7
  monitoring_interval                   = 60
  monitoring_role_arn                   = aws_iam_role.rds_monitoring.arn

  deletion_protection       = var.db_deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = var.db_final_snapshot_identifier

  depends_on = [
    aws_cloudwatch_log_group.rds_postgresql,
    aws_cloudwatch_log_group.rds_upgrade,
    aws_iam_role_policy_attachment.rds_monitoring
  ]
}

resource "aws_cloudwatch_log_group" "rds_postgresql" {
  name              = "/aws/rds/instance/${local.name}-postgres/postgresql"
  retention_in_days = 30
}

resource "aws_cloudwatch_log_group" "rds_upgrade" {
  name              = "/aws/rds/instance/${local.name}-postgres/upgrade"
  retention_in_days = 30
}

resource "aws_iam_role" "rds_monitoring" {
  name = "${local.name}-rds-monitoring"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "monitoring.rds.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}
