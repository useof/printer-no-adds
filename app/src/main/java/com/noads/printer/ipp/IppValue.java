package com.noads.printer.ipp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A single IPP attribute value: its syntax tag plus the decoded payload.
 *
 * <p>The decoded payload is one of:
 * <ul>
 *     <li>{@link String} for the text/keyword/uri/charset families</li>
 *     <li>{@link Integer} for integer and enum</li>
 *     <li>{@link Boolean} for boolean</li>
 *     <li>{@code int[]} of length 2 for rangeOfInteger, length 3 for resolution
 *         ({@code {crossFeed, feed, units}})</li>
 *     <li>{@code Map<String, IppAttribute>} for a collection</li>
 *     <li>{@code null} for the out-of-band tags (unsupported, no-value)</li>
 * </ul>
 */
public final class IppValue {

    public final int tag;
    @Nullable
    public final Object value;

    public IppValue(int tag, @Nullable Object value) {
        this.tag = tag;
        this.value = value;
    }

    @Nullable
    public String asString() {
        return value instanceof String ? (String) value : (value == null ? null : String.valueOf(value));
    }

    public int asInt(int fallback) {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        return fallback;
    }

    public boolean asBoolean(boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return fallback;
    }

    @NonNull
    @Override
    public String toString() {
        if (value instanceof int[]) {
            int[] a = (int[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }
}
