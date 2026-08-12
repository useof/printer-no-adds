package com.noads.printer.ipp;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Speaks IPP to one printer over HTTP(S).
 *
 * <p>All methods block; call them off the main thread.
 */
public final class IppClient {

    private static final String TAG = "IppClient";
    private static final String CONTENT_TYPE = "application/ipp";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int STREAM_CHUNK_BYTES = 32 * 1024;

    private static final AtomicInteger REQUEST_IDS = new AtomicInteger(1);

    /** The {@code ipp://} or {@code ipps://} URI advertised by the printer. */
    private final String printerUri;
    private final URL httpUrl;

    public IppClient(@NonNull String printerUri) throws IOException {
        this.printerUri = printerUri;
        this.httpUrl = toHttpUrl(printerUri);
    }

    /** Reports how many bytes of the document have been pushed so far. */
    public interface ProgressListener {
        void onProgress(long bytesSent, long totalBytes);
    }

    /* ------------------------------------------------------------------ */
    /* Operations                                                         */
    /* ------------------------------------------------------------------ */

    /** Get-Printer-Attributes for everything the UI needs to build a job. */
    public IppResponse getPrinterAttributes() throws IOException {
        IppRequest request = new IppRequest(Ipp.OP_GET_PRINTER_ATTRIBUTES, nextRequestId())
                .standardOperationAttributes()
                .attr(Ipp.TAG_URI, "printer-uri", printerUri)
                .requestedAttributes(
                        "printer-name",
                        "printer-info",
                        "printer-location",
                        "printer-make-and-model",
                        "printer-state",
                        "printer-state-reasons",
                        "printer-state-message",
                        "printer-is-accepting-jobs",
                        "document-format-supported",
                        "document-format-default",
                        "media-supported",
                        "media-default",
                        "media-source-supported",
                        "sides-supported",
                        "sides-default",
                        "print-color-mode-supported",
                        "print-color-mode-default",
                        "print-quality-supported",
                        "copies-supported",
                        "orientation-requested-supported",
                        "printer-resolution-supported",
                        "printer-resolution-default",
                        "color-supported",
                        "marker-names",
                        "marker-levels",
                        "marker-colors",
                        "marker-types",
                        "ipp-versions-supported",
                        "operations-supported")
                .end();
        return execute("Get-Printer-Attributes", request, null, 0, null);
    }

    /**
     * Validate-Job: asks the printer whether it would accept this job without
     * actually sending the document.
     */
    public IppResponse validateJob(@NonNull String documentFormat, @NonNull JobOptions options)
            throws IOException {
        IppRequest request = new IppRequest(Ipp.OP_VALIDATE_JOB, nextRequestId())
                .standardOperationAttributes()
                .attr(Ipp.TAG_URI, "printer-uri", printerUri)
                .attr(Ipp.TAG_NAME_WITHOUT_LANGUAGE, "requesting-user-name", requestingUserName())
                .attr(Ipp.TAG_MIME_MEDIA_TYPE, "document-format", documentFormat);
        appendJobAttributes(request, options);
        return execute("Validate-Job", request.end(), null, 0, null);
    }

    /**
     * Print-Job: uploads {@code document} and returns the assigned job id.
     *
     * @return the printer's {@code job-id}, or -1 if the printer accepted the
     *         job without reporting one.
     */
    public int printJob(@NonNull File document,
                        @NonNull String documentFormat,
                        @NonNull JobOptions options,
                        @Nullable ProgressListener listener) throws IOException {
        IppRequest request = new IppRequest(Ipp.OP_PRINT_JOB, nextRequestId())
                .standardOperationAttributes()
                .attr(Ipp.TAG_URI, "printer-uri", printerUri)
                .attr(Ipp.TAG_NAME_WITHOUT_LANGUAGE, "requesting-user-name", requestingUserName())
                .attr(Ipp.TAG_NAME_WITHOUT_LANGUAGE, "job-name", trimJobName(options.jobName))
                .attrBoolean("ipp-attribute-fidelity", false)
                .attr(Ipp.TAG_MIME_MEDIA_TYPE, "document-format", documentFormat);
        appendJobAttributes(request, options);

        IppResponse response = execute("Print-Job", request.end(),
                document, document.length(), listener);

        IppResponse.Group jobGroup = response.group(Ipp.TAG_JOB_ATTRIBUTES);
        return jobGroup != null ? jobGroup.getInt("job-id", -1) : response.getInt("job-id", -1);
    }

