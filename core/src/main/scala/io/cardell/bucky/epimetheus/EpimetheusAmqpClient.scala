package io.cardell.bucky.epimetheus

import cats.MonadError
import cats.effect.Resource
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
import io.chrisdavenport.epimetheus.CollectorRegistry
import io.chrisdavenport.epimetheus.Counter
import io.chrisdavenport.epimetheus.Counter.UnlabelledCounter
import io.chrisdavenport.epimetheus.Label
import io.chrisdavenport.epimetheus.Name
import scala.concurrent.duration.FiniteDuration
import shapeless.Sized

case class EpimetheusAmqpMetrics[F[_]](
    publishedMessages: UnlabelledCounter[F, PublishLabel[F]],
    consumedMessages: UnlabelledCounter[F, ConsumeLabel[F]]
)

object EpimetheusAmqpMetrics {

  def register[F[_]: Sync](
      registry: CollectorRegistry[F]
  ): F[EpimetheusAmqpMetrics[F]] =
    register[F](registry, defaultPrefix)

  /** Attempt to register relevant AmqpClient metrics with the given
    * CollectorRegistry
    */
  def register[F[_]: Sync](
      registry: CollectorRegistry[F],
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
      consumedMessages <- Counter.labelled(
        registry,
        prefix |+| sep |+| Name("consumed_messages"),
        "Consumed messages",
        Sized(
          Label("exchange"),
          Label("routing_key"),
          Label("result")
        ),
        (labelConsumedMessage[F] _).tupled
      )
    } yield EpimetheusAmqpMetrics[F](
      publishedMessages,
      consumedMessages
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
      action: Option[ConsumeAction]
  ) = {
    def actionString = action match {
      case None                     => "error"
      case Some(Ack)                => "ack"
      case Some(DeadLetter)         => "dead-letter"
      case Some(RequeueImmediately) => "requeue-immediately"
    }

    Sized(
      del.envelope.exchangeName.value,
      del.envelope.routingKey.value,
      actionString
    )
  }
}

object EpimetheusAmqpClient {

  def apply[F[_]: Sync](
      amqpClient: AmqpClient[F],
      registry: CollectorRegistry[F]
  ): F[AmqpClient[F]] =
    apply[F](amqpClient, registry, EpimetheusAmqpMetrics.defaultPrefix)

  def apply[F[_]: Sync](
      amqpClient: AmqpClient[F],
      registry: CollectorRegistry[F],
      prefix: Name
  ): F[AmqpClient[F]] =
    EpimetheusAmqpMetrics.register[F](registry, prefix).map { metrics =>
      apply[F](amqpClient, metrics)
    }

  def apply[F[_]](
      amqpClient: AmqpClient[F],
      amqpMetrics: EpimetheusAmqpMetrics[F]
  )(implicit M: MonadCancel[F, _], E: MonadError[F, _]): AmqpClient[F] =
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
        val meteredHandler = { (del: Delivery) =>
          E.attemptTap(handler(del)) { maybeAction =>
            amqpMetrics.consumedMessages
              .label((del, maybeAction.toOption))
              .inc
          }
        }

        amqpClient.registerConsumer(
          queueName,
          meteredHandler,
          exceptionalAction,
          prefetchCount,
          shutdownTimeout,
          shutdownRetry
        )
      }

      def isConnectionOpen: F[Boolean] =
        amqpClient.isConnectionOpen

    }
}
