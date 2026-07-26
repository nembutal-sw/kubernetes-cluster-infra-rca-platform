package io.clusterinfra.rca.webconsole;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.security.OpaqueTokenHasher;
import io.clusterinfra.rca.webconsole.security.PasswordHasher;
import io.clusterinfra.rca.webconsole.security.Sha256Digest;
import io.clusterinfra.rca.webconsole.security.TokenGenerator;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TestSecurity {
    private static final String TEST_PEPPER =
        "unit-test-opaque-token-pepper-32-bytes-minimum";

    private TestSecurity() {
    }

    public static PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    public static TokenGenerator tokenGenerator() {
        return new TokenGenerator();
    }

    public static Sha256Digest sha256Digest() {
        return new Sha256Digest();
    }

    public static OpaqueTokenHasher opaqueTokenHasher() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getSecurity().setOpaqueTokenPepper(TEST_PEPPER);
        return new OpaqueTokenHasher(properties);
    }

    public static ClusterRepository clusterRepository(JdbcTemplate jdbc) {
        return new ClusterRepository(
            jdbc,
            tokenGenerator(),
            opaqueTokenHasher(),
            passwordHasher()
        );
    }
}
