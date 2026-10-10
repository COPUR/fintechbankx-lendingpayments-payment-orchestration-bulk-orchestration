{{- define "bulk.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- /*
Every pod of the release, the API pods and the migration Job pod alike, carries
app.kubernetes.io/name = the service account name: the mesh repo's
NetworkPolicies grant Aurora egress (5432) by that label only. The component
label keeps them apart (cicd-templates 335a345): "service" for the Deployment, which every selector
(Deployment, Service, PDB, NetworkPolicy, topology spread; the HPA targets the
Deployment) requires, "db-migration" for the hook Job.
*/ -}}
{{- define "bulk.selectorLabels" -}}
{{ include "bulk.instanceLabels" . }}
app.kubernetes.io/component: service
{{- end -}}

{{- define "bulk.instanceLabels" -}}
app.kubernetes.io/name: {{ include "bulk.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- /* Labels of every object, without the component (each template sets its own). */ -}}
{{- define "bulk.commonLabels" -}}
{{ include "bulk.instanceLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
fintechbankx.io/app: app-pay-bulk-orchestration
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
fintechbankx.io/squad: {{ required "squad is required (payments)" .Values.squad }}
{{- end -}}

{{- /* Labels of the API's objects. */ -}}
{{- define "bulk.labels" -}}
{{ include "bulk.commonLabels" . }}
app.kubernetes.io/component: service
{{- end -}}

{{- define "bulk.secretName" -}}
{{ include "bulk.name" . }}-secrets
{{- end -}}

{{- /*
Secrets Manager key for an ExternalSecret remoteRef. Platform contract "Secret
stores and deploy supply chain": a service ExternalSecret may read only keys
under <env>/<service account>/ (admission policy fintechbankx-externalsecret-scope),
e.g. dev/payment-bulk-orchestration-service/db-app. Anything else fails the render.
Usage: include "bulk.remoteKey" (list "externalSecret.remoteSecretName" .Values.externalSecret.remoteSecretName .)
*/ -}}
{{- define "bulk.remoteKey" -}}
{{- $field := index . 0 -}}
{{- $key := required (printf "%s is required" $field) (index . 1) -}}
{{- $root := index . 2 -}}
{{- $pattern := printf "^(dev|staging|prod)/%s/[a-z0-9-]+$" $root.Values.serviceAccount.name -}}
{{- if not (regexMatch $pattern $key) -}}
{{- fail (printf "%s must be <env>/%s/<name> (env dev, staging or prod), got %q" $field $root.Values.serviceAccount.name $key) -}}
{{- end -}}
{{- $key -}}
{{- end -}}

{{- /*
Labels of every ExternalSecret: the admission policy requires
app.kubernetes.io/name = the service account name. Without a component: each
ExternalSecret adds its own (service or db-migration).
*/ -}}
{{- define "bulk.externalSecretLabels" -}}
{{- if ne (include "bulk.name" .) .Values.serviceAccount.name -}}
{{- fail (printf "chart name %q must equal serviceAccount.name %q (ExternalSecret label app.kubernetes.io/name)" (include "bulk.name" .) .Values.serviceAccount.name) -}}
{{- end -}}
{{ include "bulk.commonLabels" . }}
{{- end -}}

{{- /*
The service account a pod runs as. Pods are labelled app.kubernetes.io/name =
the chart name, which the mesh policy reads as the service account: the two
must be equal, so the label never claims a principal the pod does not run as.
*/ -}}
{{- define "bulk.serviceAccountName" -}}
{{- if ne (include "bulk.name" .) .Values.serviceAccount.name -}}
{{- fail (printf "chart name %q must equal serviceAccount.name %q (pod label app.kubernetes.io/name)" (include "bulk.name" .) .Values.serviceAccount.name) -}}
{{- end -}}
{{- .Values.serviceAccount.name -}}
{{- end -}}

{{- define "bulk.migrationName" -}}
{{ include "bulk.name" . }}-db-migration
{{- end -}}
