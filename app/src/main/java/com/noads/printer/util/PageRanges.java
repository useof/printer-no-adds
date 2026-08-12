package com.noads.printer.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parses page-range text like {@code 1-3, 5, 8-10} into IPP rangeOfInteger pairs. */
public final class PageRanges {

    private PageRanges() {
    }

    public static final class InvalidRangeException extends Exception {
        public InvalidRangeException(String message) {
            super(message);
        }
    }

    /**
     * @param input     user text; blank means "every page"
     * @param pageCount total pages, or -1 when unknown (bounds are then not checked)
     * @return sorted, merged ranges, or null for "every page"
     */
    @Nullable
    public static int[][] parse(@NonNull String input, int pageCount) throws InvalidRangeException {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        List<int[]> ranges = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }

            int lower;
            int upper;
            int dash = piece.indexOf('-');
            if (dash < 0) {
                lower = upper = parseNumber(piece);
            } else {
                String left = piece.substring(0, dash).trim();
                String right = piece.substring(dash + 1).trim();
                lower = left.isEmpty() ? 1 : parseNumber(left);
                upper = right.isEmpty()
                        ? (pageCount > 0 ? pageCount : lower)
                        : parseNumber(right);
            }

            if (lower > upper) {
                throw new InvalidRangeException("Page " + lower + " comes after " + upper);
            }
            if (pageCount > 0 && lower > pageCount) {
                throw new InvalidRangeException(
                        "The document only has " + pageCount + " page(s)");
            }
            if (pageCount > 0) {
                upper = Math.min(upper, pageCount);
            }
            ranges.add(new int[]{lower, upper});
        }

        if (ranges.isEmpty()) {
            return null;
        }

        Collections.sort(ranges, (a, b) -> Integer.compare(a[0], b[0]));

        List<int[]> merged = new ArrayList<>();
        int[] current = ranges.get(0);
        for (int i = 1; i < ranges.size(); i++) {
            int[] next = ranges.get(i);
            if (next[0] <= current[1] + 1) {
                current[1] = Math.max(current[1], next[1]);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        // "1-N" over the whole document is the same as sending no ranges at all.
        if (pageCount > 0 && merged.size() == 1
                && merged.get(0)[0] == 1 && merged.get(0)[1] == pageCount) {
            return null;
        }

        return merged.toArray(new int[0][]);
    }

    private static int parseNumber(String text) throws InvalidRangeException {
        try {
            int value = Integer.parseInt(text);
            if (value < 1) {
                throw new InvalidRangeException("Pages start at 1");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new InvalidRangeException("\"" + text + "\" is not a page number");
        }
    }

}
