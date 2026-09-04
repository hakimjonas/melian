package net.ghoula.melian.doctool

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.meta.*
import scala.meta.dialects.Scala3
import scala.meta.tokens.{Token, Tokens}

/** Per-file documentation counts used by the ratchet. */
final case class FileReport(documented: Int, total: Int) derives CanEqual {
  def undocumented: Int = total - documented
}

/** One public member missing a scaladoc, with location for the failure report. */
final case class Violation(file: String, member: String, line: Int) derives CanEqual

/** One counted member: its display name, modifiers, effective start offset, and source line. */
private[doctool] final case class Member(
  name: String,
  mods: List[Mod],
  start: Int,
  line: Int
)

/** The pana-style scaladoc coverage gate: enumerates public members in main sources, verifies a
  * non-empty scaladoc is attached to each, and ratchet-compares per-file counts against the
  * checked-in snapshot. Coverage may only improve: documenting members passes, adding undocumented
  * members or losing docs fails.
  */
object DocCoverage {

  final case class Analysis(
    reports: Map[String, FileReport],
    violations: Map[String, Vector[Violation]]
  )

  final case class CheckResult(ok: Boolean, regressions: Vector[String], summary: String)

  /** Parse and count every source tree under `dirs` (relative keys computed against `root`). */
  def analyze(root: Path, dirs: Seq[Path]): Either[String, Analysis] = {
    val files: Vector[(Path, String)] = dirs.toVector.flatMap { dir =>
      if Files.exists(dir) then {
        Files
          .walk(dir)
          .filter((p: Path) => p.toString.endsWith(".scala"))
          .toList
          .asScala
          .toVector
          .map(p => p -> root.relativize(p).toString)
      } else Vector.empty
    }
    val parsed = files.map { (path, key) =>
      analyzeFile(key, path).map { case (report, violations) =>
        key -> (report, violations)
      }
    }
    val failures = parsed.collect { case Left(message) => message }
    if failures.nonEmpty then {
      Left(failures.mkString("\n"))
    } else {
      val perFile = parsed.collect { case Right(entry) => entry }
      Right(
        Analysis(
          perFile.map((k, v) => k -> v._1).toMap,
          perFile.collect { case (k, (_, vs)) if vs.nonEmpty => k -> vs }.toMap
        )
      )
    }
  }

  def analyzeFile(key: String, path: Path): Either[String, (FileReport, Vector[Violation])] = {
    val text = Files.readString(path)
    Input.VirtualFile(key, text).parse[Source] match {
      case Parsed.Success(tree) =>
        val members = membersOf(tree).filterNot(m => excluded(m.mods))
        val attached = docAttached(members, tree.tokens)
        val violations = members.zipWithIndex.collect {
          case (m, idx) if !attached(idx) =>
            Violation(key, m.name, m.line)
        }.toVector
        Right((FileReport(members.length - violations.length, members.length), violations))
      case Parsed.Error(pos, message, _) =>
        Left(s"$key: parse failed at line ${pos.startLine + 1}: $message")
    }
  }

  private def templateStats(templ: Template): Seq[Stat] = templ match {
    case Template.After_4_9_9(_, _, body, _) => body.stats
  }

  private def mkMember(t: Tree, name: String, mods: List[Mod]): Member =
    Member(
      name,
      mods,
      (mods.map(_.pos.start) :+ t.pos.start).min,
      t.pos.startLine + 1
    )

  private def patName(pats: Seq[Pat]): String =
    pats.headOption match {
      case Some(Pat.Var(name)) => name.value
      case Some(other) => other.syntax.take(40)
      case None => "<member>"
    }

  private def excluded(mods: List[Mod]): Boolean = mods.exists {
    case _: Mod.Private => true
    case _: Mod.Protected => true
    case _: Mod.Override => true
    case _: Mod.Implicit => true
    case _ => false
  }

