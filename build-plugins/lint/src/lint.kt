package dev.jamesrich.sofaking.lint

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString

/** Fails when a Kotlin file under [sourceDir] breaks the style in the repository's `.editorconfig`. */
@TaskAction
fun ktlintCheck(@Input sourceDir: Path, @Input ktlintClasspath: Classpath) =
    ktlint(sourceDir, ktlintClasspath, format = false)

/** Rewrites the Kotlin files under [sourceDir] in that style; it always runs, since its input is its output. */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun ktlintFormat(@Input sourceDir: Path, @Input ktlintClasspath: Classpath) =
    ktlint(sourceDir, ktlintClasspath, format = true)

/** Fails when detekt finds an issue under [sourceDir] with the repository's rules on top of detekt's defaults. */
@TaskAction
fun detektCheck(@Input sourceDir: Path, @Input configFile: Path, @Input detektClasspath: Classpath) {
    if (!sourceDir.isDirectory()) return
    val arguments = listOf(
        "--input",
        sourceDir.pathString,
        "--config",
        configFile.pathString,
        "--build-upon-default-config",
        "--parallel",
    )
    runTool("detekt", DETEKT_MAIN_CLASS, detektClasspath, sourceDir, arguments)
}

private fun ktlint(sourceDir: Path, classpath: Classpath, format: Boolean) {
    if (!sourceDir.isDirectory()) return
    // Run from the module with a pattern relative to it, so ktlint reports paths that open from there.
    val pattern = "${sourceDir.fileName.pathString}/**/*.kt"
    val arguments = listOfNotNull("--relative", "--format".takeIf { format }, pattern)
    runTool("ktlint", KTLINT_MAIN_CLASS, classpath, sourceDir.parent, arguments)
}

/** Both tools ship a command line and no API the plugin could call, so each runs in a JVM of its own. */
private fun runTool(name: String, mainClass: String, classpath: Classpath, workingDir: Path, arguments: List<String>) {
    val command = buildList {
        add(ProcessHandle.current().info().command().orElse("java"))
        // Both embed the Kotlin compiler, which reads java.lang reflectively.
        add("--add-opens=java.base/java.lang=ALL-UNNAMED")
        add("-cp")
        add(classpath.resolvedFiles.joinToString(File.pathSeparator))
        add(mainClass)
        addAll(arguments)
    }
    val exitCode = ProcessBuilder(command).directory(workingDir.toFile()).inheritIO().start().waitFor()
    check(exitCode == 0) { "$name exited with code $exitCode; its findings are above." }
}

private const val KTLINT_MAIN_CLASS = "com.pinterest.ktlint.Main"
private const val DETEKT_MAIN_CLASS = "dev.detekt.cli.Main"
