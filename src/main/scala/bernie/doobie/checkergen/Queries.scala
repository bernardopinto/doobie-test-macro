package bernie.doobie.checkergen

import doobie.*
import doobie.implicits.*
import doobie.util.pos.Pos

object Queries {

  trait Env {
    def name: String
  }

  object Env {
    def apply(n: String): Env = new Env {
      def name = n
    }

    given Env = Env("Bernardo")
  }

  case class Country(code: Int, name: String, pop: Int, gnp: Double)

  val trivial =
    sql"""
            select 42, 'foo'::varchar
        """.query[(Int, String)]

  def biggerThan(minPop: Short) =
    sql"""
            select code, name, population, gnp, indepyear
            from country
            where population > $minPop
        """.query[Country]

  def biggerThan(minPop: Int) =
    sql"""
            select code, name, population, gnp, indepyear
            from country
            where population > $minPop
        """.query[Country]

  def testWithParams(a: Int, b: String) =
    sql"""
            select code, name, population, gnp, indepyear
            from country
            where population > $a and name = $b
        """.query[Country]

  val update: Update0 =
    sql"""
            update country set name = "new" where name = "old"
        """.update

  def setNameWithCode()(using e: Env): Update[(String, Int)] =
    Update[(String, Int)](
      sql =
        // language=sql
        "UPDATE country SET name = ? WHERE code = ?",
      pos = Some(Pos.instance),
      label = e.name,
    )
}
