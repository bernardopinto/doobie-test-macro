package bernie.doobie.checkergen

import quoted.*
import doobie.util.query
import doobie.util.testing.Analyzable

/** Wrapper that captures a query/update with its Analyzable instance at compile time. This allows
  * type-safe checking without runtime type matching.
  */
trait CheckableQuery {
  type A
  val value: A
  val analyzable: Analyzable[A]
}

object CheckableQuery {

  /** Create a CheckableQuery capturing the Analyzable instance at call site */
  def apply[T](v: T)(using a: Analyzable[T]): CheckableQuery = new CheckableQuery {
    type A = T
    val value: A = v
    val analyzable: Analyzable[A] = a
  }
}

case class TestCase(
    testName: String,
    methodName: String,
    query: CheckableQuery,
)

object DoobieTestGen {

  given Short = 0
  given Int = 0
  given String = "0"
  given Long = 0L
  given Float = 0.0f
  given Double = 0.0
  given Boolean = false
  given Char = '0'
  given Byte = 0.toByte

  inline def generateTestsDebug[A](inline instance: A): List[TestCase] =
    ${ generateTestsImpl('instance, debug = true) }

  inline def generateTests[A](inline instance: A): List[TestCase] =
    ${ generateTestsImpl('instance, debug = false) }

  def generateTestsImpl[A: Type](instance: Expr[A], debug: Boolean = false)(using
      q: Quotes,
  ): Expr[List[TestCase]] = {
    import q.reflect.*

    val term = instance.asTerm
    val typeRepr = TypeRepr.of[A]
    val typeSymbol = typeRepr.typeSymbol

    // Validation 1: Check if it's an object or class
    if (!typeSymbol.isClassDef) {
      report.errorAndAbort(s"Expected an object or class, but got: ${typeRepr.show}")
    }

    // Validation 2: Check if it's a module (object)
    if (!typeSymbol.flags.is(Flags.Module)) {
      report.warning(
        s"${typeSymbol.name} is not an object. Consider using an object for query definitions.",
        term.pos,
      )
    }

    // Step 1: Extract the object/class name (remove trailing $ from object names)
    val objectName = typeSymbol.name.stripSuffix("$")

    if (debug) {
      println(s"typeRepr: ${typeRepr.show(using Printer.TypeReprStructure)}")
      println(s"typeSymbol: ${typeSymbol.fullName}")
      println("objectName: " + objectName)
    }

    val queryZeroT = TypeRepr.of[doobie.Query0[?]].typeSymbol
    val updateZeroT = TypeRepr.of[doobie.Update0].typeSymbol
    val updateT = TypeRepr.of[doobie.Update[?]].typeSymbol

    def recurseType(
        tr: TypeRepr,
        paramTypeLists: List[List[TypeRepr]],
    ): (List[List[TypeRepr]], Option[DoobieQueryType]) = {
      tr match {
        case mt @ MethodType(paramNames, paramTypes, resultType) =>
          if (debug) {
            println(s"recursing MethodType: ${tr.show(using Printer.TypeReprStructure)}")
            println(s"paramNames: ${paramNames.mkString(", ")}")
            println(
              s"paramTypes: ${paramTypes.map(_.show(using Printer.TypeReprStructure)).mkString(", ")}",
            )
            println(s"resultType: ${resultType.show(using Printer.TypeReprStructure)}")
          }

          val newParamTypeLists = paramTypeLists :+ paramTypes

          resultType match {
            case _: MethodType => recurseType(resultType, newParamTypeLists)
            case _             =>
              val queryType = getDoobieQueryType(resultType)
              (newParamTypeLists, queryType)
          }

        case _ => (paramTypeLists, None)
      }

    }

    // Helper function to generate a unique test name including parameter types
    // This differentiates overloaded methods (e.g., biggerThan(Short) vs biggerThan(Int))
    // Only adds type parameters if the method name is duplicated
    def generateTestName(
        methodName: String,
        paramTypeLists: List[List[TypeRepr]],
        isDuplicated: Boolean,
    ): String =
      if (!isDuplicated || paramTypeLists.flatten.isEmpty) {
        methodName
      } else {
        val allParamTypes = paramTypeLists
          .map(_.map(_.show(using Printer.TypeReprStructure)).mkString(", "))
          .mkString("(", ")(", ")")
        s"$methodName$allParamTypes"
      }

    def buildCall(methodName: String, paramTypeLists: List[List[TypeRepr]], select: Term): Term = {
      paramTypeLists match {
        case Nil                => select
        case types :: restTypes =>
          val args = types.map { paramType =>
            paramType.asType match {
              case '[t] =>
                Expr.summon[t] match {
                  case Some(expr) =>
                    if (debug) {
                      println(s"Found implicit via Expr.summon for ${paramType.show}")
                    }
                    expr.asTerm
                  case None =>
                    report.errorAndAbort(
                      s"Cannot find implicit value for ${paramType.show} required by method $methodName",
                    )
                }
            }
          }

          val applied = Apply(select, args)
          buildCall(methodName, restTypes, applied)
      }
    }

    // Enum to track the type of doobie query with its actual return type
    enum DoobieQueryType {
      case Query0Type(returnType: TypeRepr)
      case Update0Type
      case UpdateType(returnType: TypeRepr)
    }

    def getDoobieQueryType(tr: TypeRepr): Option[DoobieQueryType] = {
      val dealiasedType = tr.dealias
      val isQuery0 = dealiasedType match {
        case AppliedType(tc, _) => tc.typeSymbol == queryZeroT
        case _                  => false
      }
      val isUpdate0 = dealiasedType.typeSymbol == updateZeroT
      val isUpdate = dealiasedType match {
        case AppliedType(tc, _) => tc.typeSymbol == updateT
        case _                  => false
      }

      if (isQuery0) Some(DoobieQueryType.Query0Type(dealiasedType))
      else if (isUpdate0) Some(DoobieQueryType.Update0Type)
      else if (isUpdate) Some(DoobieQueryType.UpdateType(dealiasedType))
      else None
    }

    // First pass: collect all Query0/Update0 methods with their information
    case class MethodInfo(
        symbol: Symbol,
        methodName: String,
        paramTypeLists: List[List[TypeRepr]],
        queryType: DoobieQueryType,
    )

    val methodInfos = typeSymbol.declarations
      .filter { m =>
        (m.isDefDef && !m.isClassConstructor && !m.flags.is(Flags.Private)) ||
        (m.isValDef && !m.flags.is(Flags.Private))
      }
      .flatMap { symbol =>
        val tr = typeRepr.memberType(symbol)
        if (debug) {
          println(
            s"symbol: ${symbol.name}, isValDef: ${symbol.isValDef}, isDefDef: ${symbol.isDefDef}",
          )
          println(s"tr: ${tr.show(using Printer.TypeReprStructure)}")
        }

        // For vals, check the type directly; for defs, recurse through MethodType
        val (paramTypeLists, queryTypeOpt) = if (symbol.isDefDef) {
          recurseType(tr, List.empty)
        } else {
          val queryType = getDoobieQueryType(tr)
          if (debug) {
            println(s"val ${symbol.name} - queryType: $queryType")
          }

          (List.empty[List[TypeRepr]], queryType)
        }

        queryTypeOpt.map(qt => MethodInfo(symbol, symbol.name, paramTypeLists, qt))
      }

    // Identify duplicated method names
    val methodNameCounts = methodInfos.groupBy(_.methodName).view.mapValues(_.size).toMap
    val duplicatedMethodNames = methodNameCounts.filter(_._2 > 1).keySet

    // Second pass: generate test cases with appropriate naming
    val testCases = methodInfos.map { methodInfo =>
      val methodName = methodInfo.methodName
      val isDuplicated = duplicatedMethodNames.contains(methodName)

      // Generate test name: include type parameters only if method name is duplicated
      val uniqueMethodName = generateTestName(methodName, methodInfo.paramTypeLists, isDuplicated)
      val testName = s"$objectName.$uniqueMethodName"

      // Build the actual method call with null arguments cast to appropriate types (or direct access for vals)
      val methodSelect = Select(term, methodInfo.symbol)
      val fullCall = buildCall(methodInfo.methodName, methodInfo.paramTypeLists, methodSelect)

      // Generate CheckableQuery with Analyzable captured at compile time.
      // The compiler will summon the Analyzable instance based on the actual return type.
      val checkableQueryExpr: Expr[CheckableQuery] = methodInfo.queryType match {
        case DoobieQueryType.Query0Type(returnType) =>
          // Extract the type parameter A from Query0[A] and use it explicitly
          returnType match {
            case AppliedType(_, List(innerType)) =>
              if (debug) {
                println(s"innerType: ${innerType.show(using Printer.TypeReprStructure)}")
              }
              innerType.asType match {
                case '[a] => '{ CheckableQuery(${ fullCall.asExprOf[doobie.Query0[a]] }) }
              }
            case _ =>
              report.errorAndAbort(s"Expected Query0[A] but got: ${returnType.show}")
          }
        case DoobieQueryType.Update0Type =>
          '{ CheckableQuery(${ fullCall.asExprOf[doobie.Update0] }) }
        case DoobieQueryType.UpdateType(returnType) =>
          // Extract the type parameter A from Update[A] and convert to Update0
          returnType match {
            case AppliedType(_, List(innerType)) =>
              innerType.asType match {
                case '[a] =>
                  // Generate: methodCall().toUpdate0(null.asInstanceOf[a])
                  val updateExpr = fullCall.asExprOf[doobie.Update[a]]

                  val nullArg = '{ null.asInstanceOf[a] }
                  // TODO should summon `a` instead of null.asInstanceOf[a]?
                  '{ CheckableQuery(${ updateExpr }.toUpdate0($nullArg)) }
              }
            case _ =>
              report.errorAndAbort(s"Expected Update[A] but got: ${returnType.show}")
          }
      }

      if (debug) {
        println(
          s"checkableQueryExpr print: ${checkableQueryExpr.asTerm.show(using Printer.TreeStructure)}",
        )
      }

      // Generate TestCase as an Expr using quotations
      '{
        TestCase(
          ${ Expr(testName) },
          ${ Expr(methodName) },
          $checkableQueryExpr,
        )
      }
    }

    if (testCases.isEmpty) {
      val pos = instance.asTerm.pos
      val availableMembers = typeSymbol.declarations
        .filter(m => m.isDefDef || m.isValDef)
        .map(m => s"${if (m.isValDef) "val" else "def"} ${m.name}")
        .filterNot(_.contains("<init>"))
        .mkString(", ")

      report.errorAndAbort(
        s"""No Query0, Update0, or Update[A] methods or values found in $objectName
                   |
                   |Available members: $availableMembers
                   |
                   |Ensure your methods/values return doobie.Query0[A], doobie.Update0, or doobie.Update[A]
                   |""".stripMargin,
        pos,
      )
    }

    // Extract all method names that were processed
    val processedMethodNames = methodInfos.map(_.methodName).mkString(", ")
    report.info(
      s"Generated ${testCases.length} test cases for $objectName: $processedMethodNames",
    )

    // Convert List[Expr[TestCase]] to Expr[List[TestCase]]
    Expr.ofList(testCases)
  }

}
