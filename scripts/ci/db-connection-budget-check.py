#!/usr/bin/env python3
"""The Aurora connection alarm budget and the chart must describe the same fleet.

deploy/terraform sizes the DatabaseConnections alarm as
    service_max_replicas x db_pool_max + db_connection_headroom
from Terraform variables, while the pods' real figures live in the Helm chart
(HPA maxReplicas, config.DB_POOL_MAX). Nothing links the two, so this check
reads the rendered chart (stdin) and the Terraform variables (defaults in
variables.tf, overridden by an optional tfvars file) and fails when they
disagree, or when main.tf no longer derives the alarm threshold from them.

Usage: helm template ... | python3 scripts/ci/db-connection-budget-check.py <terraform dir> [<tfvars file>]
"""
import re
import sys
from pathlib import Path

import yaml

VARIABLES = ("service_max_replicas", "db_pool_max", "db_connection_headroom")
BUDGET = "var.service_max_replicas * var.db_pool_max + var.db_connection_headroom"


def fail(message):
    print(f"db-connection-budget-check: {message}", file=sys.stderr)
    sys.exit(1)


def terraform_values(tf_dir, tfvars):
    variables_tf = (tf_dir / "variables.tf").read_text()
    values = {}
    for name in VARIABLES:
        block = re.search(r'variable\s+"%s"\s*\{(.*?)\n\}' % name, variables_tf, re.S)
        if not block:
            fail(f'variable "{name}" not found in {tf_dir / "variables.tf"}')
        default = re.search(r"^\s*default\s*=\s*(\d+)\s*$", block.group(1), re.M)
        if default:
            values[name] = int(default.group(1))
    if tfvars:
        text = Path(tfvars).read_text()
        for name in VARIABLES:
            override = re.search(r"^\s*%s\s*=\s*(\S+)\s*$" % name, text, re.M)
            if override:
                if not override.group(1).isdigit():
                    fail(f"{tfvars}: {name} must be a literal number, got {override.group(1)}")
                values[name] = int(override.group(1))
    missing = [name for name in VARIABLES if name not in values]
    if missing:
        fail(f"no value for {', '.join(missing)} (no default and not in {tfvars or 'a tfvars file'})")
    return values


def check_main_tf(tf_dir):
    main_tf = re.sub(r"\s+", " ", (tf_dir / "main.tf").read_text())
    if f"db_connection_budget = {BUDGET}" not in main_tf:
        fail(f"main.tf: local.db_connection_budget must be {BUDGET}")
    alarm = re.search(r'resource "aws_cloudwatch_metric_alarm" "aurora_connections" \{(.*?)(?=\bresource "|$)', main_tf)
    if not alarm or "threshold = local.db_connection_budget " not in alarm.group(1) + " ":
        fail("main.tf: aws_cloudwatch_metric_alarm.aurora_connections threshold must be local.db_connection_budget")


def chart_values(docs):
    by_kind = {}
    for doc in docs:
        by_kind.setdefault(doc["kind"], []).append(doc)
    hpas = by_kind.get("HorizontalPodAutoscaler", [])
    deployments = by_kind.get("Deployment", [])
    if len(deployments) != 1:
        fail(f"expected one Deployment, got {len(deployments)}")
    if hpas:
        max_replicas = int(hpas[0]["spec"]["maxReplicas"])
    else:
        max_replicas = int(deployments[0]["spec"].get("replicas", 1))
    pools = [cm["data"]["DB_POOL_MAX"] for cm in by_kind.get("ConfigMap", []) if "DB_POOL_MAX" in cm.get("data", {})]
    if len(pools) != 1:
        fail(f"expected DB_POOL_MAX in one ConfigMap, found {len(pools)}")
    return max_replicas, int(pools[0])


def main():
    if len(sys.argv) not in (2, 3):
        fail("usage: helm template ... | db-connection-budget-check.py <terraform dir> [<tfvars file>]")
    tf_dir = Path(sys.argv[1])
    tfvars = sys.argv[2] if len(sys.argv) == 3 else None
    check_main_tf(tf_dir)
    tf = terraform_values(tf_dir, tfvars)
    max_replicas, pool = chart_values([d for d in yaml.safe_load_all(sys.stdin) if d])

    source = tfvars or f"{tf_dir}/variables.tf defaults"
    errors = []
    if tf["service_max_replicas"] != max_replicas:
        errors.append(f"service_max_replicas is {tf['service_max_replicas']} ({source}) "
                      f"but the chart's HPA maxReplicas is {max_replicas}")
    if tf["db_pool_max"] != pool:
        errors.append(f"db_pool_max is {tf['db_pool_max']} ({source}) but the chart's DB_POOL_MAX is {pool}")
    if errors:
        fail("; ".join(errors))
    budget = max_replicas * pool + tf["db_connection_headroom"]
    print(f"db-connection-budget-check: {source}: alarm above {max_replicas} replicas x {pool} connections"
          f" + {tf['db_connection_headroom']} headroom = {budget}, matching the chart")


if __name__ == "__main__":
    main()
