import { EmptyState, Icon, MetricTile } from "../../components/common";

import { formatBytes, formatDate, shortHash } from "../../lib/consoleUtils";
import type { EvidenceBundleManifest, PlatformInfo, TFunction } from "../../types";

interface BundleVerificationPanelProps {
  manifest?: EvidenceBundleManifest | null;
  platformInfo?: PlatformInfo | null;
  onCopy?: (text: string) => void;
  t: TFunction;
}

export function BundleVerificationPanel({ manifest, platformInfo, onCopy, t }: BundleVerificationPanelProps) {
  const exportSecurity = (platformInfo?.export_security || platformInfo?.exportSecurity || {}) as Record<string, unknown>;
  const entries = manifest?.entries || [];
  const command = String(manifest?.verification_command || manifest?.verificationCommand || "");
  const signatureEnabled = Boolean(manifest?.signature_enabled ?? manifest?.signatureEnabled ?? exportSecurity.bundle_signature_enabled);
  if (!manifest) {
    return <EmptyState message={t("Manifest summary is available to export-authorized users after report detail loads.")} />;
  }
  return (
    <div className="bundle-verification">
      <div className="bundle-verify-grid">
        <MetricTile label={t("Bundle file")} value={manifest.filename || "bundle.zip"} tone="blue" icon="file-earmark-zip" />
        <MetricTile label={t("Entry count")} value={manifest.entry_count ?? manifest.entryCount ?? entries.length} tone="teal" icon="list-check" />
        <MetricTile label={t("ZIP size")} value={formatBytes(manifest.zip_bytes ?? manifest.zipBytes)} tone="green" icon="archive" />
        <MetricTile label={t("Raw payload")} value={formatBytes(manifest.raw_bytes ?? manifest.rawBytes)} tone="amber" icon="database" />
      </div>

      <div className="manifest-summary-grid">
        <div>
          <span>{t("Hash algorithm")}</span>
          <strong>{String(manifest.hash_algorithm || manifest.hashAlgorithm || "SHA-256")}</strong>
        </div>
        <div>
          <span>{t("Signature")}</span>
          <strong>{signatureEnabled ? `${manifest.signature_algorithm || manifest.signatureAlgorithm || "HMAC-SHA256"} / ${manifest.signature_key_id || manifest.signatureKeyId || "default"}` : t("Unsigned bundle")}</strong>
        </div>
        <div>
          <span>{t("Last generated")}</span>
          <strong>{formatDate(manifest.generated_at || manifest.generatedAt)}</strong>
        </div>
        <div>
          <span>{t("Max bundle size")}</span>
          <strong>{formatBytes(manifest.max_bundle_bytes ?? manifest.maxBundleBytes ?? exportSecurity.max_bundle_bytes)}</strong>
        </div>
      </div>

      <div className="verify-command-box">
        <div>
          <span>{t("Offline verify")}</span>
          <code>{command || "python3 scripts/verify_evidence_bundle.py <bundle.zip>"}</code>
        </div>
        <button className="btn btn-sm btn-outline-secondary icon-button" onClick={() => onCopy?.(command)}>
          <Icon name="clipboard-check" /><span>{t("Copy verifier")}</span>
        </button>
      </div>

      <div className="manifest-entry-list">
        <div className="manifest-entry-head">
          <strong>{t("Entry hashes")}</strong>
          <span>{entries.length} SHA-256</span>
        </div>
        {entries.slice(0, 6).map((entry) => (
          <div key={entry.path} className="manifest-entry">
            <code>{entry.path}</code>
            <span>{shortHash(entry.sha256)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
