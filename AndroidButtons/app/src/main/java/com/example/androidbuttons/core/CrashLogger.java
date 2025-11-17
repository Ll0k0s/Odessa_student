package com.example.androidbuttons.core;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Registers a global uncaught-exception handler that mirrors stack traces into the
 * {@link ConsoleLogRepository}, so crashes are visible inside the in-app console.
 */
public final class CrashLogger {

    private static final String TAG = "CrashLogger";
    private static boolean installed;

    private CrashLogger() {
    }

    public static synchronized void install(ConsoleLogRepository consoleLogRepository) {
        if (installed) {
            return;
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String trace = buildStackTrace(thread, throwable);
            Log.e(TAG, "Uncaught exception on " + thread.getName(), throwable);
            if (consoleLogRepository != null) {
                consoleLogRepository.append(trace);
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
        installed = true;
    }

    private static String buildStackTrace(Thread thread, Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        printWriter.append("[#CRASH#]")
                .append(thread.getName())
                .append(':')
                .append(' ')
                .append(throwable.getClass().getSimpleName())
                .append(" - ")
                .append(String.valueOf(throwable.getMessage()))
                .append('\n');
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return stringWriter.toString();
    }
}
