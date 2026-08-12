package com.noads.printer.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.noads.printer.ipp.Ipp;
import com.noads.printer.ipp.IppAttribute;
import com.noads.printer.ipp.IppResponse;
import com.noads.printer.ipp.IppValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What one printer says it can do, distilled from Get-Printer-Attributes. */
public final class PrinterCapabilities {

    /** Formats this app can hand to a printer, best first. */
    public static final String FORMAT_PDF = "application/pdf";
    public static final String FORMAT_JPEG = "image/jpeg";
    public static final String FORMAT_POSTSCRIPT = "application/postscript";
    public static final String FORMAT_OCTET_STREAM = "application/octet-stream";

    @Nullable public final String name;
    @Nullable public final String makeAndModel;
    @Nullable public final String location;
    @Nullable public final String stateMessage;

    public final int state;
    public final boolean acceptingJobs;
    public final boolean colorSupported;
    public final int maxCopies;

    @NonNull public final List<String> stateReasons;
    @NonNull public final List<String> documentFormats;
    @NonNull public final List<String> media;
    @NonNull public final List<String> mediaSources;
    @NonNull public final List<String> sides;
    @NonNull public final List<String> colorModes;
    @NonNull public final List<Integer> qualities;

    @Nullable public final String defaultMedia;
    @Nullable public final String defaultSides;
    @Nullable public final String defaultColorMode;

    @NonNull public final List<Supply> supplies;

    /** One consumable (toner/ink) level reported through the marker-* attributes. */
    public static final class Supply {
        public final String name;
        /** 0-100, or -1 when the printer reports an unknown level. */
        public final int levelPercent;
        @Nullable public final String colorHex;

        Supply(String name, int levelPercent, @Nullable String colorHex) {
            this.name = name;
            this.levelPercent = levelPercent;
            this.colorHex = colorHex;
        }
    }

    private PrinterCapabilities(Builder b) {
        this.name = b.name;
        this.makeAndModel = b.makeAndModel;
        this.location = b.location;
        this.stateMessage = b.stateMessage;
        this.state = b.state;
        this.acceptingJobs = b.acceptingJobs;
        this.colorSupported = b.colorSupported;
        this.maxCopies = b.maxCopies;
        this.stateReasons = b.stateReasons;
        this.documentFormats = b.documentFormats;
        this.media = b.media;
        this.mediaSources = b.mediaSources;
        this.sides = b.sides;
        this.colorModes = b.colorModes;
        this.qualities = b.qualities;
        this.defaultMedia = b.defaultMedia;
        this.defaultSides = b.defaultSides;
        this.defaultColorMode = b.defaultColorMode;
        this.supplies = b.supplies;
    }

    public static PrinterCapabilities from(@NonNull IppResponse response) {
        Builder b = new Builder();
        b.name = response.getString("printer-name");
        b.makeAndModel = response.getString("printer-make-and-model");
        b.location = emptyToNull(response.getString("printer-location"));
        b.stateMessage = emptyToNull(response.getString("printer-state-message"));
        b.state = response.getInt("printer-state", Ipp.PRINTER_STATE_IDLE);
        b.acceptingJobs = response.getBoolean("printer-is-accepting-jobs", true);
        b.colorSupported = response.getBoolean("color-supported", false);
        b.stateReasons = response.getStrings("printer-state-reasons");
        b.documentFormats = response.getStrings("document-format-supported");
        b.media = response.getStrings("media-supported");
        b.mediaSources = response.getStrings("media-source-supported");
        b.sides = response.getStrings("sides-supported");
        b.colorModes = response.getStrings("print-color-mode-supported");
        b.defaultMedia = response.getString("media-default");
        b.defaultSides = response.getString("sides-default");
        b.defaultColorMode = response.getString("print-color-mode-default");

        IppAttribute copies = response.get("copies-supported");
        b.maxCopies = 99;
        if (copies != null) {
            IppValue v = copies.first();
            if (v != null && v.value instanceof int[]) {
                int[] range = (int[]) v.value;
                if (range.length >= 2 && range[1] > 0) {
                    b.maxCopies = Math.min(range[1], 999);
                }
            }
        }

        IppAttribute quality = response.get("print-quality-supported");
        if (quality != null) {
            for (IppValue v : quality.values) {
                int q = v.asInt(-1);
                if (q >= 3 && q <= 5) { // draft, normal, high
                    b.qualities.add(q);
                }
            }
        }
        if (b.qualities.isEmpty()) {
            Collections.addAll(b.qualities, 3, 4, 5);
        }

        b.supplies = parseSupplies(response);
        return new PrinterCapabilities(b);
    }