  /** Members needing docs, and the scopes to descend into: package trees, template bodies, and
    * extension groups. Def and Val bodies are never descended into, so nested helpers stay out of
    * the count. Members of private/protected aggregates are not user-facing API and are skipped
    * along with the aggregate itself. Given instances and exports are not members.
    */
  private def membersOf(t: Tree): Vector[Member] = t match {
    case Source(stats) => stats.flatMap(membersOf).toVector
    case Pkg.After_4_9_9(_, stats) => stats.flatMap(membersOf).toVector
    case t: Defn.Class => if excluded(t.mods) then Vector.empty else memberWithMembers(t, t.name.value, t.mods)
    case t: Defn.Trait => if excluded(t.mods) then Vector.empty else memberWithMembers(t, t.name.value, t.mods)
    case t: Defn.Object => if excluded(t.mods) then Vector.empty else memberWithMembers(t, t.name.value, t.mods)
    case t: Defn.Enum => if excluded(t.mods) then Vector.empty else memberWithMembers(t, t.name.value, t.mods)
    case Defn.ExtensionGroup.After_4_6_0(_, Term.Block(stats)) => stats.flatMap(membersOf).toVector
    case Defn.ExtensionGroup.After_4_6_0(_, body: Tree) => membersOf(body)
    case m: Defn.EnumCase => Vector(mkMember(m, m.name.value, m.mods))
    case m: Defn.Type => Vector(mkMember(m, m.name.value, m.mods))
    case m: Decl.Type => Vector(mkMember(m, m.name.value, m.mods))
    case m: Defn.Def => Vector(mkMember(m, m.name.value, m.mods))
    case m: Decl.Def => Vector(mkMember(m, m.name.value, m.mods))
    case m: Defn.Val => Vector(mkMember(m, patName(m.pats), m.mods))
    case m: Decl.Val => Vector(mkMember(m, patName(m.pats), m.mods))
    case m: Defn.Var => Vector(mkMember(m, patName(m.pats), m.mods))
    case m: Decl.Var => Vector(mkMember(m, patName(m.pats), m.mods))
    case _ => Vector.empty
  }

  private def memberWithMembers(t: Tree, name: String, mods: List[Mod]): Vector[Member] = {
    val stats = t match {
      case c: Defn.Class => c.templ
      case tr: Defn.Trait => tr.templ
      case o: Defn.Object => o.templ
      case e: Defn.Enum => e.templ
    }
    mkMember(t, name, mods) +: templateStats(stats).flatMap(membersOf).toVector
  }

  /** Single forward pass over the token stream: a scaladoc comment counts as the doc of the member
    * whose effective start is the next non-whitespace token position; any intervening code token
    * detaches it.
    */
  private def docAttached(members: Vector[Member], tokens: Tokens): Vector[Boolean] = {
    val starts = members.map(_.start).toSet
    val documented = tokens
      .foldLeft((Option.empty[Token.Comment], Set.empty[Int])) { case ((lastDoc, acc), token) =>
        token match {
          case c: Token.Comment => (Some(c), acc)
          case ws if ws.syntax.forall(_.isWhitespace) => (lastDoc, acc)
          case other =>
            val at = other.pos.start
            val newlyAttached = starts.contains(at) && lastDoc.exists(isScaladoc)
            (None, if newlyAttached then acc + at else acc)
        }
      }
      ._2
    members.map(m => documented.contains(m.start))
  }

  private def isScaladoc(c: Token.Comment): Boolean = {
    val text = c.syntax
    if text.startsWith("/**") && text.endsWith("*/") && text.length >= 5 then {
      text.substring(3, text.length - 2).exists(!_.isWhitespace)
    } else {
      false
    }
  }

  /** Ratchet: per file, the undocumented count may not grow. Files absent from the baseline fail on
    * any undocumented member; files removed from the sources are ignored.
    */
  def check(current: Map[String, FileReport], baseline: Map[String, FileReport]): CheckResult = {
    val regressions = current.toVector.sortBy(_._1).flatMap { (file, report) =>
      val old = baseline.getOrElse(file, FileReport(0, 0))
      if report.undocumented > old.undocumented
      then Some(s"$file: ${report.undocumented} undocumented (baseline ${old.undocumented})")
      else None
    }
    val total = current.values.foldLeft(0)((a, r) => a + r.total)
    val documented = current.values.foldLeft(0)((a, r) => a + r.documented)
    val pct = if total == 0 then 100.0 else documented * 100.0 / total
    val summary = f"doc coverage: $documented/$total documented ($pct%.1f%%)"
    CheckResult(regressions.isEmpty, regressions, summary)
  }

  /** Snapshot serialization: sorted, stable, hand-parsed — no JSON dependency. */
  def writeSnapshot(path: Path, reports: Map[String, FileReport]): Unit = {
    val lines = reports.toVector.sortBy(_._1).map { (file, r) =>
      s"""  "$file": {"documented": ${r.documented}, "total": ${r.total}}"""
    }
    Files.writeString(path, s"{\n${lines.mkString(",\n")}\n}\n"): Unit
  }

  def readSnapshot(path: Path): Map[String, FileReport] = {
    if !Files.exists(path) then {
      Map.empty
    } else {
      val pattern = java.util.regex.Pattern.compile(
        "^\\s*\"(.+)\": \\{\"documented\": (\\d+), \"total\": (\\d+)\\},?$"
      )
      Files
        .readAllLines(path)
        .asScala
        .toVector
        .flatMap { line =>
          val m = pattern.matcher(line)
          if m.matches() then Some(m.group(1) -> FileReport(m.group(2).toInt, m.group(3).toInt))
          else None
        }
        .toMap
    }
  }
}
