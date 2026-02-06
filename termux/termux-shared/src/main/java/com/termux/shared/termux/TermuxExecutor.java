package com.termux.shared.termux;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TermuxExecutor {
    private static final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void executeInBackground(Runnable task) {
        backgroundExecutor.execute(task);
    }

    public static void executeOnMain(Runnable task) {
        mainHandler.post(task);
    }

    public static void execute(Runnable backgroundTask, Runnable mainThreadCallback) {
        backgroundExecutor.execute(() -> {
            try {
                backgroundTask.run();
            } finally {
                if (mainThreadCallback != null) {
                    mainHandler.post(mainThreadCallback);
                }
            }
        });
    }
}
