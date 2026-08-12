package com.noads.printer.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Renders a web page to PDF by loading it in an off-screen {@link WebView} and
 * drawing the laid-out view into a {@link PdfDocument}, one page-height slice at
 * a time.
 *
 * <p>The obvious route — {@link WebView#createPrintDocumentAdapter(String)} —
 * cannot be driven from application code: {@code PrintDocumentAdapter}'s
 * {@code LayoutResultCallback} and {@code WriteResultCallback} have
 * package-private constructors, so they can only be subclassed from inside
 * {@code android.print}. Declaring our own class in that package is the usual
 * workaround, and it relies on the runtime not enforcing the access check —
 * not something to build a printing path on. Drawing the view ourselves uses
 * only public API. The trade-off is raster output rather than selectable text,
 * which for printing changes nothing.
 *
 * <p>The WebView must be attached and have a non-zero size or it lays out to
 * nothing and the PDF comes out blank, so the caller supplies a container to
 * attach an off-screen instance to.
 *
 * <p>Everything here runs on the main thread; the result arrives on the
 * callback, also on the main thread.
 */
public final class WebToPdf {

    private static final String TAG = "WebToPdf";
    private static final long LOAD_TIMEOUT_MS = 45_000;
    /** Give layout and web fonts a beat to settle after onPageFinished. */
    private static final long SETTLE_DELAY_MS = 700;
    /** A runaway page (infinite scroll, broken layout) must not print forever. */
    private static final int MAX_PAGES = 200;

    public interface Callback {
        void onSuccess(@NonNull File pdf);

        void onFailure(@NonNull Exception error);
    }

    private WebToPdf() {
    }

    @MainThread
    public static void convert(@NonNull Context context,
                               @NonNull ViewGroup container,
                               @NonNull String url,
                               @NonNull PageGeometry geometry,
                               @NonNull File destination,
                               @NonNull Callback callback) {

        WebView webView = new WebView(context);
        // Off-screen but measurable: a zero-size or GONE WebView renders nothing.
        webView.setVisibility(View.INVISIBLE);
        // draw() targets the PdfDocument's software canvas; a hardware layer can
        // come out blank there.
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        container.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setDomStorageEnabled(true);
        // Remote content only; no reason to expose the app's own files to it.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        State state = new State(webView, container, destination, geometry, callback);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String loadedUrl, Bitmap favicon) {
                state.scheduleTimeout();
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                // onPageFinished can fire more than once (frames, redirects).
                state.onPageFinished();
            }

            @Override
            public void onReceivedError(WebView view,
                                        WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    state.fail(new IOException("Could not load page: " + error.getDescription()));
                }
            }
        });

        state.scheduleTimeout();
        webView.loadUrl(url);
    }

    /** Holds the one-shot state so the callback can only fire once. */
    private static final class State {
        private final WebView webView;
        private final ViewGroup container;
        private final File destination;
        private final PageGeometry geometry;
        private final Callback callback;
        private final Runnable timeoutRunnable;

        private boolean settled;
        private boolean writing;

        State(WebView webView, ViewGroup container, File destination,
              PageGeometry geometry, Callback callback) {
            this.webView = webView;
            this.container = container;
            this.destination = destination;
            this.geometry = geometry;
            this.callback = callback;
            this.timeoutRunnable = () -> fail(new IOException("Timed out loading the page"));
        }

        void scheduleTimeout() {
            webView.removeCallbacks(timeoutRunnable);
            webView.postDelayed(timeoutRunnable, LOAD_TIMEOUT_MS);
        }

        void onPageFinished() {
            if (settled || writing) {
                return;
            }
            webView.postDelayed(this::write, SETTLE_DELAY_MS);
        }

        private void write() {
            if (settled || writing) {
                return;
            }
            writing = true;
            webView.removeCallbacks(timeoutRunnable);

            try {
                render();
            } catch (IOException | RuntimeException e) {
                fail(e instanceof IOException ? (IOException) e : new IOException(e));
                return;
            }
            succeed();
        }

        /**
         * Lays the WebView out at its full content height, then draws it into the
         * PDF page by page. The view is scaled so its width fills the printable
         * width of the page; each page shows the next slice of the same drawing.
         */
        private void render() throws IOException {
            int viewWidth = webView.getWidth();
            if (viewWidth <= 0) {
                throw new IOException("The page had no width to render into");
            }

            // getContentHeight() is in CSS pixels; getScale() converts to view pixels.
            int contentHeight = Math.round(webView.getContentHeight() * webView.getScale());
            if (contentHeight <= 0) {
                contentHeight = webView.getHeight();
            }
            if (contentHeight <= 0) {
                throw new IOException("The page produced an empty document");
            }

            // Lay the view out over its whole content so draw() emits all of it,
            // not just the visible viewport.
            webView.measure(
                    View.MeasureSpec.makeMeasureSpec(viewWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(contentHeight, View.MeasureSpec.EXACTLY));
            webView.layout(0, 0, viewWidth, contentHeight);

            float scale = (float) geometry.contentWidth() / viewWidth;
            // How much of the view fits on one page, in view pixels.
            float sliceHeight = geometry.contentHeight() / scale;
            int pageCount = Math.min(
                    (int) Math.ceil(contentHeight / sliceHeight), MAX_PAGES);
            if (pageCount * sliceHeight < contentHeight) {
                Log.w(TAG, "Page truncated at " + MAX_PAGES + " pages");
            }

            PdfDocument document = new PdfDocument();
            try {
                for (int i = 0; i < pageCount; i++) {
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                            geometry.width, geometry.height, i + 1).create();
                    PdfDocument.Page page = document.startPage(info);
                    Canvas canvas = page.getCanvas();
                    canvas.drawColor(Color.WHITE);

                    canvas.save();
                    canvas.translate(geometry.margin, geometry.margin);
                    canvas.clipRect(0, 0, geometry.contentWidth(), geometry.contentHeight());
                    canvas.scale(scale, scale);
                    canvas.translate(0, -i * sliceHeight);
                    webView.draw(canvas);
                    canvas.restore();

                    document.finishPage(page);
                }

                try (OutputStream out = new FileOutputStream(destination)) {
                    document.writeTo(out);
                }
            } finally {
                document.close();
            }

            if (destination.length() == 0) {
                throw new IOException("The page produced an empty document");
            }
        }

        void succeed() {
            if (settled) {
                return;
            }
            settled = true;
            cleanUp();
            callback.onSuccess(destination);
        }

        void fail(Exception error) {
            if (settled) {
                return;
            }
            settled = true;
            cleanUp();
            callback.onFailure(error);
        }

        private void cleanUp() {
            webView.removeCallbacks(timeoutRunnable);
            webView.stopLoading();
            webView.setWebViewClient(new WebViewClient());
            container.removeView(webView);
            webView.destroy();
        }
    }
}
