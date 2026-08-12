package com.noads.printer.render;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scrie un PDF minimal în care fiecare pagină conține o singură imagine JPEG,
 * înglobată ca atare ({@code /DCTDecode}).
 *
 * <p>Există pentru că {@link android.graphics.pdf.PdfDocument} înglobează
 * bitmap-ul necomprimat: o poză de telefon la 300 dpi ajunge la zeci de MB, iar
 * imprimanta acceptă jobul și apoi rămâne blocată în „processing", fără să scoată
 * hârtia. Aceeași poză ca JPEG în PDF ocupă sub 2 MB.
 *
 * <p>Obiectele se scriu în ordinea în care vin, iar tabela xref se construiește
 * din pozițiile reținute — de aceea catalogul și nodul de pagini se scriu la
 * final, când se știu toți copiii.
 */
final class JpegPdfWriter implements Closeable {

    /** Rezervat pentru catalog; 2 pentru nodul /Pages, ambele scrise la final. */
    private static final int CATALOG_OBJECT = 1;
    private static final int PAGES_OBJECT = 2;

    private final OutputStream out;
    /** Poziția în fișier a fiecărui obiect, indexată de la 1. */
    private final List<Long> offsets = new ArrayList<>();
    private final List<Integer> pageObjects = new ArrayList<>();

    private long position;
    private boolean finished;

    JpegPdfWriter(@NonNull OutputStream out) throws IOException {
        this.out = out;
        write("%PDF-1.4\n");
        // Comentariu cu octeți >127: marchează fișierul drept binar, ca uneltele
        // care îl transferă să nu-l trateze ca text.
        writeBytes(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
    }

    /**
     * Adaugă o pagină de {@code pageWidth x pageHeight} puncte, cu imaginea
     * așezată în dreptunghiul dat (tot în puncte, origine în colțul din
     * stânga-jos, ca în PDF).
     */
    void addPage(int pageWidth, int pageHeight,
                 @NonNull byte[] jpeg, int imageWidth, int imageHeight,
                 float left, float bottom, float width, float height) throws IOException {

        int imageObject = startObject();
        write("<</Type/XObject/Subtype/Image/Width " + imageWidth
                + "/Height " + imageHeight
                + "/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/DCTDecode/Length "
                + jpeg.length + ">>\nstream\n");
        writeBytes(jpeg);
        write("\nendstream\nendobj\n");

        String content = String.format(Locale.US,
                "q\n%.2f 0 0 %.2f %.2f %.2f cm\n/Im0 Do\nQ\n",
                width, height, left, bottom);
        byte[] contentBytes = content.getBytes(StandardCharsets.US_ASCII);

        int contentObject = startObject();
        write("<</Length " + contentBytes.length + ">>\nstream\n");
        writeBytes(contentBytes);
        write("endstream\nendobj\n");

        int pageObject = startObject();
        write("<</Type/Page/Parent " + PAGES_OBJECT + " 0 R"
                + "/MediaBox[0 0 " + pageWidth + " " + pageHeight + "]"
                + "/Resources<</XObject<</Im0 " + imageObject + " 0 R>>>>"
                + "/Contents " + contentObject + " 0 R>>\nendobj\n");
        pageObjects.add(pageObject);
    }

    /** Scrie nodul de pagini, catalogul, xref-ul și trailer-ul. */
    void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;

        StringBuilder kids = new StringBuilder();
        for (int object : pageObjects) {
            kids.append(object).append(" 0 R ");
        }
        setObject(PAGES_OBJECT);
        write("<</Type/Pages/Kids[" + kids.toString().trim() + "]/Count "
                + pageObjects.size() + ">>\nendobj\n");

        setObject(CATALOG_OBJECT);
        write("<</Type/Catalog/Pages " + PAGES_OBJECT + " 0 R>>\nendobj\n");

        long xrefPosition = position;
        int size = offsets.size() + 1;
        write("xref\n0 " + size + "\n");
        write("0000000000 65535 f \n");
        for (long offset : offsets) {
            write(String.format(Locale.US, "%010d 00000 n \n", offset));
        }
        write("trailer\n<</Size " + size + "/Root " + CATALOG_OBJECT + " 0 R>>\n"
                + "startxref\n" + xrefPosition + "\n%%EOF\n");
        out.flush();
    }

    /** Alocă următorul număr de obiect liber și îi scrie antetul. */
    private int startObject() throws IOException {
        int number = nextFreeObject();
        setObject(number);
        return number;
    }

    private int nextFreeObject() {
        int number = offsets.size() + 1;
        // 1 și 2 sunt rezervate pentru catalog și nodul de pagini.
        while (number == CATALOG_OBJECT || number == PAGES_OBJECT) {
            offsets.add(0L);
            number = offsets.size() + 1;
        }
        return number;
    }

    /** Reține poziția obiectului {@code number} și scrie antetul lui. */
    private void setObject(int number) throws IOException {
        while (offsets.size() < number) {
            offsets.add(0L);
        }
        offsets.set(number - 1, position);
        write(number + " 0 obj\n");
    }

    private void write(String text) throws IOException {
        writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeBytes(byte[] bytes) throws IOException {
        out.write(bytes);
        position += bytes.length;
    }

    @Override
    public void close() throws IOException {
        finish();
    }
}
