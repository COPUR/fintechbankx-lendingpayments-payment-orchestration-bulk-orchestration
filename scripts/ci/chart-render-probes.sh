#!/usr/bin/env bash
# Chart lint, render and refuse probes for deploy/helm/payment-bulk-orchestration-service, run by the deploy/helm job
# in .github/workflows/deployability.yml. It lives here, not inline, because GitHub
# turns a run: block that contains an expression into one expression and refuses
# the whole workflow above 21,000 characters (zero-job startup failure). The image
# tag comes from GITHUB_SHA, which the runner sets to the same value as github.sha.
set -e
: "${GITHUB_SHA:?GITHUB_SHA must be set (the runner sets it)}"
chart=deploy/helm/payment-bulk-orchestration-service
args="--set image.repository=ci/payment-bulk-orchestration-service --set image.tag=${GITHUB_SHA}"
args="$args --set externalSecret.remoteSecretName=dev/payment-bulk-orchestration-service/db-app"
args="$args --set externalSecret.serviceClientSecretName=dev/payment-bulk-orchestration-service/oidc-client"
args="$args --set migration.remoteSecretName=dev/payment-bulk-orchestration-service/db-migration"
args="$args --set-string config.DB_URL=jdbc:postgresql://ci-db:5432/db_pay_bulk_orchestration_ci?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem"
args="$args --set config.KAFKA_BOOTSTRAP_SERVERS=ci-kafka:9098"
args="$args --set config.OIDC_ISSUER_URI=https://ci-idp/realms/fintechbankx"
args="$args --set config.OIDC_JWK_SET_URI=https://ci-idp/realms/fintechbankx/protocol/openid-connect/certs"
args="$args --set config.OIDC_TOKEN_URI=https://ci-idp/realms/fintechbankx/protocol/openid-connect/token"
args="$args --set config.ACCOUNTS_SERVICE_BASE_URL=http://ci-accounts:8080"
helm lint "$chart" $args
helm template ci "$chart" --namespace payments $args > /dev/null
helm template ci "$chart" --namespace payments -f "$chart/values-dev.yaml" $args > /dev/null
helm template ci "$chart" --namespace payments --set kafkaStrimzi.enabled=true $args > /dev/null
# The pods verify Aurora's certificate against the platform RDS CA bundle.
grep -q "name: rds-ca-bundle" <<<"$(helm template ci "$chart" --namespace payments $args)"
# Shared-chart convention (cicd-templates 4f0f266): mounted at /etc/fintechbankx/rds-ca, path in DB_SSL_ROOT_CERT,
# in the pods and in the migration Job.
rendered="$(helm template ci "$chart" --namespace payments $args)"
[ "$(grep -c 'mountPath: "/etc/fintechbankx/rds-ca"' <<<"$rendered")" -eq 2 ]
[ "$(grep -A1 "name: DB_SSL_ROOT_CERT" <<<"$rendered" | grep -c 'value: "/etc/fintechbankx/rds-ca/global-bundle.pem"')" -eq 2 ]
if grep -q "/etc/ssl/rds" <<<"$rendered"; then
  echo "chart still mounts the RDS CA bundle at the old path /etc/ssl/rds" >&2; exit 1
fi
# Guardrail 4a (round 6): DB_URL is parsed the way PgJDBC parses it (templates/_helpers.tpl,
# bulk.validateJdbcUrl), not searched by substring. Each of these must fail the render: no verify-full; a root
# certificate outside the mounted bundle; a later sslmode=disable (the driver takes the last one); a decoy
# sslrootcert before the real one; sslfactory / sslhostnameverifier / service (bypass verification); an
# sslmode hidden in another parameter's value; upper-case SSLMODE (the driver ignores it, so prefer applies);
# percent-encoded '=' or a percent-encoded key; and a TLS key before the '?'.
ca=sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem
for bad in "sslmode=require&$ca" \
           "sslmode=verify-full&sslrootcert=/etc/ssl/rds/global-bundle.pem" \
           "sslmode=verify-full&$ca&sslmode=disable" \
           "sslrootcert=/tmp/decoy.pem&sslmode=verify-full&$ca" \
           "sslmode=verify-full&$ca&sslfactory=org.postgresql.ssl.NonValidatingFactory" \
           "sslmode=verify-full&$ca&sslhostnameverifier=x.Y" \
           "sslmode=verify-full&$ca&service=aurora" \
           "sslmode=require&applicationName=sslmode=verify-full&$ca" \
           "SSLMODE=verify-full&$ca" \
           "sslmode%3Dverify-full&$ca&sslmode=verify-full" \
           "sslmode=verify-full&$ca&ssl%6Dode=disable"; do
  if helm template ci "$chart" $args --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?$bad" > /dev/null 2>&1; then
    echo "chart rendered DB_URL ...?$bad (needs exactly one lower-case sslmode=verify-full and the mounted RDS CA bundle)" >&2; exit 1
  fi
