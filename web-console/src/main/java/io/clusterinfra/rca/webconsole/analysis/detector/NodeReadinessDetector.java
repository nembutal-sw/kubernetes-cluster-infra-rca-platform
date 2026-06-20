package io.clusterinfra.rca.webconsole.analysis.detector;

import org.springframework.stereotype.Component;

@Component
public class NodeReadinessDetector extends AbstractStatusDetector {
    public NodeReadinessDetector() {
        super(
            "node-readiness",
            "node",
            "node_not_ready",
            "kubernetes",
            "Kubernetes reports the node as not ready.",
            "Correlate node conditions with kubelet, runtime, disk, memory, PID, and network evidence."
        );
    }
}
