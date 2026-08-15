package com.noads.printer.print;

import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrinterId;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.noads.printer.PrinterApp;
import com.noads.printer.R;
import com.noads.printer.ipp.Ipp;
import com.noads.printer.ipp.JobOptions;
import com.noads.printer.model.Printer;
import com.noads.printer.model.PrinterCapabilities;
import com.noads.printer.raster.PdfToRaster;
import com.noads.printer.util.DocumentUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pluginul de printare al sistemului: face ca butonul „Print" din orice
 * aplicație să ajungă la imprimantele găsite de noi.
 *
 * <p>Se activează o singură dată din Settings → Printing. De acolo încolo,
 * Android se ocupă de dialog și de preview, iar noi primim documentul deja ca
 * PDF și îl trimitem prin aceeași conductă ca ecranul propriu: capabilități →
 * format (PDF sau raster) → Print-Job.
 *
 * <p>Toate metodele de mai jos sunt chemate pe firul principal, deci citirea
 * documentului și conversia se fac pe un executor separat.
 */
public final class NoAdsPrintService extends PrintService {

    private static final String TAG = "NoAdsPrintService";

    /** Ce trimite și AirPrint; vezi RASTER_DPI din ecranul de print. */
    private static final int RASTER_DPI = 300;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Job-urile în lucru, ca o cerere de anulare să le poată opri. Cheia e
     * PrintJobId, nu PrintJobInfo: info-ul e o copie care se schimbă la fiecare
     * actualizare de stare, deci nu se poate căuta după el.
     */
    private final Map<PrintJobId, PrintJobManager.Submission> submissions = new HashMap<>();

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new IppPrinterDiscoverySession(this);
    }

    @Override
    protected void onRequestCancelPrintJob(@NonNull PrintJob printJob) {
        PrintJobManager.Submission submission = submissions.remove(printJob.getId());
        if (submission == null) {
            printJob.cancel();
            return;
        }
        PrinterApp.from(this).jobs().cancel(submission, (cancelled, error) -> printJob.cancel());
    }

    @Override
    protected void onPrintJobQueued(@NonNull PrintJob printJob) {
        PrintJobInfo info = printJob.getInfo();
        Printer printer = printerFor(info.getPrinterId());
        if (printer == null) {
            printJob.fail(getString(R.string.error_printer_gone));
            return;
        }

        printJob.start();

        // Documentul vine ca descriptor de fișier; îl copiem în cache pentru că
        // fluxul de trimitere are nevoie de un File (mărime cunoscută, retrimis
        // la nevoie).
        executor.execute(() -> {
            File document;
            try {
                document = copyDocument(printJob);
            } catch (IOException e) {
                Log.w(TAG, "Could not read the queued document", e);
                mainThread(() -> printJob.fail(e.getMessage()));
                return;
            }
            mainThread(() -> submit(printJob, printer, document));
        });
    }

    private void submit(PrintJob printJob, Printer printer, File document) {
        PrintJobInfo info = printJob.getInfo();
        PrintJobManager jobs = PrinterApp.from(this).jobs();

        jobs.loadCapabilities(printer, new PrintJobManager.CapabilitiesCallback() {
            @Override
            public void onCapabilities(@NonNull PrinterCapabilities capabilities) {
                String format = capabilities.chooseFormatForPdf();
                if (format == null) {
                    printJob.fail(getString(R.string.unsupported_printer_message));
                    return;
                }
                send(printJob, printer, document, format, buildOptions(info, capabilities));
            }

            @Override
            public void onFailed(@NonNull Exception error) {
                // Imprimanta nu răspunde la Get-Printer-Attributes. PDF e singura
                // presupunere rezonabilă; dacă nu îl acceptă, jobul eșuează cu
                // eroarea ei, nu în tăcere.
                Log.w(TAG, "Get-Printer-Attributes failed; assuming PDF", error);
                send(printJob, printer, document, PrinterCapabilities.FORMAT_PDF,
                        buildOptions(info, null));
            }
        });
    }

    private void send(PrintJob printJob, Printer printer, File document,
                      String format, JobOptions options) {
        if (!PdfToRaster.isRasterFormat(format)) {
            submitDocument(printJob, printer, document, format, options);
            return;
        }

        // Ca în ecranul propriu: rasterul conține exact paginile cerute, deci
        // atributul IPP trebuie scos ca imprimanta să nu filtreze a doua oară.
        int[][] ranges = options.pageRanges;
        options.pageRanges = null;
        options.orientation = JobOptions.ORIENTATION_PORTRAIT;

        executor.execute(() -> {
            try {
                File raster = PdfToRaster.convert(document,
                        DocumentUtils.newJobFile(NoAdsPrintService.this, ".raster"),
                        format, RASTER_DPI,
                        JobOptions.COLOR_MONOCHROME.equals(options.colorMode),
                        options.media, ranges);
                mainThread(() ->
                        submitDocument(printJob, printer, raster, format, options));
            } catch (IOException e) {
                Log.w(TAG, "Rasterising failed", e);
                mainThread(() -> printJob.fail(e.getMessage()));
            }
        });
    }

    private void submitDocument(PrintJob printJob, Printer printer, File document,
                                String format, JobOptions options) {
        PrintJobManager jobs = PrinterApp.from(this).jobs();
        PrintJobManager.Submission submission = jobs.submit(printer, document, format, options,
                new PrintJobManager.JobListener() {
                    @Override
                    public void onStage(@NonNull PrintJobManager.Stage stage) {
                    }

                    @Override
                    public void onUploadProgress(int percent) {
                    }

                    @Override
                    public void onSubmitted(int jobId) {
                    }

                    @Override
                    public void onJobStateChanged(int jobState, @Nullable String reason) {
                        if (jobState == Ipp.JOB_STATE_COMPLETED) {
                            finish(printJob, true, null);
                        } else if (jobState == Ipp.JOB_STATE_ABORTED
                                || jobState == Ipp.JOB_STATE_CANCELED) {
                            finish(printJob, false, reason);
                        }
                    }

                    @Override
                    public void onFailed(@NonNull Exception error) {
                        finish(printJob, false, error.getMessage());
                    }
                });
        submissions.put(printJob.getId(), submission);
    }

    private void finish(PrintJob printJob, boolean completed, @Nullable String reason) {
        submissions.remove(printJob.getId());
        if (!printJob.isStarted()) {
            return;
        }
        if (completed) {
            printJob.complete();
        } else {
            printJob.fail(reason);
        }
    }

    /** Traduce setările alese în dialogul Android în atribute IPP. */
    private JobOptions buildOptions(PrintJobInfo info, @Nullable PrinterCapabilities caps) {
        PrintAttributes attributes = info.getAttributes();
        JobOptions options = new JobOptions();
        options.jobName = info.getLabel() == null
                ? getString(R.string.untitled_document)
                : info.getLabel().toString();
        options.copies = Math.max(1, info.getCopies());
        options.media = MediaMapping.toPwgName(attributes.getMediaSize());
        options.colorMode = attributes.getColorMode() == PrintAttributes.COLOR_MODE_MONOCHROME
                ? JobOptions.COLOR_MONOCHROME
                : JobOptions.COLOR_COLOR;
        options.sides = sidesFor(attributes.getDuplexMode());
        options.pageRanges = pageRangesFor(info);

        // Dacă imprimanta nu suportă ce a ales sistemul, mai bine lipsă decât
        // respins: un atribut nesuportat poate face imprimanta să refuze jobul.
        if (caps != null && !caps.sides.isEmpty() && !caps.sides.contains(options.sides)) {
            options.sides = JobOptions.SIDES_ONE_SIDED;
        }
        if (caps != null && !caps.media.isEmpty() && !caps.media.contains(options.media)) {
            options.media = caps.defaultMedia;
        }
        return options;
    }

    private static String sidesFor(int duplexMode) {
        switch (duplexMode) {
            case PrintAttributes.DUPLEX_MODE_LONG_EDGE:
                return JobOptions.SIDES_TWO_SIDED_LONG_EDGE;
            case PrintAttributes.DUPLEX_MODE_SHORT_EDGE:
                return JobOptions.SIDES_TWO_SIDED_SHORT_EDGE;
            default:
                return JobOptions.SIDES_ONE_SIDED;
        }
    }

    /**
     * Intervalele din dialogul Android sunt 0-based; IPP le numără de la 1.
     * {@code null} înseamnă tot documentul.
     */
    @Nullable
    private static int[][] pageRangesFor(PrintJobInfo info) {
        PageRange[] pages = info.getPages();
        if (pages == null || pages.length == 0) {
            return null;
        }
        for (PageRange range : pages) {
            if (PageRange.ALL_PAGES.equals(range)) {
                return null;
            }
        }
        int[][] ranges = new int[pages.length][2];
        for (int i = 0; i < pages.length; i++) {
            ranges[i][0] = pages[i].getStart() + 1;
            ranges[i][1] = pages[i].getEnd() + 1;
        }
        return ranges;
    }

    @NonNull
    private File copyDocument(PrintJob printJob) throws IOException {
        ParcelFileDescriptor descriptor = printJob.getDocument().getData();
        if (descriptor == null) {
            throw new IOException("The document is no longer available");
        }
        File destination = DocumentUtils.newJobFile(this, ".pdf");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        return destination;
    }

    @Nullable
    private Printer printerFor(@Nullable PrinterId printerId) {
        if (printerId == null) {
            return null;
        }
        return PrinterApp.from(this).printers().byUri(printerId.getLocalId());
    }

    private void mainThread(Runnable action) {
        mainHandler.post(action);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
