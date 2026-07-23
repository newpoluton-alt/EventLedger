# EventLedger infrastructure

This directory models a production deployment in `eu-central-1` across three
availability zones:

- an internet-facing Application Load Balancer with TLS and access logs;
- an AWS WAF web ACL with managed common rules, source-IP rate limiting, and
  redacted security logs;
- private Fargate tasks with deployment rollback and target-tracking scaling;
- Multi-AZ PostgreSQL with encrypted storage, managed credentials, backups,
  Performance Insights, and deletion protection;
- three encrypted Amazon MSK brokers using SASL/SCRAM and a replication factor
  of three;
- a three-node encrypted ElastiCache Redis replication group;
- Route53 aliasing to the TLS load balancer;
- CloudWatch logs, actionable SNS alarms, container insights, and an ADOT
  sidecar exporting traces to AWS X-Ray and application metrics to CloudWatch;
- automatic ECS deployments after completed database-credential rotations.

The topology deliberately places ECS tasks in private subnets and all stateful
services in isolated data subnets. Security groups permit only the required
application-to-service ports.

## Prerequisites

- Terraform 1.7 or newer
- AWS CLI credentials for the target account
- an ACM certificate in the deployment region
- a public Route53 hosted zone for the certificate hostname
- an encrypted S3 state bucket and DynamoDB lock table
- customer-managed KMS keys and existing database and MSK client secrets
- at least one SNS topic subscribed to the production incident channel
- an immutable EventLedger image already pushed to the ECR repository

The remote state contains sensitive values. Restrict bucket and KMS access,
enable bucket versioning and CloudTrail data events, and never commit a
populated `.tfvars` file or a plan file.

## Prepare the database identity

The application does not receive the RDS master credential. Create a
customer-managed Secrets Manager secret containing exactly these fields:

```json
{"username":"eventledger_app","password":"generated-value"}
```

Use the same value through an audited database-admin path to create a dedicated
login that owns the EventLedger schema and can run its Flyway migrations, but
cannot administer the RDS instance or other databases. Pass the secret and its
KMS key ARNs through `database_app_secret_arn` and
`database_app_kms_key_arn`. Terraform outputs the RDS-managed administrator
secret ARN, private application subnets, and application security group needed
to construct that short-lived bootstrap path; it never outputs a password.

Configure rotation for that identity in Secrets Manager. EventBridge invokes a
small Lambda only after `RotationSucceeded` and forces an ECS deployment,
because secret environment variables are read only when a task starts. It does
not react to `PutSecretValue`, which can represent an `AWSPENDING` rotation
stage. Failed deployments roll back through the ECS deployment circuit breaker.
Use an alternating-user rotation strategy where the database policy permits it,
and alarm on both Lambda errors and the encrypted dead-letter queue so a missed
rollout cannot remain silent. After a manual database-secret update, wait until
the intended version is `AWSCURRENT` and force an ECS deployment explicitly.

## Prepare the Kafka identity

Amazon MSK requires SCRAM secrets to:

1. have a name beginning with `AmazonMSK_`;
2. use a customer-managed KMS key;
3. contain exactly the `username` and `password` JSON fields.

Create the secret outside Terraform through your organization's secret
provisioning workflow. Pass only its ARN and KMS key ARN to Terraform. The
cluster association adds the policy MSK needs, while the ECS execution role is
granted read/decrypt access to that one secret.

MSK can take up to ten minutes to synchronize an updated SCRAM secret. Rotate
this credential through an audited operation: publish the new `AWSCURRENT`
value, wait at least ten minutes and verify authentication from inside the VPC,
then run `aws ecs update-service --cluster CLUSTER --service SERVICE
--force-new-deployment`. Keep the existing tasks serving until the replacement
deployment is healthy. Kafka rotation is intentionally not connected to the
immediate database-rotation hook.

Amazon MSK does not support configuring `super.users`. A new cluster therefore
starts with `kafka_acl_bootstrap_complete = false`, which temporarily preserves
Kafka's allow-if-no-ACL behavior. After the application creates its declared
topics, use an approved client in the VPC to grant the SCRAM principal explicit
topic, consumer-group, transactional-ID, idempotent-write, and administrative
permissions required by EventLedger. Verify those ACLs from a second client,
then set:

```hcl
kafka_acl_bootstrap_complete = true
```

Review and apply that change to switch unmatched resources to default-deny.
Do not set the flag before the ACLs exist; doing so locks every SCRAM client
out. Retain the exact ACL commands and verification output with the deployment
record.

The following template grants only the resource families used by EventLedger.
Run it with a protected `client.properties` file that authenticates the
configured SCRAM user, and replace the binary path if necessary:

```bash
export KAFKA_USERNAME="eventledger"

kafka-acls.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config client.properties --add \
  --allow-principal "User:${KAFKA_USERNAME}" \
  --cluster --operation Describe --operation IdempotentWrite

for topic_prefix in payments. settlements.; do
  kafka-acls.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
    --command-config client.properties --add \
    --allow-principal "User:${KAFKA_USERNAME}" \
    --resource-pattern-type prefixed --topic "$topic_prefix" \
    --operation Create --operation Read --operation Write \
    --operation Describe --operation DescribeConfigs --operation AlterConfigs
done

kafka-acls.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config client.properties --add \
  --allow-principal "User:${KAFKA_USERNAME}" \
  --resource-pattern-type prefixed --group event-ledger \
  --operation Read --operation Describe

kafka-acls.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
  --command-config client.properties --list \
  --principal "User:${KAFKA_USERNAME}"
```