    public IppResponse getJobAttributes(int jobId) throws IOException {
        IppRequest request = new IppRequest(Ipp.OP_GET_JOB_ATTRIBUTES, nextRequestId())
                .standardOperationAttributes()
                .attr(Ipp.TAG_URI, "printer-uri", printerUri)
                .attr(Ipp.TAG_INTEGER, "job-id", jobId)
                .attr(Ipp.TAG_NAME_WITHOUT_LANGUAGE, "requesting-user-name", requestingUserName())
                .requestedAttributes("job-id", "job-state", "job-state-reasons",
                        "job-state-message", "job-impressions-completed", "job-name")
                .end();
        return execute("Get-Job-Attributes", request, null, 0, null);
    }

    public void cancelJob(int jobId) throws IOException {
        IppRequest request = new IppRequest(Ipp.OP_CANCEL_JOB, nextRequestId())
                .standardOperationAttributes()
                .attr(Ipp.TAG_URI, "printer-uri", printerUri)
                .attr(Ipp.TAG_INTEGER, "job-id", jobId)
                .attr(Ipp.TAG_NAME_WITHOUT_LANGUAGE, "requesting-user-name", requestingUserName())
                .end();
        execute("Cancel-Job", request, null, 0, null);
    }

    /* ------------------------------------------------------------------ */
    /* Job attribute group                                                */
    /* ------------------------------------------------------------------ */

    private static void appendJobAttributes(IppRequest request, JobOptions options) {
        request.group(Ipp.TAG_JOB_ATTRIBUTES);
        request.attr(Ipp.TAG_INTEGER, "copies", Math.max(1, options.copies));

        if (options.sides != null) {
            request.attr(Ipp.TAG_KEYWORD, "sides", options.sides);
        }
        if (options.colorMode != null && !JobOptions.COLOR_AUTO.equals(options.colorMode)) {
            request.attr(Ipp.TAG_KEYWORD, "print-color-mode", options.colorMode);
        }
        if (options.quality > 0) {
            request.attr(Ipp.TAG_ENUM, "print-quality", options.quality);
        }
        if (options.orientation > 0) {
            request.attr(Ipp.TAG_ENUM, "orientation-requested", options.orientation);
        }
        if (options.media != null) {
            request.attr(Ipp.TAG_KEYWORD, "media", options.media);
        }
        if (options.mediaSource != null) {
            request.attr(Ipp.TAG_KEYWORD, "media-source", options.mediaSource);
        }
        if (options.pageRanges != null && options.pageRanges.length > 0) {
            appendPageRanges(request, options.pageRanges);
        }
    }

    private static void appendPageRanges(IppRequest request, int[][] ranges) {
        // rangeOfInteger has no String form, so the raw 8-byte payload is built
        // by hand: lower(4) upper(4), repeated as additional values.
        for (int i = 0; i < ranges.length; i++) {
            int lower = ranges[i][0];
            int upper = ranges[i].length > 1 ? ranges[i][1] : ranges[i][0];
            request.attrRange(i == 0 ? "page-ranges" : null, lower, upper);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Transport                                                          */
    /* ------------------------------------------------------------------ */

    private IppResponse execute(String operation,
                                IppRequest request,
                                @Nullable File document,
                                long documentLength,
                                @Nullable ProgressListener listener) throws IOException {
        byte[] header = request.toByteArray();
        HttpURLConnection connection = openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", CONTENT_TYPE);
            connection.setRequestProperty("Accept", CONTENT_TYPE);
            connection.setRequestProperty("Accept-Encoding", "identity");

            if (document == null) {
                connection.setFixedLengthStreamingMode(header.length);
            } else {
                long total = header.length + documentLength;
                connection.setFixedLengthStreamingMode(total);
            }

            try (OutputStream out = new BufferedOutputStream(connection.getOutputStream(), STREAM_CHUNK_BYTES)) {
                out.write(header);
                if (document != null) {
                    streamDocument(document, documentLength, out, listener);
                }
                out.flush();
            }

            int httpStatus = connection.getResponseCode();
            if (httpStatus == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new IppException(0x0402, "Printer requires authentication (HTTP 401)");
            }
            if (httpStatus < 200 || httpStatus >= 300) {
                throw new IOException(operation + " failed: HTTP " + httpStatus + " "
                        + connection.getResponseMessage());
            }

            IppResponse response;
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), STREAM_CHUNK_BYTES)) {
                response = IppResponse.parse(in);
            }

