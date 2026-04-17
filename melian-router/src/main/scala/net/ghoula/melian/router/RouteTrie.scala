package net.ghoula.melian.router

import net.ghoula.eru.http.Method

/** Segment-by-segment trie for O(m) path dispatch where m = number of path segments. */
object RouteTrie {

  enum LookupResult derives CanEqual {
    case NotFound
    case MethodNotAllowed(allowed: Set[Method])
    case Matched(entry: RouteEntry, pathParams: Map[String, String])
  }

  private[router] final case class Node(
    literals: Map[String, Node],
    param: Option[(String, Node)],
    handlers: Map[Method, RouteEntry]
  )

  private[router] val emptyNode: Node = Node(Map.empty, None, Map.empty)

  def build(routes: Vector[RouteEntry]): Either[String, RouteTrie] = {
    routes
      .foldLeft[Either[String, Node]](Right(emptyNode)) { (acc, entry) =>
        for {
          node <- acc
          parsed <- PathTemplate.parse(entry.pathTemplate)
        } yield insertRoute(node, parsed.segments, entry)
      }
      .map(root => new RouteTrie(root))
  }

  private def insertRoute(node: Node, segments: List[PathTemplate.Segment], entry: RouteEntry): Node = {
    segments match {
      case Nil =>
        val updated = node.handlers.updated(entry.method, entry)
        node.copy(handlers = updated)

      case PathTemplate.Segment.Literal(value) :: rest =>
        val child = node.literals.getOrElse(value, emptyNode)
        val updatedChild = insertRoute(child, rest, entry)
        node.copy(literals = node.literals.updated(value, updatedChild))

      case PathTemplate.Segment.Param(name) :: rest =>
        val (_, child) = node.param.getOrElse((name, emptyNode))
        val updatedChild = insertRoute(child, rest, entry)
        node.copy(param = Some((name, updatedChild)))
    }
  }
}

final class RouteTrie private[router] (private val root: RouteTrie.Node) {

  def lookup(path: String, method: Method): RouteTrie.LookupResult = {
    val segments = splitPath(path)
    walk(root, segments, Map.empty) match {
      case None => RouteTrie.LookupResult.NotFound
      case Some((node, params)) =>
        node.handlers.get(method) match {
          case Some(entry) => RouteTrie.LookupResult.Matched(entry, params)
          case None =>
            if node.handlers.isEmpty then RouteTrie.LookupResult.NotFound
            else RouteTrie.LookupResult.MethodNotAllowed(node.handlers.keySet)
        }
    }
  }

  private def walk(
    node: RouteTrie.Node,
    segments: List[String],
    params: Map[String, String]
  ): Option[(RouteTrie.Node, Map[String, String])] = {
    segments match {
      case Nil => Some((node, params))
      case seg :: rest =>
        node.literals.get(seg) match {
          case Some(child) => walk(child, rest, params)
          case None =>
            node.param match {
              case Some((name, child)) => walk(child, rest, params.updated(name, seg))
              case None => None
            }
        }
    }
  }

  private def splitPath(path: String): List[String] = {
    val stripped = if path.startsWith("/") then path.substring(1) else path
    if stripped.isEmpty then Nil
    else stripped.split("/").toList
  }
}
