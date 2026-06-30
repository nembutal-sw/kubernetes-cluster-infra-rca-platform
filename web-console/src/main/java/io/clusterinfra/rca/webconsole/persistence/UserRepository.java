package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcRcaStore store;

    public UserRepository(JdbcRcaStore store) {
        this.store = store;
    }

    public Optional<UserAccount> authenticate(String username, String password) {
        return store.authenticateUser(username, password);
    }

    public UserAccount ensureDefaultAdmin(String username, String password) {
        return store.ensureDefaultAdmin(username, password);
    }

    public Optional<UserAccount> find(String userId) {
        return store.getUserById(userId);
    }

    public boolean changePassword(String userId, String currentPassword, String newPassword) {
        return store.changeUserPassword(userId, currentPassword, newPassword);
    }

    public Optional<UserAccount> changeLoginId(String userId, String currentPassword, String newUsername) {
        return store.changeUserLoginId(userId, currentPassword, newUsername);
    }
}
