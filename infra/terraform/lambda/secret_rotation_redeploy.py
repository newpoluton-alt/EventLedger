import json
import logging
import os

import boto3


LOGGER = logging.getLogger()
LOGGER.setLevel(logging.INFO)

ECS = boto3.client("ecs")
ALLOWED_SECRET_ARNS = set(json.loads(os.environ["SECRET_ARNS"]))


def _secret_identifiers(value):
    identifiers = set()
    if isinstance(value, dict):
        for key, nested in value.items():
            if key in {"arn", "aRN", "secretId", "SecretId"} and isinstance(nested, str):
                identifiers.add(nested)
            identifiers.update(_secret_identifiers(nested))
    elif isinstance(value, list):
        for nested in value:
            identifiers.update(_secret_identifiers(nested))
    return identifiers


def _is_allowed(identifier):
    for secret_arn in ALLOWED_SECRET_ARNS:
        if identifier == secret_arn:
            return True
        secret_name = secret_arn.split(":secret:", maxsplit=1)[-1]
        if identifier == secret_name or secret_name.startswith(f"{identifier}-"):
            return True
    return False


def handler(event, _context):
    candidates = _secret_identifiers(event)
    candidates.update(
        resource for resource in event.get("resources", []) if isinstance(resource, str)
    )
    if not any(_is_allowed(candidate) for candidate in candidates):
        LOGGER.info(
            "Ignoring secret event %s because it does not target an EventLedger runtime secret",
            event.get("detail", {}).get("eventName", "unknown"),
        )
        return {"redeployed": False}

    response = ECS.update_service(
        cluster=os.environ["ECS_CLUSTER"],
        service=os.environ["ECS_SERVICE"],
        forceNewDeployment=True,
    )
    deployment = response["service"]["deployments"][0]
    LOGGER.info("Started ECS deployment %s after a runtime-secret update", deployment["id"])
    return {"redeployed": True, "deploymentId": deployment["id"]}
