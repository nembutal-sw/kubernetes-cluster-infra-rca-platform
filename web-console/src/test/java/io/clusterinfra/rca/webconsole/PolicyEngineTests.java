package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.service.PolicyEngine;
import org.junit.jupiter.api.Test;

class PolicyEngineTests {
    private final PolicyEngine policy = new PolicyEngine();

    @Test
    void readOnlyRuleActionCanBeAutomated() {
        RecommendedAction action = policy.classify(
            "inspect_storage_state",
            "Inspect filesystem and inode state with df and findmnt.",
            "The operation is read-only."
        );

        assertThat(action.policy()).isEqualTo(PolicyLevel.AUTO_SAFE);
        assertThat(action.automationAllowed()).isTrue();
        assertThat(action.guardrails()).isEmpty();
    }

    @Test
    void llmActionCanNeverTriggerAutomation() {
        RecommendedAction action = policy.classify(
            "inspect_network_state",
            "Inspect routes and socket state.",
            "Read-only verification.",
            "llm",
            java.util.Map.of()
        );

        assertThat(action.policy()).isEqualTo(PolicyLevel.AUTO_SAFE);
        assertThat(action.automationAllowed()).isFalse();
        assertThat(action.guardrails()).contains("llm_output_cannot_trigger_direct_automation");
    }

    @Test
    void destructiveCommandIsNeverAutomaticallyExecuted() {
        RecommendedAction action = policy.classify(
            "collect_more_evidence",
            "Run reboot now and then collect logs.",
            "Attempt recovery."
        );

        assertThat(action.policy()).isEqualTo(PolicyLevel.NEVER_AUTO_EXECUTE);
        assertThat(action.automationAllowed()).isFalse();
        assertThat(action.riskFactors()).contains("node_power_action");
    }

    @Test
    void mutatingSystemdCommandRequiresApproval() {
        RecommendedAction action = policy.classify(
            "restart_kubelet",
            "Run systemctl restart kubelet.",
            "Restore node reporting."
        );

        assertThat(action.policy()).isEqualTo(PolicyLevel.APPROVAL_REQUIRED);
        assertThat(action.requiresApproval()).isTrue();
        assertThat(action.automationAllowed()).isFalse();
        assertThat(action.executionPlan()).isNotNull();
        assertThat(action.executionPlan().executable()).isFalse();
        assertThat(action.guardrails()).contains("direct_agent_mutation_disabled");
    }

    @Test
    void unknownActionFallsBackToManualInvestigation() {
        RecommendedAction action = policy.classify(
            "invented_action",
            "Investigate the condition.",
            "No registered policy exists."
        );

        assertThat(action.policy()).isEqualTo(PolicyLevel.MANUAL_INVESTIGATION);
        assertThat(action.automationAllowed()).isFalse();
        assertThat(action.guardrails()).contains("unknown_action_key");
    }
}
