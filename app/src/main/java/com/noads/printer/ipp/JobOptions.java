package com.noads.printer.ipp;

import androidx.annotation.Nullable;

/** User-selectable settings for one print job. */
public final class JobOptions {

    public static final String SIDES_ONE_SIDED = "one-sided";
    public static final String SIDES_TWO_SIDED_LONG_EDGE = "two-sided-long-edge";
    public static final String SIDES_TWO_SIDED_SHORT_EDGE = "two-sided-short-edge";

    public static final String COLOR_AUTO = "auto";
    public static final String COLOR_COLOR = "color";
    public static final String COLOR_MONOCHROME = "monochrome";

    /** {@code orientation-requested} enum values from RFC 8011. */
    public static final int ORIENTATION_PORTRAIT = 3;
    public static final int ORIENTATION_LANDSCAPE = 4;

    public static final int QUALITY_DRAFT = 3;
    public static final int QUALITY_NORMAL = 4;
    public static final int QUALITY_HIGH = 5;

    public String jobName = "Document";
    public int copies = 1;
    public String sides = SIDES_ONE_SIDED;
    public String colorMode = COLOR_AUTO;
    public int quality = QUALITY_NORMAL;

    /**
     * Trimis ca {@code orientation-requested}. Pentru documentele generate de
     * aplicație (imagini, text, pagini web) orientarea e deja aplicată la
     * randare — atributul rămâne util pentru PDF-urile trimise ca atare.
     */
    public int orientation = ORIENTATION_PORTRAIT;

    /** PWG media name, e.g. {@code iso_a4_210x297mm}. Null leaves it to the printer. */
    @Nullable
    public String media;

    /** PWG media-source name, e.g. {@code tray-1}. Null leaves it to the printer. */
    @Nullable
    public String mediaSource;

    /** 1-based inclusive page ranges, or null for every page. */
    @Nullable
    public int[][] pageRanges;

}