done
if helm template ci "$chart" $args --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db;sslmode=disable?sslmode=verify-full&$ca" > /dev/null 2>&1; then
  echo "chart rendered DB_URL with a TLS key before the '?'" >&2; exit 1
fi
# Any other config value that is a PostgreSQL JDBC URL is held to the same rule.
if helm template ci "$chart" $args --set-string "config.OTHER_URL=jdbc:postgresql://other:5432/db?sslmode=disable" > /dev/null 2>&1; then
  echo "chart rendered a second JDBC URL (config.OTHER_URL) without verify-full" >&2; exit 1
fi
# Governance round 3, item 1, hardened in round 6 (guardrail 4a): no value may redirect Spring's
# configuration, name a profile, override the datasource, the Kafka protocol or the startup TLS assertion,
# in any spelling Spring's relaxed binding accepts (case, '.', '-', indexed _0 forms), and JVM option values
# may not carry such settings either (templates/_helpers.tpl, bulk.guardEnv). Each must fail the render.
for bad in "SPRING_CONFIG_IMPORT=optional:configtree:/etc/fintechbankx/config/" \
           "SPRING_CONFIG_IMPORT_0=file:/tmp/application.yml" \
           "spring_config_location=file:/tmp/application.yml" \
           "Spring_Config_Additional_Location=file:/tmp/" \
           "SPRING_CONFIG_ADDITIONAL_LOCATION_0=file:/tmp/" \
           "SPRING_CONFIG_NAME=other" \
           "SPRING_PROFILES_ACTIVE=local" \
           "SPRING_PROFILES_ACTIVE=kafka-msk\,local" \
           "SPRING_PROFILES_INCLUDE=local" \
           "SPRING_PROFILES_INCLUDE_0=local" \
           "spring.profiles.include=local" \
           "spring-profiles-include=local" \
           "SPRING_PROFILES_DEFAULT=local" \
           "SPRING_PROFILES_GROUP_KAFKA_MSK=local" \
           "spring.profiles.group.kafka-msk=local" \
           "SPRING_DATASOURCE_URL=jdbc:postgresql://other:5432/db" \
           "spring.datasource.url=jdbc:postgresql://other:5432/db" \
           "SPRING_DATASOURCE_HIKARI_JDBC_URL=jdbc:postgresql://other:5432/db" \
           "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_SSLMODE=disable" \
           "SPRING_FLYWAY_URL=jdbc:postgresql://other:5432/db" \
           "SPRING_FLYWAY_USER=x" \
           "SPRING_LIQUIBASE_URL=jdbc:postgresql://other:5432/db" \
           "SPRING_R2DBC_URL=r2dbc:postgresql://other:5432/db" \
           "FINTECHBANKX_TLS_ENFORCE=false" \
           "FINTECHBANKX_TLS_ENFORCE_0=false" \
           "fintechbankx.tls.enforce=false" \
           "FINTECHBANKX_TLS=false" \
           "SPRING_KAFKA_SECURITY_PROTOCOL=PLAINTEXT" \
           "SPRING_KAFKA_PRODUCER_SECURITY_PROTOCOL=PLAINTEXT" \
           "SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL=PLAINTEXT" \
           "SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM=" \
           "KAFKA_SECURITY_PROTOCOL=PLAINTEXT" \
           "JAVA_TOOL_OPTIONS=-Dspring.config.import=file:/tmp/application.yml" \
           "JAVA_TOOL_OPTIONS=-Dfintechbankx.tls.enforce=false" \
           "JAVA_TOOL_OPTIONS=-Dspring.kafka.security.protocol=PLAINTEXT" \
           "JDK_JAVA_OPTIONS=-Dspring.datasource.hikari.jdbc-url=jdbc:postgresql://other:5432/db" \
           "_JAVA_OPTIONS=-Dspring.profiles.include=local" \
           "JAVA_TOOL_OPTIONS=@/tmp/jvm.options" \
           "JAVA_TOOL_OPTIONS=-XX:VMOptionsFile=/tmp/jvm.options" \
           "SPRING_SSL_BUNDLE_PEM_DB_TRUSTSTORE_CERTIFICATE=file:/tmp/ca.pem" \
           "SPRING_KAFKA_SSL_BUNDLE=other" \
           "SPRING_DATA_MONGODB_SSL_BUNDLE=other" \
           "DB_SSL_ROOT_CERT=/tmp/other.pem" \
           "PGSSLROOTCERT=/tmp/other.pem" \
           "JAVA_TOOL_OPTIONS=-Djdk.tls.trustNameService=false" \
           "JAVA_TOOL_OPTIONS=-Djava.security.properties=/tmp/java.security" \
           "JAVA_TOOL_OPTIONS=-Djdk.internal.httpclient.disableHostnameVerification=true" \
           "JAVA_TOOL_OPTIONS=\$(JVM_EXTRA)" \
           "JAVA_TOOL_OPTIONS=\${JVM_EXTRA}" \
           "SPRING_KAFKA_SSL_TRUST_STORE_CERTIFICATES=file:/tmp/ca.pem" \
           "DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem\${EXTRA}" \
           "DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem\$(EXTRA)"; do
  if helm template ci "$chart" --namespace payments $args --set-string "config.$bad" > /dev/null 2>&1; then
    echo "chart rendered config.$bad (Spring config redirection, profile, datasource, Kafka protocol or TLS switch must fail the render)" >&2; exit 1
  fi
