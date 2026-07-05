package io.clusterinfra.rca.webconsole.config;

import io.clusterinfra.rca.webconsole.persistence.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class PlatformBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final RcaConsoleProperties properties;
    private final BootstrapReadiness readiness;

    public PlatformBootstrap(UserRepository users, RcaConsoleProperties properties, BootstrapReadiness readiness) {
        this.users = users;
        this.properties = properties;
        this.readiness = readiness;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getDefaultAdminUsername() == null
            || properties.getDefaultAdminUsername().isBlank()
            || properties.getDefaultAdminPassword() == null
            || properties.getDefaultAdminPassword().isBlank()) {
            readiness.markCompleted();
            return;
        }
        users.ensureDefaultAdmin(
            properties.getDefaultAdminUsername(),
            properties.getDefaultAdminPassword()
        );
        readiness.markCompleted();
    }
}
