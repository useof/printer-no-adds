package com.noads.printer.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Lays plain text out into a paginated PDF.
 *
 * <p>The whole text is measured once with {@link StaticLayout}, then sliced at
 * line boundaries so no line is cut in half across a page break.
 */
public final class TextToPdf {

    private static final float DEFAULT_TEXT_SIZE_PT = 10f;
    private static final float LINE_SPACING_MULTIPLIER = 1.25f;
    private static final int MAX_PAGES = 2000;

    private TextToPdf() {
    }

    public static void convert(@NonNull CharSequence text,
                               @NonNull PageGeometry geometry,
                               @Nullable String headerTitle,
                               @NonNull File destination) throws IOException {
        convert(text, geometry, headerTitle, DEFAULT_TEXT_SIZE_PT, true, destination);
    }

    /**
     * @param monospace true renders with a monospaced face, which keeps code and
     *                  tabular text aligned.
     */
    public static void convert(@NonNull CharSequence text,
                               @NonNull PageGeometry geometry,
                               @Nullable String headerTitle,
                               float textSizePt,
                               boolean monospace,
                               @NonNull File destination) throws IOException {

        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(textSizePt);
        paint.setTypeface(monospace ? Typeface.MONOSPACE : Typeface.SERIF);

        TextPaint headerPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        headerPaint.setColor(Color.DKGRAY);
        headerPaint.setTextSize(textSizePt * 0.85f);
        headerPaint.setTypeface(Typeface.SANS_SERIF);

        int headerHeight = headerTitle == null ? 0 : (int) Math.ceil(textSizePt * 2f);
        int contentWidth = geometry.contentWidth();
        int contentHeight = geometry.contentHeight() - headerHeight;
        if (contentWidth <= 0 || contentHeight <= 0) {
            throw new IOException("Page margins leave no room for text");
        }

        CharSequence body = text.length() == 0 ? " " : text;
        StaticLayout layout = buildLayout(body, paint, contentWidth);

        PdfDocument document = new PdfDocument();
        try {
            int firstLine = 0;
            int pageNumber = 1;
            int lineCount = layout.getLineCount();

            while (firstLine < lineCount && pageNumber <= MAX_PAGES) {
                int lastLine = lastLineFittingOn(layout, firstLine, contentHeight);
                int pageTop = layout.getLineTop(firstLine);

                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                        geometry.width, geometry.height, pageNumber).create();
                PdfDocument.Page page = document.startPage(info);
                Canvas canvas = page.getCanvas();

                if (headerTitle != null) {
                    drawHeader(canvas, headerPaint, geometry, headerTitle, pageNumber);
                }

                canvas.save();
                canvas.translate(geometry.margin, geometry.margin + headerHeight);
                canvas.clipRect(0, 0, contentWidth, contentHeight);
                canvas.translate(0, -pageTop);
                layout.draw(canvas);
                canvas.restore();

                document.finishPage(page);

                firstLine = lastLine + 1;
                pageNumber++;
            }

            try (OutputStream out = new FileOutputStream(destination)) {
                document.writeTo(out);
            }
        } finally {
            document.close();
        }
    }

    private static void drawHeader(Canvas canvas,
                                   TextPaint paint,
                                   PageGeometry geometry,
                                   String title,
                                   int pageNumber) {
        float baseline = geometry.margin + paint.getTextSize();
        String pageLabel = String.valueOf(pageNumber);
        float pageLabelWidth = paint.measureText(pageLabel);
        float titleMaxWidth = geometry.contentWidth() - pageLabelWidth - 12f;

        CharSequence clipped = android.text.TextUtils.ellipsize(
                title, paint, Math.max(0f, titleMaxWidth), android.text.TextUtils.TruncateAt.MIDDLE);

        canvas.drawText(clipped, 0, clipped.length(), geometry.margin, baseline, paint);
        canvas.drawText(pageLabel, geometry.width - geometry.margin - pageLabelWidth, baseline, paint);
    }

    /**
     * Walks forward from {@code firstLine} while the accumulated height still
     * fits. Always returns at least {@code firstLine} so an over-tall single
     * line cannot stall the loop.
     */
    private static int lastLineFittingOn(StaticLayout layout, int firstLine, int contentHeight) {
        int top = layout.getLineTop(firstLine);
        int line = firstLine;
        while (line + 1 < layout.getLineCount()
                && layout.getLineBottom(line + 1) - top <= contentHeight) {
            line++;
        }
        return line;
    }

    private static StaticLayout buildLayout(CharSequence text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
                .setIncludePad(false)
                .build();
    }
}
