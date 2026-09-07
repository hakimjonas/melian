package net.ghoula.melian.doctool

import java.nio.file.Paths

/** CLI for the scaladoc coverage gate. Modes:
  *   - `check <snapshotPath> <sourceDirs...>` — ratchet compare against the snapshot; exits 1 on
  *     regression.
  *   - `snapshot <snapshotPath> <sourceDirs...>` — rewrite the snapshot with current counts.
  *
  * Paths resolve against the working directory (the sbt launcher's directory, the repo root).
  */
object DocCoverageMain {

  def main(args: Array[String]): Unit =
    args.toList match {
      case mode :: snapshotPath :: dirs if dirs.nonEmpty && (mode == "check" || mode == "snapshot") =>
        val root = Paths.get("").toAbsolutePath
        DocCoverage.analyze(root, dirs.map(root.resolve)) match {
          case Left(message) =>
            println(message)
            sys.exit(1)
          case Right(analysis) =>
            val snapshot = root.resolve(snapshotPath)
            val result = DocCoverage.check(analysis.reports, DocCoverage.readSnapshot(snapshot))
            println(result.summary)
            mode match {
              case "snapshot" =>
                DocCoverage.writeSnapshot(snapshot, analysis.reports)
                println(s"snapshot written: ${snapshot.getFileName} (${analysis.reports.size} files)")
              case "check" if result.ok =>
                println("ratchet ok: coverage did not regress")
              case "check" =>
                printRegressions(result, analysis.violations)
                sys.exit(1)
            }
        }

      case _ =>
        println("usage: DocCoverageMain <check|snapshot> <snapshotPath> <sourceDirs...>")
        sys.exit(1)
    }

  private def printRegressions(
    result: DocCoverage.CheckResult,
    violations: Map[String, Vector[Violation]]
  ): Unit = {
    println("doc coverage regressed:")
    result.regressions.foreach(file => println(s"  $file"))
    val regressed = result.regressions.map(_.takeWhile(_ != ':'))
    regressed.foreach { file =>
      violations.get(file).foreach { vs =>
        vs.take(12).foreach { v =>
          println(s"    - ${v.member} (line ${v.line})")
        }
        if vs.length > 12 then println(s"    ... and ${vs.length - 12} more")
      }
    }
  }
}
