package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class UserSessionRepository {
    private final JdbcRcaStore store;

    public UserSessionRepository(JdbcRcaStore store) {
        this.store = store;
    }

    public Optional<UserAccount> findUserByToken(String token) {
        return store.getUserBySessionToken(token);
    }

    public String create(String userId, Instant expiresAt) {
        return store.createUserSession(userId, expiresAt);
    }

    public boolean revoke(String token) {
        return store.revokeUserSession(token);
    }

    public int deleteExpiredBefore(Instant cutoff) {
        return store.deleteExpiredSessions(cutoff);
    }
}
