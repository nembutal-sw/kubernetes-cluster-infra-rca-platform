package io.clusterinfra.rca.webconsole.config;

import io.clusterinfra.rca.webconsole.persistence.RcaRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class PlatformBootstrap implements ApplicationRunner {
    private final RcaRepository repository;
    private final RcaConsoleProperties properties;

    public PlatformBootstrap(RcaRepository repository, RcaConsoleProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        repository.ensureDefaultAdmin(
            properties.getDefaultAdminUsername(),
            properties.getDefaultAdminPassword()
        );
    }
}
