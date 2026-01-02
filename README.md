# Doobie Test Macro

A Scala 3 macro library that automatically generates type-safe test cases for doobie queries and updates.
Compatible with Specs2, Scalatest, Weaver and Munit.

## Overview

This macro analyzes your doobie query objects at compile time and generates structured test cases (`TestCase`) with compile-time verified implicit parameters. It captures `Analyzable` instances at compile time, ensuring type-safe query checking without runtime type matching.

## Features

- **Compile-time analysis**: Discovers all `Query0[A]`, `Update0`, and `Update[A]` methods and values automatically
- **Type-safe with captured implicits**: Returns `List[TestCase]` with `CheckableQuery` that captures `Analyzable` at compile 
- **Implicit parameter resolution**: Requires all method parameters (including `using` parameters) to have implicit instances in ope
- **Zero runtime overhead**: All analysis and implicit resolution happens at compile time
- **Handles type aliases**: Correctly identifies `Update0` even when used as a type alias
- **Supports `Update[A]`**: Automatically converts parameterized updates to `Update0` via `.toUpdate0()`
- **Rich error reporting**: Provides helpful compile-time errors when queries or required implicits are not found
- **Supports both `def` and `val`**: Detects query definitions whether they're methods or values
- **Overload-aware naming**: Differentiates overloaded methods by including parameter types in test names

## Usage

### 1. Define Your Queries

```scala
import doobie.*
import doobie.implicits.*

object DoobieQueries {
  
  // Custom context for queries
  trait Env {
    def name: String
  }
  
  object Env {
    given Env = new Env { def name = "production" }
  }
  
  // Methods with parameters
  def query1(name: String, country: String): Query0[String] = {
    sql"select name from country where name = $name and country = $country"
      .query[String]
  }

  // Methods without parameters
  def query2: Query0[Int] = {
    sql"select count(*) from country"
      .query[Int]
  }

  // Val definitions
  val trivialQuery: Query0[(Int, String)] =
    sql"""select 42, 'foo'::varchar"""
      .query[(Int, String)]

  // Updates
  def update1(name: String): Update0 = {
    sql"update country set name = $name"
      .update
  }
  
  // Parameterized update (Update[A])
  def setName()(using e: Env): Update[(String, Int)] =
    Update[(String, Int)](
      sql = "UPDATE country SET name = ? WHERE code = ?",
      pos = Some(Pos.instance),
      label = e.name
    )
  
  val updateQuery: Update0 =
    sql"""update country set name = "new" where name = "old""""
      .update
}
```

### 2. Generate Test Cases

```scala
import bernie.doobie.checkergen.DoobieTestGen.*

// Provide implicit instances for all parameter types
given String = "test"
given Int = 42

// Generate test cases at compile time
// The macro will summon implicit values for all parameters
val testCases = generateTests(DoobieQueries)

// testCases is List[TestCase] where each TestCase contains:
// - testName: String (e.g., "DoobieQueries.query1")
// - methodName: String (e.g., "query1")
// - query: CheckableQuery (with Analyzable captured at compile time!)
```

**Important**: The macro requires implicit instances in scope for **every parameter type** used by your query methods. If an implicit is missing, you'll get a compile-time error.

### 3. Use in ScalaTest

```scala
import org.scalatest.funsuite.AnyFunSuite
import doobie.scalatest.IOChecker
import cats.effect.IO
import doobie.util.transactor.Transactor

class DoobieQueriesSpec extends AnyFunSuite with IOChecker {
  
  override def transactor: Transactor[IO] = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver",
    url = "jdbc:postgresql://localhost:5432/mydb",
    user = "user",
    password = "password",
    logHandler = None
  )
  
  // Generate test cases - implicit instances are captured at compile time
  val testCases = generateTests(DoobieQueries)
  
  testCases.foreach { testCase =>
    test(testCase.testName) {
      // No runtime type matching needed!
      // Analyzable is captured at compile time
      check(testCase.query.value)(using testCase.query.analyzable)
    }
  }
}
```

