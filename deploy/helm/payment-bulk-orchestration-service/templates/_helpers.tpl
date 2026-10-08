{{- define "bulk.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "bulk.selectorLabels" -}}
app.kubernetes.io/name: {{ include "bulk.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "bulk.labels" -}}
{{ include "bulk.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
fintechbankx.io/app: app-pay-bulk-orchestration
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "bulk.secretName" -}}
{{ include "bulk.name" . }}-secrets
{{- end -}}
