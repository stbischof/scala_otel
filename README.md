# OpenTelemetry-Annotation-Showcase (Scala, JDK 8)

Eine Datei — [`src/main/scala/demo/OtelAnnotationShowcase.scala`](src/main/scala/demo/OtelAnnotationShowcase.scala) —
demonstriert alle Features der OpenTelemetry-Instrumentation-Annotationen,
ausgeführt mit **JDK 8** (`~/bin/jdk_8`) und dem
**Auto-Instrumentation-Agent 2.31.1** (`otel/opentelemetry-javaagent.jar`).
Bewusst ohne manuelle OTel-API — alles läuft über Annotationen bzw. Agent-Config.

## Starten

```bash
./run.sh
```

Baut mit Maven (`~/bin/maven-3.9.11`) und startet die Demo; Traces gehen per
Console-Exporter auf stdout. Für ein echtes Backend die OTLP-Zeile in `run.sh`
einkommentieren.

## Features

| # | Feature | Methode |
|---|---------|---------|
| 1 | `@WithSpan` mit Default-Namen (`Klasse.methode`) | `simpleSpan` |
| 2 | `@WithSpan("eigener-name")` | `lookupCustomer` |
| 3 | `@WithSpan(kind = …)` — alle 5 SpanKinds | `handleIncomingRequest` (SERVER), `callPaymentService` (CLIENT), `publishOrderEvent` (PRODUCER), `consumeOrderEvent` (CONSUMER), Rest INTERNAL |
| 4 | `@WithSpan(inheritContext = false)` → neuer Root-Trace | `backgroundHousekeeping` |
| 5 | `@SpanAttribute` — String, Long, Double, Boolean, List | `allAttributeTypes` |
| 6 | `@AddingSpanAttributes` — Attribute an den aktuellen Span, kein neuer | `storeAttributesOnCurrentSpan` |
| 7 | Exception → Status ERROR + exception-Event | `failingOperation` |
| 8 | `CompletableFuture` → Span endet erst bei Completion | `asyncOperation` |
| 9 | Verschachtelte Spans / Trace-Hierarchie | `handleIncomingRequest` + Kinder |
| 10 | Span ohne Annotation per `otel.instrumentation.methods.include` | `methodWithoutAnnotation` |

## Scala-Details (empirisch geprüft)

- **S1** — `object`-Methoden liegen auf `demo.OtelAnnotationShowcase$` (mit `$`);
  genau so muss der Name in `methods.include`/`exclude-methods` stehen.
- **S2** — `@SpanAttribute` ohne Namen funktioniert nicht: scalac schreibt keine
  `MethodParameters` (javac `-parameters`) → Attribut fehlt still. Immer benennen.
- **S3** — `scala.concurrent.Future`-Rückgabe wird vom Async-Support nicht
  erkannt: Span endet sofort (`operation.scala-future` ~0 ms vs.
  `operation.async` ~300 ms). Kontext-Propagation *in* den Future-Body
  funktioniert aber (`operation.inside-scala-future` hängt im richtigen Trace).
- **S4** — mehrelementige Java-Annotationen brauchen benannte Argumente:
  `@WithSpan(value = "…", kind = …)`; nur `value` darf positional stehen.
- **S5** — `@WithSpan` im Trait erzeugt **doppelte Spans**: die Annotation landet
  auf Interface-Default-Methode *und* Mixin-Forwarder, der Agent instrumentiert
  beide (2× `audit.trail`, unterscheidbar an `code.namespace`). Abhilfe: nur in
  Klassen/objects annotieren oder
  `…annotations.exclude-methods='demo.Auditable[audit]'`.
- **S6** — normale Klassen (`OrderValidator`) verhalten sich wie Java, ohne `$`.
- **S7** — Lambdas/anonyme Funktionen sind nicht annotierbar; bei
  `AnyVal`-Value-Classes (`$extension`-Methoden) `@WithSpan` vermeiden.
- **S8** — Default-Parameter (synthetische `m$default$N`-Methoden) sind unkritisch.

## Metrik-Annotationen (`@Timed` / `@Counted`)?

Gibt es Stand heute (Agent 2.31.x) **noch nicht**, sie sind aber geplant:
`@Counted` ist in Arbeit ([PR #19379](https://github.com/open-telemetry/opentelemetry-java-instrumentation/pull/19379),
ungemergt — LongCounter pro Aufruf, Name via `value` oder `ClassName.methodName`,
noch ohne Attribut-Support); `@Timed` und weitere Ideen im Sammel-Issue
[#7030](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/7030).
Released würden sie im selben Artefakt
`io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations` landen.

## Erwartete Ausgabe (verifiziert)

Ein Trace unter `http.handle-request` (SERVER) mit allen Kind-Spans;
`background.housekeeping` mit eigener Trace-ID; `operation.failing` mit
ERROR-Status; die Async-/Future-Kontraste und Trait-Duplikate wie unter
S3/S5 beschrieben.