## Initialize and review

```bash
cp infra/terraform/backend.hcl.example infra/terraform/backend.hcl
cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars
export TF_VAR_redis_auth_token="$(openssl rand -hex 32)"

terraform -chdir=infra/terraform init -backend-config=backend.hcl
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform validate
```

Store the plan as a short-lived, encrypted CI artifact if it must leave the
runner; it can contain credentials. The checked-in defaults are production
oriented and incur meaningful AWS cost, especially the NAT gateways, MSK
brokers, and Multi-AZ databases.

## Rotate the Redis credential

Redis AUTH changes use AWS's two-phase flow so tasks never receive a password
before ElastiCache accepts it. First keep `TF_VAR_redis_auth_token` set to the
current token, set a different `TF_VAR_redis_next_auth_token`, and apply. That
uses `ROTATE`, waits for ElastiCache, publishes the new application secret, and
rolls the task definition while both passwords are accepted. After the ECS
deployment is healthy, promote the new value and remove the transition value:

```bash
export TF_VAR_redis_auth_token="$TF_VAR_redis_next_auth_token"
unset TF_VAR_redis_next_auth_token
terraform -chdir=infra/terraform plan -out=eventledger-redis-finalize.tfplan
terraform -chdir=infra/terraform apply eventledger-redis-finalize.tfplan
```

The second apply uses `SET` to revoke the old password. Review both plans,
apply each phase immediately, and never skip the health check between phases.

## Bootstrap the platform and publish the first image

On the first deployment, create the immutable ECR repository and publish the
image. Then apply the complete platform with zero application tasks. This
creates the private administration path without starting EventLedger against a
database identity that does not exist:

```bash
terraform -chdir=infra/terraform apply \
  -target=aws_ecr_repository.application \
  -target=aws_ecr_lifecycle_policy.application

aws ecr get-login-password --region eu-central-1 \
  | docker login --username AWS --password-stdin ACCOUNT_ID.dkr.ecr.eu-central-1.amazonaws.com
docker buildx build --platform linux/amd64 --target runtime \
  --tag ACCOUNT_ID.dkr.ecr.eu-central-1.amazonaws.com/eventledger:sha-GIT_SHA \
  --push .

terraform -chdir=infra/terraform plan \
  -var=desired_count=0 \
  -out=eventledger-bootstrap.tfplan
terraform -chdir=infra/terraform apply eventledger-bootstrap.tfplan
```

Use the resulting private subnets, application security group, database
endpoint, and RDS administrator-secret ARN to create the dedicated database
login through an approved short-lived administration task. Verify its TLS
connection and migration privileges before enabling the service. Then review
and apply the normal desired count:

```bash
terraform -chdir=infra/terraform plan -out=eventledger.tfplan
terraform -chdir=infra/terraform apply eventledger.tfplan
```

Replace `GIT_SHA` with 7–64 lowercase hexadecimal characters and set the same
`image_tag` in the reviewed variables. The autoscaling target raises a
zero-task service to the configured minimum during the second apply. Use
`-target` only for the ECR bootstrap; subsequent deployments should plan the
complete configuration. Supply a fresh, globally unique RDS and Redis final
snapshot identifier before every approved replacement.

Use a deployment role with least privilege in CI. Do not use long-lived AWS
access keys; prefer GitHub OIDC with a repository- and branch-restricted trust
policy.

## Kafka topics

Automatic topic creation is disabled. Provision these topics through a
deployment job or Spring `NewTopic` declarations before serving traffic:

| Topic | Partitions | Replication factor | Minimum ISR |
| --- | ---: | ---: | ---: |
| `payments.v1` | 6 | 3 | 2 |
| `payments.v1.DLT` | 6 | 3 | 2 |
| `settlements.v1` | 6 | 3 | 2 |
| `settlements.v1.DLT` | 6 | 3 | 2 |

Retain dead-letter topics long enough for investigation. Never replay them
without confirming the consumer's idempotency key behavior.

## API access and observability

Terraform generates the application API key, stores it in Secrets Manager, and
injects it as `EVENTLEDGER_API_KEY`. The secret value is never a Terraform
output, but it is present in encrypted Terraform state. Authorized clients send
it as `X-API-Key`; rotate it only through a coordinated client and deployment
change.

The public listener rejects `/actuator` paths. AWS WAF applies the managed
common rule set and a source-IP rate limit before requests reach the
application; its logs redact `X-API-Key`, and request sampling is disabled so
the credential cannot appear in sampled headers. ADOT scrapes Prometheus
metrics from the application inside the task, publishes the `EventLedger` CloudWatch
namespace through Embedded Metric Format, and exports traces to X-Ray.
CloudWatch alarms cover target 5xx responses, unhealthy tasks, RDS and Redis
pressure, outbox backlog, and reconciliation mismatches. The
`alarm_notification_arns` variable must contain at least one subscribed SNS
topic.

For local development, Prometheus is available on `127.0.0.1:9090`, grouped
alerts are visible in Alertmanager on `127.0.0.1:9093`, and dashboards are
provisioned in Grafana on `127.0.0.1:3000`. Alertmanager intentionally has no
external local receiver; production notification is handled by the required
SNS alarm actions.