done
# Dotted and dashed spellings are literal keys only in a values file (--set would nest them into a map,
# which the chart refuses as a non-scalar); Spring reads them as the same names.
for bad in "spring.profiles.include" "spring.profiles.active" "spring-profiles-include" "spring.profiles.group.kafka-msk" \
           "spring.config.import" "spring.config.import[0]" "spring.datasource.url" "spring.datasource.hikari.jdbc-url" \
           "fintechbankx.tls.enforce" "spring.kafka.producer.properties.security.protocol" "spring.kafka.properties.ssl.truststore.location"; do
  if helm template ci "$chart" --namespace payments $args -f <(printf 'config:\n  "%s": "local"\n' "$bad") > /dev/null 2>&1; then
    echo "chart rendered config.\"$bad\" from a values file (must fail the render)" >&2; exit 1
  fi
done
# SPRING_APPLICATION_JSON (any spelling) through a values file: it can set anything.
if helm template ci "$chart" --namespace payments $args \
     -f <(printf 'config:\n  SPRING_APPLICATION_JSON: \x27{"fintechbankx":{"tls":{"enforce":false}}}\x27\n') > /dev/null 2>&1; then
  echo "chart rendered config.SPRING_APPLICATION_JSON (must fail the render)" >&2; exit 1
fi
if helm template ci "$chart" --namespace payments $args \
     -f <(printf 'config:\n  spring.application.json: \x27{"fintechbankx":{"tls":{"enforce":false}}}\x27\n') > /dev/null 2>&1; then
  echo "chart rendered config.spring.application.json (must fail the render)" >&2; exit 1
fi
# Template interpolation: every key and value is quoted, so a value cannot close its line and add a key,
# and a key that is not a ConfigMap key ([-._a-zA-Z0-9]+) fails the render (fbx.validateKeyNames).
if helm template ci "$chart" --namespace payments $args -f <(printf 'config:\n  "SAFE\\nSPRING_CONFIG_IMPORT": "file:/tmp/x.yml"\n') > /dev/null 2>&1; then
  echo "chart rendered a config key containing a newline" >&2; exit 1
fi
injected="$(helm template ci "$chart" --namespace payments $args -f <(printf 'config:\n  OIDC_AUDIENCE: "x\\nSPRING_CONFIG_IMPORT: file:/tmp/x.yml\\nSPRING_DATASOURCE_URL: jdbc:x"\n'))"
if grep -qE '^ *(SPRING_CONFIG_IMPORT|SPRING_DATASOURCE_URL):' <<<"$injected"; then
  echo "a config value containing a newline injected an extra ConfigMap key" >&2; exit 1
