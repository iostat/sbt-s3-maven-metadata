package io.stat.metadatify

import java.io.ByteArrayInputStream

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.s3.model._
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import io.stat.metadatify.MavenHelpers._

import scala.annotation.tailrec
import scala.collection.JavaConverters

/**
  * Reads an S3 path and generates maven-metadata for artifacts within it
  * because everybody seems to hate me today
  *
  * @author Ilya Ostrovskiy (https://github.com/iostat/) 
  */
object Main {
  case class Integration(fail: () => Unit, logInfo: (String => Unit), logError: (String => Unit))
  var io: Integration = null

  /**
    * Read a POM from S3 and convert it to MavenVersionedArtifact
    */
  private[this] def loadPoms(amazonS3: AmazonS3): S3ObjectSummary => MavenVersionedArtifact = { os =>
    io.logInfo(s"loading POM at s3://${os.getBucketName}/${os.getKey}")
    val xmlRawStream = amazonS3.getObject(os.getBucketName, os.getKey).getObjectContent
    val xml = scala.xml.XML.load(xmlRawStream)

    val group    = xml \ "groupId"    text
    val artifact = xml \ "artifactId" text
    val version  = xml \ "version"    text

    new MavenVersionedArtifact(getMetadataPath(os.getKey), group, artifact, version)
  }

  /**
    * Write the maven-metadata.xml of a single MavenMetadata
    */
  private[this] def writeSingleMetadata(amazonS3: AmazonS3, s3Bucket: String, chroot: String):
    MavenMetadata => Unit = metadata => {
      val xmlPath           = metadata.artifact.metadataPath
      val s3KeyName         = xmlPath
      val metadataXmlBytes  = metadata.toXML.toString.getBytes("UTF-8")
      val putObjectMetadata = new ObjectMetadata()

      putObjectMetadata.setContentLength(metadataXmlBytes.length)

      io.logInfo(s"writing to s3://$s3Bucket/$s3KeyName (${metadataXmlBytes.length} bytes)")

      val putRequest =
        new PutObjectRequest(s3Bucket, s3KeyName, new ByteArrayInputStream(metadataXmlBytes), putObjectMetadata)
          .withCannedAcl(CannedAccessControlList.PublicRead)

      amazonS3.putObject(putRequest)
    }

  /**
    * Write the maven-metadata.xml for a single artifact and all its versions, and
    * a maven-metadata for each individual version.
    */
  private[this] def writeArtifactMetadatas(amazonS3: AmazonS3, s3Bucket: String, chroot: String):
    MavenMetadata => Unit = metadata => {
      writeSingleMetadata(amazonS3, s3Bucket, chroot)(metadata)
      metadata.metadatasForVersions.foreach(writeSingleMetadata(amazonS3, s3Bucket, chroot))
    }

  /**
    * Recursively load all object summaries in an S3 listing
    */
  @tailrec private[this] def loadAllObjectSummaries
    (thusFar: Seq[S3ObjectSummary], amazonS3: AmazonS3, listing: ObjectListing): Seq[S3ObjectSummary] = {
      val next: Seq[S3ObjectSummary] =
        thusFar ++
          JavaConverters
            .asScalaIteratorConverter(listing.getObjectSummaries.iterator)
            .asScala
            .toSeq

      if(listing.isTruncated) {
        loadAllObjectSummaries(next, amazonS3, amazonS3.listNextBatchOfObjects(listing))
      } else {
        next
      }
    }

  private[this] def parseArgs(args: Array[String]): Option[(String, String)] = args match {
    case Array(oneArg) =>
      val stripURI = oneArg.replaceFirst("s3://", "")
      val firstSlashIndex = stripURI.indexOf('/')
      val bucket = if (firstSlashIndex == -1) stripURI else stripURI.substring(0, firstSlashIndex)
      val chroot = if (firstSlashIndex == -1) ""       else stripURI.substring(firstSlashIndex + 1)

      Some((bucket, chroot))
    case Array(bucket, chroot, _*) => Some((bucket, chroot))
    case _ => None
  }


  def main(args: Array[String]): Unit = {
    if(io == null) {
      io = Integration(fail = { sys.exit(1)}, logInfo = Console.out.println, logError = Console.err.println)
    }

    parseArgs(args) match {
      case None =>
        io.logError("USAGE: Metadatify        BUCKET [CHROOT]")
        io.logError("   or: Metadatify [s3://]BUCKET[/CHROOT]")
        io.fail
      case Some((s3Bucket, s3Chroot)) =>
        val credentials: AWSCredentials = new ProfileCredentialsProvider().getCredentials
        val amazonS3 = new AmazonS3Client(credentials)

        // 0) list all files under chroot
        val listing      = amazonS3.listObjects(s3Bucket, s3Chroot)
        val allSummaries = loadAllObjectSummaries(Seq(), amazonS3, listing)

        // 1) keep only the poms
        // 2) load POMs and convert them to MavenVersionedArtifact, keeping track of where the artifact was stored
        // 3) group by the path/to/artifact, groupID, and artifactID, creating a Map(aforementioned -> Stream(version1, version2, etc.))
        // 4) convert the values in that map to a Seq of strings containing the version, and sort them, and deduplicate (just in case)
        // 5) convert that map into MavenMetadata objects
        // 6) write a maven-metadata.xml for the group:artifact as well as individual metadatas for each version
        allSummaries
          .filter(_.getKey.endsWith(".pom"))
          .map(loadPoms(amazonS3))
          .groupBy(_.artifact)
          .mapValues(_.map(_.version).toList.sorted)
          .map(MavenMetadata.tupled)
          .foreach(writeArtifactMetadatas(amazonS3, s3Bucket, s3Chroot))
    }
  }
}
