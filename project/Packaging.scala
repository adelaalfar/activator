import sbt._
import Keys._

import SbtSupport.sbtLaunchJar

package sbt {
  object IvySbtCheater {
    def toID(m: ModuleID) = IvySbt toID m
  }
}

object Packaging {
  import com.typesafe.packager.Keys._
  import com.typesafe.packager.PackagerPlugin._

  val repackagedLaunchJar = TaskKey[File]("repackaged-launch-jar", "The SNAP launch jar.")
  val repackagedLaunchMappings = TaskKey[Seq[(File, String)]]("repackaged-launch-mappings", "New files for sbt-launch-jar")

  val localRepoArtifacts = SettingKey[Seq[ModuleID]]("local-repository-artifacts", "Artifacts included in the local repository.")
  val localRepoName = "install-to-local-repository"
  val localRepo = SettingKey[File]("local-repository", "The location to install a local repository.")
  val localRepoCreated = TaskKey[File]("local-repository-created", "Creates a local repository in the specified location.")

  def settings: Seq[Setting[_]] = packagerSettings ++ Seq(
    name := "snap",
    wixConfig := <wix/>,
    maintainer := "Josh Suereth <joshua.suereth@typesafe.com>",
    packageSummary := "Typesafe SNAP",
    packageDescription := """A templating and project runner for Typesafe applications.""",
    mappings in Universal <+= repackagedLaunchJar map { jar =>
      jar -> "bin/snap-launch.jar"
    },
    mappings in Universal <++= localRepoCreated map { repo =>
      for {
        (file, path) <- (repo.*** --- repo) x relativeTo(repo)
      } yield file -> ("repository/" + path)
    },
    rpmRelease := "1",
    rpmVendor := "typesafe",
    rpmUrl := Some("http://github.com/scala/scala-dist"),
    rpmLicense := Some("BSD"),

    repackagedLaunchJar <<= (target, sbtLaunchJar, repackagedLaunchMappings) map repackageJar,
    repackagedLaunchMappings := Seq.empty,
    repackagedLaunchMappings <+= (target, scalaVersion, version) map makeLauncherProps,

    localRepo <<= target(_ / "local-repository"),
    localRepoArtifacts := Seq.empty,
    resolvers <+= localRepo apply { f => Resolver.file(localRepoName, f)(Resolver.ivyStylePatterns) },
    localRepoCreated <<= (localRepo, localRepoArtifacts, ivySbt, streams) map { (r, m, i, s) => 
      createLocalRepository(m, i, s.log)
      r
    }
  )


  def createLocalRepository(
      modules: Seq[ModuleID], 
      ivy: IvySbt, 
      log: Logger): Unit = ivy.withIvy(log) { ivy =>
    
    import org.apache.ivy.core.module.id.ModuleRevisionId
    import org.apache.ivy.core.report.ResolveReport
    import org.apache.ivy.core.install.InstallOptions
    import org.apache.ivy.plugins.matcher.PatternMatcher

    def installModule(module: ModuleID): ResolveReport = {
      // TODO - Use SBT's default ModuleID -> ModuleRevisionId
      val mrid = IvySbtCheater toID module
      ivy.install(mrid, "sbt-chain", localRepoName, 
                new InstallOptions()
                    .setTransitive(true)
                    .setValidate(false)
                    .setOverwrite(true)
                    .setConfs(Array("default", "compile"))
                    .setMatcherName(PatternMatcher.EXACT))
                    // Grab all Artifacts
                    //.setArtifactFilter(FilterHelper.getArtifactTypeFilter(`type`))
    }
    modules foreach installModule     
  }


  // TODO - Use SBT caching API for this.
  def repackageJar(target: File, launcher: File, replacements: Seq[(File, String)] = Seq.empty): File = IO.withTemporaryDirectory { tmp =>
    val jardir = tmp / "jar"
    IO.createDirectory(jardir)
    IO.unzip(launcher, jardir)

    // Copy new files
    val copys =
      for((file, path) <- replacements) 
      yield file -> (jardir / path)
    IO.copy(copys, overwrite=true, preserveLastModified=false)

    // Create new launcher jar    
    val tmplauncher = tmp / "snap-launcher.jar"
    val files = (jardir.*** --- jardir) x relativeTo(jardir)
    IO.zip(files, tmplauncher)
    
    // Put new launcher jar in new location.
    val nextlauncher = target / "snap-launcher.jar"
    if(nextlauncher.exists) IO.delete(nextlauncher)
    IO.move(tmplauncher, nextlauncher)
    nextlauncher
  }


  // NOTE; Shares boot directory with SBT, good thing or bad?  not sure.
  // TODO - Just put this in the sbt-launch.jar itself!
  def makeLauncherProps(target: File, scalaVersion: String, version: String): (File, String) = {
    val tdir = target / "generated-sources"
    if(!tdir.exists) tdir.mkdirs()
    val tprops = tdir / (name + ".properties")
    // TODO - better caching
    // TODO - Add a local repository for resolving...
    if(!tprops.exists) IO.write(tprops, """
[scala]
  version: %s

[app]
  org: com.typesafe.snap
  name: snap-launcher
  version: %s
  class: snap.SnapLauncher
  cross-versioned: true
  components: xsbti

[repositories]
  local
  maven-central
  typesafe-releases: http://typesafe.artifactoryonline.com/typesafe/releases
  typesafe-ivy-releases: http://typesafe.artifactoryonline.com/typesafe/ivy-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]

[boot]
 directory: ${dsbt.boot.directory-${dsbt.global.base-${user.home}/.sbt}/boot/}

[ivy]
  ivy-home: ${user.home}/.ivy2
  checksums: ${sbt.checksums-sha1,md5}
  override-build-repos: ${sbt.override.build.repos-false}
""" format(scalaVersion, version))
    tprops -> "sbt/sbt.boot.properties"
  }
}