package net.ghoula.melian.router

import scala.deriving.Mirror

import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.ast.xml.XmlNode
import net.ghoula.sarati.ast.yaml.YamlValue
import net.ghoula.sarati.codec.Decoder

/** The three decoders a [[net.ghoula.melian.Coded]] body needs, one per supported media type.
  *
  * Bundling them lets a case class provide a single `given` via [[CodedDecoder.derived]] rather
  * than three separate `Decoder` instances. The macro still accepts the individual decoders as a
  * fallback ([[CodedDecoder.fromDecoders]]).
  */
trait CodedDecoder[A] {
  def json: Decoder[JsonValue, A]
  def xml: Decoder[XmlNode, A]
  def yaml: Decoder[YamlValue, A]
}

object CodedDecoder {

  private final class Bundle[A](
    val json: Decoder[JsonValue, A],
    val xml: Decoder[XmlNode, A],
    val yaml: Decoder[YamlValue, A]
  ) extends CodedDecoder[A]

  /** Derives all three decoders from a case class via Sarati's `Decoder.derived`. */
  inline def derived[A](using Mirror.ProductOf[A]): CodedDecoder[A] =
    fromDecoders(Decoder.derived[JsonValue, A], Decoder.derived[XmlNode, A], Decoder.derived[YamlValue, A])

  /** Wraps three explicit decoders into a bundle. */
  def fromDecoders[A](
    jsonDecoder: Decoder[JsonValue, A],
    xmlDecoder: Decoder[XmlNode, A],
    yamlDecoder: Decoder[YamlValue, A]
  ): CodedDecoder[A] =
    new Bundle(jsonDecoder, xmlDecoder, yamlDecoder)
}
