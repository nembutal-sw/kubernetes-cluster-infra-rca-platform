RCA CI/CD review draft — 2026-10-18

These files are an implemented local draft. They have not been installed in Jenkins,
executed on ci-agent-01, pushed to Git, or used to publish any image or pull request.
The original source clone and existing successful Jenkins job were not changed.

Intended separate pipeline branch layout:
  ci/Jenkinsfile                 <- this Jenkinsfile
  ci/Dockerfile.platform-runtime <- this Dockerfile.platform-runtime
  ci/scripts/*                  <- scripts/*
  ci/tests/*                    <- tests/* (local logic tests; optional in CI)
Create a separate Jenkins Pipeline from SCM test job pointing at ci/Jenkinsfile.
Do not paste this into or replace the existing rca-platform-ci inline pipeline.
The pipeline records SCM.GIT_COMMIT of its own reviewed branch, then checks out that
exact full 40-character SHA separately as the application source. It rejects unequal
pipeline/source SHAs. A feature-branch trial therefore tests that feature commit;
after adoption, a main-branch build tests that exact main commit. Both SHAs are
recorded in image/JAR publication evidence; no separate moving main checkout is used.

Actual Jenkins facts supplied by the coordinating agent:
- Jenkins 2.580.1; existing job rca-platform-ci is an inline four-stage job.
- Its stages are Git Checkout / Env / Backend Test / Integrated Package.
- Java 21.0.12.1 and Maven 3.9.9 are present; node/npm were absent from the normal PATH.
- Existing Jenkins credential inventory had ci-agent-01-ssh, no registry credential.
- New private GitOps repository: nembutal-sw/rca-platform-gitops, main branch.
These are observed facts from the coordinating agent; this subtask performed no
infrastructure access or credential inspection.

Configured targets and credential REFERENCES
- Agent label: linux-ci; effective user jenkins-agent; hostname ci-agent-01.
- Source: nembutal-sw/kubernetes-cluster-infra-rca-platform at the pipeline SCM SHA.
- Registry: HTTPS 10.7.254.6:5000, TLS verification remains enabled.
- Jenkins username/password credential ID: rca-registry-build (build account only).
- Jenkins secret-text fine-grained GitHub token ID: rca-gitops-github.
- GitOps values path: environments/onprem/platform/values.yaml.
- Default user Node path: ~/.local/share/rca-cicd/node-v22.23.3-linux-x64/bin.
  Official archive SHA-256 supplied for installation verification:
  df450af89261115ef9f9e3830c3eeb2cc9213b63c720b1af623cb5dcbe2e02de
  Set RCA_NODE_BIN in the new job if the checksum-verified user installation uses
  another path. Java 21, Maven, Python >=3.12 with venv, curl, flock and rootless
  Podman with delegated cgroup-v2 CPU/memory controllers must be available.
- The GitOps token must be limited to the GitOps repository, Contents: read/write
  and Pull requests: read/write; Metadata read is implicit. It grants no access to
  the source repository, Jenkins, Registry, cluster or Argo CD.
Credential values do not belong in these files. The registry credential is bound
only while publishing and stored in a job-owned temporary authfile, deleted on exit.
The GitHub token is bound only in the PR stage, used in memory in HTTPS headers,
and never passed in a URL, shell argument, Git remote, response log or artifact.

Gates and resulting behavior
1. Poll source SCM every ten minutes using H/10, not every minute. Concurrent builds
   of this new job are disabled; a queued build does not interrupt the previous one.
2. Jenkins exposes Backend Tests, Frontend Tests, Python Agent Tests and Integration
   Package as separate visible stages. test.sh accepts only the corresponding fixed
   modes backend, frontend, python and aggregate. All three suites write their own
   success marker containing the same full source SHA.
3. Backend Tests runs mvn -Pfrontend clean verify once. This includes Java unit tests, packaging and
   Failsafe integration tests. Then require both existing XML-report verifiers:
   scripts/verify_database_compatibility_report.py (four DB compatibility methods),
   scripts/verify_agent_enrollment_migration_report.py (two packaged CLI methods).
   Missing/skipped DB tests fail the new job. The earlier user-provided 377-test
   result with skips is not treated as proof that these full gates passed.
   Because the canonical Maven frontend profile packages React assets, the integrated
   JAR is already produced in this stage; Maven is not run again later.
   Frontend Tests runs locked npm install, Vitest with one worker plus default/JUnit
   reporters, TypeScript check and Vite build. Python Agent Tests compiles Python
   and runs all tests under tests/. Jenkins publishes frontend and Python XML along
   with Surefire/Failsafe XML from Backend Tests.
   Integration Package requires all three current-SHA markers, rechecks XML evidence
   and validates React index/JS assets in the existing integrated JAR. These are
   inexpensive evidence checks, not another test or package run.
4. Native Maven Testcontainers connects to a job-owned, ephemeral rootless Podman
   API child process only in Backend Tests. Other test modes do not start that API.
   No host/socket bind mount, Docker daemon, sudo, privileged
   container, persistent system service, or change to another CI job is used.
   Ryuk is disabled for this rootless setup. Normal JUnit/Testcontainers teardown
   removes test DB containers; the job stops its own API child on exit. A forced
   agent/process termination may leave test containers; this draft deliberately
   performs no global prune or cleanup of other jobs' containers.
5. Build Platform runtime from the exact tested integrated JAR, checking React index
   and JS assets in the JAR first. The runtime Dockerfile preserves the audited
   upstream pinned runtime base and non-root settings. This avoids rerunning Maven
   inside an image build where DB tests could otherwise silently skip.
6. Build Agent from the exact checkout's Dockerfile.agent with a separate owned
   source context. Normalize copied Python files to 0644/directories 0755 to prevent
   the previously observed umask077/non-root import failure. No source bytes change.
7. Both images receive full-SHA OCI revision labels and tags git-FULLSHA-bBUILD.
   Builds explicitly use OCI isolation, TLS verification and no inherited HTTP proxy,
   with one CPU, 2GiB, one Buildah job and rootless networking. Both images
   must default to 65532:65532. Agent must import its runtime modules as that user
   with network disabled and read-only filesystem. Platform must run non-root and
   pass readiness in a temporary isolated H2 fixture bound only to 127.0.0.1:18088.
   No production DB, node, Secret, Agent enrollment or external app is contacted.
8. PUBLISH_IMAGES defaults false. When true, both preceding gates must have passed.
   A tag lookup must specifically prove it is missing; errors and existing tags
   fail closed. Push uses normal TLS verification and digest files. Both resulting
   repo@sha256 references must be remotely readable before publication is confirmed.
   Tags are treated as immutable by CI; server-side immutability is not assumed.
   Two Registry pushes are not a transaction: if the second push fails, the first
   immutable image may remain. No GitOps PR is then created; nothing is rolled back.
9. OPEN_GITOPS_PR defaults false and requires PUBLISH_IMAGES. The helper reads the
   values file at one immutable GitOps main SHA, creates a new branch at that same
   SHA, changes exactly platform.image.digest and opens a review PR. The helper
   preserves other bytes/comments and rejects ambiguous mappings or another image
   repository. It does not merge, invoke Argo CD or update Agent/DB/RBAC/storage.
   Failed/unknown API writes are not automatically retried; inspect candidate
   branch and PR state first to avoid duplicate external writes.

First execute the separate test job with PUBLISH_IMAGES=false and OPEN_GITOPS_PR=false.
After all real gates pass and both scoped Jenkins credentials are provisioned,
explicitly change the approved production job defaults to enable image publication
and GitOps review PR creation on SCM changes. The draft deliberately does not enable
external writes before that validation. Existing single-executor scheduling and
disableConcurrentBuilds serialize this job; every tag includes full SHA/build number
and an existing registry tag is rejected. No global lock or other job is modified.

Optional browser verification
RUN_BROWSER_TESTS defaults false. Vitest/type-check/build are always required.
When requested, Playwright downloads Chromium to the user cache, runs existing
isolated H2 workflow E2E, and tests routes against the temporary image fixture.
It never requests --with-deps or sudo. Missing browser OS dependencies fail only
the explicitly requested browser run. Test fixture passwords in build-gate.sh and
the upstream E2E config are public, isolated test data, not operating credentials.

Source audit (read-only, commit af730cd657581dde532526be04b6478555351c52)
- No repository Jenkinsfile or nested AGENTS.md was found. Workspace AGENTS.md says
  sources/ is read-only; no synced source was changed.
- .github/workflows/ci.yml:13-28 Python compile/pytest; :30-48 frontend unit/build;
  :50-67 Maven verify plus both report gates; :80-108 optional browser reference.
- web-console/pom.xml:23 Java21; :32-33 Node22.22.0/npm11.3.0; :200 Failsafe;
  :231-244 integrated frontend install/build; :253-281 copy/check React assets.
  The frontend Maven profile builds assets but does not execute npm test.
- web-console/frontend/package.json:7 Node floor; :11-16 build/test/e2e/routes.
- pyproject.toml:5 supports Python>=3.10; :15-16 test discovery; existing CI uses3.12.
- DatabaseCompatibilityTests.java:81 and AgentEnrollmentMigrationPackagedJarIT.java:21
  use disabledWithoutDocker=true; skipped tests alone can therefore yield green Maven.
- Dockerfile.agent:23 COPY then :25 USER65532; OCI labels at :7-11.
- Dockerfile.web-console:15 integrated verify; :39 JAR copy; :43 USER65532;
  OCI labels at :25-29. The draft runtime image consumes the gated JAR instead.
- playwright.config.ts:11 one worker; :28-47 isolated local H2 server settings.

Local validation performed
- All five shell helper files passed bash -n (syntax only; helpers not executed).
- Python source parsed successfully; eleven local unittest cases passed, including
  digest-only edits, comments/CRLF, duplicate and repository rejection, immutable
  GitOps base snapshot, one-file PUT, no merge request and pipeline/source mismatch
  rejection before any API access. The API was a fake object;
  no network or operating credential was used.
- Original source git status remained clean.
Pending real validation: Jenkins declarative validation, new-job checkout and tool
paths, Python venv support, Podman API/Testcontainers compatibility, zero-skip DB
results, image/runtime gates, Registry credential creation/TLS push and real PR.
No runtime success is claimed for those pending checks.
