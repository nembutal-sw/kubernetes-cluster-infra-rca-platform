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

{{- define "cluster-infra-rca-platform.platformName" -}}
{{- printf "%s-platform" (include "cluster-infra-rca-platform.fullname" .) | trunc 63 | trimSuffix "-" -}}
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

{{- define "cluster-infra-rca-platform.jdbcUrl" -}}
{{- if .Values.platform.secret.jdbcUrl -}}
{{- .Values.platform.secret.jdbcUrl -}}
{{- else if .Values.database.enabled -}}
{{- $type := include "cluster-infra-rca-platform.databaseType" . -}}
{{- $database := .Values.database.auth.database | toString -}}
{{- $service := include "cluster-infra-rca-platform.databaseServiceName" . -}}
{{- $port := include "cluster-infra-rca-platform.databasePort" . -}}
{{- if eq $type "mariadb" -}}
{{- printf "jdbc:mariadb://%s:%s/%s" $service $port $database -}}
{{- else -}}
{{- printf "jdbc:postgresql://%s:%s/%s" $service $port $database -}}
{{- end -}}
{{- else -}}
{{- required "platform.secret.jdbcUrl is required when database.enabled=false" .Values.platform.secret.jdbcUrl -}}
{{- end -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.databaseUsername" -}}
{{- default .Values.database.auth.username .Values.platform.secret.databaseUsername -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.databasePassword" -}}
{{- default .Values.database.auth.password .Values.platform.secret.databasePassword -}}
{{- end -}}

{{- define "cluster-infra-rca-platform.secretName" -}}
{{- if .Values.platform.secret.create -}}
{{- printf "%s-secret" (include "cluster-infra-rca-platform.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- required "platform.secret.existingSecret is required when platform.secret.create=false" .Values.platform.secret.existingSecret -}}
{{- end -}}
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

{{- define "cluster-infra-rca-platform.platformSelectorLabels" -}}
{{ include "cluster-infra-rca-platform.selectorLabels" . }}
app.kubernetes.io/component: platform
{{- end -}}

{{- define "cluster-infra-rca-platform.databaseSelectorLabels" -}}
{{ include "cluster-infra-rca-platform.selectorLabels" . }}
app.kubernetes.io/component: database
{{- end -}}