The macro generates test cases where:
- `testCase.query.value` is the actual `Query0[A]` or `Update0`
- `testCase.query.analyzable` is the `Analyzable` instance captured at compile time

## API

### CheckableQuery

```scala
trait CheckableQuery {
  type A
  val value: A                    // The actual Query0[_] or Update0
  val analyzable: Analyzable[A]   // Analyzable instance captured at compile time
}
```

This existential type wrapper allows the macro to capture the `Analyzable` instance at compile time, eliminating the need for runtime type matching.

### TestCase

```scala
case class TestCase(
  testName: String,           // e.g., "DoobieQueries.query1"
  methodName: String,         // e.g., "query1"
  query: CheckableQuery       // Query/Update with Analyzable captured!
)
```

### Macro Functions

```scala
// Standard macro - generates test cases with CheckableQuery
inline def generateTests[A](inline instance: A): List[TestCase]

// Debug mode - prints additional compile-time information
inline def generateTestsDebug[A](inline instance: A): List[TestCase]
```

**Note**: Both macros require implicit instances in scope for all parameter types used by the query methods.

## How It Works

1. **Macro Expansion**: At compile time, the macro receives your query object
2. **Type Analysis**: Analyzes the type signature of all methods and values
3. **Query Detection**: Identifies methods/values returning `Query0[A]`, `Update0`, or `Update[A]`
4. **Implicit Resolution**: Uses `Expr.summon[T]` to resolve implicit instances for all parameters at compile time
5. **AST Generation**: Creates method call AST with summoned implicit values
6. **Update[A] Handling**: Converts `Update[A]` to `Update0` via `.toUpdate0(null.asInstanceOf[A])`
7. **Analyzable Capture**: Wraps each query in `CheckableQuery` which captures the `Analyzable` instance
8. **TestCase Construction**: Creates `TestCase` objects with `CheckableQuery`


## Error Handling

The macro provides helpful compile-time errors:

### No Queries Found

```scala
[error] No Query0, Update0, or Update[A] methods or values found in MyQueries
[error] 
[error] Available members: def someMethod, val someValue, def anotherMethod
[error] 
[error] Ensure your methods/values return doobie.Query0[A], doobie.Update0, or doobie.Update[A]
```

### Missing Implicit Instance

```scala
[error] Cannot find implicit value for Int required by method.
[error] 
[error] Please ensure the implicit is in scope at the call site.
```

To fix, provide a `given` instance:

```scala
given Int = 42
val testCases = generateTests(DoobieQueries)
```

## Implicit Parameter Requirements

The macro requires **implicit instances for all parameter types** to be in scope when calling `generateTests` or `generateTestsDebug`. This includes:

1. **Regular parameters**: `def query(id: Int)` requires `given Int`
2. **Using parameters**: `def query()(using env: Env)` requires `given Env`
3. **Multiple parameters**: `def query(name: String, age: Int)` requires both `given String` and `given Int`

### Example

```scala
// Define your queries
object MyQueries {
  def findUser(id: Int): Query0[User] = ???
  def updateUser(name: String, age: Int): Update0 = ???
  def withContext()(using ctx: Context): Query0[Data] = ???
}

// Provide implicit instances
given Int = 1
given String = "test"
given Context = Context("prod")

// Now generate tests
val testCases = generateTests(MyQueries)
```

## Validation

The macro performs several validations:

1. Checks if the input is a class or object
2. Warns if it's not a module (object)
3. Errors if no Query0/Update0/Update[A] methods are found
4. Errors if required implicit instances are not in scope


## Requirements

- Scala 3.6.2+
- Doobie 1.0.0-RC8+

## Completed Features

- [x] Generate actual test methods (not just metadata)
- [x] Support for `val` definitions in addition to `def` methods
- [x] Implicit parameter resolution at compile time
- [x] Support for `Update[A]` (parameterized updates)
- [x] Capture `Analyzable` instances at compile time
- [x] Support for `using` parameters
- [x] Overload-aware test naming
- [x] Type-safe query wrapping with `CheckableQuery`


## License

MIT License - see [LICENSE](LICENSE) file for details.

