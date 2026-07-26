package io.clusterinfra.rca.webconsole.config;

import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository;
import io.clusterinfra.rca.webconsole.security.AgentSecurityPolicy;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentEnrollmentSecurityStartupValidator implements ApplicationRunner {
    private final Environment environment;
    private final AgentEnrollmentRepository enrollments;
    private final AgentSecurityPolicy securityPolicy;

    public AgentEnrollmentSecurityStartupValidator(
        Environment environment,
        AgentEnrollmentRepository enrollments,
        AgentSecurityPolicy securityPolicy
    ) {
        this.environment = environment;
        this.enrollments = enrollments;
        this.securityPolicy = securityPolicy;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            return;
        }
        List<String> unsafeClusters = enrollments.findAllConfigurations().stream()
            .filter(profile -> securityPolicy.isKubernetesApiAudience(profile.audience()))
            .map(AgentEnrollmentRepository.AgentEnrollmentConfiguration::clusterId)
            .toList();
        if (!unsafeClusters.isEmpty()) {
            throw new IllegalStateException(
                "Production Agent enrollment profiles must use a dedicated audience; "
                    + "Kubernetes API audience configured for clusters: "
                    + String.join(", ", unsafeClusters)
            );
        }
    }
}
