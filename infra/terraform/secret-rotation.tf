data "archive_file" "secret_rotation_redeploy" {
  type        = "zip"
  source_file = "${path.module}/lambda/secret_rotation_redeploy.py"
  output_path = "${path.module}/lambda/secret_rotation_redeploy.zip"
}

resource "aws_cloudwatch_log_group" "secret_rotation_redeploy" {
  name              = "/aws/lambda/${local.name}-secret-rotation-redeploy"
  retention_in_days = 30
}

resource "aws_sqs_queue" "secret_rotation_dead_letter" {
  name                      = "${local.name}-secret-rotation-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_iam_role" "secret_rotation_redeploy" {
  name = "${local.name}-secret-rotation-redeploy"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "lambda.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "secret_rotation_redeploy_logs" {
  role       = aws_iam_role.secret_rotation_redeploy.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "secret_rotation_redeploy" {
  name = "force-eventledger-deployment"
  role = aws_iam_role.secret_rotation_redeploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "RedeployEventLedger"
        Effect = "Allow"
        Action = [
          "ecs:DescribeServices",
          "ecs:UpdateService"
        ]
        Resource = "arn:${data.aws_partition.current.partition}:ecs:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:service/${aws_ecs_cluster.this.name}/${aws_ecs_service.application.name}"
      },
      {
        Sid      = "SendFailedEventsToDeadLetterQueue"
        Effect   = "Allow"
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.secret_rotation_dead_letter.arn
      }
    ]
  })
}

resource "aws_lambda_function" "secret_rotation_redeploy" {
  function_name = "${local.name}-secret-rotation-redeploy"
  description   = "Roll EventLedger tasks after the application database secret finishes rotating"
  role          = aws_iam_role.secret_rotation_redeploy.arn
  runtime       = "python3.13"
  handler       = "secret_rotation_redeploy.handler"
  filename      = data.archive_file.secret_rotation_redeploy.output_path
  timeout       = 30
  memory_size   = 128

  source_code_hash = data.archive_file.secret_rotation_redeploy.output_base64sha256

  environment {
    variables = {
      ECS_CLUSTER = aws_ecs_cluster.this.name
      ECS_SERVICE = aws_ecs_service.application.name
      SECRET_ARNS = jsonencode([
        var.database_app_secret_arn
      ])
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.secret_rotation_redeploy,
    aws_iam_role_policy.secret_rotation_redeploy,
    aws_iam_role_policy_attachment.secret_rotation_redeploy_logs
  ]
}

resource "aws_lambda_function_event_invoke_config" "secret_rotation_redeploy" {
  function_name                = aws_lambda_function.secret_rotation_redeploy.function_name
  maximum_event_age_in_seconds = 3600
  maximum_retry_attempts       = 2

  destination_config {
    on_failure {
      destination = aws_sqs_queue.secret_rotation_dead_letter.arn
    }
  }
}

resource "aws_cloudwatch_event_rule" "runtime_secret_changed" {
  name        = "${local.name}-runtime-secret-changed"
  description = "Detect a completed application database credential rotation"

  event_pattern = jsonencode({
    source = ["aws.secretsmanager"]
    "detail-type" = [
      "AWS API Call via CloudTrail",
      "AWS Service Event via CloudTrail"
    ]
    detail = {
      eventSource = ["secretsmanager.amazonaws.com"]
      eventName   = ["RotationSucceeded"]
    }
  })
}

resource "aws_cloudwatch_event_target" "secret_rotation_redeploy" {
  rule      = aws_cloudwatch_event_rule.runtime_secret_changed.name
  target_id = "RedeployEventLedger"
  arn       = aws_lambda_function.secret_rotation_redeploy.arn

  retry_policy {
    maximum_event_age_in_seconds = 3600
    maximum_retry_attempts       = 5
  }

  dead_letter_config {
    arn = aws_sqs_queue.secret_rotation_dead_letter.arn
  }

  depends_on = [
    aws_lambda_permission.eventbridge_secret_rotation,
    aws_sqs_queue_policy.secret_rotation_dead_letter
  ]
}

resource "aws_sqs_queue_policy" "secret_rotation_dead_letter" {
  queue_url = aws_sqs_queue.secret_rotation_dead_letter.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "AllowEventBridgeDeadLetters"
      Effect = "Allow"
      Principal = {
        Service = "events.amazonaws.com"
      }
      Action   = "sqs:SendMessage"
      Resource = aws_sqs_queue.secret_rotation_dead_letter.arn
      Condition = {
        ArnEquals = {
          "aws:SourceArn" = aws_cloudwatch_event_rule.runtime_secret_changed.arn
        }
        StringEquals = {
          "aws:SourceAccount" = data.aws_caller_identity.current.account_id
        }
      }
    }]
  })
}

resource "aws_lambda_permission" "eventbridge_secret_rotation" {
  statement_id  = "AllowEventBridgeSecretRotation"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.secret_rotation_redeploy.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.runtime_secret_changed.arn
}
