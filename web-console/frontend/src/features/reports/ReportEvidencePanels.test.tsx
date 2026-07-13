import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { CandidateList } from "./ReportEvidencePanels";

afterEach(cleanup);

describe("CandidateList", () => {
  it("renders provider-grounded evidence IDs with the candidate", () => {
    render(
      <CandidateList
        candidates={[{
          cause: "inode exhaustion",
          confidence_score: 92,
          supporting_evidence_ids: ["ev-a1b2c3d4", "ev-e5f6a7b8"],
          supporting_evidence: ["inode usage exceeded the critical threshold"],
          evidence_paths: ["disk.filesystems./var.inode_usage_percent"],
        }]}
        t={(value) => value}
      />,
    );

    expect(screen.getByLabelText("Evidence IDs").textContent).toContain("ev-a1b2c3d4");
    expect(screen.getByLabelText("Evidence IDs").textContent).toContain("ev-e5f6a7b8");
    expect(screen.getByLabelText("Evidence paths").textContent).toContain("disk.filesystems./var.inode_usage_percent");
  });
});
