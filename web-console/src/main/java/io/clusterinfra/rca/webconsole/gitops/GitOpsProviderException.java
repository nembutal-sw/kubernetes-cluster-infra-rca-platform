package io.clusterinfra.rca.webconsole.gitops;

public class GitOpsProviderException extends RuntimeException {
    public GitOpsProviderException(String message) {
        super(message);
    }

    public GitOpsProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
