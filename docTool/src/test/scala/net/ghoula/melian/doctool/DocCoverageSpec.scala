package net.ghoula.melian.doctool

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** The coverage gate's contract: what counts as a member, what attaches a doc, and what the ratchet
  * lets through.
  */
class DocCoverageSpec extends AnyFlatSpec with Matchers {

  private val tempDir: Path = Files.createTempDirectory("doctool-spec")

  private def analyzeSrc(text: String): (FileReport, Vector[Violation]) = {
    val file = tempDir.resolve(s"Fixture${Integer.toHexString(text.hashCode)}.scala")
    Files.writeString(file, text)
    DocCoverage.analyzeFile(file.getFileName.toString, file) match {
      case Right(result) => result
      case Left(message) => fail(message)
    }
  }

  private def violationNames(vs: Vector[Violation]): Vector[String] = vs.map(_.member)

  "the member count" should "count documented and undocumented public defs" in {
    val (report, violations) = analyzeSrc(
      """/** documented. */
        |def good: Int = 1
        |
        |def bad: Int = 2
        |""".stripMargin
    )
    report shouldBe FileReport(1, 2)
    violationNames(violations) shouldBe Vector("bad")
  }

  it should "count classes, objects, traits, enums, cases, types and vals" in {
    val (report, violations) = analyzeSrc(
      """class C
        |object O
        |trait T
        |enum E {
        |  case A
        |  /** documented case. */
        |  case B
        |}
        |type Alias = Int
        |val value = 1
        |""".stripMargin
    )
    report.total shouldBe 8
    violationNames(violations).toSet shouldBe Set("C", "O", "T", "E", "A", "Alias", "value")
  }

  it should "count extension methods" in {
    val (report, violations) = analyzeSrc(
      """extension (i: Int) {
        |  /** documented. */
        |  def good: Int = i
        |  def bad: Int = i
        |}
        |""".stripMargin
    )
    report shouldBe FileReport(1, 2)
    violationNames(violations) shouldBe Vector("bad")
  }

  it should "not descend into def or val bodies" in {
    val (report, _) = analyzeSrc(
      """/** documented. */
        |def outer: Int = {
        |  def nestedHelper = 1
        |  val nestedValue = 2
        |  3
        |}
        |""".stripMargin
    )
    report shouldBe FileReport(1, 1)
  }

  it should "not count given instances or exports" in {
    val (report, violations) = analyzeSrc(
      """given intOrdering: Ordering[Int] = Ordering.Int
        |
        |class C {
        |  /** documented. */
        |  def m = 1
        |}
        |""".stripMargin
    )
    report shouldBe FileReport(1, 2)
    violationNames(violations) shouldBe Vector("C")
  }

  it should "skip members of private and qualified-private aggregates" in {
    val (report, _) = analyzeSrc(
      """private[doctool] object Internal {
        |  def helper = 1
        |}
        |
        |class Public {
        |  private case class Hidden(a: Int) {
        |    def alsoHidden = 2
        |  }
        |
        |  /** documented. */
        |  def m = 3
        |}
        |""".stripMargin
    )
    report shouldBe FileReport(1, 2)
  }

  it should "skip private, protected, override and implicit members" in {
    val (report, violations) = analyzeSrc(
      """class C {
        |  private def a = 1
        |  private[doctool] def b = 2
        |  protected def c = 3
        |  override def toString: String = "C"
        |  implicit def d(i: Int): Int = i
        |  /** documented. */
        |  def e = 4
        |}
        |""".stripMargin
    )
    report shouldBe FileReport(1, 2)
    violationNames(violations) shouldBe Vector("C")
  }

  "doc attachment" should "attach across annotations but not across code" in {
    val (report, violations) = analyzeSrc(
      """/** documented. */
        |@java.lang.Deprecated
        |def annotated: Int = 1
        |
        |/* block comment is not scaladoc */
        |def blockCommented: Int = 2
        |
        |// line comment is not scaladoc
        |def lineCommented: Int = 3
        |""".stripMargin
    )
    report shouldBe FileReport(1, 3)
    violationNames(violations).toSet shouldBe Set("blockCommented", "lineCommented")
  }

  it should "not let a previous member's doc leak through a closing brace" in {
    val (report, violations) = analyzeSrc(
      """class First {
        |  /** first's doc. */
        |  def m = 1
        |}
        |
        |class Second {
        |  def n = 2
        |}
        |""".stripMargin
    )
    violationNames(violations).toSet shouldBe Set("First", "Second", "n")
    report.total shouldBe 4
  }

  it should "require non-empty doc content" in {
    val (report, violations) = analyzeSrc(
      """/**/
        |def emptyDoc: Int = 1
        |
        |/**   */
        |def blankDoc: Int = 2
        |""".stripMargin
    )
    violationNames(violations).toSet shouldBe Set("emptyDoc", "blankDoc")
    report.total shouldBe 2
  }

  "the ratchet" should "reject new undocumented members" in {
    val baseline = Map("f.scala" -> FileReport(2, 2))
    val current = Map("f.scala" -> FileReport(2, 3))
    val result = DocCoverage.check(current, baseline)
    result.ok shouldBe false
    result.regressions shouldBe Vector("f.scala: 1 undocumented (baseline 0)")
  }

  it should "accept documented additions and improvements" in {
    val baseline = Map("f.scala" -> FileReport(2, 4))
    val improved = Map("f.scala" -> FileReport(4, 4))
    val added = Map("f.scala" -> FileReport(4, 5))
    DocCoverage.check(improved, baseline).ok shouldBe true
    DocCoverage.check(added, baseline).ok shouldBe true
  }

  it should "accept deleting documented members but reject trading docs for gaps" in {
    val baseline = Map("f.scala" -> FileReport(4, 4))
    val deleted = Map("f.scala" -> FileReport(3, 3))
    val traded = Map("f.scala" -> FileReport(3, 4))
    DocCoverage.check(deleted, baseline).ok shouldBe true
    DocCoverage.check(traded, baseline).ok shouldBe false
  }

  it should "fail any undocumented member in a file absent from the baseline" in {
    val result = DocCoverage.check(Map("new.scala" -> FileReport(3, 5)), Map.empty)
    result.ok shouldBe false
  }

  "the snapshot" should "round-trip" in {
    val reports = Map(
      "a.scala" -> FileReport(3, 5),
      "b/c.scala" -> FileReport(0, 1)
    )
    val path = Files.createTempFile("snapshot", ".json")
    DocCoverage.writeSnapshot(path, reports)
    DocCoverage.readSnapshot(path) shouldBe reports
  }
}
