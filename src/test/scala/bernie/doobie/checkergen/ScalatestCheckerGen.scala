package bernie.doobie.checkergen

import org.scalatest.funsuite.AnyFunSuite
import doobie.scalatest.IOChecker
import cats.effect.IO
import doobie.util.transactor.Transactor
import bernie.doobie.checkergen.DoobieTestGen.{generateTests, given}
import doobie.implicits.*

class ScalatestCheckerGen extends AnyFunSuite with IOChecker {

  override def transactor: Transactor[IO] = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver",
    url = "jdbc:postgresql://localhost:5432/docker?searchpath=public",
    user = "docker",
    password = "docker",
    logHandler = None,
  )

  generateTests(Queries).foreach { testCase =>
    test(testCase.testName) {
      check(testCase.query.value)(using testCase.query.analyzable)
    }
  }
}
