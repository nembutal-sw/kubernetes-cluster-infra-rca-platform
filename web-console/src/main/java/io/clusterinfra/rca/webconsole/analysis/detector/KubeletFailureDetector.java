package io.clusterinfra.rca.webconsole.analysis.detector;

import org.springframework.stereotype.Component;

@Component
public class KubeletFailureDetector extends AbstractStatusDetector {
    public KubeletFailureDetector() {
        super(
            "kubelet-failure",
            "kubelet",
            "kubelet_unit_unhealthy",
            "kubelet",
            "Kubelet is not active or healthy.",
            "Inspect kubelet status, logs, API connectivity, and runtime socket."
        );
    }
}
