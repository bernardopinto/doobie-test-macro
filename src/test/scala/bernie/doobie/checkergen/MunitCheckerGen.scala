package bernie.doobie.checkergen

import _root_.munit._
import doobie.util.transactor.Transactor
import cats.effect.IO
import bernie.doobie.checkergen.DoobieTestGen.{generateTests, given}
import doobie.util.testing.Analyzable

class MunitCheckerGen extends FunSuite with doobie.munit.IOChecker {

  override val colors = doobie.util.Colors.None // just for docs

  val transactor = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver",
    url = "jdbc:postgresql://localhost:5432/docker?searchpath=public",
    user = "docker",
    password = "docker",
    logHandler = None,
  )

  given Tuple2[String, Int] = ("test", 1)

  generateTests(Queries).foreach { testCase =>
    test(testCase.testName) {
      given Analyzable[testCase.query.A] = testCase.query.analyzable
      check(testCase.query.value)
    }
  }

}
