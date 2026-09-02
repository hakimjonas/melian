package net.ghoula.melian.router

import parser.core.ParseError
import parser.core.Result as RumilResult
import parsers.json.parseJson
import parsers.xml.parseXml
import parsers.yaml.parseYaml

import net.ghoula.eru.Eru
import net.ghoula.eru.http.{Body, Charset, DecodeError, Headers, MediaType}
import net.ghoula.melian.{FieldError, RequestError}
import net.ghoula.sarati.Result as SaratiResult
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.ast.xml.XmlNode
import net.ghoula.sarati.ast.yaml.YamlValue
import net.ghoula.sarati.codec.Decoder
import net.ghoula.valar.{ValidationResult, Validator}

/** Content-Type dispatch for [[net.ghoula.melian.Coded]] request bodies.
  *
  * A `Coded[A]` parameter accepts `application/json`, `application/xml`, and `application/yaml`.
  * The Content-Type header selects the parse + decode path; the decoded value is then run through
  * Valar validation, exactly like [[net.ghoula.melian.Json]].
  */
object CodedBody {

  private val codedMediaTypes: List[MediaType] =
    List(MediaType.applicationJson, MediaType.applicationXml, MediaType("application", "yaml"))

  def decode[A](headers: Headers, body: Body)(using
    coded: CodedDecoder[A],
    validator: Validator[A]
  ): Eru[RequestError, A] = {
    val raw = headers.contentTypeRaw.getOrElse("").toLowerCase

    if body.isEmpty then Eru.fail(RequestError.BodyMissing)
    else if raw.contains("xml") then decodeParsed[A, XmlNode](body)(parseXmlRoot, xmlDepth)(coded.xml, validator)
    else if raw.contains("yaml") then decodeParsed[A, YamlValue](body)(parseYamlRoot, yamlDepth)(coded.yaml, validator)
    else if raw.contains("json") || raw.isEmpty then
      decodeParsed[A, JsonValue](body)(parseJsonText, jsonDepth)(coded.json, validator)
    else
      Eru.fail(
        RequestError.UnsupportedMediaType(
          codedMediaTypes,
          headers.contentTypeRaw.flatMap(parseMediaType)
        )
      )
  }

  private def parseJsonText(t: String): RumilResult[ParseError, JsonValue] = parseJson(t)

  private def parseXmlRoot(t: String): RumilResult[ParseError, XmlNode] =
    parseXml(t) match {
      case RumilResult.Success(doc, consumed) => RumilResult.Success(doc.root, consumed)
      case RumilResult.Partial(doc, errs, consumed) => RumilResult.Partial(doc.root, errs, consumed)
      case RumilResult.Failure(errs, furthest) => RumilResult.Failure(errs, furthest)
    }

  private def parseYamlRoot(t: String): RumilResult[ParseError, YamlValue] =
    parseYaml(t) match {
      case RumilResult.Success(doc, consumed) => RumilResult.Success(doc.root, consumed)
      case RumilResult.Partial(doc, errs, consumed) => RumilResult.Partial(doc.root, errs, consumed)
      case RumilResult.Failure(errs, furthest) => RumilResult.Failure(errs, furthest)
    }

  private def decodeParsed[A, AST](
    body: Body
  )(
    parse: String => RumilResult[ParseError, AST],
    depthOf: AST => Int
  )(dec: Decoder[AST, A], validator: Validator[A]): Eru[RequestError, A] =
    readText(body).mapError(e => RequestError.DecodeFailed(List(e.message))).flatMap { t =>
      parse(t) match {
        case RumilResult.Success(ast, _) => decodeGuarded(ast, dec, validator, depthOf)
        case RumilResult.Partial(ast, _, _) => decodeGuarded(ast, dec, validator, depthOf)
        case RumilResult.Failure(errs, _) => Eru.fail(RequestError.ParseFailed(errs.map(_.toString)))
      }
    }

  private def decodeGuarded[A, AST](
    ast: AST,
    dec: Decoder[AST, A],
    validator: Validator[A],
    depthOf: AST => Int
  ): Eru[RequestError, A] =
    if depthOf(ast) > SaratiBridge.MaxJsonDepth then
      Eru.fail(RequestError.DecodeFailed(List(s"Body nesting depth exceeds limit of ${SaratiBridge.MaxJsonDepth}")))
    else decodeAndValidate(ast, dec, validator)

  private def decodeAndValidate[A, AST](ast: AST, dec: Decoder[AST, A], validator: Validator[A]): Eru[RequestError, A] =
    dec.decode(ast) match {
      case SaratiResult.Success(value, _) => validate(value, validator)
      case SaratiResult.Partial(value, _, _) => validate(value, validator)
      case SaratiResult.Failure(errs, _) => Eru.fail(RequestError.DecodeFailed(errs.map(_.toString)))
    }

  private def validate[A](value: A, validator: Validator[A]): Eru[RequestError, A] =
    validator.validate(value) match {
      case ValidationResult.Valid(v) => Eru.succeed(v)
      case ValidationResult.Invalid(errs) =>
        Eru.fail(RequestError.ValidationFailed(errs.map(e => FieldError(e.fieldPath.mkString("."), e.message, e.code))))
    }

  // --- Depth guards (security: prevents stack overflow from nested payloads) ---

  private def jsonDepth(value: JsonValue): Int = value match {
    case JsonValue.Array(elements) => if elements.isEmpty then 1 else 1 + elements.iterator.map(jsonDepth).max
    case JsonValue.Object(fields) => if fields.isEmpty then 1 else 1 + fields.valuesIterator.map(jsonDepth).max
    case _ => 0
  }

  private def xmlDepth(node: XmlNode): Int = node match {
    case XmlNode.Element(_, _, children) => if children.isEmpty then 1 else 1 + children.iterator.map(xmlDepth).max
    case _ => 0
  }

  private def yamlDepth(value: YamlValue): Int = value match {
    case YamlValue.Sequence(elements) => if elements.isEmpty then 1 else 1 + elements.iterator.map(yamlDepth).max
    case YamlValue.Mapping(pairs) => if pairs.isEmpty then 1 else 1 + pairs.valuesIterator.map(yamlDepth).max
    case _ => 0
  }

  private def parseMediaType(raw: String): Option[MediaType] =
    MediaType.parse(raw).attempt.unsafeRunSync() match {
      case net.ghoula.eru.Result.Success(mt) => Some(mt)
      case net.ghoula.eru.Result.Failure(_) => None
    }

  /** Reads a body's raw text without routing through `BodyDecoder[String]`, so a JSON body is not
    * decoded and re-encoded before the Coded dispatch parses the text. Mirrors the plain-text
    * semantics of `BodyDecoder[String]`.
    */
  private[router] def readText(body: Body): Eru[DecodeError, String] = body match {
    case _: Body.Empty.type => Eru.succeed("")
    case Body.Text(value, _, _) => Eru.succeed(value)
    case Body.Binary(bytes, _) => Eru.succeed(bytes.asString(Charset.UTF8))
    case stream: Body.Stream =>
      stream.asString().mapError(e => DecodeError(s"Failed to decode stream to string: ${e.message}", None))
  }
}
