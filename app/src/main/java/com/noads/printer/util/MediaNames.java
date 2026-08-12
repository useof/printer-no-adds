package com.noads.printer.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Turns PWG self-describing media names into something a person would say. */
public final class MediaNames {

    private MediaNames() {
    }

    /**
     * {@code iso_a4_210x297mm} becomes "A4 (210 x 297 mm)";
     * {@code na_letter_8.5x11in} becomes "Letter (8.5 x 11 in)".
     */
    @NonNull
    public static String friendly(@Nullable String pwgName) {
        if (pwgName == null || pwgName.isEmpty()) {
            return "Default";
        }
        // Shape: <region>_<name>_<width>x<height><unit>
        String[] parts = pwgName.split("_");
        if (parts.length < 3) {
            return pwgName;
        }

        String name = parts[1].replace('-', ' ');
        String dimensions = parts[parts.length - 1];

        StringBuilder label = new StringBuilder(capitalizeWords(name));
        String pretty = prettyDimensions(dimensions);
        if (pretty != null) {
            label.append(" (").append(pretty).append(')');
        }
        return label.toString();
    }

    @Nullable
    private static String prettyDimensions(String dimensions) {
        int x = dimensions.indexOf('x');
        if (x <= 0) {
            return null;
        }
        String width = dimensions.substring(0, x);
        String rest = dimensions.substring(x + 1);

        String unit = "";
        int unitStart = rest.length();
        while (unitStart > 0 && Character.isLetter(rest.charAt(unitStart - 1))) {
            unitStart--;
        }
        if (unitStart < rest.length()) {
            unit = rest.substring(unitStart);
            rest = rest.substring(0, unitStart);
        }
        if (width.isEmpty() || rest.isEmpty()) {
            return null;
        }
        return width + " x " + rest + (unit.isEmpty() ? "" : " " + unit);
    }

    /** {@code a4} becomes "A4"; {@code executive} becomes "Executive". */
    private static String capitalizeWords(String text) {
        // Short tokens like a4/a5/b5 read better fully upper-cased.
        if (text.length() <= 3 && text.matches("[a-z]\\d.*")) {
            return text.toUpperCase();
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean atWordStart = true;
        for (char c : text.toCharArray()) {
            if (atWordStart && Character.isLetter(c)) {
                sb.append(Character.toUpperCase(c));
                atWordStart = false;
            } else {
                sb.append(c);
                if (c == ' ' || c == '-') {
                    atWordStart = true;
                }
            }
        }
        return sb.toString();
    }

    /** Labels for the {@code sides} keyword. */
    @NonNull
    public static String sidesLabel(@Nullable String sides) {
        if (sides == null) {
            return "Default";
        }
        switch (sides) {
            case "one-sided": return "Single-sided";
            case "two-sided-long-edge": return "Double-sided (flip on long edge)";
            case "two-sided-short-edge": return "Double-sided (flip on short edge)";
            default: return sides.replace('-', ' ');
        }
    }

    /** Labels for the {@code print-color-mode} keyword. */
    @NonNull
    public static String colorModeLabel(@Nullable String mode) {
        if (mode == null) {
            return "Default";
        }
        switch (mode) {
            case "auto": return "Automatic";
            case "color": return "Colour";
            case "monochrome": return "Black and white";
            case "auto-monochrome": return "Automatic black and white";
            case "bi-level": return "Black and white (no shades)";
            default: return mode.replace('-', ' ');
        }
    }

    @NonNull
    public static String qualityLabel(int quality) {
        switch (quality) {
            case 3: return "Draft";
            case 5: return "High";
            case 4:
            default: return "Normal";
        }
    }
}
