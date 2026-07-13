import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { AgentHealthView, ClusterView } from "../../types";
import { AgentFleetPanel } from "./ClusterPanels";

const t = (value: string) => value;
const clusters: ClusterView[] = [
  { cluster_id: "cluster-prod", name: "prod-east", status: "active" },
];
const agents: AgentHealthView[] = [
  agent("worker-ok", "healthy", 12),
  agent("worker-stale", "stale", 420),
  agent("worker-collector", "collector_degraded", 24, ["Kernel collector unavailable"]),
  agent("worker-version", "version_mismatch", 18, ["Agent protocol 1 is outside supported range"]),
  agent("worker-auth", "unauthorized", 8, ["Agent authentication failed"]),
  agent("worker-offline", "offline", 7_500),
];

afterEach(cleanup);

describe("AgentFleetPanel", () => {
  it("shows every operational status and filters the fleet", () => {
    render(<AgentFleetPanel agents={agents} clusters={clusters} onOpenCluster={() => undefined} t={t} />);

    expect(screen.getAllByTestId("agent-fleet-row")).toHaveLength(6);
    expect(within(screen.getByTestId("agent-status-filter-version_mismatch")).getByText("1")).toBeTruthy();
    expect(within(screen.getByTestId("agent-status-filter-unauthorized")).getByText("1")).toBeTruthy();

    fireEvent.click(screen.getByTestId("agent-status-filter-unauthorized"));
    expect(screen.getAllByTestId("agent-fleet-row")).toHaveLength(1);
    expect(screen.getByText("worker-auth")).toBeTruthy();
    expect(screen.getByText("Agent authentication failed")).toBeTruthy();
  });

  it("searches operational context and opens a known cluster", () => {
    const onOpenCluster = vi.fn();
    render(<AgentFleetPanel agents={agents} clusters={clusters} onOpenCluster={onOpenCluster} t={t} />);

    fireEvent.change(screen.getByTestId("agent-fleet-search"), { target: { value: "protocol 1" } });
    expect(screen.getAllByTestId("agent-fleet-row")).toHaveLength(1);
    expect(screen.getByText("worker-version")).toBeTruthy();

    fireEvent.change(screen.getByTestId("agent-fleet-search"), { target: { value: "" } });
    fireEvent.click(screen.getAllByRole("button", { name: "prod-east" })[0]);
    expect(onOpenCluster).toHaveBeenCalledWith(clusters[0]);
  });
});

function agent(
  nodeName: string,
  healthStatus: string,
  heartbeatAgeSeconds: number,
  reasons: string[] = [],
): AgentHealthView {
  return {
    agent_id: `agent-${nodeName}`,
    cluster_id: "cluster-prod",
    node_name: nodeName,
    agent_version: "0.1.0",
    agent_protocol_version: "1",
    platform_protocol_version: "1",
    health_status: healthStatus,
    heartbeat_age_seconds: heartbeatAgeSeconds,
    supported_collectors: ["disk", "kernel", "runtime"],
    reasons,
  };
}
