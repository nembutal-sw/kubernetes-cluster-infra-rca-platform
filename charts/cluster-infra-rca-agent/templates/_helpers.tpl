{{- define "cluster-infra-rca-agent.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cluster-infra-rca-agent.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := include "cluster-infra-rca-agent.name" . -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "cluster-infra-rca-agent.namespace" -}}
{{- default .Release.Namespace .Values.namespace.name -}}
{{- end -}}

{{- define "cluster-infra-rca-agent.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cluster-infra-rca-agent.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "cluster-infra-rca-agent.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/part-of: cluster-infra-rca
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{ include "cluster-infra-rca-agent.selectorLabels" . }}
{{- end -}}

{{- define "cluster-infra-rca-agent.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "cluster-infra-rca-agent.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "cluster-infra-rca-agent.configMapName" -}}
{{- printf "%s-config" (include "cluster-infra-rca-agent.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cluster-infra-rca-agent.secretName" -}}
{{- if .Values.secret.create -}}
{{- include "cluster-infra-rca-agent.fullname" . -}}
{{- else -}}
{{- required "secret.existingSecret.name is required when secret.create=false" .Values.secret.existingSecret.name -}}
{{- end -}}
{{- end -}}
