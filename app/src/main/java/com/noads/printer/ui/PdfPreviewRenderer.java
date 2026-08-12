package com.noads.printer.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Renders pages of a local PDF to bitmaps for the preview pane.
 *
 * <p>{@link PdfRenderer} allows only one open page at a time and is not thread
 * safe, so every call is funnelled through one background thread.
 */
public final class PdfPreviewRenderer implements Closeable {

    /** Widest bitmap to produce; the preview never needs more than this. */
    private static final int MAX_PREVIEW_WIDTH_PX = 1400;

    public interface PageCallback {
        void onPageRendered(int pageIndex, @NonNull Bitmap bitmap);

        void onFailed(@NonNull Exception error);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private ParcelFileDescriptor descriptor;
    @Nullable
    private PdfRenderer renderer;

    private volatile int pageCount;
    private volatile boolean closed;

    /** Opens {@code pdf} and reports its page count, or an error. */
    public void open(@NonNull File pdf, @NonNull OpenCallback callback) {
        executor.execute(() -> {
            try {
                closeRenderer();
                descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(descriptor);
                pageCount = renderer.getPageCount();
                final int count = pageCount;
                mainHandler.post(() -> callback.onOpened(count));
            } catch (Exception e) {
                closeRenderer();
                mainHandler.post(() -> callback.onFailed(e));
            }
        });
    }

    public interface OpenCallback {
        void onOpened(int pageCount);

        void onFailed(@NonNull Exception error);
    }

    /**
     * Renders one page scaled to {@code targetWidthPx}.
     *
     * @param pageIndex zero-based.
     */
    public void renderPage(int pageIndex, int targetWidthPx, @NonNull PageCallback callback) {
        executor.execute(() -> {
            PdfRenderer active = renderer;
            if (closed || active == null) {
                mainHandler.post(() -> callback.onFailed(new IOException("Preview is closed")));
                return;
            }
            if (pageIndex < 0 || pageIndex >= active.getPageCount()) {
                mainHandler.post(() -> callback.onFailed(
                        new IOException("No page " + (pageIndex + 1))));
                return;
            }

            PdfRenderer.Page page = null;
            try {
                page = active.openPage(pageIndex);
                int width = Math.min(Math.max(targetWidthPx, 1), MAX_PREVIEW_WIDTH_PX);
                int height = Math.max(1,
                        Math.round(width * (float) page.getHeight() / page.getWidth()));

                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                // PdfRenderer draws only the page content; the paper itself has
                // to be painted or transparent areas show through as black.
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                mainHandler.post(() -> {
                    if (closed) {
                        bitmap.recycle();
                    } else {
                        callback.onPageRendered(pageIndex, bitmap);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> callback.onFailed(e));
            } finally {
                if (page != null) {
                    page.close();
                }
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        executor.execute(this::closeRenderer);
        executor.shutdown();
    }

    private void closeRenderer() {
        if (renderer != null) {
            try {
                renderer.close();
            } catch (Exception ignored) {
                // Already closed.
            }
            renderer = null;
        }
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // Nothing useful to do while tearing down.
            }
            descriptor = null;
        }
    }
}
