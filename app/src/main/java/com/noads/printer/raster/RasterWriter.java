package com.noads.printer.raster;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Baza comună pentru cele două formate raster pe care le înțeleg imprimantele
 * fără interpretor de PDF: Apple URF ({@code image/urf}, ce trimite AirPrint) și
 * PWG Raster ({@code image/pwg-raster}).
 *
 * <p>Diferă doar antetele; datele de imagine sunt identice — linii comprimate
 * PackBits, grupate după câte linii identice se repetă:
 *
 * <pre>
 *   [nr. repetări - 1]  [cod] [pixel]        cod 0..127   → pixelul se repetă cod+1 ori
 *                       [cod] [pixeli...]    cod 129..255 → urmează 257-cod pixeli
 * </pre>
 *
 * <p>Codul 128 e singura diferență între formate (URF îl citește ca „restul
 * liniei e alb", PWG ca 129 de pixeli literali), deci nu-l emitem niciodată și
 * același codor servește ambele.
 */
public abstract class RasterWriter implements Closeable {

    /** Cel mult atâția pixeli într-un grup, din cauza codului de un octet. */
    private static final int MAX_RUN = 128;
    /** Octetul de repetare a liniei e tot de un octet: 256 de linii identice. */
    private static final int MAX_LINE_REPEAT = 256;

    protected final OutputStream out;

    private byte[] pendingLine;
    private int pendingRepeats;
    private int pixelSize = 1;

    protected RasterWriter(@NonNull OutputStream out) {
        this.out = out;
    }

    /**
     * Începe o pagină nouă.
     *
     * @param widthPx    lățimea în pixeli
     * @param heightPx   înălțimea în pixeli
     * @param dpi        rezoluția la care s-a randat pagina
     * @param grayscale  true → 8 biți/pixel alb-negru, false → 24 biți sRGB
     * @param widthPts   lățimea paginii în puncte (1/72 inch)
     * @param heightPts  înălțimea paginii în puncte
     * @param mediaName  numele PWG al hârtiei, sau null
     */
    public final void startPage(int widthPx, int heightPx, int dpi, boolean grayscale,
                                int widthPts, int heightPts, String mediaName)
            throws IOException {
        flushPendingLine();
        pendingLine = null;
        pendingRepeats = 0;
        pixelSize = grayscale ? 1 : 3;
        writePageHeader(widthPx, heightPx, dpi, grayscale, widthPts, heightPts, mediaName);
    }

    /**
     * Adaugă o linie de pixeli, în ordinea de sus în jos. Liniile identice
     * consecutive se strâng într-un singur grup, ceea ce contează enorm pentru
     * marginile albe ale unei pagini.
     */
    public final void writeLine(@NonNull byte[] line) throws IOException {
        if (pendingLine != null
                && pendingRepeats < MAX_LINE_REPEAT
                && sameBytes(pendingLine, line)) {
            pendingRepeats++;
            return;
        }
        flushPendingLine();
        pendingLine = line.clone();
        pendingRepeats = 1;
    }

    /** Închide pagina curentă, scriind ce a mai rămas în buffer. */
    public final void endPage() throws IOException {
        flushPendingLine();
        pendingLine = null;
        pendingRepeats = 0;
    }

    private void flushPendingLine() throws IOException {
        if (pendingLine == null || pendingRepeats == 0) {
            return;
        }
        out.write(pendingRepeats - 1);
        encodeLine(pendingLine);
        pendingRepeats = 0;
    }

    /** PackBits pe pixeli întregi, nu pe octeți. */
    private void encodeLine(byte[] line) throws IOException {
        int pixels = line.length / pixelSize;
        int index = 0;
        while (index < pixels) {
            int runLength = 1;
            while (index + runLength < pixels
                    && runLength < MAX_RUN
                    && samePixel(line, index, index + runLength)) {
                runLength++;
            }

            if (runLength > 1) {
                out.write(runLength - 1);
                out.write(line, index * pixelSize, pixelSize);
                index += runLength;
                continue;
            }

            // Fără repetiție: adună pixeli distincți într-un grup literal. Se
            // oprește la doi identici, care se codează mai scurt ca repetiție.
            int literal = 1;
            while (index + literal < pixels
                    && literal < MAX_RUN
                    && !samePixel(line, index + literal, index + literal - 1)) {
                literal++;
            }
            if (literal == 1) {
                // Un singur pixel: codul de repetiție „o dată" e cel mai scurt.
                out.write(0);
                out.write(line, index * pixelSize, pixelSize);
            } else {
                out.write(257 - literal);
                out.write(line, index * pixelSize, literal * pixelSize);
            }
            index += literal;
        }
    }

    private boolean samePixel(byte[] line, int a, int b) {
        int offsetA = a * pixelSize;
        int offsetB = b * pixelSize;
        for (int i = 0; i < pixelSize; i++) {
            if (line[offsetA + i] != line[offsetB + i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameBytes(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    protected abstract void writePageHeader(int widthPx, int heightPx, int dpi,
                                            boolean grayscale, int widthPts, int heightPts,
                                            String mediaName) throws IOException;

    protected final void writeInt(int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    @Override
    public void close() throws IOException {
        endPage();
        out.flush();
    }
}
