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

{{- define "bulk.migrationName" -}}
{{ include "bulk.name" . }}-db-migration
{{- end -}}

{{- /*
Environment the chart refuses to render (governance round 3, item 1).
- Spring config redirection: SPRING_CONFIG_IMPORT, SPRING_CONFIG_LOCATION and
  SPRING_CONFIG_ADDITIONAL_LOCATION would point the service at another
  configuration source. A configtree is allowed only as a value this chart itself
  renders, on the fixed mount optional:configtree:/etc/fintechbankx/config/ (this
  chart renders none today).
- Datasource and Flyway URL keys (SPRING_DATASOURCE_URL, SPRING_FLYWAY_URL) would
  bypass the DB_URL verify-full check in configmap.yaml.
- FINTECHBANKX_TLS_ENFORCE would switch off the service's startup TLS assertion
  (fintechbankx.tls.enforce, true by default; only local and test configuration
  set it false, never the chart).
Keys are compared whatever their case, with "." and "-" read as "_" (Spring's
relaxed binding); values are searched for the same names, so a
JAVA_TOOL_OPTIONS=-Dspring.config.import=... does not slip through either.
Usage: include "bulk.guardEnv" (list "config" .Values.config)
*/ -}}
{{- define "bulk.guardEnv" -}}
{{- $field := index . 0 -}}
{{- $env := index . 1 -}}
{{- $forbidden := list "SPRING_CONFIG_IMPORT" "SPRING_CONFIG_LOCATION" "SPRING_CONFIG_ADDITIONAL_LOCATION" "SPRING_DATASOURCE_URL" "SPRING_FLYWAY_URL" "FINTECHBANKX_TLS_ENFORCE" -}}
{{- range $key, $value := $env -}}
{{- $normalKey := regexReplaceAll "[.-]" (upper $key) "_" -}}
{{- $normalValue := regexReplaceAll "[.-]" (upper (toString $value)) "_" -}}
{{- range $name := $forbidden -}}
{{- if eq $normalKey $name -}}
{{- fail (printf "%s.%s is not allowed: the chart renders this Spring setting itself (templates/_helpers.tpl, bulk.guardEnv)" $field $key) -}}
{{- end -}}
{{- if contains $name $normalValue -}}
{{- fail (printf "%s.%s must not carry %s in its value (templates/_helpers.tpl, bulk.guardEnv)" $field $key $name) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
