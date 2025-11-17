package com.example.androidbuttons.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Registers a global uncaught-exception handler that mirrors stack traces into the
 * {@link ConsoleLogRepository}, so crashes are visible inside the in-app console.
 */
public final class CrashLogger {

    private static final String TAG = "CrashLogger";
    private static final String CRASH_FILE_NAME = "last-crash.log";
    private static boolean installed;
    private static File crashFile;
    private static ConsoleLogRepository consoleRepo;

    private CrashLogger() {
    }

    public static synchronized void install(Context context, ConsoleLogRepository consoleLogRepository) {
        if (installed) {
            return;
        }
        Context appContext = context.getApplicationContext();
        crashFile = new File(appContext.getFilesDir(), CRASH_FILE_NAME);
        consoleRepo = consoleLogRepository;
        replayPersistedCrash();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String trace = buildStackTrace(thread, throwable);
            Log.e(TAG, "Uncaught exception on " + thread.getName(), throwable);
            appendToConsole(trace);
            persistCrash(trace);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
        installed = true;
    }

    private static void appendToConsole(String trace) {
        if (consoleRepo != null) {
            consoleRepo.append(trace);
        }
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

    private static void persistCrash(String trace) {
        if (crashFile == null) {
            return;
        }
        try (FileOutputStream fos = new FileOutputStream(crashFile, false)) {
            fos.write(trace.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Failed to persist crash log", e);
        }
    }

    private static void replayPersistedCrash() {
        if (crashFile == null || !crashFile.exists()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(crashFile)) {
            byte[] data = new byte[(int) crashFile.length()];
            int read = fis.read(data);
            if (read > 0) {
                String trace = new String(data, 0, read, StandardCharsets.UTF_8);
                appendToConsole(trace);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read persisted crash log", e);
        } finally {
            // Удаляем файл, чтобы не повторять сообщение на следующем запуске
            boolean deleted = crashFile.delete();
            if (!deleted) {
                Log.w(TAG, "Could not delete crash log file " + crashFile.getAbsolutePath());
            }
        }
    }
}
