package io.cardell.bucky

import cats.effect.kernel.Outcome
import com.itv.bucky.consume.ConsumeAction
import com.itv.bucky.consume.Delivery
import com.itv.bucky.publish.PublishCommand

package object epimetheus {

  type PublishLabel[F[_]] = (PublishCommand, Outcome[F, _, Unit])

  type ConsumeLabel[F[_]] = (Delivery, Option[ConsumeAction])

}
