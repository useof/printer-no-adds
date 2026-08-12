package com.noads.printer.print;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.noads.printer.ipp.Ipp;
import com.noads.printer.ipp.IppClient;
import com.noads.printer.ipp.IppException;
import com.noads.printer.ipp.IppResponse;
import com.noads.printer.ipp.JobOptions;
import com.noads.printer.model.Printer;
import com.noads.printer.model.PrinterCapabilities;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs IPP work off the main thread and reports back on it. */
public final class PrintJobManager {

    private static final String TAG = "PrintJobManager";

    /** How long to keep asking the printer what happened to a submitted job. */
    private static final long JOB_POLL_INTERVAL_MS = 2_000;
    private static final long JOB_POLL_TIMEOUT_MS = 120_000;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Progress of one submission, delivered on the main thread. */
    public interface JobListener {
        /** A short description of the current step, e.g. "Uploading". */
        void onStage(@NonNull Stage stage);

        /** 0-100 while the document uploads. */
        void onUploadProgress(int percent);

        /** The printer accepted the job. {@code jobId} is -1 if it reported none. */
        void onSubmitted(int jobId);

        /** Terminal job state from the printer, once polling resolves it. */
        void onJobStateChanged(int jobState, @Nullable String reason);

        void onFailed(@NonNull Exception error);
    }

    public enum Stage {
        CONTACTING_PRINTER,
        VALIDATING,
        UPLOADING,
        WAITING_FOR_PRINTER,
        DONE
    }

    public interface CapabilitiesCallback {
        void onCapabilities(@NonNull PrinterCapabilities capabilities);

        void onFailed(@NonNull Exception error);
    }

    /** A submission in flight, so the UI can cancel it. */
    public static final class Submission {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile int jobId = -1;
        private volatile IppClient client;

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

    }

    /**
     * Stops watching {@code submission} and, if the printer already took the
     * job, asks it to cancel. Runs off the main thread; {@code onResult} is
     * called back on it.
     */
    @MainThread
    public void cancel(@NonNull Submission submission, @NonNull CancelCallback onResult) {
        submission.cancel();
        IppClient client = submission.client;
        int jobId = submission.jobId;
        if (client == null || jobId <= 0) {
            // Nothing reached the printer yet, so there is nothing to recall.
            onResult.onCancelled(true, null);
            return;
        }
        executor.execute(() -> {
            try {
                client.cancelJob(jobId);
                mainHandler.post(() -> onResult.onCancelled(true, null));
            } catch (Exception e) {
                Log.w(TAG, "Cancel-Job failed", e);
                mainHandler.post(() -> onResult.onCancelled(false, e));
            }
        });
    }

    public interface CancelCallback {
        /**
         * @param error why the printer refused, when {@code cancelled} is false
         */
        void onCancelled(boolean cancelled, @Nullable Exception error);
    }

    /* ------------------------------------------------------------------ */
    /* Capabilities                                                       */
    /* ------------------------------------------------------------------ */

    @AnyThread
    public void loadCapabilities(@NonNull Printer printer, @NonNull CapabilitiesCallback callback) {
        executor.execute(() -> {
            try {
                IppClient client = new IppClient(printer.uri);
                IppResponse response = client.getPrinterAttributes();
                PrinterCapabilities capabilities = PrinterCapabilities.from(response);
                mainHandler.post(() -> callback.onCapabilities(capabilities));
            } catch (Exception e) {
                Log.w(TAG, "Get-Printer-Attributes failed for " + printer.uri, e);
                mainHandler.post(() -> callback.onFailed(e));
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Submitting                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Uploads {@code document} to {@code printer} and then polls until the job
     * reaches a terminal state or {@link #JOB_POLL_TIMEOUT_MS} elapses.
     */
    @MainThread
    public Submission submit(@NonNull Printer printer,
                             @NonNull File document,
                             @NonNull String documentFormat,
                             @NonNull JobOptions options,
                             @NonNull JobListener listener) {

        Submission submission = new Submission();

        executor.execute(() -> {
            try {
                post(listener, () -> listener.onStage(Stage.CONTACTING_PRINTER));
                IppClient client = new IppClient(printer.uri);
                submission.client = client;

                if (submission.isCancelled()) {
                    return;
                }

                post(listener, () -> listener.onStage(Stage.VALIDATING));
                try {
                    client.validateJob(documentFormat, options);
                } catch (IppException e) {
                    // Some printers do not implement Validate-Job and answer
                    // server-error-operation-not-supported. That is not a
                    // reason to refuse to print.
                    if (e.statusCode != 0x0501) {
                        throw e;
                    }
                    Log.d(TAG, "Validate-Job unsupported; sending anyway");
                }

                if (submission.isCancelled()) {
                    return;
                }

                post(listener, () -> listener.onStage(Stage.UPLOADING));
                final int[] lastPercent = {-1};
                int jobId = client.printJob(document, documentFormat, options,
                        (sent, total) -> {
                            if (total <= 0) {
                                return;
                            }
                            int percent = (int) (sent * 100 / total);
                            if (percent != lastPercent[0]) {
                                lastPercent[0] = percent;
                                post(listener, () -> listener.onUploadProgress(percent));
                            }
                        });

                submission.jobId = jobId;
                post(listener, () -> listener.onSubmitted(jobId));

                if (jobId <= 0) {
                    // Nothing to poll; treat acceptance as success.
                    post(listener, () -> {
                        listener.onJobStateChanged(Ipp.JOB_STATE_COMPLETED, null);
                        listener.onStage(Stage.DONE);
                    });
                    return;
                }

                post(listener, () -> listener.onStage(Stage.WAITING_FOR_PRINTER));
                pollJob(client, jobId, submission, listener);

            } catch (Exception e) {
                Log.w(TAG, "Print job failed", e);
                post(listener, () -> listener.onFailed(e));
            }
        });

        return submission;
    }

    private void pollJob(IppClient client,
                         int jobId,
                         Submission submission,
                         JobListener listener) {
        long deadline = System.currentTimeMillis() + JOB_POLL_TIMEOUT_MS;
        int lastState = -1;

        while (System.currentTimeMillis() < deadline) {
            if (submission.isCancelled()) {
                return;
            }
            try {
                IppResponse response = client.getJobAttributes(jobId);
                IppResponse.Group group = response.group(Ipp.TAG_JOB_ATTRIBUTES);
                int state = group != null
                        ? group.getInt("job-state", -1)
                        : response.getInt("job-state", -1);
                String reason = response.getString("job-state-reasons");

                if (state != lastState && state > 0) {
                    lastState = state;
                    final int reported = state;
                    post(listener, () -> listener.onJobStateChanged(reported, reason));
                }

                if (Ipp.isJobFinished(state)) {
                    post(listener, () -> listener.onStage(Stage.DONE));
                    return;
                }
            } catch (IOException e) {
                // A printer that drops the job record once it finishes answers
                // client-error-not-found. Nothing left to watch, so stop.
                Log.d(TAG, "Job polling ended: " + e.getMessage());
                post(listener, () -> {
                    listener.onJobStateChanged(Ipp.JOB_STATE_COMPLETED, null);
                    listener.onStage(Stage.DONE);
                });
                return;
            }

            try {
                Thread.sleep(JOB_POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // Timed out watching; the job is still queued as far as we know.
        post(listener, () -> listener.onStage(Stage.DONE));
    }

    private void post(JobListener listener, Runnable action) {
        mainHandler.post(action);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
