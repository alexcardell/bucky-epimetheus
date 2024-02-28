package io.cardell.bucky.epimetheus

import cats.effect.Resource
import cats.effect.kernel.Clock
import cats.effect.kernel.MonadCancel
import cats.effect.kernel.Outcome
import cats.effect.kernel.Outcome.Canceled
import cats.effect.kernel.Outcome.Errored
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.Sync
import cats.implicits._
import com.itv.bucky.AmqpClient
import com.itv.bucky.Handler
import com.itv.bucky.Publisher
import com.itv.bucky.QueueName
import com.itv.bucky.consume.Ack
import com.itv.bucky.consume.ConsumeAction
import com.itv.bucky.consume.DeadLetter
import com.itv.bucky.consume.Delivery
import com.itv.bucky.consume.RequeueImmediately
import com.itv.bucky.decl.Declaration
import com.itv.bucky.publish.PublishCommand
import io.chrisdavenport.epimetheus.Counter
import io.chrisdavenport.epimetheus.Counter.UnlabelledCounter
import io.chrisdavenport.epimetheus.Histogram
import io.chrisdavenport.epimetheus.Histogram.UnlabelledHistogram
import io.chrisdavenport.epimetheus.Label
import io.chrisdavenport.epimetheus.Name
import io.chrisdavenport.epimetheus.PrometheusRegistry
import scala.concurrent.duration.FiniteDuration
import shapeless.Sized

case class EpimetheusAmqpMetrics[F[_]](
    publishedMessages: UnlabelledCounter[F, PublishLabel[F]],
    publishedMessageDuration: UnlabelledHistogram[F, PublishLabel[F]],
    consumedMessages: UnlabelledCounter[F, ConsumeLabel[F]],
    consumedMessageDuration: UnlabelledHistogram[F, ConsumeLabel[F]]
)

object EpimetheusAmqpMetrics {

  def register[F[_]: Sync](
      registry: PrometheusRegistry[F]
  ): F[EpimetheusAmqpMetrics[F]] =
    register[F](registry, defaultPrefix)

  /** Attempt to register relevant AmqpClient metrics with the given
    * PrometheusRegistry
    */
  def register[F[_]: Sync](
      registry: PrometheusRegistry[F],
      prefix: Name = defaultPrefix
  ): F[EpimetheusAmqpMetrics[F]] = {
    for {
      publishedMessages <- Counter.labelled(
        registry,
        prefix |+| sep |+| Name("published_messages"),
        "Published messages",
        Sized(Label("exchange"), Label("routing_key"), Label("outcome")),
        (labelPublishedMessage[F] _).tupled
      )
      publishedMessageDurations <- Histogram.labelled(
        registry,
        prefix |+| sep |+| Name("published_messages_duration_ms"),
        "Message publish operation duration in milliseconds.",
        Sized(Label("exchange"), Label("routing_key"), Label("outcome")),
        (labelPublishedMessage[F] _).tupled
      )

      consumedMessages <- Counter.labelled(
        registry,
        prefix |+| sep |+| Name("consumed_messages"),
        "Consumed messages",
        Sized(
          Label("exchange"),
          Label("routing_key"),
          Label("result"),
          Label("outcome")
        ),
        (labelConsumedMessage[F] _).tupled
      )

      consumedMessageDurations <- Histogram.labelled(
        registry,
        prefix |+| sep |+| Name("consumed_messages_duration_ms"),
        "Message consume handler duration in milliseconds.",
        Sized(
          Label("exchange"),
          Label("routing_key"),
          Label("result"),
          Label("outcome")
        ),
        (labelConsumedMessage[F] _).tupled
      )
    } yield EpimetheusAmqpMetrics[F](
      publishedMessages,
      publishedMessageDurations,
      consumedMessages,
      consumedMessageDurations
    )
  }

  val defaultPrefix = Name("bucky")
  private val sep = Name("_")

