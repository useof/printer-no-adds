package com.noads.printer.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Content-Uri plumbing: names, MIME types, and the job scratch directory. */
public final class DocumentUtils {

    private static final String JOBS_DIR = "print-jobs";
    private static final int COPY_BUFFER = 64 * 1024;

    private DocumentUtils() {
    }

    /** Cache directory holding the PDFs queued for printing. */
    public static File jobsDir(@NonNull Context context) {
        File dir = new File(context.getCacheDir(), JOBS_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create " + dir);
        }
        return dir;
    }

    /** Allocates a fresh file in the jobs directory. */
    public static File newJobFile(@NonNull Context context, @NonNull String suffix) {
        File dir = jobsDir(context);
        return new File(dir, "job-" + System.nanoTime() + suffix);
    }

    /** Removes every queued file. Safe to call while nothing is printing. */
    public static void clearJobs(@NonNull Context context) {
        File[] files = jobsDir(context).listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** The user-visible file name behind a content Uri, or a sensible fallback. */
    @NonNull
    public static String displayName(@NonNull Context context, @NonNull Uri uri) {
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver()
                    .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String name = cursor.getString(index);
                        if (name != null && !name.trim().isEmpty()) {
                            return name.trim();
                        }
                    }
                }
            } catch (Exception ignored) {
                // Providers are free to reject the projection; fall through.
            }
        }
        String last = uri.getLastPathSegment();
        return last != null && !last.isEmpty() ? last : "Document";
    }

    /** Size in bytes, or -1 when the provider does not report one. */
    public static long size(@NonNull Context context, @NonNull Uri uri) {
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver()
                    .query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (index >= 0 && !cursor.isNull(index)) {
                        return cursor.getLong(index);
                    }
                }
            } catch (Exception ignored) {
                // Fall through to the file-based path below.
            }
        }
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            return f.exists() ? f.length() : -1;
        }
        return -1;
    }

    /** MIME type from the resolver, falling back to the file extension. */
    @NonNull
    public static String mimeType(@NonNull Context context, @NonNull Uri uri) {
        String type = context.getContentResolver().getType(uri);
        if (type != null && !type.isEmpty()) {
            return type.toLowerCase();
        }
        String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
        if (extension != null && !extension.isEmpty()) {
            String guessed = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(extension.toLowerCase());
            if (guessed != null) {
                return guessed.toLowerCase();
            }
        }
        return "application/octet-stream";
    }

    public static boolean isPdf(@NonNull String mimeType) {
        return mimeType.startsWith("application/pdf");
    }

    public static boolean isImage(@NonNull String mimeType) {
        return mimeType.startsWith("image/");
    }

    public static boolean isText(@NonNull String mimeType) {
        return mimeType.startsWith("text/")
                || "application/json".equals(mimeType)
                || "application/xml".equals(mimeType);
    }

    /** Copies a Uri's bytes into {@code destination}. */
    public static void copyTo(@NonNull Context context, @NonNull Uri uri, @NonNull File destination)
            throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Cannot open " + uri);
            }
            try (OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[COPY_BUFFER];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
        }
    }

    /** "1.4 MB" style size for the UI. */
    @NonNull
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        return String.format(java.util.Locale.US, "%.1f MB", mb);
    }

    /** Strips the extension so a file name can serve as an IPP job-name. */
    @NonNull
    public static String jobNameFor(@NonNull String displayName) {
        int dot = displayName.lastIndexOf('.');
        String base = dot > 0 ? displayName.substring(0, dot) : displayName;
        return base.trim().isEmpty() ? "Document" : base.trim();
    }
}
