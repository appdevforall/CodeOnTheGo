package com.termux.app.terminal;

import com.itsaky.androidide.terminal.TerminalCommandRequests;
import android.app.Service;
import androidx.annotation.NonNull;
import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import java.io.Closeable;

/** The {@link TerminalSessionClient} implementation that may require a {@link Service} for its interface methods. */
public class TermuxTerminalSessionServiceClient extends TermuxTerminalSessionClientBase implements
    Closeable {

    private static final String LOG_TAG = "TermuxTerminalSessionServiceClient";

    private TermuxService mService;

    public TermuxTerminalSessionServiceClient(TermuxService service) {
        this.mService = service;
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        // The activity is gone; its client is not there to report a plugin command's exit.
        TerminalCommandRequests.shared.onSessionFinished(finishedSession);
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }

    @Override
    public void close() {
        mService = null;
    }
}
