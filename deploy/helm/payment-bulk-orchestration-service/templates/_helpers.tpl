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
fintechbankx.io/squad: {{ required "squad is required (payments)" .Values.squad | quote }}
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
Values guard (governance round 3, item 1; round 6, guardrail 4a). The platform
guard is vendored byte-identical in _fbx_helpers.tpl (cicd-templates
charts/fintechbankx-service/templates/_helpers.tpl at 6b6c317; sha256 pinned
in README.md and checked by the deployability workflow). fbx.guard reads only
.Values, so bulk.guard feeds it an adapter dict built from this chart's own
values, mapping every route the chart renders:
  config            -> .Values.config (the ConfigMap, envFrom of the API pods)
  extraEnv          -> none (the chart renders no values-named env entries)
  envFrom           -> none (only the chart's own configMapRef and secretRef)
  extraEnvFrom      -> none
  javaToolOptions   -> none (the image's own JAVA_TOOL_OPTIONS; a config
                       JAVA_TOOL_OPTIONS / JDK_JAVA_OPTIONS / _JAVA_OPTIONS is
                       checked as a config key)
  databaseCa        -> rdsCaBundle (enabled, mountPath, key, configMapName)
  kafka.runtime     -> kafkaStrimzi.enabled (false -> msk, true -> strimzi);
                       fbx.kafkaProfile renders SPRING_PROFILES_ACTIVE from it
  externalSecret    -> the service ExternalSecret's fixed entries
                       (SPRING_DATASOURCE_PASSWORD, SERVICE_CLIENT_SECRET, with
                       their values-named remoteRef keys); no extraData, no dataFrom
The migration Job's ExternalSecret (SPRING_FLYWAY_USER, SPRING_FLYWAY_PASSWORD:
the schema owner credential, Job only, chart literals, not values-named) is
not passed: fbx.datasourceOverrideName refuses every spring.flyway.* name by
design, and this chart's owner/runtime role split needs exactly those two.
Called once at the top of the Deployment and of the migration Job.
Rules kept on top of fbx.guard (bulk.guardEnv), because it does not do them:
  - a non-scalar config value (a dotted name given to --set nests into a map
    that would render as the env name "spring"; quote it in a values file,
    where the name rules apply);
  - names under spring.kafka.(properties|producer.properties|
    consumer.properties|admin.properties|streams.properties): fbx refuses the
    ssl.* names there and checks the *security.protocol and
    *endpoint.identification.algorithm values, but leaves the other raw client
    properties (sasl.*, ...) alone; the profiles carry the Kafka client
    settings, so this chart accepts none of them from values;
  - kafkaStrimzi.enabled must be a boolean.
Dropped (fbx.guard covers them, 6b6c317 included): spring.kafka[.<client>].ssl.*
and spring.kafka[.<client>].properties.ssl.* names, spring.data.mongodb.*,
kafka and mongodb in JVM option values, JDBC URL parsing, datasource/Flyway/Liquibase/
R2DBC/application.json/jdbc_url/sslfactory/sslhostnameverifier names,
spring.config.* and spring.profiles.* in every form, fintechbankx.tls.*,
spring.ssl.* and ssl bundle names, DB_SSL_ROOT_CERT and other sslmode/
sslrootcert names, KAFKA_SECURITY_PROTOCOL and spring.kafka.*security.protocol
values, JVM option values (fintechbankx.tls, security.protocol, jdk.tls,
java.security.properties, '$(' and '${' included), key shapes.
*/ -}}
{{- define "bulk.kafkaRuntime" -}}
{{- if not (kindIs "bool" .Values.kafkaStrimzi.enabled) -}}
{{- fail (printf "kafkaStrimzi.enabled must be a boolean (it selects the kafka-msk or kafka-strimzi profile), got %q" (toString .Values.kafkaStrimzi.enabled)) -}}
{{- end -}}
{{- ternary "strimzi" "msk" .Values.kafkaStrimzi.enabled -}}
{{- end -}}

{{- /* The only Spring profile the chart renders: kafka-msk or kafka-strimzi, through the vendored fbx.kafkaProfile. */ -}}
{{- define "bulk.kafkaProfile" -}}
{{- include "fbx.kafkaProfile" (dict "Values" (dict "kafka" (dict "runtime" (include "bulk.kafkaRuntime" .)))) -}}
{{- end -}}

{{- define "bulk.guard" -}}
{{- include "fbx.guard" (dict "Values" (dict
      "config" .Values.config
      "extraEnv" (list)
      "envFrom" (list)
      "extraEnvFrom" (list)
      "javaToolOptions" ""
      "databaseCa" (dict "enabled" true "mountPath" .Values.rdsCaBundle.mountPath "key" .Values.rdsCaBundle.key "configMapName" .Values.rdsCaBundle.configMapName)
      "kafka" (dict "runtime" (include "bulk.kafkaRuntime" .))
      "externalSecret" (dict "enabled" .Values.externalSecret.enabled
                             "data" (list (dict "secretKey" "SPRING_DATASOURCE_PASSWORD" "property" "password" "remoteSecretName" (.Values.externalSecret.remoteSecretName | default ""))
                                          (dict "secretKey" "SERVICE_CLIENT_SECRET" "property" "client_secret" "remoteSecretName" (.Values.externalSecret.serviceClientSecretName | default "")))
                             "extraData" (list)
                             "dataFrom" (list)))) -}}
{{- include "bulk.guardEnv" (list "config" .Values.config) -}}
{{- end -}}

{{- define "bulk.guardEnv" -}}
{{- $field := index . 0 -}}
{{- $env := index . 1 -}}
{{- range $key, $value := $env -}}
{{- if or (kindIs "map" $value) (kindIs "slice" $value) -}}
{{- fail (printf "%s.%s must be a scalar: a dotted name given to --set becomes a nested map; quote it in a values file (templates/_helpers.tpl, bulk.guardEnv)" $field $key) -}}
{{- end -}}
{{- $n := include "fbx.canonicalName" $key -}}
{{- if or (regexMatch "(?i)^spring[._-]?kafka[._-]?(properties|producer[._-]?properties|consumer[._-]?properties|admin[._-]?properties|streams[._-]?properties)[._-]" (toString $key)) (regexMatch "^spring\\.kafka\\.(properties|producer\\.properties|consumer\\.properties|admin\\.properties|streams\\.properties)\\." $n) -}}
{{- fail (printf "%s.%s is not allowed: a raw Kafka client property there can change the client's settings past the chart's checks; the kafka-msk and kafka-strimzi profiles carry them (templates/_helpers.tpl, bulk.guardEnv)" $field $key) -}}
{{- end -}}
{{- end -}}
{{- end -}}
