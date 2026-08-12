package com.noads.printer.render;

import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Page dimensions in PostScript points (1/72 inch), the unit
 * {@link android.graphics.pdf.PdfDocument} works in.
 */
public final class PageGeometry {

    public static final int A4_WIDTH = 595;
    public static final int A4_HEIGHT = 842;
    public static final int LETTER_WIDTH = 612;
    public static final int LETTER_HEIGHT = 792;
    public static final int LEGAL_WIDTH = 612;
    public static final int LEGAL_HEIGHT = 1008;
    public static final int A5_WIDTH = 420;
    public static final int A5_HEIGHT = 595;

    /** Half an inch on every side. */
    public static final int DEFAULT_MARGIN = 36;

    public final int width;
    public final int height;
    public final int margin;

    public PageGeometry(int width, int height, int margin) {
        this.width = width;
        this.height = height;
        this.margin = margin;
    }

    public static PageGeometry a4() {
        return new PageGeometry(A4_WIDTH, A4_HEIGHT, DEFAULT_MARGIN);
    }

    /**
     * Maps a PWG media name onto a page size. Unknown names fall back to A4,
     * which is what the vast majority of printers outside North America load.
     */
    public static PageGeometry forMedia(@Nullable String pwgMediaName) {
        if (pwgMediaName == null) {
            return a4();
        }
        String media = pwgMediaName.toLowerCase();
        if (media.startsWith("na_letter") || media.startsWith("na_executive")) {
            return new PageGeometry(LETTER_WIDTH, LETTER_HEIGHT, DEFAULT_MARGIN);
        }
        if (media.startsWith("na_legal")) {
            return new PageGeometry(LEGAL_WIDTH, LEGAL_HEIGHT, DEFAULT_MARGIN);
        }
        if (media.startsWith("iso_a5")) {
            return new PageGeometry(A5_WIDTH, A5_HEIGHT, DEFAULT_MARGIN);
        }
        return a4();
    }

    /** Swaps width and height, keeping the margin. */
    public PageGeometry landscape() {
        return width >= height ? this : new PageGeometry(height, width, margin);
    }

    public PageGeometry portrait() {
        return height >= width ? this : new PageGeometry(height, width, margin);
    }

    public int contentWidth() {
        return width - 2 * margin;
    }

    public int contentHeight() {
        return height - 2 * margin;
    }

    @NonNull
    public Rect contentRect() {
        return new Rect(margin, margin, width - margin, height - margin);
    }
}
