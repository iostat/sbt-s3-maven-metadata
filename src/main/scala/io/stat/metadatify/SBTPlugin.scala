package io.stat.metadatify

import ohnosequences.sbt.SbtS3Resolver.autoImport.S3Resolver
import sbt.Keys._
import sbt._

object SBTPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger  = allRequirements

  lazy val publishS3 = TaskKey[Unit]("publishS3", "Update the maven-metadata.xml`s in S3")

  override lazy val projectSettings = Seq(
    publishS3 <<=
      (
        publish, publishTo,
        organization, name, version,
        crossVersion, scalaVersion, scalaBinaryVersion,
        sbtPlugin, sbtBinaryVersion,
        streams
      ) map {
        (pt, pd, o, a, v, cv, sv, sbv, sbtp, sbtbv, s) => publishS3TaskImpl(pd, o % a % v, cv, sv, sbv, sbtp, sbtbv, s)
    }
  )

  private[this] def publishS3TaskImpl(destRepo: Option[Resolver],
                                      module: ModuleID,
                                      crossVersion: CrossVersion,
                                      scalaVersion: String,
                                      scalaBinaryVersion: String,
                                      sbtPlugin: Boolean,
                                      sbtBinaryVersion: String,
                                      streams: TaskStreams) = {
    val organization = module.organization

    // artifact scala-lang cross-version
    val artifactSLCV  =
      CrossVersion
        .apply(crossVersion, scalaVersion, scalaBinaryVersion)
        .map(_(module.name))
        .getOrElse(module.name)

    val artifact =
      if(sbtPlugin) {
        artifactSLCV + s"_$sbtBinaryVersion"
      } else {
        artifactSLCV
      }

    destRepo match {
      case Some(mavenResolver: MavenRepository) =>
        val repoRoot = mavenResolver.root
        streams.log.info(s"sPublishing to MavenRepository at $repoRoot")
        runMetadatifyMain(repoRoot, organization, artifact, streams.log)
      case Some(rawResolver: RawRepository) =>
        val resolver = rawResolver.resolver
        resolver match {
          case asS3Resolver: S3Resolver =>
            val repoRoot = asS3Resolver.url.url
            streams.log.info(s"Publishing to sbt-s3-resolver repository at $repoRoot")
            runMetadatifyMain(repoRoot, organization, artifact, streams.log)
          case _ =>
            streams.log.error(s"Resolver is not supported: $resolver")
        }
      case Some(x) =>
        streams.log.error(s"$x is not a support Repository!")
      case None =>
        streams.log.error("No publishTo has been specified!")
    }
  }

  //noinspection ConvertibleToMethodValue as sbt.Logger uses some type magic that confuses the compiler
  private[this] def runMetadatifyMain(repo: String, org: String, art: String, logger: Logger): Unit = {
    val fullChroot = Array(
      repo,
      org.replaceAll("\\.", "/"),
      art
    ).mkString("/")

    logger.info(s"Updating metadatas under $fullChroot")
    Main.io = Main.Integration(
      fail = () => {
        throw new IllegalStateException("Metadatify failed!")
      },
      logInfo = logger.info(_),
      logError = logger.error(_)
    )
    Main.main(Array(fullChroot))
  }
}
