package com.noads.printer.raster;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Apple URF ({@code image/urf}) — formatul pe care îl trimite AirPrint și
 * singurul pe care îl acceptă multe imprimante fără interpretor de PDF.
 *
 * <p>Antet de fișier: {@code "UNIRAST\0"} plus numărul de pagini. Antet de
 * pagină: 32 de octeți, întregi în ordinea rețelei. Structura e cea din
 * decodorul de referință {@code urftopdf} (Neil Armstrong), singura descriere
 * publică a formatului — Apple nu l-a documentat.
 */
public final class UrfWriter extends RasterWriter {

    /** Valori din {@code unirast.h}: 0 = gri pe 8 biți, 1 = sRGB pe 24. */
    private static final int COLOR_SPACE_GRAYSCALE_8 = 0;
    private static final int COLOR_SPACE_SRGB_24 = 1;

    /** Fără duplex și calitate normală; față/verso rămâne pe atributul IPP. */
    private static final int DUPLEX_NONE = 1;
    private static final int QUALITY_NORMAL = 4;

    public UrfWriter(@NonNull OutputStream out, int pageCount) throws IOException {
        super(out);
        out.write("UNIRAST\0".getBytes(StandardCharsets.US_ASCII));
        writeInt(pageCount);
    }

    @Override
    protected void writePageHeader(int widthPx, int heightPx, int dpi, boolean grayscale,
                                   int widthPts, int heightPts, String mediaName)
            throws IOException {
        out.write(grayscale ? 8 : 24);
        out.write(grayscale ? COLOR_SPACE_GRAYSCALE_8 : COLOR_SPACE_SRGB_24);
        out.write(DUPLEX_NONE);
        out.write(QUALITY_NORMAL);
        writeInt(0);            // rezervat
        writeInt(0);            // rezervat
        writeInt(widthPx);
        writeInt(heightPx);
        writeInt(dpi);
        writeInt(0);            // rezervat
        writeInt(0);            // rezervat
    }
}