fi
[ "$(python3 -c "import sys,yaml; print(sum(1 for d in yaml.safe_load_all(sys.stdin) if d and d.get('kind')=='ConfigMap' for k in d['data'] if k in ('SPRING_CONFIG_IMPORT','SPRING_DATASOURCE_URL')))" <<<"$injected")" -eq 0 ]
# refuse <expected ERE> <helm args...>: the render must fail, and for that reason, so a probe cannot
# pass because helm failed to parse it or another rule fired first.
refuse() {
  local expect="$1"; shift
  local out
  if out="$(helm template ci "$chart" --namespace payments $args "$@" 2>&1 >/dev/null)"; then
    echo "chart rendered with $*" >&2; exit 1
  fi
  if ! grep -qE -- "$expect" <<<"$out"; then
    echo "chart refused $* for another reason than '$expect': $out" >&2; exit 1
  fi
}
# What 6b6c317 adds, each refused for its own reason. The database CA mount is pinned (fbx.validateDatabaseCa):
# /app is the image WORKDIR and Spring Boot loads ./config/application.yml; another ConfigMap or key replaces
# the database trust anchor; the adapter must pass configMapName (Helm deletes a key set to null).
refuse 'config.ACCOUNTS_SERVICE_BASE_URL is required' --set-string config.ACCOUNTS_SERVICE_BASE_URL=
refuse 'databaseCa.mountPath must be /etc/fintechbankx/rds-ca' --set-string rdsCaBundle.mountPath=/app/config
refuse 'databaseCa.key must be global-bundle.pem' --set-string rdsCaBundle.key=other-bundle.pem
refuse 'databaseCa.configMapName must be rds-ca-bundle' --set-string rdsCaBundle.configMapName=any-config
refuse 'databaseCa.configMapName is required while databaseCa.enabled' --set rdsCaBundle.configMapName=null
# Kafka client TLS names (fbx.validateKafkaTlsValue and the name rule), whatever the runtime.
refuse 'config.SPRING_KAFKA_SSL_TRUST_STORE_LOCATION is not allowed: a Kafka client TLS setting' --set-string config.SPRING_KAFKA_SSL_TRUST_STORE_LOCATION=file:/etc/other/any.jks
refuse 'config.SPRING_KAFKA_PRODUCER_PROPERTIES_SSL_TRUSTSTORE_CERTIFICATES is not allowed: a Kafka client TLS setting' --set-string config.SPRING_KAFKA_PRODUCER_PROPERTIES_SSL_TRUSTSTORE_CERTIFICATES=x
refuse 'config.spring.kafka.properties.ssl.truststore.location is not allowed: a Kafka client TLS setting' --set-string 'config.spring\.kafka\.properties\.ssl\.truststore\.location=/tmp/x'
refuse 'config.KAFKA_TLS_CERT is not allowed' --set kafkaStrimzi.enabled=true --set-string config.KAFKA_TLS_CERT=x
refuse 'config.KAFKA_TLS_CA is not allowed' --set-string config.KAFKA_TLS_CA=x
refuse 'config.KAFKA_SECURITY_PROTOCOL must be SASL_SSL or SSL' --set-string config.KAFKA_SECURITY_PROTOCOL=PLAINTEXT
refuse 'config.KAFKA_SECURITY_PROTOCOL must be SSL with kafka.runtime strimzi' --set kafkaStrimzi.enabled=true --set-string config.KAFKA_SECURITY_PROTOCOL=SASL_SSL
refuse 'config.SPRING_KAFKA_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM must be https' --set-string config.SPRING_KAFKA_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM=
refuse 'config.SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM must be https' --set-string config.SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM=
# The guard names sasl.* and jaas (a Kafka SASL or JAAS setting); the chart-local rule refuses the other raw Kafka client properties.
refuse 'config.spring.kafka.properties.sasl.jaas.config is not allowed: a Kafka SASL or JAAS setting' --set-string 'config.spring\.kafka\.properties\.sasl\.jaas\.config=x'
refuse 'config.spring.kafka.consumer.properties.sasl.mechanism is not allowed: a Kafka SASL or JAAS setting' --set-string 'config.spring\.kafka\.consumer\.properties\.sasl\.mechanism=x'
refuse 'config.SPRING_KAFKA_PRODUCER_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM is not allowed: a raw Kafka client property' --set-string config.SPRING_KAFKA_PRODUCER_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM=https
# DocumentDB names: the connection string comes only from MONGODB_URI in a Secret.
refuse 'config.SPRING_DATA_MONGODB_URI is not allowed: a spring.data.mongodb.\* property' --set-string config.SPRING_DATA_MONGODB_URI=mongodb://docdb.example.internal:27017/db
refuse 'config.MONGODB_URI is not allowed: MONGODB_URI is the DocumentDB connection string' --set-string config.MONGODB_URI=mongodb://docdb.example.internal:27017/db
# Property reading: Spring Boot splits an environment name on '.' only and drops '_' and other characters
# inside an element, so these bind spring.kafka.ssl.*, spring.data.mongodb.* and spring.profiles.active
# (fbx.propertyName); a security.protocol spelled with '-', '_' or brackets is value-checked all the same.
refuse 'config.spring.kafka.s_sl.trust-store-type is not allowed: a Kafka client TLS setting' --set-string 'config.spring\.kafka\.s_sl\.trust-store-type=PKCS12'
refuse 'config.spring.data.mongo_db.host is not allowed: a spring.data.mongodb.\* property' --set-string 'config.spring\.data\.mongo_db\.host=docdb.example.internal'
refuse 'config.spring.pro_files.active is not allowed' --set-string 'config.spring\.pro_files\.active=local'
refuse 'config.spring.kafka.secu-rity.protocol must be SASL_SSL or SSL' --set-string 'config.spring\.kafka\.secu-rity\.protocol=PLAINTEXT'
refuse 'config.spring.kafka.security.pro_tocol must be SASL_SSL or SSL' --set-string 'config.spring\.kafka\.security\.pro_tocol=PLAINTEXT'
refuse 'config.spring.kafka.properties\[security.protocol\] must be SASL_SSL or SSL' --set-string 'config.spring\.kafka\.properties\[security\.protocol\]=PLAINTEXT'
refuse 'config.fintech-bankx.tls.enforce is not allowed' --set-string 'config.fintech-bankx\.tls\.enforce=false'
# JVM options in a config entry: kafka and mongodb are refused with the datasource and TLS settings.
refuse 'config.JAVA_TOOL_OPTIONS must not mention .*kafka' --set-string 'config.JAVA_TOOL_OPTIONS=-Dspring.kafka.bootstrap-servers=other.example.internal:9092'
refuse 'config.JAVA_TOOL_OPTIONS must not mention .*mongodb' --set-string 'config.JAVA_TOOL_OPTIONS=-Dspring.data.mongodb.uri=mongodb://docdb.example.internal:27017/db'
# Schema-management names, JVM options and bracket names (cicd-templates 73e1881): each refused for its own reason.
refuse 'config\.SPRING_JPA_HIBERNATE_DDL_AUTO is not allowed: a schema-management setting' --set-string config.SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop
refuse 'config\.spring\.jpa\.generate-ddl is not allowed: a schema-management setting' --set-string 'config.spring\.jpa\.generate-ddl=true'
refuse 'config\.SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO is not allowed: a schema-management setting' --set-string config.SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO=update
refuse 'config\.SPRING_SQLINIT_MODE is not allowed: a schema-management setting' --set-string config.SPRING_SQLINIT_MODE=always
refuse 'config\.JAVAX_PERSISTENCE_SCHEMA_GENERATION_DATABASE_ACTION is not allowed: a schema-management setting' --set-string config.JAVAX_PERSISTENCE_SCHEMA_GENERATION_DATABASE_ACTION=drop-and-create
refuse 'config\.spring\.j_pa\.hibernate\.ddl-auto is not allowed: a schema-management setting' --set-string 'config.spring\.j_pa\.hibernate\.ddl-auto=create'
refuse 'config\.JAVA_TOOL_OPTIONS must not mention .*spring\.jpa, spring\.sql\.init, hibernate' --set-string 'config.JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -Dspring.jpa.hibernate.ddl-auto=create-drop'
refuse 'config\.JDK_JAVA_OPTIONS must not mention .*hibernate' --set-string config.JDK_JAVA_OPTIONS=-Dhibernate.hbm2ddl.auto=update
refuse 'config\.JAVA_TOOL_OPTIONS must not mention .*spring\.sql\.init' --set-string config.JAVA_TOOL_OPTIONS=-Dspring.sql.init.mode=always
refuse 'config\.JAVA_TOOL_OPTIONS must not mention .*spring\.jpa' --set-string 'config.JAVA_TOOL_OPTIONS=-Dspring.j_pa.generate-ddl=true'
# Still allowed: the datasource credentials and a JVM option that mentions none of the guarded settings.
helm template ci "$chart" --namespace payments $args --set-string config.SPRING_DATASOURCE_USERNAME=payment_bulk_app > /dev/null
helm template ci "$chart" --namespace payments $args --set-string "config.JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" > /dev/null
# The profile and the Kafka protocol come from the boolean kafkaStrimzi.enabled only: kafka-msk/SASL_SSL by
# default, kafka-strimzi/SSL when set; neither is in the ConfigMap.
deployment="$(helm template ci "$chart" --namespace payments $args --show-only templates/deployment.yaml)"
grep -A1 'name: SPRING_PROFILES_ACTIVE' <<<"$deployment" | grep -q 'value: "kafka-msk"'
grep -A1 'name: KAFKA_SECURITY_PROTOCOL' <<<"$deployment" | grep -q 'value: "SASL_SSL"'
strimzi="$(helm template ci "$chart" --namespace payments --set kafkaStrimzi.enabled=true $args --show-only templates/deployment.yaml)"
grep -A1 'name: SPRING_PROFILES_ACTIVE' <<<"$strimzi" | grep -q 'value: "kafka-strimzi"'
grep -A1 'name: KAFKA_SECURITY_PROTOCOL' <<<"$strimzi" | grep -q 'value: "SSL"'
if helm template ci "$chart" --namespace payments $args --show-only templates/configmap.yaml | grep -qE 'SPRING_PROFILES|KAFKA_SECURITY_PROTOCOL'; then
  echo "the ConfigMap carries the profile or the Kafka protocol; they must come from kafkaStrimzi.enabled" >&2; exit 1
