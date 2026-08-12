package com.noads.printer.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
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
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * Renders a web page to PDF by driving the {@link PrintDocumentAdapter} that
 * {@link WebView#createPrintDocumentAdapter(String)} hands back.
 *
 * <p>The WebView must live in the view hierarchy and have a non-zero size or it
 * lays out to nothing and the PDF comes out blank, so the caller supplies a
 * container to attach an off-screen instance to.
 *
 * <p>Everything here runs on the main thread; the result arrives on the
 * callback, also on the main thread.
 */
public final class WebToPdf {

    private static final String TAG = "WebToPdf";
    private static final long LOAD_TIMEOUT_MS = 45_000;
    /** Give layout and web fonts a beat to settle after onPageFinished. */
    private static final long SETTLE_DELAY_MS = 700;

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

            PrintAttributes attributes = new PrintAttributes.Builder()
                    .setMediaSize(mediaSizeFor(geometry))
                    .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();

            PrintDocumentAdapter adapter =
                    webView.createPrintDocumentAdapter(destination.getName());
            CancellationSignal cancellation = new CancellationSignal();

            adapter.onLayout(attributes, attributes, cancellation,
                    new PrintDocumentAdapter.LayoutResultCallback() {
                        @Override
                        public void onLayoutFinished(android.print.PrintDocumentInfo info,
                                                     boolean changed) {
                            performWrite(adapter, cancellation);
                        }

                        @Override
                        public void onLayoutFailed(CharSequence error) {
                            fail(new IOException("Page layout failed: " + error));
                        }

                        @Override
                        public void onLayoutCancelled() {
                            fail(new IOException("Page layout was cancelled"));
                        }
                    }, null);
        }

        private void performWrite(PrintDocumentAdapter adapter, CancellationSignal cancellation) {
            ParcelFileDescriptor descriptor;
            try {
                descriptor = ParcelFileDescriptor.open(destination,
                        ParcelFileDescriptor.MODE_CREATE
                                | ParcelFileDescriptor.MODE_TRUNCATE
                                | ParcelFileDescriptor.MODE_READ_WRITE);
            } catch (IOException e) {
                fail(e);
                return;
            }

            adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, descriptor, cancellation,
                    new PrintDocumentAdapter.WriteResultCallback() {
                        @Override
                        public void onWriteFinished(PageRange[] pages) {
                            closeQuietly(descriptor);
                            adapter.onFinish();
                            if (destination.length() == 0) {
                                fail(new IOException("The page produced an empty document"));
                            } else {
                                succeed();
                            }
                        }

                        @Override
                        public void onWriteFailed(CharSequence error) {
                            closeQuietly(descriptor);
                            adapter.onFinish();
                            fail(new IOException("Could not write the PDF: " + error));
                        }

                        @Override
                        public void onWriteCancelled() {
                            closeQuietly(descriptor);
                            adapter.onFinish();
                            fail(new IOException("Writing the PDF was cancelled"));
                        }
                    });
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

    private static PrintAttributes.MediaSize mediaSizeFor(PageGeometry geometry) {
        if (geometry.width == PageGeometry.LETTER_WIDTH
                && geometry.height == PageGeometry.LETTER_HEIGHT) {
            return PrintAttributes.MediaSize.NA_LETTER;
        }
        if (geometry.width == PageGeometry.LEGAL_WIDTH
                && geometry.height == PageGeometry.LEGAL_HEIGHT) {
            return PrintAttributes.MediaSize.NA_LEGAL;
        }
        if (geometry.width == PageGeometry.A5_WIDTH
                && geometry.height == PageGeometry.A5_HEIGHT) {
            return PrintAttributes.MediaSize.ISO_A5;
        }
        return PrintAttributes.MediaSize.ISO_A4;
    }

    private static void closeQuietly(@Nullable ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException e) {
            Log.w(TAG, "Could not close the PDF descriptor", e);
        }
    }
}
