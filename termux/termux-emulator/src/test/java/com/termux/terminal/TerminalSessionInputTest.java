package com.termux.terminal;

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;

public class TerminalSessionInputTest extends TestCase {

    /** Input must not block the (main) caller when the process is not reading stdin. */
    public void testWriteDoesNotBlockWhenProcessIsNotReading() throws Exception {
        TerminalSession session = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 100, null);
        session.mShellPid = 1; // Running, but no writer thread drains the queue.

        byte[] input = new byte[3 * 4096];
        for (int i = 0; i < input.length; i++) input[i] = (byte) i;

        Thread writer = new Thread(() -> {
            session.write(input, 0, 4096);
            session.write(input, 4096, input.length - 4096);
        });
        writer.setDaemon(true);
        writer.start();
        writer.join(2000);
        assertFalse("write() blocked on a full input queue", writer.isAlive());

        ByteArrayOutputStream queued = new ByteArrayOutputStream();
        for (byte[] chunk : session.mTerminalToProcessIOQueue) queued.write(chunk);
        assertTrue(java.util.Arrays.equals(input, queued.toByteArray()));
    }
}
