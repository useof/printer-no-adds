package com.noads.printer.print;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.noads.printer.util.DocumentUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the user asked to print, before it becomes a PDF. */
public final class PrintSource {

    public enum Kind {
        PDF,
        IMAGES,
        TEXT,
        WEB_PAGE
    }

    @NonNull public final Kind kind;
    @NonNull public final List<Uri> uris;
    @Nullable public final String url;
    @NonNull public final String displayName;
    /** Bytes of the original input, or -1 when unknown. */
    public final long sizeBytes;

    private PrintSource(@NonNull Kind kind,
                        @NonNull List<Uri> uris,
                        @Nullable String url,
                        @NonNull String displayName,
                        long sizeBytes) {
        this.kind = kind;
        this.uris = Collections.unmodifiableList(uris);
        this.url = url;
        this.displayName = displayName;
        this.sizeBytes = sizeBytes;
    }

    /** Classifies a single document Uri by its MIME type. */
    @Nullable
    public static PrintSource fromUri(@NonNull Context context, @NonNull Uri uri) {
        String mime = DocumentUtils.mimeType(context, uri);
        String name = DocumentUtils.displayName(context, uri);
        long size = DocumentUtils.size(context, uri);
        List<Uri> single = Collections.singletonList(uri);

        if (DocumentUtils.isPdf(mime)) {
            return new PrintSource(Kind.PDF, single, null, name, size);
        }
        if (DocumentUtils.isImage(mime)) {
            return new PrintSource(Kind.IMAGES, single, null, name, size);
        }
        if (DocumentUtils.isText(mime)) {
            return new PrintSource(Kind.TEXT, single, null, name, size);
        }
        // Unrecognised types are not printable without a converter this app
        // does not have; the caller turns null into a clear message.
        return null;
    }

    /** Several images printed as one document, one image per page. */
    public static PrintSource fromImages(@NonNull Context context, @NonNull List<Uri> images) {
        if (images.isEmpty()) {
            throw new IllegalArgumentException("No images given");
        }
        if (images.size() == 1) {
            Uri only = images.get(0);
            return new PrintSource(Kind.IMAGES, Collections.singletonList(only), null,
                    DocumentUtils.displayName(context, only),
                    DocumentUtils.size(context, only));
        }
        long total = 0;
        for (Uri uri : images) {
            long size = DocumentUtils.size(context, uri);
            if (size > 0) {
                total += size;
            }
        }
        return new PrintSource(Kind.IMAGES, new ArrayList<>(images), null,
                images.size() + " images", total);
    }

    public static PrintSource fromText(@NonNull String text, @NonNull String title) {
        // The text itself travels in the display name slot's companion field via
        // TEXT kind with no Uri; PrintJobManager reads it from inlineText.
        PrintSource source = new PrintSource(Kind.TEXT, new ArrayList<>(), null, title,
                text.getBytes().length);
        source.inlineText = text;
        return source;
    }

    public static PrintSource fromUrl(@NonNull String url) {
        String normalized = url.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        return new PrintSource(Kind.WEB_PAGE, new ArrayList<>(), normalized,
                Uri.parse(normalized).getHost() != null
                        ? Uri.parse(normalized).getHost()
                        : normalized,
                -1);
    }

    /** Set only for {@link #fromText}: text supplied directly rather than by Uri. */
    @Nullable
    public String inlineText;

    @NonNull
    public String jobName() {
        return DocumentUtils.jobNameFor(displayName);
    }
}
