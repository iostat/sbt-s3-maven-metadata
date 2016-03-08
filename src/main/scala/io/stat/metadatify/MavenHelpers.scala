package io.stat.metadatify

/**
  * Helper case classes and such for dealing with Maven artifacts on a high level.
  * MetadataPath is always going to be where the MavenMetadata for the artifact is ultimately stored.
  * We use a bit of magic in MavenMetadata.metadatasForVersions to allow semi-recursive generation of all
  * maven-metadata.xmls we'll actually need
  *
  * @author Ilya Ostrovskiy (https://github.com/iostat/) 
  */
object MavenHelpers {
  val metadataSuffix = "/maven-metadata.xml"

  private[this] def parentDir(path: String): String = {
    // a ghetto way of doing "/path/to/something/etc.ext/../" (yielding "/path/to/something")
    path.substring(0, path.lastIndexOf('/'))
  }

  def getMetadataPath(originalKey: String): String = {
    parentDir(parentDir(originalKey)) + metadataSuffix
  }

  case class MavenArtifact(metadataPath: String, group: String, artifact: String)

  case class MavenVersionedArtifact(artifact: MavenArtifact, version: String) {
    def this(metadataPath: String, group: String, artifact: String, version: String) = this(MavenArtifact(metadataPath, group, artifact), version)
  }

  case class MavenMetadata(artifact: MavenArtifact, versions: Seq[String]) {
    private[this] def makeVersionTag(v: String) = <version>{v}</version>
    def toXML =
      <metadata modelVersion="1.1.0">
        <groupId>{artifact.group}</groupId>
        <artifactId>{artifact.artifact}</artifactId>
        <versioning>
          <latest>{versions.last}</latest>
          <release/>
          <versions>{versions map makeVersionTag}</versions>
          <lastUpdated>{System.currentTimeMillis / 1000}</lastUpdated>
        </versioning>
      </metadata>

    def metadatasForVersions: Seq[MavenMetadata] = versions map { version => {
      val newMetadataPath = parentDir(artifact.metadataPath) + s"/$version" + metadataSuffix
      MavenMetadata(MavenArtifact(newMetadataPath, artifact.group, artifact.artifact), Seq(version))
    }}
  }
}
