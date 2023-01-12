package io.cardell.bucky.epimetheus

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.Resource
import cats.implicits._
import com.itv.bucky.ExchangeName
import com.itv.bucky.RoutingKey
import com.itv.bucky.circe.auto._
import com.itv.bucky.consume.Ack
import com.itv.bucky.publish.MessageProperties
import com.itv.bucky.test.AmqpClientTest
import com.itv.bucky.wiring.Wiring
import com.itv.bucky.wiring.WiringName
import io.chrisdavenport.epimetheus.CollectorRegistry
import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import weaver.IOSuite

trait TestData {
  val exchangeName = ExchangeName("exchange")
  val routingKey = RoutingKey("routingKey")
  val props = MessageProperties.minimalBasic

  val msg = TestEvent("a2", 3)

  case class TestEvent(a: String, B: Int)

  object TestEvent {
    implicit val encoder: Encoder[TestEvent] = deriveEncoder
    implicit val decoder: Decoder[TestEvent] = deriveDecoder
  }

  object TestWiring
      extends Wiring[TestEvent](name = WiringName("test-event-wiring"))

  def exactlyOne[A](ls: List[A]): IO[A] = ls match {
    case head :: Nil =>
      IO.pure(head)
    case _ :: _ :: _ =>
      IO.raiseError(new Throwable("too many values in list"))
    case Nil =>
      IO.raiseError(new Throwable("no values in list"))
  }

  def getCollector(
      reg: CollectorRegistry[IO],
      name: String
  ) =
    IO(CollectorRegistry.Unsafe.asJava(reg))
      .map(_.metricFamilySamples())
      .map(_.asScala.toList)
      .map(_.find(_.name == s"bucky_$name"))
      .flatMap(
        IO.fromOption(_)(new Throwable(s"could not find collector ${name}"))
      )
      .map(_.samples.asScala.toList)
      .map(_.filter { s => s.name == s"bucky_${name}_total" })
      .map(s => NonEmptyList.fromList(s))
      .flatMap(
        IO.fromOption(_)(new Throwable(s"could not find sample ${name}"))
      )
      .map(_.toList)
}

object TestData extends TestData

object EpimetheusAmqpClientTest extends IOSuite with TestData {

  type Res = ExecutionContext

  override def sharedResource: Resource[IO, Res] =
    Resource.eval(IO(ExecutionContext.global))

  test("increments published messages counter") { (ec, _) =>
    implicit val ecc = ec

    AmqpClientTest[IO].clientStrict().use { amqp =>
      for {
        reg <- CollectorRegistry.build[IO]
        metrics <- EpimetheusAmqpMetrics.register[IO](reg)
        epAmqp = EpimetheusAmqpClient[IO](amqp, metrics)

        publisher <- TestWiring.publisher(epAmqp)

        _ <- publisher(msg)
        c1 <- getCollector(reg, "published_messages")
          .map(_.filter(_.labelValues.contains("succeeded")))
          .flatMap(exactlyOne)

        _ <- publisher(msg)
        c2 <- getCollector(reg, "published_messages")
          .map(_.filter(_.labelValues.contains("succeeded")))
          .flatMap(exactlyOne)

      } yield expect(c1.value == 1.0).and(expect(c2.value == 2.0))
    }
  }

  test("increments consumed messages counter") { (ec, _) =>
    implicit val ecc = ec

    AmqpClientTest[IO].clientStrict().use { amqp =>
      CollectorRegistry.build[IO].flatMap { reg =>
        val setup = for {
          metrics <- EpimetheusAmqpMetrics.register[IO](reg)
          publisher <- TestWiring.publisher(amqp)
          epAmqp = EpimetheusAmqpClient[IO](amqp, metrics)
        } yield (epAmqp, publisher)

        val resources = for {
          s <- Resource.eval(setup)
          client = s._1
          publisher = s._2
          // consumer opens and consumes in background
          _ <- TestWiring.registerConsumer(client)(_ => IO(Ack))
        } yield publisher

        val consumedMessages =
          getCollector(reg, "consumed_messages")
            .map(
              _.filter(s =>
                s.labelValues.contains("ack") &&
                  s.labelValues.contains("succeeded")
              )
            )
            .flatMap(exactlyOne)

        resources.use { publisher =>
          for {
            _ <- publisher(msg)
            c1 <- consumedMessages
            _ <- publisher(msg)
            c2 <- consumedMessages
            _ <- IO.sleep(1.second)
          } yield expect(c1.value == 1.0).and(expect(c2.value == 2.0))
        }
      }
    }
  }
}
