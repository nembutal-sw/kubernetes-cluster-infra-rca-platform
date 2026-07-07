package io.clusterinfra.rca.webconsole.persistence;

public class DuplicateLoginIdException extends RuntimeException {
    public DuplicateLoginIdException(String loginId) {
        super("login id already exists: " + loginId);
    }
}
