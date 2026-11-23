package com.itsaky.androidide.terminal

import com.google.common.truth.Truth.assertThat
import com.termux.shared.termux.TermuxConstants
import com.termux.terminal.ShellIntegrationMark
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Runs the real agent-run.sh with bash and reads its output the way the Terminal does. */
class AgentRunnerTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private val runner by lazy { AgentRunner(tmp.root) }

	@Before
	fun setUp() {
		assumeTrue("needs bash", File(BASH).canExecute())
	}

	/** What a terminal makes of a run: the marks it saw, and the output recorded between them. */
	private class Run(
		val marks: List<ShellIntegrationMark>,
		val output: String,
	)

	/** What the runner writes to the terminal for command [id], before the terminal reads it. */
	private fun rawOutput(id: String): String {
		val process =
			ProcessBuilder(BASH, File(tmp.root, "agent-run").path, id)
				.apply { environment()["HOME"] = tmp.root.path }
				.redirectErrorStream(true)
				.start()
		val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
		process.waitFor()
		return output
	}

	private fun run(id: String): Run {
		// A pty turns \n into \r\n on the way out; the pipe here does not.
		val bytes = rawOutput(id).replace("\n", "\r\n").toByteArray()

		val marks = mutableListOf<ShellIntegrationMark>()
		lateinit var terminal: TerminalEmulator
		var recorder: CommandRecorder? = null
		val output =
			object : TerminalOutput() {
				override fun write(
					data: ByteArray,
					offset: Int,
					count: Int,
				) = Unit

				override fun titleChanged(
					oldTitle: String?,
					newTitle: String?,
				) = Unit

				override fun onCopyTextToClipboard(text: String?) = Unit

				override fun onPasteTextFromClipboard() = Unit

				override fun onBell() = Unit

				override fun onColorsChanged() = Unit

				override fun onShellIntegrationMark(mark: ShellIntegrationMark) {
					marks += mark
					when (mark.kind) {
						ShellIntegrationMark.Kind.OUTPUT_START -> recorder = CommandRecorder.sizedLike(terminal).also(terminal::setOutputTap)
						ShellIntegrationMark.Kind.COMMAND_FINISHED -> terminal.setOutputTap(null)
						else -> Unit
					}
				}
			}
		terminal = TerminalEmulator(output, 80, 24, 100, null)
		terminal.append(bytes, bytes.size)
		return Run(marks, recorder?.output().orEmpty())
	}

	@Test
	fun commandReportsItsOutputAndExitCode() {
		val id = runner.prepare("echo \"it's quoted\"; exit 3", null)

		val run = run(id)

		assertThat(run.marks.map { it.kind })
			.containsExactly(ShellIntegrationMark.Kind.OUTPUT_START, ShellIntegrationMark.Kind.COMMAND_FINISHED)
			.inOrder()
		assertThat(run.marks.map { it.options[AgentRunner.ID_OPTION] }).containsExactly(id, id)
		assertThat(run.marks.last().exitCode).isEqualTo(3)
		assertThat(run.output).isEqualTo("\$ echo \"it's quoted\"; exit 3\nit's quoted")
	}

	@Test
	fun controlCharactersInTheCommandAreEchoedVisiblyAndRunUnchanged() {
		val out = File(tmp.root, "out")
		// Printed raw, the OSC 52 would put "PWNED", base64-encoded here, on the clipboard.
		val id = runner.prepare(": '\u001b]52;c;UFdORUQ=\u0007'; printf '%s' 'a\rb\u009bc' > ${out.path}", null)

		val output = rawOutput(id)

		assertThat(output).doesNotContain("\u001b]52")
		assertThat(output).contains("\$ : '<U+001B>]52;c;UFdORUQ=<U+0007>'; printf '%s' 'a<U+000D>b<U+009B>c' > ${out.path}\n")
		assertThat(out.readText()).isEqualTo("a\rb\u009bc")
	}

	@Test
	fun syntaxErrorIsReportedWithoutItsControlCharactersAndNothingRuns() {
		val out = File(tmp.root, "out")
		// bash quotes the line of a syntax error in its message, OSC 52 and all.
		val id = runner.prepare("touch ${out.path}; echo '\u001b]52;c;UFdORUQ=\u0007' (", null)

		val output = rawOutput(id)

		// The runner's own marks are the only control sequences left.
		val text = output.replace(Regex("\u001b]133;[^\u0007]*\u0007"), "")
		assertThat(text).doesNotContain("\u001b")
		assertThat(text).doesNotContain("\u0007")
		assertThat(text).contains("?]52;c;UFdORUQ=?")
		assertThat(output).contains("\u001b]133;D;2;")
		assertThat(out.exists()).isFalse()
	}

	@Test
	fun commandThatTurnsOnExtglobBeforeUsingItRuns() {
		val run = run(runner.prepare("shopt -s extglob\ncase ab in +(a|b)) echo matched;; esac", null))

		assertThat(run.output.lines().last()).isEqualTo("matched")
		assertThat(run.marks.last().exitCode).isEqualTo(0)
	}

	@Test
	fun commandTooLongForOneExecArgumentRuns() {
		val out = File(tmp.root, "out")
		// exec refuses an argument over 32 pages: 128 KiB at 4 KiB pages, 512 KiB at 16 KiB.
		val id = runner.prepare(": '${"a".repeat(1 shl 20)}'; touch ${out.path}", null)

		val output = rawOutput(id)

		// Only the exit code, as a failure message quoting the output would hold the whole command.
		assertThat(Regex("\u001b]133;D;(\\d+);").find(output)?.groupValues?.get(1)).isEqualTo("0")
		assertThat(out.exists()).isTrue()
	}

	@Test
	fun carriageReturnInTheCommandCannotRewriteItsEcho() {
		// Printed raw, the CR would return to the start of the line, and the echo would show only
		// what follows it.
		val run = run(runner.prepare(": 'rm -rf ~'\r: 'harmless'", null))

		assertThat(run.output.lines().first()).isEqualTo("\$ : 'rm -rf ~'<U+000D>: 'harmless'")
		assertThat(run.marks.last().exitCode).isEqualTo(0)
	}

	@Test
	fun displayedCommandMakesOnlyControlCharactersVisible() {
		assertThat(AgentRunner.displayed("a\u0000b\u001bc\u007fd\u0080e\u009ff\u00a0g\th\ni\u00e9\u2028"))
			.isEqualTo("a<U+0000>b<U+001B>c<U+007F>d<U+0080>e<U+009F>f\u00a0g\th\ni\u00e9\u2028")
	}

	@Test
	fun commandRunsInItsWorkingDirectory() {
		val dir = tmp.newFolder("project")

		val run = run(runner.prepare("pwd -P", dir.path))

		assertThat(run.output.lines().last()).isEqualTo(dir.canonicalPath)
		assertThat(run.marks.last().exitCode).isEqualTo(0)
	}

	@Test
	fun missingWorkingDirectoryEndsWithAnErrorCode() {
		val run = run(runner.prepare("pwd", File(tmp.root, "gone").path))

		assertThat(run.marks.last().exitCode).isEqualTo(1)
	}

	@Test
	fun missingCommandStillReportsItsEnd() {
		// Writes the runner; the command run below is another one, never prepared.
		runner.prepare("true", null)

		val run = run("never-prepared")

		assertThat(run.marks.map { it.kind }).containsExactly(ShellIntegrationMark.Kind.COMMAND_FINISHED)
		assertThat(run.marks.single().exitCode).isEqualTo(1)
	}

	@Test
	fun execInTheCommandStillReportsItsEnd() {
		val run = run(runner.prepare("exec sh -c 'exit 4'", null))

		assertThat(run.marks.last().kind).isEqualTo(ShellIntegrationMark.Kind.COMMAND_FINISHED)
		assertThat(run.marks.last().exitCode).isEqualTo(4)
	}

	@Test
	fun clearedExitTrapInTheCommandStillReportsItsEnd() {
		val run = run(runner.prepare("trap - EXIT; exit 6", null))

		assertThat(run.marks.last().kind).isEqualTo(ShellIntegrationMark.Kind.COMMAND_FINISHED)
		assertThat(run.marks.last().exitCode).isEqualTo(6)
	}

	@Test
	fun marksGiveTheRunnersPid() {
		val run = run(runner.prepare("true", null))

		assertThat(run.marks.map { it.options[AgentRunner.PID_OPTION]?.toIntOrNull() }).doesNotContain(null)
	}

	@Test
	fun preparingAgainLeavesAnUpToDateRunnerUntouched() {
		runner.prepare("true", null)
		val installed = inode(File(tmp.root, "agent-run"))

		runner.prepare("true", null)

		assertThat(inode(File(tmp.root, "agent-run"))).isEqualTo(installed)
	}

	@Test
	fun staleRunnerIsReplacedWithoutCuttingShortARunReadingIt() {
		val script = File(tmp.root, "agent-run").apply { writeText("echo old runner\n") }

		script.inputStream().use { reading ->
			runner.prepare("true", null)

			// A bash already reading the old runner reads it to its end.
			assertThat(reading.readBytes().toString(Charsets.UTF_8)).isEqualTo("echo old runner\n")
		}
		assertThat(script.readText()).contains("cogo-id")
	}

	@Test
	fun discardRemovesTheCommandFiles() {
		val id = runner.prepare("true", null)

		runner.discard(id)

		assertThat(tmp.root.list()).asList().containsExactly("agent-run")
	}

	@Test
	fun commandFilesAreRemovedOnceRead() {
		val id = runner.prepare("true", null)

		run(id)

		assertThat(tmp.root.list()).asList().containsExactly("agent-run")
	}

	@Test
	fun withdrawnCommandNeverRuns() {
		val id = runner.prepare("echo ran", null)

		assertThat(runner.withdraw(id)).isTrue()
		val run = run(id)

		assertThat(run.marks.map { it.kind }).containsExactly(ShellIntegrationMark.Kind.COMMAND_FINISHED)
		assertThat(run.marks.single().exitCode).isEqualTo(1)
		assertThat(tmp.root.list()).asList().containsExactly("agent-run")
	}

	@Test
	fun commandTheRunnerTookCannotBeWithdrawn() {
		val id = runner.prepare("true", null)
		run(id)

		assertThat(runner.withdraw(id)).isFalse()
	}

	@Test
	fun typedRunLineClearsThePromptAndRunsTheCommand() {
		assertThat(AgentRunner(File("/tmp")).typedRunLine("3f2a"))
			.isEqualTo("\u0015bash \"/tmp/agent-run\" 3f2a\r")
	}

	@Test
	fun termuxRunnerIsRunByItsAbsolutePathNotTheSessionsTmpdir() {
		// A profile may set its own TMPDIR; the runner is not there.
		val dir = TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH

		assertThat(AgentRunner.termux.typedRunLine("3f2a")).isEqualTo("\u0015bash \"$dir/agent-run\" 3f2a\r")
		assertThat(AgentRunner.termux.firstRunArguments("3f2a")[2]).contains("bash \"$dir/agent-run\"")
	}

	@Test
	fun firstCommandOfASessionSeesTheLoginProfile() {
		// What a typed command inherits from the session's login shell.
		File(tmp.root, ".bash_profile").writeText("export FROM_PROFILE=yes\n")
		val id = runner.prepare("echo \"profile: \$FROM_PROFILE\"", null)

		val process =
			ProcessBuilder(listOf(BASH) + runner.firstRunArguments(id))
				.apply {
					environment()["HOME"] = tmp.root.path
					environment().remove("FROM_PROFILE")
				}.redirectErrorStream(true)
				.start()
		// The login shell the session becomes afterwards reads end-of-input and exits.
		process.outputStream.close()
		val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
		process.waitFor()

		assertThat(output).contains("profile: yes")
	}

	private fun inode(file: File): Any = Files.getAttribute(file.toPath(), "unix:ino")

	private companion object {
		const val BASH = "/bin/bash"
	}
}
