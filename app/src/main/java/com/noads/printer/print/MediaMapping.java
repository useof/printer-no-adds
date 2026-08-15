package com.noads.printer.print;

import android.print.PrintAttributes.MediaSize;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Traduce între numele PWG folosite de IPP ({@code iso_a4_210x297mm}) și
 * {@link MediaSize}, tipul cu care lucrează cadrul de printare din Android.
 *
 * <p>Lista e scurtă intenționat: acoperă formatele pe care le raportează
 * imprimantele de birou. Ce nu e aici cade pe A4, ca peste tot în aplicație.
 */
public final class MediaMapping {

    public static final String DEFAULT_PWG_NAME = "iso_a4_210x297mm";

    private static final class Entry {
        final String pwgName;
        final MediaSize size;

        Entry(String pwgName, MediaSize size) {
            this.pwgName = pwgName;
            this.size = size;
        }
    }

    private static final Entry[] TABLE = {
            new Entry("iso_a3_297x420mm", MediaSize.ISO_A3),
            new Entry(DEFAULT_PWG_NAME, MediaSize.ISO_A4),
            new Entry("iso_a5_148x210mm", MediaSize.ISO_A5),
            new Entry("iso_a6_105x148mm", MediaSize.ISO_A6),
            new Entry("jis_b5_182x257mm", MediaSize.JIS_B5),
            new Entry("na_letter_8.5x11in", MediaSize.NA_LETTER),
            new Entry("na_legal_8.5x14in", MediaSize.NA_LEGAL),
            new Entry("na_index-4x6_4x6in", MediaSize.NA_INDEX_4X6),
            new Entry("na_index-5x8_5x8in", MediaSize.NA_INDEX_5X8),
    };

    private MediaMapping() {
    }

    /** {@code null} pentru un nume PWG pe care Android nu îl are. */
    @Nullable
    public static MediaSize toMediaSize(@Nullable String pwgName) {
        if (pwgName == null) {
            return null;
        }
        for (Entry entry : TABLE) {
            if (entry.pwgName.equals(pwgName)) {
                return entry.size;
            }
        }
        return null;
    }

    /** Numele PWG al unei dimensiuni Android; A4 pentru orice necunoscut. */
    @NonNull
    public static String toPwgName(@Nullable MediaSize size) {
        if (size == null) {
            return DEFAULT_PWG_NAME;
        }
        // Orientarea nu face parte din numele PWG: peisajul e aceeași hârtie.
        String id = size.asPortrait().getId();
        for (Entry entry : TABLE) {
            if (entry.size.getId().equals(id)) {
                return entry.pwgName;
            }
        }
        return DEFAULT_PWG_NAME;
    }
}
