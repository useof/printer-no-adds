package com.noads.printer.print;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Scoate criptarea dintr-un PDF, ca să-l poată deschide {@code PdfRenderer} (preview,
 * raster) și să-l poată interpreta imprimanta.
 *
 * <p>{@code PdfRenderer} refuză orice PDF cu parolă de deschidere (până la API 35 nu
 * are nici măcar unde primi parola), iar imprimantele nu cer parole, deci un PDF
 * criptat trimis ca atare iese gol sau e respins. PDF-urile cu doar parolă de
 * proprietar (restricții de print/copiere) se deschid cu parola goală; le decriptăm
 * și pe ele, pentru imprimantele care nu știu deloc de criptare.
 */
public final class PdfDecryptor {

    /** PDF-ul are parolă de deschidere și nu am primit-o, sau am primit una greșită. */
    public static final class PasswordRequiredException extends IOException {
        /** {@code true} când s-a încercat o parolă, deci cea dată a fost greșită. */
        public final boolean wrongPassword;

        PasswordRequiredException(boolean wrongPassword) {
            super(wrongPassword ? "Wrong PDF password" : "The PDF is password protected");
            this.wrongPassword = wrongPassword;
        }
    }

    private static final byte[] ENCRYPT_KEY = "/Encrypt".getBytes(StandardCharsets.US_ASCII);

    private PdfDecryptor() {
    }

    /**
     * Cheia {@code /Encrypt} stă în trailer sau în dicționarul xref stream-ului, care
     * nu sunt comprimate niciodată, deci o căutare de octeți o găsește. Un fals pozitiv
     * (cuvântul în conținutul unui stream necomprimat) costă doar o încărcare în plus.
     */
    public static boolean isEncrypted(@NonNull File pdf) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(pdf), 64 * 1024)) {
            int matched = 0;
            int b;
            while ((b = in.read()) != -1) {
                if (b == ENCRYPT_KEY[matched]) {
                    matched++;
                    if (matched == ENCRYPT_KEY.length) {
                        return true;
                    }
                } else {
                    matched = b == ENCRYPT_KEY[0] ? 1 : 0;
                }
            }
            return false;
        }
    }

    /**
     * Scrie în {@code destination} o copie fără criptare a lui {@code source}.
     *
     * @param password parola de deschidere; {@code null} încearcă parola goală.
     * @throws PasswordRequiredException fără parolă sau cu una greșită.
     */
    public static void decrypt(@NonNull Context context,
                               @NonNull File source,
                               @Nullable String password,
                               @NonNull File destination) throws IOException {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        // Fișiere temporare în loc de heap: un PDF scanat de zeci de MB nu încape
        // întreg în memorie pe un telefon modest.
        MemoryUsageSetting memory = MemoryUsageSetting.setupTempFileOnly()
                .setTempDir(context.getCacheDir());
        try (PDDocument document = PDDocument.load(source,
                password == null ? "" : password, memory)) {
            document.setAllSecurityToBeRemoved(true);
            document.save(destination);
        } catch (InvalidPasswordException e) {
            throw new PasswordRequiredException(password != null && !password.isEmpty());
        }
    }
}
