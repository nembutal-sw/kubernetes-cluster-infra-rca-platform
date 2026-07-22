import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ClusterDetailState, ClusterView } from "../../types";
import { ClusterDetail } from "./ClusterPanels";

const cluster: ClusterView = {
  cluster_id: "cluster-1",
  name: "production",
  environment: "prod",
};

const detail: ClusterDetailState = {
  agents: [],
  evidence: [],
  topology: null,
  enrollment: {
    cluster_id: "cluster-1",
    mode: "kubernetes_token_review",
    configured: true,
    api_server_url: "https://kubernetes.example:6443",
    ca_sha256: "a".repeat(64),
    audience: "https://kubernetes.default.svc",
    namespace: "rca-system",
    service_account: "cluster-infra-rca-agent",
    bootstrap_fallback_allowed: false,
    bootstrap_token_rotation_required: true,
    updated_at: "2026-07-22T00:00:00Z",
  },
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function renderDetail(canAdmin: boolean, onUpdate = vi.fn()) {
  render(
    <ClusterDetail
      cluster={cluster}
      detail={detail}
      onStartCollection={vi.fn()}
      onUpdateThresholds={vi.fn()}
      onClearThresholds={vi.fn()}
      onUpdateEnrollment={onUpdate}
      canOperate={canAdmin}
      canAdmin={canAdmin}
      t={(key) => key}
    />,
  );
  fireEvent.click(screen.getByText("Agent enrollment"));
  return onUpdate;
}

describe("Agent enrollment panel", () => {
  it("shows trusted status without exposing admin controls to a viewer", () => {
    renderDetail(false);

    expect(screen.getByText("Kubernetes TokenReview")).not.toBeNull();
    expect(screen.getByText("Bootstrap fallback disabled")).not.toBeNull();
    expect(screen.getByText("a".repeat(64))).not.toBeNull();
    expect(screen.queryByLabelText("Enrollment mode")).toBeNull();
    expect(screen.queryByText("Save enrollment")).toBeNull();
  });

  it("lets an admin return to bootstrap mode with a minimal payload", async () => {
    const onUpdate = vi.fn().mockResolvedValue(undefined);
    renderDetail(true, onUpdate);

    fireEvent.change(screen.getByLabelText("Enrollment mode"), { target: { value: "bootstrap_token" } });
    fireEvent.click(screen.getByText("Save enrollment"));

    await waitFor(() => expect(onUpdate).toHaveBeenCalledWith(cluster, { mode: "bootstrap_token" }));
  });

  it("requires confirmation before strict mode revokes bootstrap fallback", async () => {
    const onUpdate = vi.fn().mockResolvedValue(undefined);
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const bootstrapDetail: ClusterDetailState = {
      ...detail,
      enrollment: {
        cluster_id: "cluster-1",
        mode: "bootstrap_token",
        configured: false,
        bootstrap_fallback_allowed: true,
        bootstrap_token_rotation_required: false,
      },
    };
    render(
      <ClusterDetail
        cluster={cluster}
        detail={bootstrapDetail}
        onStartCollection={vi.fn()}
        onUpdateThresholds={vi.fn()}
        onClearThresholds={vi.fn()}
        onUpdateEnrollment={onUpdate}
        canOperate
        canAdmin
        t={(key) => key}
      />,
    );
    fireEvent.click(screen.getByText("Agent enrollment"));
    fireEvent.change(screen.getByLabelText("Enrollment mode"), {
      target: { value: "kubernetes_token_review" },
    });
    fireEvent.change(screen.getByLabelText("API Server URL"), {
      target: { value: "https://kubernetes.example:6443" },
    });
    fireEvent.change(screen.getByLabelText("Audience"), {
      target: { value: "https://kubernetes.default.svc" },
    });
    fireEvent.change(screen.getByLabelText("CA bundle PEM"), {
      target: { value: "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----" },
    });
    fireEvent.click(screen.getByLabelText("Allow bootstrap fallback"));
    fireEvent.click(screen.getByText("Save enrollment"));

    expect(confirm).toHaveBeenCalledOnce();
    expect(onUpdate).not.toHaveBeenCalled();
  });
});
