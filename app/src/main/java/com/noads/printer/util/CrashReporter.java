package com.noads.printer.util;

import android.content.Context;
import android.content.Intent;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.noads.printer.ui.CrashActivity;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Shows the stack trace of an uncaught exception on the device instead of just
 * letting the system kill the process with "the app has an error".
 *
 * <p>Without a PC there is no way to read logcat, so a crash on someone else's
 * phone is otherwise unreportable: all they can pass on is "it closes". The
 * trace is handed to {@link CrashActivity}, which runs in its OWN process
 * ({@code :crash} in the manifest) so it survives the death of the process that
 * crashed.
 *
 * <p>This is deliberately part of the app rather than a debug-only extra: the
 * published build is the one people actually run into problems with.
 */
public final class CrashReporter {

    private static final String CRASH_PROCESS_SUFFIX = ":crash";

    private CrashReporter() {
    }

    /**
     * Installs the handler. Call this as the very first thing in
     * {@code Application.onCreate()} so it also covers the rest of start-up.
     */
    public static void install(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                Intent intent = CrashActivity.intentFor(appContext, describe(thread, error));
                appContext.startActivity(intent);
            } catch (Throwable ignored) {
                // Showing the report is best-effort; never mask the original crash.
            }

            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    /**
     * True in the separate process that renders the report. {@code PrinterApp}
     * skips its start-up work there — if that work is what crashed, running it
     * again would take the report screen down with it.
     */
    public static boolean isCrashProcess(@NonNull Context context) {
        String name = processName();
        return name != null && name.endsWith(CRASH_PROCESS_SUFFIX);
    }

    @NonNull
    private static String describe(@NonNull Thread thread, @NonNull Throwable error) {
        StringWriter writer = new StringWriter();
        PrintWriter out = new PrintWriter(writer);
        out.println("Thread: " + thread.getName());
        out.println("Android: " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        out.println("Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        out.println();
        error.printStackTrace(out);
        out.flush();
        return writer.toString();
    }

    /**
     * {@code Application.getProcessName()} only exists from API 28, and this app
     * runs from 24, so read it out of {@code /proc/self/cmdline}.
     */
    @Nullable
    private static String processName() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            // The file is a NUL-separated argv; the process name is the first entry.
            int end = line.indexOf('\0');
            return (end >= 0 ? line.substring(0, end) : line).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
