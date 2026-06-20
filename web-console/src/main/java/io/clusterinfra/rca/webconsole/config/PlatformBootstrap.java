package io.clusterinfra.rca.webconsole.config;

import io.clusterinfra.rca.webconsole.persistence.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class PlatformBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final RcaConsoleProperties properties;

    public PlatformBootstrap(UserRepository users, RcaConsoleProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        users.ensureDefaultAdmin(
            properties.getDefaultAdminUsername(),
            properties.getDefaultAdminPassword()
        );
    }
}
