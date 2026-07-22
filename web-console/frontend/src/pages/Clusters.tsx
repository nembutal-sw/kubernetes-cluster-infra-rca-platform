import { PageHeader, Surface } from "../components/common";
import { AgentFleetPanel, ClusterDetail, ClusterForm, ClusterList, InstallCommand } from "../features/clusters/ClusterPanels";
import type {
  AgentHealthView,
  AgentEnrollmentUpdate,
  ClusterCreateForm,
  ClusterDetailState,
  ClusterView,
  InstallCommandView,
  TFunction,
  UserAccount,
} from "../types";

type MaybePromise<T = void> = T | Promise<T>;

interface ClustersViewProps {
  clusters: ClusterView[];
  selectedCluster: ClusterView | null;
  clusterDetail: ClusterDetailState | null;
  agentHealth: AgentHealthView[];
  installCommand: InstallCommandView | null;
  currentUser: UserAccount;
  onCreate: (form: ClusterCreateForm) => MaybePromise;
  onSelect: (cluster: ClusterView) => MaybePromise;
  onGenerateInstall: (clusterId: string, backendUrl?: string) => MaybePromise<unknown>;
  onStartCollection: (cluster: ClusterView) => MaybePromise;
  onUpdateThresholds: (cluster: ClusterView, thresholds: Record<string, number>, reason: string) => MaybePromise;
  onClearThresholds: (cluster: ClusterView) => MaybePromise;
  onUpdateEnrollment: (cluster: ClusterView, update: AgentEnrollmentUpdate) => MaybePromise;
  onDelete: (cluster: ClusterView) => void;
  onRotateToken: (cluster: ClusterView) => MaybePromise;
  onCopy: (text: string) => MaybePromise;
  t: TFunction;
}

export function ClustersView({
  clusters,
  selectedCluster,
  clusterDetail,
  agentHealth,
  installCommand,
  currentUser,
  onCreate,
  onSelect,
  onGenerateInstall,
  onStartCollection,
  onUpdateThresholds,
  onClearThresholds,
  onUpdateEnrollment,
  onDelete,
  onRotateToken,
  onCopy,
  t,
}: ClustersViewProps) {
  const canOperate = ["admin", "operator"].includes(currentUser.role);
  return (
    <div className="page-stack">
      <PageHeader title={t("Clusters")} subtitle={t("Register clusters, install node agents, and inspect collected evidence.")} />
      <div className="split-grid">
        <Surface title={t("Create cluster")} subtitle={t("Minimal registration flow")}>
          <ClusterForm onCreate={onCreate} disabled={!canOperate} t={t} />
          {installCommand && <InstallCommand command={installCommand} onCopy={onCopy} t={t} />}
        </Surface>
        <Surface title={t("Cluster topology")} subtitle={`${clusters.length} ${t("registered")}`}>
          <ClusterList
            clusters={clusters}
            selectedCluster={selectedCluster}
            onSelect={onSelect}
            onGenerateInstall={onGenerateInstall}
            onDelete={onDelete}
            onRotateToken={onRotateToken}
            canOperate={canOperate}
            currentUser={currentUser}
            t={t}
          />
        </Surface>
      </div>
      <Surface title={t("Agent connectivity")} subtitle={t("Fleet-wide heartbeat, compatibility, and collector posture")}>
        <AgentFleetPanel agents={agentHealth} clusters={clusters} onOpenCluster={onSelect} t={t} />
      </Surface>
      {selectedCluster && (
        <Surface title={selectedCluster.name} subtitle={`${selectedCluster.cluster_id} / ${selectedCluster.environment || "n/a"}`}>
          <ClusterDetail
            cluster={selectedCluster}
            detail={clusterDetail}
            onStartCollection={onStartCollection}
            onUpdateThresholds={onUpdateThresholds}
            onClearThresholds={onClearThresholds}
            onUpdateEnrollment={onUpdateEnrollment}
            canOperate={canOperate}
            canAdmin={currentUser.role === "admin"}
            t={t}
          />
        </Surface>
      )}
    </div>
  );
}
