package bernie.doobie.checkergen

import org.specs2.mutable.Specification
import doobie.util.transactor.Transactor
import cats.effect.IO
import bernie.doobie.checkergen.DoobieTestGen.{generateTests, given}

class Specs2CheckerGen extends Specification with doobie.specs2.IOChecker {

  val transactor = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver",
    url = "jdbc:postgresql://localhost:5432/docker?searchpath=public",
    user = "docker",
    password = "docker",
    logHandler = None,
  )

  generateTests(Queries).map { testCase =>
    check(testCase.query)
  }

}
