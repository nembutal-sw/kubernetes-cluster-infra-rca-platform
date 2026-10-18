RCA delivery CI candidate

Use a separate reviewed SCM job. Pipeline checkout and application checkout must
share the same full 40-character Git SHA. SCM polling runs every ten minutes at a
Jenkins-selected offset; disableConcurrentBuilds serializes this job. Publication,
GitOps PR creation default to true after all mandatory gates. Optional browser tests
remain false. A manual verification build can explicitly disable publication and PRs.

Mandatory stage sequence
1. Runtime prerequisites: rootless local Podman, cgroup v2 CPU/memory delegation,
   Java21, Maven, Node>=22.22, Python>=3.12 and user-owned isolated Python venv.
2. Backend Tests: one canonical mvn -Pfrontend clean verify. This builds the React
   assets and integrated Spring JAR, runs Java tests and real DB integration tests.
   A private short-lived Podman API child serves Testcontainers for this stage only.
   Existing DB/enrollment report gates reject silent integration-test skips.
3. Frontend Tests: npm ci, Vitest with JUnit output, then frontend type-check/build.
4. Python Agent Tests: compileall and pytest with JUnit output.
5. Integration Package: check all three same-SHA markers, actual JUnit testcase
   counts, and React assets in the existing JAR. Maven is not run a second time.
6. Build and non-root image gates: package the tested JAR into the Platform image,
   build Agent from the same source, require UID65532 and the complete OCI revision.
   Agent imports in a read-only, network-none sandbox. Platform passes local-only
   readiness against an isolated H2 fixture. Podman builds use one CPU via supported
   --cpu-period/--cpu-quota and 2GiB memory, OCI isolation and verified Registry TLS.
7. Agent safe observation mode: run the built image's real capability CLI and one
   register/heartbeat/evidence cycle against in-container loopback fixtures. Require
   safe mode, disabled privileged/eBPF collectors, no forbidden external commands, no mutating
   Kubernetes requests and evidence <=64KiB. Only exact read-only uname -p is allowed
   for CPython platform metadata; dmesg/eBPF/systemctl are forbidden. No host mounts
   or real cluster access.
8. Image security scan and SBOM: scan both actual local image IDs with Trivy0.76.0,
   OS and library packages, before entering any publishing credential scope. Reject
   fixable HIGH/CRITICAL findings, unsupported coverage, stale DBs, scanner failures
   or missing evidence. Reports include all severities under the same ignore-unfixed
   policy. Retain CycloneDX SBOMs and JSON evidence even if the gate fails. Each scan
   uses a user transient scope with CPU100%, memory1GiB, tasks128 and concurrency1.
9. Gated automatic publication: all runtime, safe-mode and security markers must
   match the full SHA. Require scanned image IDs to match both runtime and scanner
   evidence, push those immutable local IDs under full-SHA/build-number remote tags,
   and reject an existing Registry tag. Verify tag manifest raw bytes, digest header
   and push digest file agree; verify config blob hash, full OCI revision and UID.
   Remote config digest must equal the scanned image ID. TLS never bypasses CA or
   hostname checks. Two image pushes are not transactional; failure blocks the PR.
10. Automatic GitOps proposal: change only the Platform digest at one immutable
   GitOps main snapshot and open a review PR. Never merge, sync Argo, auto-deploy
   Agent, or modify DB/PVC/RBAC. No automatic prune or self-healing is configured.

Pinned user-owned tools, provisioned separately without sudo or system changes
- Node22.23.3: ~/.local/share/rca-cicd/node-v22.23.3-linux-x64/bin.
- pip26.2.1 wheel: ~/.local/share/rca-cicd/pip-26.2.1-py3-none-any.whl;
  SHA25671138adf1f4ca900cdb7d289c21b7494329f2332b6d85f0e1c42108c0384ed3e.
  Create venv --without-pip, then install that verified wheel offline into the venv.
- Trivy0.76.0: ~/.local/share/rca-cicd/trivy-v0.76.0/trivy;
  executable SHA2561f889576636db0623539cb339df7d06c40ebf39e7919489eafa1160e29808f19.
  Fresh official GHCR vulnerability/Java databases are mandatory before frozen scans.

Credentials are folder-scoped Jenkins references rca-registry-build and
rca-gitops-github. The public GitOps repository is nembutal-sw/rca-platform-gitops.
No credentials, auth files, OCI tar archives or scanner caches are archived. Archive
patterns explicitly select public test/runtime/scan/SBOM/digest/PR evidence only.

The pipeline never installs host packages, mounts host sockets, uses privileged
containers, changes Jenkins global settings, prunes images or touches other jobs.
Playwright is optional and never installs OS packages. Fixture passwords/tokens are
public synthetic test data. Runtime gates do not certify production connectivity,
bootstrap token lifetime, Agent re-enrollment, production RBAC or host diagnostics.

One-time user-owned prerequisites can be installed with ci/install-node-user.sh,
ci/install-pip-user.sh and ci/install-trivy-user.sh under jenkins-agent. These
installers do not grant sudo or install system packages. Java21/Maven3.9.9 and
rootless Podman/cgroup delegation remain existing host prerequisites.
