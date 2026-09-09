#!/usr/bin/env bash
# Baut und startet die Demo mit JDK 8 + OpenTelemetry-Javaagent.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="$HOME/bin/jdk_8"
MVN="$HOME/bin/maven-3.9.11/bin/mvn"

AGENT=otel/opentelemetry-javaagent.jar
[ -f "$AGENT" ] || curl -sL --create-dirs -o "$AGENT" \
  "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar"

echo ">>> Build mit $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
"$MVN" -q -DskipTests package

CP="target/classes:$(echo target/lib/*.jar | tr ' ' ':')"

# Traces landen ueber den Console-Exporter auf stdout (kein Backend noetig).
# Alternative Collector/Jaeger: -Dotel.traces.exporter=otlp -Dotel.exporter.otlp.endpoint=http://localhost:4317
#
# methods.include: Span OHNE Annotation (Feature 10; object-Methoden liegen auf "...Showcase$").
# Gegenstueck zum Testen einkommentieren - schaltet annotierte Methoden ab:
#   -Dotel.instrumentation.opentelemetry-instrumentation-annotations.exclude-methods='demo.OtelAnnotationShowcase$[simpleSpan]' \
exec "$JAVA_HOME/bin/java" \
  -javaagent:otel/opentelemetry-javaagent.jar \
  -Dotel.service.name=scala-annotation-demo \
  -Dotel.traces.exporter=console \
  -Dotel.metrics.exporter=none \
  -Dotel.logs.exporter=none \
  -Dotel.instrumentation.methods.include='demo.OtelAnnotationShowcase$[methodWithoutAnnotation]' \
  -cp "$CP" demo.OtelAnnotationShowcase
