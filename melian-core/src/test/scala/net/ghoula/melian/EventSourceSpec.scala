package net.ghoula.melian

import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger

import net.ghoula.eru.Eru

class EventSourceSpec extends FunSuite {

  test("fromList emits every event in order, then ends") {
    val source = EventSource.fromList(List(1, 2, 3))

    val pulled = List(source.pull, source.pull, source.pull, source.pull).map(_.unsafeRunSync())
    assertEquals(pulled, List(Some(1), Some(2), Some(3), None))
  }

  test("empty ends immediately") {
    assertEquals(EventSource.empty[Int].pull.unsafeRunSync(), None)
  }

  test("fromPull re-evaluates the effect on every pull") {
    val counter = new AtomicInteger(0)
    val source = EventSource.fromPull {
      val n = counter.incrementAndGet()
      if n <= 3 then Eru.succeed(Some(n)) else Eru.succeed(None)
    }

    val pulled = List(source.pull, source.pull, source.pull, source.pull).map(_.unsafeRunSync())
    assertEquals(pulled, List(Some(1), Some(2), Some(3), None))
  }

  test("map transforms events") {
    val source = EventSource.fromList(List("a", "bb")).map(_.length)
    assertEquals(List(source.pull, source.pull, source.pull).map(_.unsafeRunSync()), List(Some(1), Some(2), None))
  }

  test("filter skips non-matching events without ending the stream") {
    val source = EventSource.fromList(List(1, 2, 3, 4)).filter(_ % 2 == 0)
    assertEquals(List(source.pull, source.pull, source.pull).map(_.unsafeRunSync()), List(Some(2), Some(4), None))
  }

  test("collect transforms and skips in one pass") {
    val source = EventSource.fromList(List("x1", "skip", "x2")).collect {
      case s if s.startsWith("x") => Some(s.tail.toInt)
      case _ => None
    }
    assertEquals(List(source.pull, source.pull, source.pull).map(_.unsafeRunSync()), List(Some(1), Some(2), None))
  }

  test("++ concatenates before ending") {
    val source = EventSource.fromList(List(1, 2)) ++ EventSource.fromList(List(3))
    assertEquals(
      List(source.pull, source.pull, source.pull, source.pull).map(_.unsafeRunSync()),
      List(Some(1), Some(2), Some(3), None)
    )
  }

  test("a pull failure is surfaced on the error channel") {
    val source = EventSource.fromPull(Eru.fail(new RuntimeException("broker down")))
    source.pull.attempt.unsafeRunSync() match {
      case net.ghoula.eru.Result.Failure(error) =>
        assertEquals(error.getMessage, "broker down")
      case other => fail(s"Expected the source failure, got: $other")
    }
  }
}
