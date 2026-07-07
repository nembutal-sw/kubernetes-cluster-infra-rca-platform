package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CollectorSelectionServiceTests {
    private final CollectorSelectionService service = new CollectorSelectionService();

    @Test
    void diskPressureCollectorsIncludeDiskInodeAndKernelEvidence() {
        assertThat(service.collectorsFor("DiskPressure"))
            .containsExactly("node", "disk", "inode", "kernel", "systemd");
    }

    @Test
    void networkUnavailableCollectorsIncludeCniDnsAndConntrackEvidence() {
        assertThat(service.collectorsFor("NetworkUnavailable"))
            .contains("cni", "dns", "conntrack", "kernel");
    }

    @Test
    void unknownAlertFallsBackToBroadNodeInfrastructureCollectors() {
        assertThat(service.collectorsFor("CustomNodeSignal"))
            .contains("node", "runtime", "disk", "inode", "memory", "network", "kernel");
    }
}
