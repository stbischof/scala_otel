package demo

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.instrumentation.annotations.{AddingSpanAttributes, SpanAttribute, WithSpan}

import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random

/**
 * OpenTelemetry-Annotation-Showcase (Scala, JDK 8, Auto-Instrumentation-Agent).
 *
 * Zeigt alle Features von
 * io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations
 * (Details an den Methoden, Nummern 1-10) sowie die Scala-Besonderheiten S1-S8
 * (siehe README, Abschnitt "Scala-Details").
 *
 * Metrik-Annotationen (@Counted/@Timed) gibt es noch nicht: @Counted ist in
 * Arbeit (PR #19379, ungemergt - LongCounter pro Aufruf, Name via value oder
 * "ClassName.methodName"), @Timed offen (Sammel-Issue #7030).
 *   https://github.com/open-telemetry/opentelemetry-java-instrumentation/pull/19379
 *   https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/7030
 */
object OtelAnnotationShowcase extends Auditable {

  def main(args: Array[String]): Unit = {
    println("=== OpenTelemetry Annotation Showcase (Scala auf JDK 8) ===\n")

    handleIncomingRequest("order-4711", "stbischof")   // 9. gemeinsamer Trace

    // 7. Exception -> Status ERROR + exception-Event am Span
    try failingOperation("kaputt") catch {
      case e: IllegalStateException => println(s"  erwartet gefangen: ${e.getMessage}")
    }

    // 8. vs. S3: CompletableFuture wird asynchron beendet, Scala-Future nicht
    println(s"  async-Ergebnis: ${asyncOperation(300).get(5, TimeUnit.SECONDS)}")
    println(s"  scala-future-Ergebnis: ${Await.result(scalaFutureOperation(300), 5.seconds)}")

    println("=== fertig ===")
  }

  /**
   * 2.+3. Eigener Span-Name + SpanKind.SERVER; ruft alle weiteren Demos im
   * selben Trace auf (9. Verschachtelung).
   * S4: mehrelementige Java-Annotationen brauchen in Scala benannte Argumente
   * (@WithSpan(value=..., kind=...)); nur value allein darf positional stehen.
   */
  @WithSpan(value = "http.handle-request", kind = SpanKind.SERVER)
  def handleIncomingRequest(@SpanAttribute("order.id") orderId: String,
                            @SpanAttribute("user.name") user: String): Unit = {
    simpleSpan()                                          // 1. Default-Name
    lookupCustomer(user)                                  // 2. eigener Span-Name
    storeAttributesOnCurrentSpan(orderId, premium = true) // 6. @AddingSpanAttributes
    callPaymentService(orderId, 149.99)                   // 3. CLIENT
    publishOrderEvent(orderId)                            // 3. PRODUCER
    consumeOrderEvent(orderId)                            // 3. CONSUMER
    allAttributeTypes("DE", 42L, 0.19, ok = true,
      java.util.Arrays.asList("a", "b", "c"))             // 5. Attribut-Typen
    unnamedAttribute("wird-uebersprungen")                // S2. ohne Namen
    new OrderValidator().validate(orderId)                // S6. normale Klasse
    audit(s"request:$orderId")                            // S5. Trait-Methode
    backgroundHousekeeping()                              // 4. inheritContext=false
    methodWithoutAnnotation()                             // 10. per Config instrumentiert
  }

  /** 1. Minimalfall: Span-Name = "OtelAnnotationShowcase$.simpleSpan", Kind = INTERNAL. */
  @WithSpan
  def simpleSpan(): Unit = work(20)

  /** 2. Eigener Span-Name statt "Klasse.methode". */
  @WithSpan("db.lookup-customer")
  def lookupCustomer(@SpanAttribute("user.name") user: String): String = {
    work(30)
    s"customer:$user"
  }

  /** 6. Kein neuer Span - Parameter werden Attribute des AKTUELLEN Spans (http.handle-request). */
  @AddingSpanAttributes
  def storeAttributesOnCurrentSpan(@SpanAttribute("enriched.order.id") orderId: String,
                                   @SpanAttribute("enriched.premium") premium: Boolean): Unit =
    work(5)

  /** 3. SpanKind.CLIENT: simulierter ausgehender Call. */
  @WithSpan(value = "payment.charge", kind = SpanKind.CLIENT)
  def callPaymentService(@SpanAttribute("order.id") orderId: String,
                         @SpanAttribute("payment.amount") amount: Double): Unit = work(50)

  /** 3. SpanKind.PRODUCER. */
  @WithSpan(value = "queue.publish-order", kind = SpanKind.PRODUCER)
  def publishOrderEvent(@SpanAttribute("messaging.message.id") orderId: String): Unit = work(10)

  /** 3. SpanKind.CONSUMER. */
  @WithSpan(value = "queue.consume-order", kind = SpanKind.CONSUMER)
  def consumeOrderEvent(@SpanAttribute("messaging.message.id") orderId: String): Unit = work(15)

