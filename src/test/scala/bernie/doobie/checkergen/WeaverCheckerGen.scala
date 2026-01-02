package bernie.doobie.checkergen

import _root_.weaver.*
import doobie.weaver.*
import bernie.doobie.checkergen.DoobieTestGen.{generateTests, given}
import doobie.util.transactor.Transactor
import cats.effect.IO
import cats.effect.Resource
import doobie.util.testing.Analyzable

object WeaverCheckerGen extends IOSuite with IOChecker {

  override type Res = Transactor[IO]
  override def sharedResource: Resource[IO, Res] =
    Resource.pure(
      Transactor.fromDriverManager[IO](
        driver = "org.postgresql.Driver",
        url = "jdbc:postgresql://localhost:5432/docker?searchpath=public",
        user = "docker",
        password = "docker",
        logHandler = None,
      ),
    )

  generateTests(Queries).foreach { testCase =>
    test(testCase.testName) { case given Transactor[IO] =>
      given Analyzable[testCase.query.A] = testCase.query.analyzable
      check(testCase.query.value)
    }
  }

}
