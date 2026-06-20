package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.PolicyLevel;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ActionPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PolicyEngine {
    private static final Map<String, Rule> RULES = rules();
    private static final List<RiskPattern> NEVER = List.of(
        risk("\\b(reboot|shutdown|poweroff|halt)\\b", "node_power_action"),
        risk("\\brm\\s+-[^\\n]*[rf]", "recursive_delete"),
        risk("\\b(mkfs|wipefs|shred|fdisk|parted)\\b", "filesystem_destructive_command"),
        risk("\\bdd\\s+(if|of)=", "block_device_write"),
        risk("\\bkubeadm\\s+reset\\b", "cluster_reset"),
        risk("\\betcd(?:ctl)?\\b.*\\b(member\\s+)?(remove|delete)\\b", "etcd_membership_change"),
        risk("\\bkubectl\\s+(delete|replace)\\b", "kubernetes_object_mutation"),
        risk("\\b(crictl|docker)\\s+(rm|rmi|stop|kill)\\b", "runtime_object_mutation")
    );
    private static final List<RiskPattern> GITOPS = List.of(
        risk("\\b(CNI|CoreDNS|DNS|MTU|conntrack|sysctl|kubelet)\\b.*\\b(config|change|update|patch|limit)\\b",
            "configuration_change"),
        risk("\\b(ConfigMap|DaemonSet|Deployment|Helm|manifest)\\b.*\\b(change|update|patch|apply|PR)\\b",
            "configuration_change"),
        risk("\\bkubectl\\s+(apply|patch|scale|rollout|edit)\\b", "direct_kubernetes_configuration_change"),
        risk("\\bsysctl\\s+-w\\b", "direct_kernel_parameter_change")
    );
    private static final List<RiskPattern> APPROVAL = List.of(
        risk("\\bsystemctl\\s+(restart|start|stop|reload)\\b", "systemd_unit_mutation"),
        risk("\\b(restart|cordon|drain|evict|cleanup|clean up|truncate)\\b", "node_or_workload_mutation"),
        risk("\\bip\\s+(?:link|route|addr|neigh|rule)\\s+(?:set|add|del|delete|replace|flush)\\b",
            "direct_network_mutation"),
        risk("\\bconntrack\\s+-(?:D|F)\\b", "conntrack_table_mutation")
    );
    private static final Pattern READ_ONLY = Pattern.compile(
        "\\b(collect|check|inspect|list|get|read|status|describe|logs?|journalctl|dmesg|cat|df|du|findmnt|lsblk|free|vmstat|iostat|ss|netstat|nstat)\\b",
        Pattern.CASE_INSENSITIVE
    );

    public RecommendedAction classify(String actionKey, String action, String reason) {
        return classify(actionKey, action, reason, "rule_based", Map.of());
    }

    public RecommendedAction classify(
        String actionKey,
        String action,
        String reason,
        String source,
        Map<String, Object> context
    ) {
        String key = normalize(actionKey);
        String normalizedSource = normalize(source);
        List<String> guardrails = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        Rule rule = RULES.get(key);
        if (rule == null) {
            key = "manual_investigation";
            rule = RULES.get(key);
            guardrails.add("unknown_action_key");
        }

        PolicyLevel policy = rule.policy();
        String automationMode = rule.automationMode();
        risks.addAll(rule.risks());
        String policyText = action + "\n" + reason + "\n" + context;

        List<String> neverRisks = matched(policyText, NEVER);
        List<String> gitopsRisks = matched(policyText, GITOPS);
        List<String> approvalRisks = matched(policyText, APPROVAL);
        if (!neverRisks.isEmpty()) {
            policy = PolicyLevel.NEVER_AUTO_EXECUTE;
            automationMode = "prohibited";
            guardrails.add("never_auto_execute_pattern");
            risks.addAll(neverRisks);
        } else if (!gitopsRisks.isEmpty() && lessRestrictive(policy, PolicyLevel.GITOPS_PR_ONLY)) {
            policy = PolicyLevel.GITOPS_PR_ONLY;
            automationMode = "gitops_pr";
            guardrails.add("configuration_change_requires_gitops_pr");
            risks.addAll(gitopsRisks);
        } else if (!approvalRisks.isEmpty() && lessRestrictive(policy, PolicyLevel.APPROVAL_REQUIRED)) {
            policy = PolicyLevel.APPROVAL_REQUIRED;
            automationMode = "operator_approval";
            guardrails.add("mutation_requires_operator_approval");
            risks.addAll(approvalRisks);
        }

        if (policy == PolicyLevel.AUTO_SAFE && !READ_ONLY.matcher(policyText).find()) {
            policy = PolicyLevel.MANUAL_INVESTIGATION;
            automationMode = "manual";
            guardrails.add("auto_safe_requires_read_only_signal");
            risks.add("insufficient_read_only_evidence");
        }
        if ("llm".equals(normalizedSource)) {
            guardrails.add("llm_output_cannot_trigger_direct_automation");
        }

        boolean automationAllowed = policy == PolicyLevel.AUTO_SAFE
            && !"llm".equals(normalizedSource)
            && guardrails.isEmpty();
        ActionPlan executionPlan = actionPlan(key, normalizedSource, policy);
        return new RecommendedAction(
            action,
            policy,
            reason,
            key,
            normalizedSource.isBlank() ? "rule_based" : normalizedSource,
            automationMode,
            automationAllowed,
            policy == PolicyLevel.APPROVAL_REQUIRED,
            policy == PolicyLevel.APPROVAL_REQUIRED || policy == PolicyLevel.GITOPS_PR_ONLY,
            dedupe(guardrails),
            dedupe(risks),
            executionPlan
        );
    }

    private ActionPlan actionPlan(String key, String source, PolicyLevel policy) {
        boolean ruleBased = !"llm".equals(source);
        return switch (key) {
            case "restart_kubelet" -> plan(
                "restart_systemd_unit",
                Map.of("unit", "kubelet"),
                List.of("systemctl restart kubelet", "systemctl is-active kubelet"),
                ruleBased && policy == PolicyLevel.APPROVAL_REQUIRED,
                60
            );
            case "restart_containerd" -> plan(
                "restart_systemd_unit",
                Map.of("unit", "containerd"),
                List.of("systemctl restart containerd", "systemctl is-active containerd"),
                ruleBased && policy == PolicyLevel.APPROVAL_REQUIRED,
                90
            );
            case "restart_container_runtime" -> plan(
                "restart_detected_runtime",
                Map.of(),
                List.of("systemctl restart <detected-runtime>", "systemctl is-active <detected-runtime>"),
                ruleBased && policy == PolicyLevel.APPROVAL_REQUIRED,
                90
            );
            case "cordon_node" -> new ActionPlan(
                "kubectl_cordon",
                Map.of("node", "<incident-node>"),
                List.of("kubectl cordon <incident-node>"),
                null,
                false,
                60
            );
            case "drain_node" -> new ActionPlan(
                "kubectl_drain",
                Map.of("node", "<incident-node>"),
                List.of("kubectl drain <incident-node> --ignore-daemonsets --delete-emptydir-data"),
                null,
                false,
                900
            );
            case "update_cni_mtu" -> new ActionPlan(
                "gitops_patch",
                Map.of(),
                List.of(),
                "spec:\n  template:\n    spec:\n      containers:\n        - name: cni\n          env:\n            - name: MTU\n              value: \"<review-required>\"",
                false,
                0
            );
            case "increase_conntrack_limit" -> new ActionPlan(
                "gitops_patch",
                Map.of(),
                List.of("sysctl net.netfilter.nf_conntrack_max"),
                "net.netfilter.nf_conntrack_max: <review-required>",
                false,
                0
            );
            case "inspect_storage_state" -> plan(
                "read_only_preview",
                Map.of(),
                List.of("df -hT", "df -i", "iostat -xz 1 5"),
                false,
                30
            );
            case "inspect_network_state" -> plan(
                "read_only_preview",
                Map.of(),
                List.of("ip -s link", "ip route", "ss -s", "conntrack -S"),
                false,
                30
            );
            case "inspect_kernel_state" -> plan(
                "read_only_preview",
                Map.of(),
                List.of("dmesg -T | tail -n 300"),
                false,
                30
            );
            default -> null;
        };
    }

    private ActionPlan plan(
        String commandKey,
        Map<String, String> parameters,
        List<String> preview,
        boolean executable,
        int timeoutSeconds
    ) {
        return new ActionPlan(commandKey, parameters, preview, null, executable, timeoutSeconds);
    }

    private static Map<String, Rule> rules() {
        Map<String, Rule> rules = new LinkedHashMap<>();
        rules.put("collect_more_evidence", rule(PolicyLevel.AUTO_SAFE, "read_only"));
        rules.put("collect_linux_low_level_evidence", rule(PolicyLevel.AUTO_SAFE, "read_only"));
        rules.put("inspect_kernel_state", rule(PolicyLevel.AUTO_SAFE, "read_only"));
        rules.put("inspect_network_state", rule(PolicyLevel.AUTO_SAFE, "read_only"));
        rules.put("inspect_storage_state", rule(PolicyLevel.AUTO_SAFE, "read_only"));
        rules.put("restart_kubelet", rule(PolicyLevel.APPROVAL_REQUIRED, "operator_approval",
            "node_agent_disruption", "workload_status_change"));
        rules.put("restart_containerd", rule(PolicyLevel.APPROVAL_REQUIRED, "operator_approval",
            "container_runtime_disruption", "workload_impact"));
        rules.put("restart_container_runtime", rule(PolicyLevel.APPROVAL_REQUIRED, "operator_approval",
            "container_runtime_disruption", "workload_impact"));
        rules.put("cordon_node", rule(PolicyLevel.APPROVAL_REQUIRED, "operator_approval",
            "workload_rescheduling", "capacity_reduction"));
        rules.put("drain_node", rule(PolicyLevel.APPROVAL_REQUIRED, "operator_approval",
            "workload_rescheduling", "pod_eviction"));
        rules.put("cleanup_disk", rule(PolicyLevel.APPROVAL_REQUIRED, "operator_approval",
            "data_loss", "runtime_cache_mutation"));
        rules.put("open_gitops_pr", rule(PolicyLevel.GITOPS_PR_ONLY, "gitops_pr",
            "configuration_change", "review_required"));
        rules.put("update_cni_mtu", rule(PolicyLevel.GITOPS_PR_ONLY, "gitops_pr",
            "network_partition", "configuration_change"));
        rules.put("update_dns_config", rule(PolicyLevel.GITOPS_PR_ONLY, "gitops_pr",
            "cluster_dns_disruption", "configuration_change"));
        rules.put("increase_conntrack_limit", rule(PolicyLevel.GITOPS_PR_ONLY, "gitops_pr",
            "kernel_parameter_change", "configuration_change"));
        rules.put("reboot_node", rule(PolicyLevel.NEVER_AUTO_EXECUTE, "prohibited",
            "node_reboot", "workload_outage"));
        rules.put("etcd_member_remove", rule(PolicyLevel.NEVER_AUTO_EXECUTE, "prohibited",
            "quorum_loss", "data_loss"));
        rules.put("delete_workload", rule(PolicyLevel.NEVER_AUTO_EXECUTE, "prohibited",
            "service_outage", "data_loss"));
        rules.put("manual_hardware_check", rule(PolicyLevel.MANUAL_INVESTIGATION, "manual",
            "external_dependency", "human_judgment_required"));
        rules.put("manual_investigation", rule(PolicyLevel.MANUAL_INVESTIGATION, "manual",
            "human_judgment_required"));
        return Map.copyOf(rules);
    }

    private static Rule rule(PolicyLevel policy, String mode, String... risks) {
        return new Rule(policy, mode, List.of(risks));
    }

    private static RiskPattern risk(String pattern, String risk) {
        return new RiskPattern(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE), risk);
    }

    private static List<String> matched(String text, List<RiskPattern> patterns) {
        return patterns.stream()
            .filter(item -> item.pattern().matcher(text).find())
            .map(RiskPattern::risk)
            .toList();
    }

    private static boolean lessRestrictive(PolicyLevel current, PolicyLevel candidate) {
        return precedence(current) < precedence(candidate);
    }

    private static int precedence(PolicyLevel level) {
        return switch (level) {
            case AUTO_SAFE -> 0;
            case MANUAL_INVESTIGATION -> 1;
            case APPROVAL_REQUIRED -> 2;
            case GITOPS_PR_ONLY -> 3;
            case NEVER_AUTO_EXECUTE -> 4;
        };
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
    }

    private static List<String> dedupe(List<String> values) {
        return values.stream().distinct().toList();
    }

    private record Rule(PolicyLevel policy, String automationMode, List<String> risks) {
    }

    private record RiskPattern(Pattern pattern, String risk) {
    }
}
