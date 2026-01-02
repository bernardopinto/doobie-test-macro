ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.3"

ThisBuild / organization := "bernie.doobie.checkergen"

ThisBuild / scalacOptions ++= Seq(
  "-Xprint:postInlining",
  "-Xmax-inlines:100000",
  "-explain",
)

lazy val root = (project in file("."))
  .settings(
    name := "doobie-test-macro",
    libraryDependencies ++= Seq(
      "org.postgresql"         % "postgresql"                        % "42.7.1",  
      "org.scalatest"          %% "scalatest"                        % "3.2.19",
      "org.tpolecat"           %% "doobie-core"                      % "1.0.0-RC11",
      "org.tpolecat"           %% "doobie-scalatest"                 % "1.0.0-RC11",
      "org.tpolecat"           %% "doobie-specs2"                    % "1.0.0-RC11",
      "org.tpolecat"           %% "doobie-munit"                     % "1.0.0-RC11",
      "org.tpolecat"           %% "doobie-weaver"                    % "1.0.0-RC11",

    )
  )
