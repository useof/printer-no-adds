package com.noads.printer;

import android.app.Application;

import androidx.annotation.NonNull;

import com.noads.printer.model.PrinterRepository;
import com.noads.printer.print.PrintJobManager;
import com.noads.printer.util.CrashReporter;
import com.noads.printer.util.DocumentUtils;

/** Holds the process-wide singletons. */
public final class PrinterApp extends Application {

    private PrinterRepository printerRepository;
    private PrintJobManager printJobManager;

    @Override
    public void onCreate() {
        super.onCreate();
        // Înaintea oricărei inițializări, ca să prindă și crash-urile de la pornire.
        CrashReporter.install(this);
        if (CrashReporter.isCrashProcess(this)) {
            // Procesul care doar afișează raportul; dacă inițializarea de mai jos e
            // cea care a crăpat, rularea ei aici ar dărâma și ecranul de raport.
            return;
        }
        printerRepository = new PrinterRepository(this);
        printJobManager = new PrintJobManager();
        // Nothing in the cache survives a restart usefully: the PDFs there
        // belong to jobs that were already sent or abandoned.
        DocumentUtils.clearJobs(this);
    }

    @NonNull
    public PrinterRepository printers() {
        return printerRepository;
    }

    @NonNull
    public PrintJobManager jobs() {
        return printJobManager;
    }

    @NonNull
    public static PrinterApp from(@NonNull android.content.Context context) {
        return (PrinterApp) context.getApplicationContext();
    }
}
