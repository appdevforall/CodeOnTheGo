package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** "ESC ] 133" shell-integration marks, and the output tap that records a command's bytes. */
public class ShellIntegrationTest extends TerminalTestCase {

	public void testFinishedMarkCarriesExitCodeAndOptions() {
		withTerminalSized(10, 3);
		enterString("\033]133;D;130;cogo-id=3f2a\007");

		assertEquals(
			Collections.singletonList(new ShellIntegrationMark(
				ShellIntegrationMark.Kind.COMMAND_FINISHED, 130, Collections.singletonMap("cogo-id", "3f2a"))),
			mOutput.shellIntegrationMarks);
	}

	public void testOutputStartMarkTerminatedByStringTerminator() {
		withTerminalSized(10, 3);
		enterString("\033]133;C\033\\");

		assertEquals(ShellIntegrationMark.Kind.OUTPUT_START, mOutput.shellIntegrationMarks.get(0).kind);
	}

	public void testMarkIsNotShown() {
		withTerminalSized(10, 3).enterString("a\033]133;C\007b").assertLinesAre("ab        ", "          ", "          ");
	}

	public void testUnknownKindIsIgnored() {
		withTerminalSized(10, 3);
		enterString("\033]133;Z\007");

		assertTrue(mOutput.shellIntegrationMarks.isEmpty());
	}

	public void testParseWithoutExitCode() {
		ShellIntegrationMark mark = ShellIntegrationMark.parse("D;cogo-id=x");

		assertNull(mark.exitCode);
		assertEquals("x", mark.options.get("cogo-id"));
	}

	public void testParseIgnoresAMalformedExitCode() {
		assertNull(ShellIntegrationMark.parse("D;oops").exitCode);
	}

	public void testTapReceivesTheBytesBetweenMarksSetFromTheMarkCallback() {
		ByteArrayOutputStream tapped = new ByteArrayOutputStream();
		TerminalEmulator.OutputTap tap = tapped::write;
		MockTerminalOutput output = new MockTerminalOutput() {
			@Override
			public void onShellIntegrationMark(ShellIntegrationMark mark) {
				mTerminal.setOutputTap(mark.kind == ShellIntegrationMark.Kind.OUTPUT_START ? tap : null);
			}
		};
		mOutput = output;
		mTerminal = new TerminalEmulator(output, 20, 3, 100, null);

		byte[] bytes = "before\033]133;C\007inside\033]133;D;0\007after".getBytes(StandardCharsets.UTF_8);
		mTerminal.append(bytes, bytes.length);

		// The closing mark reaches the tap too: it is cleared while that mark's last byte is processed.
		assertEquals("inside\033]133;D;0\007", new String(tapped.toByteArray(), StandardCharsets.UTF_8));
	}

	public void testResizeReachesTheTap() {
		int[] size = new int[2];
		withTerminalSized(10, 3);
		mTerminal.setOutputTap(new TerminalEmulator.OutputTap() {
			@Override
			public void onByte(byte b) {
			}

			@Override
			public void onResize(int columns, int rows) {
				size[0] = columns;
				size[1] = rows;
			}
		});

		mTerminal.resize(30, 5);

		assertEquals(30, size[0]);
		assertEquals(5, size[1]);
	}
}