    private static List<Supply> parseSupplies(IppResponse response) {
        List<Supply> out = new ArrayList<>();
        IppAttribute names = response.get("marker-names");
        IppAttribute levels = response.get("marker-levels");
        if (names == null || levels == null) {
            return out;
        }
        IppAttribute colors = response.get("marker-colors");
        int count = Math.min(names.values.size(), levels.values.size());
        for (int i = 0; i < count; i++) {
            String supplyName = names.values.get(i).asString();
            if (supplyName == null || supplyName.isEmpty()) {
                continue;
            }
            int level = levels.values.get(i).asInt(-1);
            // -1 means unknown, -2 means "some remaining"; clamp the rest to 0-100.
            int percent = level < 0 ? -1 : Math.min(level, 100);
            String color = null;
            if (colors != null && i < colors.values.size()) {
                color = emptyToNull(colors.values.get(i).asString());
            }
            out.add(new Supply(supplyName, percent, color));
        }
        return out;
    }

    /**
     * Picks the format to send for a document this app produced.
     *
     * <p>Everything the converters emit is a PDF, so PDF is preferred. A printer
     * that lists neither PDF nor {@code application/octet-stream} cannot be
     * driven without a raster pipeline, and this returns null so the caller can
     * say so instead of sending bytes the printer will reject.
     */
    @Nullable
    public String chooseFormatForPdf() {
        if (documentFormats.isEmpty()) {
            // No list means the printer did not answer; PDF is the safe guess
            // for anything advertising IPP Everywhere / AirPrint.
            return FORMAT_PDF;
        }
        if (documentFormats.contains(FORMAT_PDF)) {
            return FORMAT_PDF;
        }
        if (documentFormats.contains(FORMAT_OCTET_STREAM)) {
            // The printer sniffs the content itself; PDF usually gets through.
            return FORMAT_OCTET_STREAM;
        }
        return null;
    }

    /**
     * Ink or toner levels as "Black 62% · Cyan 14%", or an empty string when the
     * printer reports none. Supplies with an unknown level are left out.
     */
    @NonNull
    public String describeSupplies() {
        StringBuilder sb = new StringBuilder();
        for (Supply supply : supplies) {
            if (supply.levelPercent < 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(supply.name).append(' ').append(supply.levelPercent).append('%');
        }
        return sb.toString();
    }

    /** Human-readable state, e.g. "Idle" or "Stopped - out of paper". */
    @NonNull
    public String describeState() {
        String base;
        switch (state) {
            case Ipp.PRINTER_STATE_PROCESSING: base = "Printing"; break;
            case Ipp.PRINTER_STATE_STOPPED: base = "Stopped"; break;
            default: base = "Idle"; break;
        }
        String reason = firstRealStateReason();
        if (reason != null) {
            return base + " · " + reason;
        }
        if (stateMessage != null) {
            return base + " · " + stateMessage;
        }
        return base;
    }

    /** The first state reason that is not the "nothing is wrong" placeholder. */
    @Nullable
    public String firstRealStateReason() {
        for (String reason : stateReasons) {
            if (reason != null && !reason.isEmpty() && !"none".equals(reason)) {
                return reason.replace('-', ' ');
            }
        }
        return null;
    }

    @Nullable
    private static String emptyToNull(@Nullable String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static final class Builder {
        String name;
        String makeAndModel;
        String location;
        String stateMessage;
        int state = Ipp.PRINTER_STATE_IDLE;
        boolean acceptingJobs = true;
        boolean colorSupported;
        int maxCopies = 99;
        List<String> stateReasons = new ArrayList<>();
        List<String> documentFormats = new ArrayList<>();
        List<String> media = new ArrayList<>();
        List<String> mediaSources = new ArrayList<>();
        List<String> sides = new ArrayList<>();
        List<String> colorModes = new ArrayList<>();
        List<Integer> qualities = new ArrayList<>();
        String defaultMedia;
        String defaultSides;
        String defaultColorMode;
        List<Supply> supplies = new ArrayList<>();
    }
}
