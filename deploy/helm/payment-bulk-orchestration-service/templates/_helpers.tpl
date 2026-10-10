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
Values guard (governance round 3, item 1; round 6, guardrail 4a). Two layers:
1. The platform rules, vendored verbatim in _fbx-guard.tpl (cicd-templates
   charts/fintechbankx-service/templates/_helpers.tpl at 2caa48f, sha256 pinned
   in README.md and the deployability workflow): fbx.validateDatabaseTls parses
   every PostgreSQL JDBC URL in config the way PgJDBC does (exactly one
   lower-case sslmode=verify-full, exactly one sslrootcert equal to the mounted
   RDS CA bundle, no sslfactory/sslfactoryarg/sslhostnameverifier/
   sslpasswordcallback/service, no percent-encoded '=' or '&', no TLS key before
   the '?'), refuses the datasource, Flyway, Liquibase, R2DBC, application.json,
   jdbc_url, sslfactory, sslhostnameverifier, spring.config.* and
   spring.profiles.(active|include) names, and checks JVM option values. Those
   helpers read .Values.databaseCa, .Values.extraEnv, .Values.javaToolOptions and
   .Values.externalSecret.{data,extraData}; bulk.guardValues feeds them an
   adapter dict from this chart's values (rdsCaBundle; config; no extraEnv; the
   service ExternalSecret's fixed keys). The migration Job's ExternalSecret
   (SPRING_FLYWAY_USER, SPRING_FLYWAY_PASSWORD, the schema owner credential, Job
   only) is the chart's own and is not a values-named key, so it is not passed.
2. This repo's additions (bulk.guardEnv, below), on names normalised the way
   Spring's relaxed binding reads them (upper case, '.', '-', '[' and ']' as '_'):
   - the indexed and suffixed config forms (SPRING_CONFIG_IMPORT_0,
     spring.config.import[0]) and spring.profiles.(active|include|default|group...)
     in every form, because a profile activates an application-<profile>.yml in
     the image (the local profile switches the startup TLS assertion off). The
     chart renders SPRING_PROFILES_ACTIVE itself from the boolean
     kafkaStrimzi.enabled (kafka-msk or kafka-strimzi, templates/deployment.yaml);
   - fintechbankx[._-]?tls in any form (the assertion's switch);
   - spring.kafka.*security.protocol, spring.kafka.properties.* and
     KAFKA_SECURITY_PROTOCOL (the chart renders the latter from
     kafkaStrimzi.enabled: SASL_SSL or SSL);
   - JVM option values (JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS; the
     image entrypoint reads no JAVA_OPTS) may not mention fintechbankx or kafka
     either;
   - a non-scalar config value (a dotted name given to --set becomes a nested
     map that would render as an env name "spring"; quote it in a values file,
     where the name rules apply).
Usage: include "bulk.guardValues" $
*/ -}}
{{- define "bulk.guardValues" -}}
{{- $adapter := dict "Values" (dict
      "config" .Values.config
      "extraEnv" (list)
      "javaToolOptions" ""
      "externalSecret" (dict "enabled" .Values.externalSecret.enabled
                             "data" (list (dict "secretKey" "SPRING_DATASOURCE_PASSWORD") (dict "secretKey" "SERVICE_CLIENT_SECRET"))
                             "extraData" (list))
      "databaseCa" (dict "enabled" true "mountPath" .Values.rdsCaBundle.mountPath "key" .Values.rdsCaBundle.key)) -}}
{{- include "fbx.validateDatabaseTls" $adapter -}}
{{- include "bulk.guardEnv" (list "config" .Values.config) -}}
{{- if not (kindIs "bool" .Values.kafkaStrimzi.enabled) -}}
{{- fail (printf "kafkaStrimzi.enabled must be a boolean (it selects the kafka-msk or kafka-strimzi profile), got %q" (toString .Values.kafkaStrimzi.enabled)) -}}
{{- end -}}
{{- end -}}

{{- /* Spring's relaxed binding: upper case, '.' and '-' as '_'; a bracket index ([0]) folds to _0_ so the indexed rules see it. */ -}}
{{- define "bulk.normalEnvName" -}}
{{- regexReplaceAll "[.\\[\\]-]" (upper (toString .)) "_" -}}
{{- end -}}

{{- /* This repo's name rules on top of fbx.datasourceOverrideName; prints the reason (non-empty means refused). */ -}}
{{- define "bulk.forbiddenEnvName" -}}
{{- $n := include "bulk.normalEnvName" . -}}
{{- if regexMatch "(?i)^spring[._-]?config[._-]?(import|location|additional[._-]?location|name)([._-]?[0-9]+)?[._-]?$" $n -}}
a config import, location or name can load a file or config tree that sets anything (the datasource, fintechbankx.tls.enforce) where the chart cannot check it; the chart renders no config import
{{- else if regexMatch "(?i)^spring[._-]?profiles[._-]?(active|include|default|group)([._-]|$)" $n -}}
a profile can activate an application-<profile> config in the image (local switches the TLS assertion off); the chart renders SPRING_PROFILES_ACTIVE itself from kafkaStrimzi.enabled
{{- else if regexMatch "(?i)^fintechbankx[._-]?tls" $n -}}
it would switch the service's startup TLS assertion (fintechbankx.tls.enforce) off; only a local run sets it, never the chart
{{- else if regexMatch "(?i)^spring[._-]?kafka[._-].*security[._-]?protocol|^spring[._-]?kafka[._-]?properties[._-]|^kafka[._-]?security[._-]?protocol$" $n -}}
it would change the Kafka client's effective security.protocol; the chart renders KAFKA_SECURITY_PROTOCOL itself from kafkaStrimzi.enabled (SASL_SSL or SSL)
{{- end -}}
{{- end -}}

{{- define "bulk.guardEnv" -}}
{{- $field := index . 0 -}}
{{- $env := index . 1 -}}
{{- range $key, $value := $env -}}
{{- if or (kindIs "map" $value) (kindIs "slice" $value) -}}
{{- fail (printf "%s.%s must be a scalar: a dotted name given to --set becomes a nested map; quote it in a values file (templates/_helpers.tpl, bulk.guardEnv)" $field $key) -}}
{{- end -}}
{{- with include "bulk.forbiddenEnvName" $key -}}
{{- fail (printf "%s.%s is not allowed: %s (templates/_helpers.tpl, bulk.guardEnv)" $field $key .) -}}
{{- end -}}
{{- if include "fbx.isJvmOptionsName" (include "bulk.normalEnvName" $key) -}}
{{- if regexMatch "(?i)fintechbankx|kafka" (toString $value) -}}
{{- fail (printf "%s.%s must not mention fintechbankx or kafka (a -D system property would switch the TLS assertion off or change the Kafka protocol past the chart's checks) (templates/_helpers.tpl, bulk.guardEnv)" $field $key) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
