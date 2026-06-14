{{- define "cluster-infra-rca-platform.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := include "cluster-infra-rca-platform.name" . -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.backendName" -}}
{{- printf "%s-backend" (include "cluster-infra-rca-platform.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.webConsoleName" -}}
{{- printf "%s-web-console" (include "cluster-infra-rca-platform.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.databaseType" -}}
{{- $type := lower (default "postgresql" .Values.database.type) -}}
{{- if not (or (eq $type "postgresql") (eq $type "mariadb")) -}}
{{- fail "database.type must be either postgresql or mariadb" -}}
{{- end -}}
{{- $type -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.databaseName" -}}
{{- printf "%s-db" (include "cluster-infra-rca-platform.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.databaseServiceName" -}}
{{- include "cluster-infra-rca-platform.databaseName" . -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.databaseSecretName" -}}
{{- printf "%s-secret" (include "cluster-infra-rca-platform.databaseName" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.databasePort" -}}
{{- if eq (include "cluster-infra-rca-platform.databaseType" .) "mariadb" -}}3306{{- else -}}5432{{- end -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.databaseUrl" -}}
{{- if .Values.backend.secret.databaseUrl -}}
{{- .Values.backend.secret.databaseUrl -}}
{{- else if .Values.database.enabled -}}
{{- $type := include "cluster-infra-rca-platform.databaseType" . -}}
{{- $username := .Values.database.auth.username | toString | urlquery -}}
{{- $password := .Values.database.auth.password | toString | urlquery -}}
{{- $database := .Values.database.auth.database | toString | urlquery -}}
{{- $service := include "cluster-infra-rca-platform.databaseServiceName" . -}}
{{- $port := include "cluster-infra-rca-platform.databasePort" . -}}
{{- if eq $type "mariadb" -}}
{{- printf "mysql+pymysql://%s:%s@%s:%s/%s" $username $password $service $port $database -}}
{{- else -}}
{{- printf "postgresql+psycopg://%s:%s@%s:%s/%s" $username $password $service $port $database -}}
{{- end -}}
{{- else -}}
{{- required "backend.secret.databaseUrl is required when database.enabled=false" .Values.backend.secret.databaseUrl -}}
{{- end -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.secretName" -}}
{{- if .Values.backend.secret.create -}}
{{- printf "%s-secret" (include "cluster-infra-rca-platform.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- required "backend.secret.existingSecret is required when backend.secret.create=false" .Values.backend.secret.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.backendUrl" -}}
{{- default (printf "http://%s:%v" (include "cluster-infra-rca-platform.backendName" .) .Values.backend.service.port) .Values.webConsole.config.apiBaseUrl -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cluster-infra-rca-platform.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "cluster-infra-rca-platform.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/part-of: cluster-infra-rca
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{ include "cluster-infra-rca-platform.selectorLabels" . }}
{{- end -}}

{{- define "cluster-infra-rca-platform.backendSelectorLabels" -}}
{{ include "cluster-infra-rca-platform.selectorLabels" . }}
app.kubernetes.io/component: backend
{{- end -}}

{{- define "cluster-infra-rca-platform.webConsoleSelectorLabels" -}}
{{ include "cluster-infra-rca-platform.selectorLabels" . }}
app.kubernetes.io/component: web-console
{{- end -}}

{{- define "cluster-infra-rca-platform.databaseSelectorLabels" -}}
{{ include "cluster-infra-rca-platform.selectorLabels" . }}
app.kubernetes.io/component: database
{{- end -}}
