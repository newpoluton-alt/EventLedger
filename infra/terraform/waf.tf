resource "aws_cloudwatch_log_group" "waf" {
  name              = "aws-waf-logs-${local.name}"
  retention_in_days = 30
}

resource "aws_wafv2_web_acl" "application" {
  name        = "${local.name}-application"
  description = "Rate limiting and managed request filtering for EventLedger"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "RateLimitBySourceIp"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        aggregate_key_type = "IP"
        limit              = 2000
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-rate-limit"
      sampled_requests_enabled   = false
    }
  }

  rule {
    name     = "AWSManagedRulesCommonRuleSet"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-common-rules"
      sampled_requests_enabled   = false
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name}-web-acl"
    sampled_requests_enabled   = false
  }
}

resource "aws_wafv2_web_acl_association" "application" {
  resource_arn = aws_lb.this.arn
  web_acl_arn  = aws_wafv2_web_acl.application.arn
}

resource "aws_wafv2_web_acl_logging_configuration" "application" {
  resource_arn            = aws_wafv2_web_acl.application.arn
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]

  redacted_fields {
    single_header {
      name = "x-api-key"
    }
  }
}
