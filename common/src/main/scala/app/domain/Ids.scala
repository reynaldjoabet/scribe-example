package app.domain

import io.circe.{Decoder, Encoder}

opaque type OrderId = String
object OrderId {
  def apply(value: String): OrderId = value
  extension (id: OrderId) def value: String = id
  given Encoder[OrderId] = Encoder.encodeString
  given Decoder[OrderId] = Decoder.decodeString
}

opaque type CustomerId = String
object CustomerId {
  def apply(value: String): CustomerId = value
  extension (id: CustomerId) def value: String = id
  given Encoder[CustomerId] = Encoder.encodeString
  given Decoder[CustomerId] = Decoder.decodeString
}

opaque type Sku = String
object Sku {
  def apply(value: String): Sku = value
  extension (sku: Sku) def value: String = sku
  given Encoder[Sku] = Encoder.encodeString
  given Decoder[Sku] = Decoder.decodeString
}

opaque type TransactionId = String
object TransactionId {
  def apply(value: String): TransactionId = value
  extension (id: TransactionId) def value: String = id
  given Encoder[TransactionId] = Encoder.encodeString
  given Decoder[TransactionId] = Decoder.decodeString
}