fi
# Flyway runs only in the hook Job, as the schema owner; the pods never get that secret.
job="$(helm template ci "$chart" --namespace payments $args --show-only templates/migration-job.yaml)"
grep -q 'helm.sh/hook: pre-install,pre-upgrade' <<<"$job"
grep -q 'args: \["migrate"\]' <<<"$job"
grep -q 'key: "dev/payment-bulk-orchestration-service/db-migration"' <<<"$job"
deployment="$(helm template ci "$chart" --namespace payments $args --show-only templates/deployment.yaml)"
grep -A1 'name: SPRING_FLYWAY_ENABLED' <<<"$deployment" | grep -q 'value: "false"'
if grep -q 'db-migration' <<<"$deployment"; then echo "the service pods reference the migration secret" >&2; exit 1; fi
if helm template ci "$chart" $args --set migration.remoteSecretName= > /dev/null 2>&1; then
  echo "chart rendered without the migration secret" >&2; exit 1
fi
# Platform admission policy: every ExternalSecret carries
# app.kubernetes.io/name=<service account> and reads only <env>/<service account>/.
sa=payment-bulk-orchestration-service
rendered="$(helm template ci "$chart" --namespace payments $args)"
keys="$(grep -E '^ +key: ' <<<"$rendered" | awk '{print $2}' | tr -d '"')"
test -n "$keys"
if grep -vE "^(dev|staging|prod)/$sa/[a-z0-9-]+$" <<<"$keys"; then
  echo "ExternalSecret remoteRef key outside <env>/$sa/" >&2; exit 1
