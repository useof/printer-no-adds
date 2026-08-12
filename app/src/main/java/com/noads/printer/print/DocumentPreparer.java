package com.noads.printer.print;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.noads.printer.render.ImageToPdf;
import com.noads.printer.render.PageGeometry;
import com.noads.printer.render.TextToPdf;
import com.noads.printer.render.WebToPdf;
import com.noads.printer.util.DocumentUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns a {@link PrintSource} into a PDF file in the cache directory.
 *
 * <p>PDFs pass through untouched; images, text, and web pages are rendered.
 */
public final class DocumentPreparer {

    /** Refuse to slurp a text file large enough to blow the heap. */
    private static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onPrepared(@NonNull File pdf);

        void onFailed(@NonNull Exception error);
    }

    public DocumentPreparer(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * @param grayscale randează imaginile alb-negru; util pentru imprimante
     *                  monocrome, care altfel trebuie să facă ele conversia.
     * @param container needed only for {@link PrintSource.Kind#WEB_PAGE}, which
     *                  renders through an off-screen WebView.
     */
    @MainThread
    public void prepare(@NonNull PrintSource source,
                        @NonNull PageGeometry geometry,
                        boolean grayscale,
                        @Nullable ViewGroup container,
                        @NonNull Callback callback) {

        if (source.kind == PrintSource.Kind.WEB_PAGE) {
            if (container == null || source.url == null) {
                callback.onFailed(new IllegalStateException(
                        "Rendering a web page needs a view container and a URL"));
                return;
            }
            File destination = DocumentUtils.newJobFile(context, ".pdf");
            WebToPdf.convert(context, container, source.url, geometry, destination,
                    new WebToPdf.Callback() {
                        @Override
                        public void onSuccess(@NonNull File pdf) {
                            callback.onPrepared(pdf);
                        }

                        @Override
                        public void onFailure(@NonNull Exception error) {
                            callback.onFailed(error);
                        }
                    });
            return;
        }

        executor.execute(() -> {
            try {
                File pdf = convertBlocking(source, geometry, grayscale);
                mainHandler.post(() -> callback.onPrepared(pdf));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onFailed(e));
            }
        });
    }

    /** Blocking conversion for everything that does not need the main thread. */
    @NonNull
    public File convertBlocking(@NonNull PrintSource source,
                                @NonNull PageGeometry geometry,
                                boolean grayscale) throws IOException {
        File destination = DocumentUtils.newJobFile(context, ".pdf");

        switch (source.kind) {
            case PDF: {
                DocumentUtils.copyTo(context, source.uris.get(0), destination);
                assertLooksLikePdf(destination);
                return destination;
            }

            case IMAGES: {
                ImageToPdf.convert(context, source.uris, geometry, false, grayscale, destination);
                return destination;
            }

            case TEXT: {
                String text = source.inlineText != null
                        ? source.inlineText
                        : readText(source.uris.get(0));
                TextToPdf.convert(text, geometry, source.displayName, destination);
                return destination;
            }

            default:
                throw new IOException("Unsupported document type: " + source.kind);
        }
    }

    /**
     * Guards against a provider handing back HTML (an expired share link, a
     * login page) under a PDF MIME type - the printer would spit out garbage.
     */
    private static void assertLooksLikePdf(File file) throws IOException {
        if (file.length() < 5) {
            throw new IOException("The PDF is empty");
        }
        byte[] header = new byte[5];
        try (InputStream in = new java.io.FileInputStream(file)) {
            if (in.read(header) != header.length) {
                throw new IOException("The PDF is truncated");
            }
        }
        String magic = new String(header, StandardCharsets.US_ASCII);
        if (!magic.startsWith("%PDF-")) {
            throw new IOException("That file is not a valid PDF");
        }
    }

    private String readText(Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Cannot open " + uri);
            }
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_TEXT_BYTES) {
                    throw new IOException("That text file is too large to print (over "
                            + DocumentUtils.formatSize(MAX_TEXT_BYTES) + ")");
                }
                raw.write(buffer, 0, read);
            }
            return decodeText(raw.toByteArray());
        }
    }

    /** Reads as UTF-8, falling back to ISO-8859-1 for legacy files. */
    private static String decodeText(byte[] bytes) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(bytes.length);
            char[] chunk = new char[8192];
            int read;
            while ((read = reader.read(chunk)) != -1) {
                sb.append(chunk, 0, read);
            }
            String decoded = sb.toString();
            // U+FFFD means the bytes were not UTF-8 after all.
            if (decoded.indexOf('�') >= 0) {
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
            return decoded;
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
