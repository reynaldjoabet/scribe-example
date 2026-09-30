package app.domain

import io.circe.{Codec, Decoder, Encoder}

enum Currency derives CanEqual {
  case USD, EUR, GBP
}

object Currency {
  given Encoder[Currency] = Encoder.encodeString.contramap(_.toString)
  given Decoder[Currency] =
    Decoder.decodeString.emap(s => Currency.values.find(_.toString == s).toRight(s"Unknown currency: $s"))
}

/** Amount in minor units (cents) to avoid floating-point rounding. */
final case class Money(cents: Long, currency: Currency) derives Codec.AsObject {
  def *(quantity: Int): Money = copy(cents = cents * quantity)

  def +(other: Money): Money = {
    require(currency == other.currency, s"Currency mismatch: $currency vs ${other.currency}")
    copy(cents = cents + other.cents)
  }

  def isPositive: Boolean = cents > 0L
}

object Money {
  def zero(currency: Currency): Money = Money(0L, currency)
}