  /** 5. @SpanAttribute mit allen unterstuetzten Typen. */
  @WithSpan("attributes.all-types")
  def allAttributeTypes(@SpanAttribute("attr.string") country: String,
                        @SpanAttribute("attr.long") answer: Long,
                        @SpanAttribute("attr.double") taxRate: Double,
                        @SpanAttribute("attr.boolean") ok: Boolean,
                        @SpanAttribute("attr.list") tags: java.util.List[String]): Unit = work(5)

  /**
   * S2. Ohne expliziten Namen braucht der Agent Parameternamen im Bytecode
   * (javac -parameters); scalac schreibt keine -> Attribut fehlt am Span.
   * In Scala @SpanAttribute daher immer benennen.
   */
  @WithSpan("attributes.unnamed")
  def unnamedAttribute(@SpanAttribute payload: String): Unit = work(5)

  /** 4. inheritContext=false: neuer Root-Trace (eigene Trace-ID) statt Child-Span. */
  @WithSpan(value = "background.housekeeping", inheritContext = false)
  def backgroundHousekeeping(): Unit = work(25)

  /** 7. Exception -> Agent setzt Status ERROR + "exception"-Event mit Stacktrace. */
  @WithSpan("operation.failing")
  def failingOperation(@SpanAttribute("input") input: String): Unit = {
    work(10)
    throw new IllegalStateException(s"Simulierter Fehler fuer '$input'")
  }

  /** 8. CompletableFuture/CompletionStage-Rueckgabe: Span endet erst bei Completion (~300ms). */
  @WithSpan(value = "operation.async", kind = SpanKind.CLIENT)
  def asyncOperation(@SpanAttribute("delay.ms") delayMs: Long): CompletableFuture[String] =
    CompletableFuture.supplyAsync(() => {
      Thread.sleep(delayMs)
      s"done after ${delayMs}ms"
    })

  /**
   * S3. scala.concurrent.Future wird vom Async-Support NICHT erkannt: der Span
   * endet sofort beim return (~0ms, Kontrast zu operation.async). Die
   * Kontext-Propagation IN den Future-Body funktioniert aber - siehe
   * insideScalaFuture, das im richtigen Trace landet.
   */
  @WithSpan("operation.scala-future")
  def scalaFutureOperation(@SpanAttribute("delay.ms") delayMs: Long): Future[String] = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    Future {
      Thread.sleep(delayMs)
      insideScalaFuture()
      s"done after ${delayMs}ms"
    }
  }

  @WithSpan("operation.inside-scala-future")
  def insideScalaFuture(): Unit = work(5)

  /**
   * 10./S1. Keine Annotation - Span kommt aus der Agent-Config (run.sh):
   *   -Dotel.instrumentation.methods.include='demo.OtelAnnotationShowcase$[methodWithoutAnnotation]'
   * S1: object-Methoden liegen auf der Modul-Klasse "...Showcase$" (mit $) -
   * genau dieser Name muss in der Config stehen.
   * S8: der Default-Parameter erzeugt eine synthetische
   * methodWithoutAnnotation$default$1-Methode - unkritisch.
   */
  def methodWithoutAnnotation(reason: String = "scheduled"): Unit = work(5)

  /** simulierte Arbeit */
  private[demo] def work(baseMs: Int): Unit = Thread.sleep(baseMs + Random.nextInt(5))
}

/**
 * S5. @WithSpan im Trait: funktioniert, aber DOPPELT (empirisch geprueft) -
 * scalac kopiert die Annotation auf Interface-Default-Methode UND
 * Mixin-Forwarder, der Agent instrumentiert beide: ein Aufruf = zwei
 * verschachtelte "audit.trail"-Spans (code.namespace demo.Auditable bzw.
 * demo.OtelAnnotationShowcase$). Abhilfe: nur in Klassen/objects annotieren
 * oder eine Seite ausschliessen:
 *   -Dotel.instrumentation.opentelemetry-instrumentation-annotations.exclude-methods='demo.Auditable[audit]'
 */
trait Auditable {
  @WithSpan("audit.trail")
  def audit(@SpanAttribute("audit.entry") entry: String): Unit =
    OtelAnnotationShowcase.work(5)
}

/**
 * S6. @WithSpan in einer normalen Klasse: Standardfall wie in Java
 * (code.namespace=demo.OrderValidator, ohne $).
 * S7: Lambdas/anonyme Funktionen (synthetische Methoden) sind nicht
 * annotierbar; AnyVal-Methoden werden zu statischen $extension-Methoden -
 * @WithSpan dort vermeiden.
 */
class OrderValidator {
  @WithSpan("order.validate")
  def validate(@SpanAttribute("order.id") orderId: String): Boolean = {
    OtelAnnotationShowcase.work(8)
    orderId.startsWith("order-")
  }
}