            Log.d(TAG, operation + " -> " + response.statusName());
            if (!response.isSuccess()) {
                throw IppException.from(operation, response);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static void streamDocument(File document,
                                       long totalBytes,
                                       OutputStream out,
                                       @Nullable ProgressListener listener) throws IOException {
        byte[] buffer = new byte[STREAM_CHUNK_BYTES];
        long sent = 0;
        try (InputStream in = new FileInputStream(document)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                sent += read;
                if (listener != null) {
                    listener.onProgress(sent, totalBytes);
                }
            }
        }
        if (sent != totalBytes) {
            // Content-Length was already committed; a mismatch corrupts the job.
            throw new IOException("Document changed size while uploading ("
                    + sent + " of " + totalBytes + " bytes)");
        }
    }

    private HttpURLConnection openConnection() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) httpUrl.openConnection();
        if (connection instanceof HttpsURLConnection) {
            // Network printers ship self-signed certificates that no CA store
            // will ever validate, and IPP over plain HTTP is the alternative.
            // Verification is relaxed only for these LAN connections.
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(permissiveSocketFactory());
            https.setHostnameVerifier((hostname, session) -> true);
        }
        return connection;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /** {@code ipp://host:631/ipp/print} becomes {@code http://host:631/ipp/print}. */
    static URL toHttpUrl(String printerUri) throws IOException {
        Uri uri = Uri.parse(printerUri);
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IOException("Printer URI has no scheme: " + printerUri);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("Printer URI has no host: " + printerUri);
        }

        String httpScheme;
        int defaultPort;
        switch (scheme.toLowerCase()) {
            case "ipps":
            case "https":
                httpScheme = "https";
                defaultPort = 443;
                break;
            case "ipp":
            case "http":
                httpScheme = "http";
                defaultPort = 631;
                break;
            default:
                throw new IOException("Unsupported printer URI scheme: " + scheme);
        }

        int port = uri.getPort();
        if (port <= 0) {
            port = "ipp".equalsIgnoreCase(scheme) || "ipps".equalsIgnoreCase(scheme) ? 631 : defaultPort;
        }

        String path = uri.getEncodedPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getEncodedQuery();
        if (query != null && !query.isEmpty()) {
            path = path + "?" + query;
        }

        // Bracket IPv6 literals so URL accepts them.
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return new URL(httpScheme + "://" + authority + ":" + port + path);
    }

    private static String trimJobName(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Document";
        }
        String trimmed = name.trim();
        // 'name' values are capped at 255 octets by RFC 8011.
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200);
    }

    private static String requestingUserName() {
        return "android";
    }

    private static int nextRequestId() {
        return REQUEST_IDS.getAndIncrement() & 0x7FFFFFFF;
    }

    private static volatile SSLSocketFactory permissiveFactory;

    private static SSLSocketFactory permissiveSocketFactory() throws IOException {
        SSLSocketFactory factory = permissiveFactory;
        if (factory != null) {
            return factory;
        }
        synchronized (IppClient.class) {
            if (permissiveFactory == null) {
                try {
                    SSLContext context = SSLContext.getInstance("TLS");
                    context.init(null, new TrustManager[]{new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }}, new SecureRandom());
                    permissiveFactory = context.getSocketFactory();
                } catch (Exception e) {
                    throw new IOException("Cannot set up TLS for ipps://", e);
                }
            }
            return permissiveFactory;
        }
    }

}
