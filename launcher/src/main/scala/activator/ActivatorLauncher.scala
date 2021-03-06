package activator

import xsbti.{ AppMain, AppConfiguration }
import activator.properties.ActivatorProperties._
import java.io.File

/** Expose for SBT launcher support. */
class ActivatorLauncher extends AppMain {

  def run(configuration: AppConfiguration) =
    // TODO - Detect if we're running against a local project.
    try configuration.arguments match {
      case Array("ui") => RebootToUI(configuration)
      case Array("new") => Exit(ActivatorCli(configuration))
      case Array("shell") => RebootToSbt(configuration, useArguments = false)
      case _ if Sbt.looksLikeAProject(new File(".")) => RebootToSbt(configuration, useArguments = true)
      case _ => displayHelp(configuration)
    } catch {
      case e: Exception => generateErrorReport(e)
    }
  // Wrapper to return exit codes.
  case class Exit(val code: Int) extends xsbti.Exit

  def displayHelp(configuration: AppConfiguration) = {
    System.err.println(s"""| Warning:  Could not detect a local ${SCRIPT_NAME} project.
                          |
                          | If you'd like to run ${SCRIPT_NAME} in this directory, please:
                          |
                          | 1. Run the UI with `${SCRIPT_NAME} ui`
                          | 2. Create a project with `${SCRIPT_NAME} new`
                          | 3. Move into a ${SCRIPT_NAME} project directory and re-run ${SCRIPT_NAME}.
                          |""".stripMargin)
    Exit(1)
  }

  def generateErrorReport(e: Exception) = {
    // TODO - Make a real error report.
    e.printStackTrace()
    Exit(2)
  }
}
// Wrapper to return the UI application.
// TODO - Generate this via SBT code, so the hard-coded settings come
// from the build.
case class RebootToUI(configuration: AppConfiguration) extends xsbti.Reboot {
  val arguments = Array.empty[String]
  val baseDirectory = configuration.baseDirectory
  val scalaVersion = APP_SCALA_VERSION
  val app = ApplicationID(
    groupID = configuration.provider.id.groupID,
    // TODO - Pull this string from somewhere else so it's only configured in the build?
    name = "activator-ui",
    version = APP_VERSION,
    mainClass = "activator.UIMain")
}

// Wrapper to reboot into SBT.
// TODO - See if we can just read the configuration using the launcher or something...
case class RebootToSbt(configuration: AppConfiguration, useArguments: Boolean = false) extends xsbti.Reboot {

  val arguments = if (useArguments) configuration.arguments else Array.empty[String]
  val baseDirectory = configuration.baseDirectory
  val scalaVersion = SBT_SCALA_VERSION
  val app = ApplicationID(
    groupID = "org.scala-sbt",
    name = "sbt",
    version = SBT_VERSION,
    mainClass = "sbt.xMain",
    mainComponents = Array("xsbti", "extra"))
}

// Helper class to make using ApplicationID in xsbti easier.
case class ApplicationID(
  groupID: String,
  name: String,
  version: String,
  mainClass: String,
  mainComponents: Array[String] = Array("xsbti"),
  crossVersioned: Boolean = false,
  classpathExtra: Array[java.io.File] = Array.empty) extends xsbti.ApplicationID
