from scripts.llm_prometheus_rule_test import extract_rule_groups


def test_extracts_prometheus_rule_groups_without_crd_wrapper():
    rendered = """apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
spec:
  groups:
    - name: rca.llm
      rules:
        - alert: ClusterRcaLlmHighLatency
        - alert: ClusterRcaLlmHighErrorRate
        - alert: ClusterRcaLlmUsageMetadataMissing
        - alert: ClusterRcaLlmCircuitBreakerOpen
        - alert: ClusterRcaLlmEstimatedCostBudgetExceeded
"""

    result = extract_rule_groups(rendered)

    assert result.startswith("groups:\n  - name: rca.llm")
    assert "kind: PrometheusRule" not in result


def test_rejects_incomplete_prometheus_rule_render():
    rendered = """spec:
  groups:
    - name: rca.llm
      rules: []
"""

    try:
        extract_rule_groups(rendered)
    except ValueError as error:
        assert "incomplete" in str(error)
    else:
        raise AssertionError("incomplete rule render must be rejected")