  private def labelPublishedMessage[F[_]](
      cmd: PublishCommand,
      outcome: Outcome[F, _, Unit]
  ) = {
    val outcomeString = outcome match {
      case Canceled()   => "canceled"
      case Succeeded(_) => "succeeded"
      case Errored(_)   => "errored"
    }
    Sized(
      cmd.exchange.value,
      cmd.routingKey.value,
      outcomeString
    )
  }

  private def labelConsumedMessage[F[_]](
      del: Delivery,
      action: Option[ConsumeAction],
      outcome: Outcome[F, _, ConsumeAction]
  ) = {
    def actionString = action match {
      case None                     => "error"
      case Some(Ack)                => "ack"
      case Some(DeadLetter)         => "dead-letter"
      case Some(RequeueImmediately) => "requeue-immediately"
    }

    val outcomeString = outcome match {
      case Canceled()   => "canceled"
      case Succeeded(_) => "succeeded"
      case Errored(_)   => "errored"
    }

    Sized(
      del.envelope.exchangeName.value,
      del.envelope.routingKey.value,
      actionString,
      outcomeString
    )
  }
}

object EpimetheusAmqpClient {

  def apply[F[_]: Sync](
      amqpClient: AmqpClient[F],
      registry: PrometheusRegistry[F]
  ): F[AmqpClient[F]] =
    apply[F](amqpClient, registry, EpimetheusAmqpMetrics.defaultPrefix)

  def apply[F[_]: Sync](
      amqpClient: AmqpClient[F],
      registry: PrometheusRegistry[F],
      prefix: Name
  ): F[AmqpClient[F]] =
    EpimetheusAmqpMetrics.register[F](registry, prefix).map { metrics =>
      apply[F](amqpClient, metrics)
    }

  def apply[F[_]: Clock](
      amqpClient: AmqpClient[F],
      amqpMetrics: EpimetheusAmqpMetrics[F]
  )(implicit M: MonadCancel[F, _]): AmqpClient[F] =
    new AmqpClient[F] {
      def declare(declarations: Declaration*): F[Unit] =
        amqpClient.declare(declarations)

      def declare(declarations: Iterable[Declaration]): F[Unit] =
        amqpClient.declare(declarations)

      def publisher(): Publisher[F, PublishCommand] = { cmd =>
        val publisher = amqpClient.publisher()

        M.guaranteeCase(publisher(cmd)) { outcome =>
          amqpMetrics.publishedMessages.label((cmd, outcome)).inc
        }
      }

      def registerConsumer(
          queueName: QueueName,
          handler: Handler[F, Delivery],
          exceptionalAction: ConsumeAction,
          prefetchCount: Int,
          shutdownTimeout: FiniteDuration,
          shutdownRetry: FiniteDuration
      ): Resource[F, Unit] = {
        amqpClient.registerConsumer(
          queueName,
          meteredHandler(handler),
          exceptionalAction,
          prefetchCount,
          shutdownTimeout,
          shutdownRetry
        )
      }

      def isConnectionOpen: F[Boolean] =
        amqpClient.isConnectionOpen

      private def meteredHandler(
          handler: Handler[F, Delivery]
      ): Handler[F, Delivery] = { (del: Delivery) =>
        Clock[F].monotonic.flatMap { start =>
          M.guaranteeCase(handler(del)) { outcome =>
            Clock[F].monotonic.flatMap { end =>
              outcome match {
                case Succeeded(fa) =>
                  fa.flatMap { action =>
                    handlerMetrics(del, Some(action), outcome, start, end)
                  }
                case _ =>
                  handlerMetrics(del, None, outcome, start, end)
              }
            }
          }
        }
      }

      private def handlerMetrics(
          del: Delivery,
          maybeAction: Option[ConsumeAction],
          outcome: Outcome[F, _, ConsumeAction],
          start: FiniteDuration,
          end: FiniteDuration
      ): F[Unit] = {
        val labels = (del, maybeAction, outcome)
        for {
          _ <- amqpMetrics.consumedMessages
            .label(labels)
            .inc
          _ <- amqpMetrics.consumedMessageDuration
            .label(labels)
            .observe((end - start).toMillis)
        } yield ()
      }

    }

}