fi
if [ "$(grep -c '^kind: ExternalSecret' <<<"$rendered")" -ne "$(awk '/^kind: ExternalSecret/{es=1} /^---/{es=0} es && /app.kubernetes.io\/name: payment-bulk-orchestration-service$/{n++; es=0} END{print n+0}' <<<"$rendered")" ]; then
  echo "an ExternalSecret lacks app.kubernetes.io/name=$sa" >&2; exit 1
fi
if helm template ci "$chart" $args --set externalSecret.remoteSecretName=dev-$sa/db-app > /dev/null 2>&1; then
  echo "chart rendered a remoteRef key outside <env>/$sa/" >&2; exit 1
fi
# Platform review round 2: NetworkPolicy is the mesh repo's (off by default, never a CIDR),
# the observability PodMonitor is the single scrape path (no ServiceMonitor), squad label set,
# the sidecar drains for 55 s within the grace period.
if grep -qE '^kind: (NetworkPolicy|ServiceMonitor)$' <<<"$rendered"; then
  echo "chart renders a NetworkPolicy or ServiceMonitor by default" >&2; exit 1
fi
if helm template ci "$chart" $args --set networkPolicy.enabled=true | grep -qE 'ipBlock|cidr'; then
  echo "the opt-in NetworkPolicy admits a CIDR" >&2; exit 1
