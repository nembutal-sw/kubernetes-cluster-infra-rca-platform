package io.clusterinfra.rca.webconsole.service;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CollectorSelectionService {
    public List<String> collectorsFor(String alertName) {
        return switch (alertName) {
            case "NodeNotReady", "KubeletDown", "KubeletUnhealthy" ->
                List.of("node", "kubernetes", "systemd", "runtime", "kubelet", "kernel", "network", "conntrack");
            case "DiskPressure" -> List.of("node", "disk", "inode", "kernel", "systemd");
            case "MemoryPressure" -> List.of("node", "memory", "kernel", "systemd", "process");
            case "PIDPressure" -> List.of("node", "process", "systemd", "kernel");
            case "NetworkUnavailable" ->
                List.of("node", "kubernetes", "network", "cni", "dns", "conntrack", "kernel");
            case "ContainerdDown", "ContainerRuntimeUnhealthy" ->
                List.of("runtime", "systemd", "kernel", "disk");
            case "CoreDNSUnhealthy", "CoreDNSLatencyHigh" ->
                List.of("dns", "network", "cni", "conntrack", "kubernetes");
            case "EtcdLatencyHigh", "APIServerLatencyHigh" ->
                List.of("node", "kubernetes", "network", "dns", "systemd", "kernel", "disk");
            default -> List.of("node", "systemd", "runtime", "disk", "inode", "memory", "process", "network", "kernel");
        };
    }
}
