name := "sbt-s3-maven-metadata"

organization := "io.stat.metadatify"

version := "1.0-SNAPSHOT"

sbtPlugin := true

addMavenResolverPlugin

resolvers ++= Seq(
  "Bintray JCenter" at "https://jcenter.bintray.com/",
  "Era7 Maven Repo" at "https://s3-eu-west-1.amazonaws.com/releases.era7.com"
)

addSbtPlugin("ohnosequences" % "sbt-s3-resolver" % "0.14.0")
