package com.noads.printer;

import android.app.Application;

import androidx.annotation.NonNull;

import com.noads.printer.model.PrinterRepository;
import com.noads.printer.print.PrintJobManager;
import com.noads.printer.util.DocumentUtils;

/** Holds the process-wide singletons. */
public final class PrinterApp extends Application {

    private PrinterRepository printerRepository;
    private PrintJobManager printJobManager;

    @Override
    public void onCreate() {
        super.onCreate();
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
