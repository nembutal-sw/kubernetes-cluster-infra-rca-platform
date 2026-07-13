package io.clusterinfra.rca.webconsole.gitops;

import io.clusterinfra.rca.webconsole.domain.RcaModels.GitOpsChange;

public interface GitOpsProvider {
    String id();

    PullRequestResult createPullRequest(
        GitOpsChange change,
        String content,
        String title,
        String body
    );

    record PullRequestResult(
        long number,
        String url,
        String state,
        String headSha
    ) {
    }
}
