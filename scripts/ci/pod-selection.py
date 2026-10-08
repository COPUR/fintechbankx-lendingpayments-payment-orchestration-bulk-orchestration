#!/usr/bin/env python3
"""Checks a rendered chart (stdin) for how its pods are labelled and selected.

- The API pods (Deployment) and the migration Job pod both carry
  app.kubernetes.io/name = <service account>: the mesh repo's NetworkPolicies
  grant Aurora egress by that label only.
- No Deployment, Service, PodDisruptionBudget or NetworkPolicy selector
  matches the Job pod, and each one matches the API pods (the component label
  keeps them apart).
- Only the API pods carry fintechbankx.io/service-id=<service id>: platform
  scraping and alerts key on it.

Usage: helm template ... | python3 scripts/ci/pod-selection.py <service account> <service id>
"""
import sys

import yaml


def matches(selector, labels):
    if selector is None:
        return False
    match_labels = selector.get("matchLabels", selector) if isinstance(selector, dict) else {}
    if "matchExpressions" in selector:
        raise SystemExit("matchExpressions selectors are not supported by this check")
    return bool(match_labels) and all(labels.get(k) == v for k, v in match_labels.items())


def main():
    service_account, service_id = sys.argv[1], sys.argv[2]
    docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
    api_pods = [d["spec"]["template"]["metadata"]["labels"] for d in docs if d["kind"] == "Deployment"]
    job_pods = [d["spec"]["template"]["metadata"]["labels"] for d in docs if d["kind"] == "Job"]
    errors = []
    if len(api_pods) != 1 or len(job_pods) != 1:
        errors.append(f"expected one Deployment and one Job, got {len(api_pods)} and {len(job_pods)}")
    else:
        api, job = api_pods[0], job_pods[0]
        for name, labels in (("API pod", api), ("migration Job pod", job)):
            if labels.get("app.kubernetes.io/name") != service_account:
                errors.append(f"{name} lacks app.kubernetes.io/name={service_account} (mesh Aurora egress)")
        if api.get("fintechbankx.io/service-id") != service_id:
            errors.append(f"API pod lacks fintechbankx.io/service-id={service_id}")
        if "fintechbankx.io/service-id" in job:
            errors.append("migration Job pod carries fintechbankx.io/service-id (platform scraping and alerts)")
        selectors = []
        for d in docs:
            kind, name = d["kind"], d["metadata"]["name"]
            if kind == "Deployment":
                selectors.append((kind, name, d["spec"]["selector"]))
            elif kind == "Service":
                selectors.append((kind, name, {"matchLabels": d["spec"]["selector"]}))
            elif kind in ("PodDisruptionBudget",):
                selectors.append((kind, name, d["spec"]["selector"]))
            elif kind == "NetworkPolicy":
                selectors.append((kind, name, d["spec"]["podSelector"]))
        if not selectors:
            errors.append("no selectors found")
        for kind, name, selector in selectors:
            if matches(selector, job):
                errors.append(f"{kind} {name} selects the migration Job pod")
            if not matches(selector, api):
                errors.append(f"{kind} {name} does not select the API pods")
    for error in errors:
        print(f"pod-selection: {error}", file=sys.stderr)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
