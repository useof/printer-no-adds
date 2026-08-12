package com.noads.printer.raster;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * PWG Raster ({@code image/pwg-raster}), formatul standardizat de Printer
 * Working Group (5102.4).
 *
 * <p>Antetul de pagină are 1796 de octeți și e structura CUPS
 * {@code cups_page_header2_t} serializată big-endian, cu {@code MediaClass}
 * pus pe „PwgRaster". Majoritatea câmpurilor rămân zero: ele descriu opțiuni pe
 * care le trimitem oricum ca atribute IPP.
 */
public final class PwgRasterWriter extends RasterWriter {

    /** Valori CUPS: 18 = sGray, 19 = sRGB. */
    private static final int CSPACE_SGRAY = 18;
    private static final int CSPACE_SRGB = 19;

    public PwgRasterWriter(@NonNull OutputStream out) throws IOException {
        super(out);
        out.write("RaS2".getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    protected void writePageHeader(int widthPx, int heightPx, int dpi, boolean grayscale,
                                   int widthPts, int heightPts, String mediaName)
            throws IOException {
        int pixelSize = grayscale ? 1 : 3;

        writeString("PwgRaster", 64);   // MediaClass
        writeString("", 64);            // MediaColor
        writeString("", 64);            // MediaType
        writeString("", 64);            // OutputType

        writeInt(0);                    // AdvanceDistance
        writeInt(0);                    // AdvanceMedia
        writeInt(0);                    // Collate
        writeInt(0);                    // CutMedia
        writeInt(0);                    // Duplex
        writeInt(dpi);                  // HWResolution[0]
        writeInt(dpi);                  // HWResolution[1]
        writeZeros(16);                 // ImagingBoundingBox[4]
        writeInt(0);                    // InsertSheet
        writeInt(0);                    // Jog
        writeInt(0);                    // LeadingEdge
        writeZeros(8);                  // Margins[2]
        writeInt(0);                    // ManualFeed
        writeInt(0);                    // MediaPosition
        writeInt(0);                    // MediaWeight
        writeInt(0);                    // MirrorPrint
        writeInt(0);                    // NegativePrint
        writeInt(0);                    // NumCopies — copiile pleacă prin IPP
        writeInt(0);                    // Orientation
        writeInt(0);                    // OutputFaceUp
        writeInt(widthPts);             // PageSize[0]
        writeInt(heightPts);            // PageSize[1]
        writeInt(0);                    // Separations
        writeInt(0);                    // TraySwitch
        writeInt(0);                    // Tumble

        writeInt(widthPx);              // cupsWidth
        writeInt(heightPx);             // cupsHeight
        writeInt(0);                    // cupsMediaType
        writeInt(8);                    // cupsBitsPerColor
        writeInt(pixelSize * 8);        // cupsBitsPerPixel
        writeInt(widthPx * pixelSize);  // cupsBytesPerLine
        writeInt(0);                    // cupsColorOrder — chunky
        writeInt(grayscale ? CSPACE_SGRAY : CSPACE_SRGB);
        writeInt(0);                    // cupsCompression
        writeInt(0);                    // cupsRowCount
        writeInt(0);                    // cupsRowFeed
        writeInt(0);                    // cupsRowStep
        writeInt(pixelSize);            // cupsNumColors
        writeInt(0);                    // cupsBorderlessScalingFactor (float 0.0)
        writeFloat(widthPts);           // cupsPageSize[0]
        writeFloat(heightPts);          // cupsPageSize[1]
        writeZeros(16);                 // cupsImagingBBox[4]
        writeZeros(64);                 // cupsInteger[16]
        writeZeros(64);                 // cupsReal[16]
        writeZeros(1024);               // cupsString[16][64]
        writeString("", 64);            // cupsMarkerType
        writeString("", 64);            // cupsRenderingIntent
        writeString(mediaName == null ? "" : mediaName, 64);   // cupsPageSizeName
    }

    private void writeString(String value, int length) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int written = Math.min(bytes.length, length - 1);
        out.write(bytes, 0, written);
        writeZeros(length - written);
    }

    private void writeFloat(float value) throws IOException {
        writeInt(Float.floatToIntBits(value));
    }

    private void writeZeros(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            out.write(0);
        }
    }
}
