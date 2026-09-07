package net.ghoula.melian.router

/** Compile-time and runtime path template parser.
  *
  * Parses "/users/:userId/posts/:postId" into a list of literal and parameter segments.
  */
object PathTemplate {

  enum Segment {
    case Literal(value: String)
    case Param(name: String)
  }

  final case class ParsedPath(segments: List[Segment]) {

    def paramNames: List[String] = segments.collect { case Segment.Param(n) => n }

    def toPattern: String = segments match {
      case Nil => "/"
      case segs =>
        segs.map {
          case Segment.Literal(v) => v
          case Segment.Param(_) => ":param"
        }.mkString("/", "/", "")
    }
  }

  def parse(path: String): Either[String, ParsedPath] = path match {
    case p if !p.startsWith("/") => Left(s"Path must start with '/': $path")
    case "/" => Right(ParsedPath(Nil))
    case p =>
      p.stripPrefix("/")
        .split("/")
        .toList
        .foldLeft[Either[String, List[Segment]]](Right(Nil)) { (acc, part) =>
          acc.flatMap { segments =>
            part match {
              case s":$name" if name.nonEmpty => Right(segments :+ Segment.Param(name))
              case s":$_" => Left(s"Empty parameter name in path: $path")
              case "" => Left(s"Empty segment in path: $path")
              case literal => Right(segments :+ Segment.Literal(literal))
            }
          }
        }
        .map(ParsedPath(_))
  }
}
