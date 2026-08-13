package com.noads.printer.raster;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Transformă PDF-ul deja pregătit în pixeli, pentru imprimantele care nu au
 * interpretor de PDF.
 *
 * <p>Multe imprimante ieftine sunt „host-based": driverul de pe calculator le
 * trimite pagina gata rasterizată. Prin rețea, echivalentul e AirPrint, care
 * trimite URF. O astfel de imprimantă acceptă jobul PDF prin IPP, nu are ce
 * face cu el și îl aruncă în tăcere — exact simptomul „se trezește și nu
 * tipărește".
 *
 * <p>Paginile se randează în benzi orizontale, nu dintr-o bucată: o pagină A4
 * la 300 dpi are 8,7 milioane de pixeli, adică 35 MB ca bitmap ARGB.
 */
public final class PdfToRaster {

    /** Câte linii se randează odată. 256 × A4 la 300 dpi ≈ 2,5 MB. */
    private static final int STRIP_ROWS = 256;

    /** Coeficienți de luminanță (ITU-R BT.601), în aritmetică pe întregi. */
    private static final int LUMA_R = 77;
    private static final int LUMA_G = 150;
    private static final int LUMA_B = 29;

    public static final String FORMAT_URF = "image/urf";
    public static final String FORMAT_PWG_RASTER = "image/pwg-raster";

    private PdfToRaster() {
    }

    public static boolean isRasterFormat(@Nullable String format) {
        return FORMAT_URF.equals(format) || FORMAT_PWG_RASTER.equals(format);
    }

    /**
     * Randează fiecare pagină din {@code pdf} și scrie rezultatul în
     * {@code destination}, în formatul cerut.
     *
     * @param dpi        rezoluția de randare; 300 e ce trimite și AirPrint
     * @param grayscale  8 biți/pixel în loc de 24 — obligatoriu ca volum pentru
     *                   o imprimantă monocromă
     * @param mediaName  numele PWG al hârtiei, folosit doar de PWG Raster
     * @param pageRanges intervale 1-based inclusive, sau null pentru tot
     *                   documentul. Selecția se face AICI, nu prin atributul IPP
     *                   {@code page-ranges}: rasterul e deja pagina finală, iar o
     *                   imprimantă host-based nu are cum să mai aleagă din el.
     */
    @NonNull
    public static File convert(@NonNull File pdf,
                               @NonNull File destination,
                               @NonNull String format,
                               int dpi,
                               boolean grayscale,
                               @Nullable String mediaName,
                               @Nullable int[][] pageRanges) throws IOException {

        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {

            List<Integer> pages = selectPages(renderer.getPageCount(), pageRanges);
            if (pages.isEmpty()) {
                throw new IOException("The selected pages are not in this document");
            }

            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(destination));
                 RasterWriter writer = FORMAT_URF.equals(format)
                         ? new UrfWriter(out, pages.size())
                         : new PwgRasterWriter(out)) {

                for (int index : pages) {
                    writePage(renderer, index, writer, dpi, grayscale, mediaName);
                }
            }
        }
        return destination;
    }

    /** Indicii 0-based ai paginilor cerute, în ordine și fără duplicate. */
    private static List<Integer> selectPages(int pageCount, @Nullable int[][] pageRanges) {
        List<Integer> pages = new ArrayList<>();
        if (pageRanges == null || pageRanges.length == 0) {
            for (int i = 0; i < pageCount; i++) {
                pages.add(i);
            }
            return pages;
        }
        for (int page = 1; page <= pageCount; page++) {
            for (int[] range : pageRanges) {
                if (page >= range[0] && page <= range[1]) {
                    pages.add(page - 1);
                    break;
                }
            }
        }
        return pages;
    }

    private static void writePage(PdfRenderer renderer, int index, RasterWriter writer,
                                  int dpi, boolean grayscale, @Nullable String mediaName)
            throws IOException {

        int pageWidthPts;
        int pageHeightPts;
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            pageWidthPts = page.getWidth();
            pageHeightPts = page.getHeight();
        }

        // Rasterul pleacă întotdeauna în orientarea hârtiei, cu conținutul rotit
        // dacă pagina e lată — exact ce face AirPrint. O imprimantă host-based nu
        // poate roti nimic: dacă primește o pagină mai lată decât hârtia, o
        // micșorează ca să încapă, și iese o imagine mică pe foaie portret.
        boolean rotate = pageWidthPts > pageHeightPts;
        int widthPts = rotate ? pageHeightPts : pageWidthPts;
        int heightPts = rotate ? pageWidthPts : pageHeightPts;

        float scale = dpi / 72f;
        int widthPx = Math.max(1, Math.round(widthPts * scale));
        int heightPx = Math.max(1, Math.round(heightPts * scale));

        writer.startPage(widthPx, heightPx, dpi, grayscale, widthPts, heightPts, mediaName);

        int pixelSize = grayscale ? 1 : 3;
        byte[] line = new byte[widthPx * pixelSize];
        int[] row = new int[widthPx];
        Bitmap strip = Bitmap.createBitmap(widthPx, STRIP_ROWS, Bitmap.Config.ARGB_8888);

        try {
            for (int top = 0; top < heightPx; top += STRIP_ROWS) {
                int rows = Math.min(STRIP_ROWS, heightPx - top);

                // PdfRenderer desenează peste ce era în bitmap, iar zonele fără
                // conținut rămân transparente: fondul alb trebuie pus explicit.
                strip.eraseColor(Color.WHITE);

                Matrix matrix = new Matrix();
                matrix.setScale(scale, scale);
                if (rotate) {
                    // 90° în sensul acelor de ceas: colțul din stânga-sus al
                    // paginii ajunge în dreapta-sus a foii.
                    matrix.postRotate(90);
                    matrix.postTranslate(widthPx, 0);
                }
                matrix.postTranslate(0, -top);

                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    page.render(strip, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                }

                for (int y = 0; y < rows; y++) {
                    strip.getPixels(row, 0, widthPx, 0, y, widthPx, 1);
                    packLine(row, line, widthPx, grayscale);
                    writer.writeLine(line);
                }
            }
        } finally {
            strip.recycle();
        }

        writer.endPage();
    }

    /** Aplatizează o linie de pixeli ARGB în octeții pe care îi cere formatul. */
    private static void packLine(int[] row, byte[] line, int width, boolean grayscale) {
        for (int x = 0; x < width; x++) {
            int pixel = row[x];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            if (grayscale) {
                line[x] = (byte) ((LUMA_R * r + LUMA_G * g + LUMA_B * b) >> 8);
            } else {
                int offset = x * 3;
                line[offset] = (byte) r;
                line[offset + 1] = (byte) g;
                line[offset + 2] = (byte) b;
            }
        }
    }
}