fi
grep -q 'fintechbankx.io/service-id: svc-pay-bulk-orchestration' <<<"$deployment"
grep -q 'prometheus.io/port: "8081"' <<<"$deployment"
grep -q "proxy.istio.io/config: '{\"terminationDrainDuration\": \"55s\"}'" <<<"$deployment"
test "$(grep -oE 'terminationGracePeriodSeconds: [0-9]+' <<<"$deployment" | awk '{print $2}')" -gt 55
test "$(grep -c 'fintechbankx.io/squad: "payments"' <<<"$rendered")" -ge 5
# Micrometer common tags app = service account and squad = namespace, from the ConfigMap.
grep -q 'METRICS_TAG_SQUAD: "payments"' <<<"$rendered"
grep -q "METRICS_TAG_APP: \"$sa\"" <<<"$rendered"
# Pod labels and selection (cicd-templates 335a345): API and Job pods both carry
# app.kubernetes.io/name=<SA> (mesh Aurora egress); every selector requires component=service, so none
# selects the db-migration Job pod; only the API pods carry fintechbankx.io/service-id.
# PyYAML for the checks below, in a throwaway venv: the runner's Python may be externally managed,
# where pip --user fails.
if ! python3 -c "import yaml" 2>/dev/null; then
  pyyaml_venv="$(mktemp -d)/pyyaml"
  python3 -m venv "$pyyaml_venv" && "$pyyaml_venv/bin/pip" install --quiet pyyaml==6.0.2 || exit 1
  PATH="$pyyaml_venv/bin:$PATH"
fi
python3 scripts/ci/helm-selector-check.py "$sa" svc-pay-bulk-orchestration <<<"$rendered"
helm template ci "$chart" --namespace payments -f "$chart/values-dev.yaml" $args \
  | python3 scripts/ci/helm-selector-check.py "$sa" svc-pay-bulk-orchestration
helm template ci "$chart" --namespace payments --set networkPolicy.enabled=true $args \
  | python3 scripts/ci/helm-selector-check.py "$sa" svc-pay-bulk-orchestration
# The check itself must catch selectors that would pick up the Job pod.
if sed '/app.kubernetes.io\/component: service/d; /app.kubernetes.io\/component: db-migration/d' <<<"$rendered" \
     | python3 scripts/ci/helm-selector-check.py "$sa" svc-pay-bulk-orchestration 2>/dev/null; then
  echo "helm-selector-check accepted selectors that match the migration Job pod" >&2; exit 1
fi
# Parked-outbox alerting is the platform's OutboxEventsParked rule; the chart ships none.
if grep -qE 'kind: PrometheusRule|outbox_parked' <<<"$rendered"; then
  echo "the chart renders its own outbox alert rule; use the platform OutboxEventsParked" >&2; exit 1
fi
# A missing required value must fail the render.
if helm template ci "$chart" --set image.repository=x --set image.tag=y \
     --set externalSecret.remoteSecretName=dev/payment-bulk-orchestration-service/db-app \
     --set externalSecret.serviceClientSecretName=dev/payment-bulk-orchestration-service/oidc-client \
     --set migration.remoteSecretName=dev/payment-bulk-orchestration-service/db-migration > /dev/null 2>&1; then
  echo "chart rendered without required config"; exit 1
fi
