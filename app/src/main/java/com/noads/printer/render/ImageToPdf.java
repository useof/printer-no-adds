package com.noads.printer.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Wraps one or more images into a printable PDF, one image per page. */
public final class ImageToPdf {

    /**
     * Cap on the decoded pixel count. 300 dpi across a full A4 page is roughly
     * 8.7 megapixels, so 12 MP keeps quality headroom while stopping a 108 MP
     * phone photo from taking the app out of memory.
     */
    private static final int MAX_PIXELS = 12_000_000;

    private ImageToPdf() {
    }

    /**
     * Renders every image in {@code images} into {@code destination}.
     *
     * @param fitToPage true scales the image to fill the page while keeping its
     *                  aspect ratio; false keeps it inside the printable margins.
     */
    public static void convert(@NonNull Context context,
                               @NonNull List<Uri> images,
                               @NonNull PageGeometry geometry,
                               boolean fitToPage,
                               @NonNull File destination) throws IOException {
        if (images.isEmpty()) {
            throw new IOException("No images to print");
        }

        PdfDocument document = new PdfDocument();
        try {
            int pageNumber = 1;
            for (Uri image : images) {
                Bitmap bitmap = decode(context, image, geometry);
                if (bitmap == null) {
                    throw new IOException("Cannot decode image: " + image);
                }
                try {
                    // Pagina păstrează orientarea cerută de utilizator; poza e
                    // rotită dacă e nevoie (vezi drawCentered).
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                            geometry.width, geometry.height, pageNumber++).create();
                    PdfDocument.Page page = document.startPage(info);
                    drawCentered(page.getCanvas(), bitmap, geometry, fitToPage);
                    document.finishPage(page);
                } finally {
                    bitmap.recycle();
                }
            }

            try (OutputStream out = new FileOutputStream(destination)) {
                document.writeTo(out);
            }
        } finally {
            document.close();
        }
    }

    /**
     * Desenează poza centrată pe pagină, cât de mare încape, în orientarea ei
     * originală.
     *
     * <p>Poza nu se rotește niciodată: orientarea o dă hârtia, aleasă de
     * utilizator. O poză lată pe o pagină portret rămâne lată, cu spațiu gol
     * deasupra și dedesubt — pentru a o vedea mare, orientarea de ales e peisaj.
     */
    private static void drawCentered(Canvas canvas,
                                     Bitmap bitmap,
                                     PageGeometry geometry,
                                     boolean fitToPage) {
        Rect target = fitToPage
                ? new Rect(0, 0, geometry.width, geometry.height)
                : geometry.contentRect();

        float scale = Math.min(
                (float) target.width() / bitmap.getWidth(),
                (float) target.height() / bitmap.getHeight());

        float drawWidth = bitmap.getWidth() * scale;
        float drawHeight = bitmap.getHeight() * scale;
        float left = target.left + (target.width() - drawWidth) / 2f;
        float top = target.top + (target.height() - drawHeight) / 2f;

        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        canvas.drawBitmap(bitmap, null,
                new RectF(left, top, left + drawWidth, top + drawHeight), paint);
    }

    /**
     * Decodes at roughly the resolution the page needs, then applies the EXIF
     * rotation so portrait photos are not printed sideways.
     */
    private static Bitmap decode(Context context, Uri uri, PageGeometry geometry)
            throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(context, uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Not a readable image: " + uri);
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, geometry);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap decoded;
        try (InputStream in = open(context, uri)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) {
            return null;
        }

        int rotation = exifRotation(context, uri);
        if (rotation == 0) {
            return decoded;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        try {
            Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0,
                    decoded.getWidth(), decoded.getHeight(), matrix, true);
            if (rotated != decoded) {
                decoded.recycle();
            }
            return rotated;
        } catch (OutOfMemoryError e) {
            // Printing sideways beats crashing.
            return decoded;
        }
    }

    /**
     * Picks the power-of-two subsample that lands just above 300 dpi for this
     * page while staying under {@link #MAX_PIXELS}.
     */
    private static int sampleSizeFor(int width, int height, PageGeometry geometry) {
        // 300 dpi over a 72-dpi page box means ~4.17 device pixels per point.
        int targetWidth = (int) (geometry.width * 300f / 72f);
        int targetHeight = (int) (geometry.height * 300f / 72f);

        int sample = 1;
        while ((width / sample) > targetWidth * 2 || (height / sample) > targetHeight * 2) {
            sample *= 2;
        }
        while ((long) (width / sample) * (height / sample) > MAX_PIXELS) {
            sample *= 2;
        }
        return sample;
    }

    private static int exifRotation(Context context, Uri uri) {
        try (InputStream in = open(context, uri)) {
            int orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) {
            // PNG and WebP carry no EXIF; treat that as "no rotation".
            return 0;
        }
    }

    private static InputStream open(Context context, Uri uri) throws IOException {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("Cannot open " + uri);
        }
        return in;
    }
}
