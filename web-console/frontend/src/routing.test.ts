import { describe, expect, it } from "vitest";

import { clusterPath, incidentPath, parseConsoleRoute, pathForView, reportPath } from "./routing";

describe("console routing", () => {
  it("normalizes root, console, and list routes", () => {
    expect(parseConsoleRoute("/")).toMatchObject({ view: "overview", canonicalPath: "/overview", valid: true });
    expect(parseConsoleRoute("/console")).toMatchObject({ view: "overview", canonicalPath: "/overview", valid: true });
    expect(parseConsoleRoute("/settings/")).toMatchObject({ view: "settings", canonicalPath: "/settings", valid: true });
  });

  it("parses encoded detail routes", () => {
    expect(parseConsoleRoute("/clusters/cluster%2Fedge")).toMatchObject({
      view: "clusters",
      clusterId: "cluster/edge",
      canonicalPath: "/clusters/cluster%2Fedge",
    });
    expect(parseConsoleRoute("/reports/report-1").reportId).toBe("report-1");
    expect(parseConsoleRoute("/incidents/incident-1").incidentId).toBe("incident-1");
  });

  it("rejects unknown and unsupported nested routes", () => {
    expect(parseConsoleRoute("/unknown")).toMatchObject({ valid: false, canonicalPath: "/overview" });
    expect(parseConsoleRoute("/settings/detail")).toMatchObject({ valid: false, canonicalPath: "/overview" });
    expect(parseConsoleRoute("/reports/%E0%A4%A")).toMatchObject({ valid: false });
  });

  it("builds canonical encoded paths", () => {
    expect(pathForView("audit")).toBe("/audit");
    expect(pathForView("missing")).toBe("/overview");
    expect(clusterPath("cluster edge")).toBe("/clusters/cluster%20edge");
    expect(reportPath("report/1")).toBe("/reports/report%2F1");
    expect(incidentPath("incident:1")).toBe("/incidents/incident%3A1");
  });
});
