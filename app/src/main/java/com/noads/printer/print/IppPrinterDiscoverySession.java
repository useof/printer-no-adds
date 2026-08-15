package com.noads.printer.print;

import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.noads.printer.PrinterApp;
import com.noads.printer.discovery.PrinterDiscovery;
import com.noads.printer.model.Printer;
import com.noads.printer.model.PrinterCapabilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lista de imprimante pe care o vede dialogul de print al sistemului.
 *
 * <p>Sursele sunt aceleași ca în aplicație: mDNS plus imprimantele adăugate
 * manual. Capabilitățile se citesc din imprimantă abia când sistemul începe să
 * urmărească una anume — dialogul nu poate activa butonul de print fără ele.
 */
final class IppPrinterDiscoverySession extends PrinterDiscoverySession {

    private static final String TAG = "IppDiscoverySession";

    /** Rezoluția pe care o raportăm; e și cea la care rasterizăm. */
    private static final int REPORTED_DPI = 300;

    private final PrintService service;
    private final PrinterDiscovery discovery;

    /** uri -> imprimanta, ca un PrinterId să poată fi rezolvat înapoi. */
    private final Map<String, Printer> known = new LinkedHashMap<>();

    IppPrinterDiscoverySession(@NonNull PrintService service) {
        this.service = service;
        this.discovery = new PrinterDiscovery(service);
        this.discovery.setListener(new PrinterDiscovery.Listener() {
            @Override
            public void onPrinterFound(@NonNull Printer printer) {
                PrinterApp.from(service).printers().onDiscovered(printer);
                addPrinters(Collections.singletonList(basicInfo(printer)));
                known.put(printer.uri, printer);
            }

            @Override
            public void onPrinterLost(@NonNull String serviceName) {
                // Lăsăm intrarea în listă: numele serviciului mDNS nu se poate
                // mapa înapoi pe URI aici, iar o imprimantă care tocmai a tăcut
                // răspunde de obicei imediat ce e aleasă.
            }

            @Override
            public void onDiscoveryFailed(@NonNull String message) {
                Log.w(TAG, "mDNS discovery failed: " + message);
            }
        });
    }

    @Override
    public void onStartPrinterDiscovery(@NonNull List<PrinterId> priorityList) {
        List<PrinterInfo> initial = new ArrayList<>();
        for (Printer printer : PrinterApp.from(service).printers().all()) {
            known.put(printer.uri, printer);
            initial.add(basicInfo(printer));
        }
        if (!initial.isEmpty()) {
            addPrinters(initial);
        }
        discovery.start();
    }

    @Override
    public void onStopPrinterDiscovery() {
        discovery.stop();
    }

    @Override
    public void onValidatePrinters(@NonNull List<PrinterId> printerIds) {
        // Nimic de validat separat: o imprimantă care nu răspunde se vede la
        // citirea capabilităților, mai jos.
    }

    @Override
    public void onStartPrinterStateTracking(@NonNull PrinterId printerId) {
        Printer printer = known.get(printerId.getLocalId());
        if (printer == null) {
            return;
        }
        PrinterApp.from(service).jobs().loadCapabilities(printer,
                new PrintJobManager.CapabilitiesCallback() {
                    @Override
                    public void onCapabilities(@NonNull PrinterCapabilities capabilities) {
                        PrinterInfo info = detailedInfo(printerId, printer, capabilities);
                        addPrinters(Collections.singletonList(info));
                    }

                    @Override
                    public void onFailed(@NonNull Exception error) {
                        // Fără capabilități, dialogul nu ar putea printa deloc.
                        // Raportăm minimul rezonabil — A4, alb-negru — iar dacă
                        // imprimanta vrea altceva, jobul eșuează cu eroarea ei.
                        Log.w(TAG, "Falling back to default capabilities", error);
                        addPrinters(Collections.singletonList(
                                detailedInfo(printerId, printer, null)));
                    }
                });
    }

    @Override
    public void onStopPrinterStateTracking(@NonNull PrinterId printerId) {
    }

    @Override
    public void onDestroy() {
        discovery.setListener(null);
        discovery.stop();
    }

    /** Intrare fără capabilități: apare în listă, dar încă nu se poate printa. */
    private PrinterInfo basicInfo(Printer printer) {
        return new PrinterInfo.Builder(
                service.generatePrinterId(printer.uri), printer.name, PrinterInfo.STATUS_IDLE)
                .setDescription(printer.makeAndModel != null
                        ? printer.makeAndModel
                        : printer.host)
                .build();
    }

    private PrinterInfo detailedInfo(PrinterId printerId, Printer printer,
                                     @Nullable PrinterCapabilities capabilities) {

        PrinterCapabilitiesInfo.Builder builder = new PrinterCapabilitiesInfo.Builder(printerId);

        boolean addedMedia = false;
        String defaultMedia = capabilities == null ? null : capabilities.defaultMedia;
        if (capabilities != null) {
            for (String pwgName : capabilities.media) {
                PrintAttributes.MediaSize size = MediaMapping.toMediaSize(pwgName);
                if (size == null) {
                    continue;
                }
                builder.addMediaSize(size, !addedMedia && pwgName.equals(defaultMedia));
                addedMedia = true;
            }
        }
        if (!addedMedia) {
            builder.addMediaSize(PrintAttributes.MediaSize.ISO_A4, true);
        }

        builder.addResolution(new PrintAttributes.Resolution(
                "ipp-" + REPORTED_DPI, REPORTED_DPI + " dpi", REPORTED_DPI, REPORTED_DPI), true);

        boolean colour = capabilities != null && capabilities.colorSupported;
        builder.setColorModes(
                colour
                        ? PrintAttributes.COLOR_MODE_COLOR | PrintAttributes.COLOR_MODE_MONOCHROME
                        : PrintAttributes.COLOR_MODE_MONOCHROME,
                colour ? PrintAttributes.COLOR_MODE_COLOR : PrintAttributes.COLOR_MODE_MONOCHROME);

        int duplexModes = PrintAttributes.DUPLEX_MODE_NONE;
        if (capabilities != null) {
            if (capabilities.sides.contains("two-sided-long-edge")) {
                duplexModes |= PrintAttributes.DUPLEX_MODE_LONG_EDGE;
            }
            if (capabilities.sides.contains("two-sided-short-edge")) {
                duplexModes |= PrintAttributes.DUPLEX_MODE_SHORT_EDGE;
            }
        }
        builder.setDuplexModes(duplexModes, PrintAttributes.DUPLEX_MODE_NONE);

        // Marginile le decide imprimanta; noi trimitem pagina întreagă.
        builder.setMinMargins(PrintAttributes.Margins.NO_MARGINS);

        int status = capabilities != null && !capabilities.acceptingJobs
                ? PrinterInfo.STATUS_UNAVAILABLE
                : PrinterInfo.STATUS_IDLE;

        return new PrinterInfo.Builder(printerId, printer.name, status)
                .setDescription(capabilities != null && capabilities.makeAndModel != null
                        ? capabilities.makeAndModel
                        : printer.host)
                .setCapabilities(builder.build())
                .build();
    }
}
