name := "mortar"

version := "1.0"

scalaVersion := "2.12.1"

//fork in run := true

libraryDependencies ++= Seq(
  "com.lambdista" %% "config" % "0.5.1",
  "com.lambdista" %% "config-typesafe" % "0.5.1",
  "org.tinylog" % "tinylog" % "1.2",
  "org.scalaj" %% "scalaj-http" % "2.3.0",
  "org.bouncycastle" % "bcpg-jdk14" % "1.55",
  "org.typelevel" %% "squants" % "1.2.0",
  "net.sourceforge.argparse4j" % "argparse4j" % "0.7.0",
  "com.cedarsoftware" % "json-io" % "4.9.12",
  "com.typesafe.akka" %% "akka-http" % "10.0.5",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.5",
  "com.typesafe.akka" %% "akka-cluster" % "2.4.17",
  "com.typesafe.akka" %% "akka-distributed-data-experimental" % "2.4.17",
  "com.typesafe.akka" %% "akka-persistence" % "2.4.17",
  "com.typesafe.akka" %% "akka-persistence-tck" % "2.4.17"
)
