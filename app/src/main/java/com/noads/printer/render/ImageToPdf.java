package com.noads.printer.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
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
     * 8.7 megapixels — dincolo de atât nu se mai câștigă nimic la tipar, doar
     * memorie consumată de o poză de 108 MP și octeți în plus în JPEG.
     */
    private static final int MAX_PIXELS = 9_000_000;

    /**
     * Calitatea JPEG a imaginii înglobate. 90 e vizual indistinct de original la
     * tipar și ține o poză de telefon pe A4 sub 2 MB — necomprimată, aceeași poză
     * dădea un PDF de peste 20 MB, pe care imprimanta îl accepta și apoi îl lăsa
     * blocat în „processing", fără să scoată hârtia.
     */
    private static final int JPEG_QUALITY = 90;

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

        try (OutputStream out = new FileOutputStream(destination);
             JpegPdfWriter writer = new JpegPdfWriter(out)) {
            for (Uri image : images) {
                Bitmap bitmap = decode(context, image, geometry);
                if (bitmap == null) {
                    throw new IOException("Cannot decode image: " + image);
                }
                try {
                    addPage(writer, bitmap, geometry, fitToPage);
                } finally {
                    bitmap.recycle();
                }
            }
        }
    }

    /** Așază poza pe pagină, centrată, cât de mare încape, fără să o rotească. */
    private static void addPage(JpegPdfWriter writer,
                                Bitmap bitmap,
                                PageGeometry geometry,
                                boolean fitToPage) throws IOException {
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
        // PDF numără de jos în sus, spre deosebire de Rect.
        float bottom = geometry.height - (top + drawHeight);

        byte[] jpeg = toJpeg(bitmap);
        writer.addPage(geometry.width, geometry.height,
                jpeg, bitmap.getWidth(), bitmap.getHeight(),
                left, bottom, drawWidth, drawHeight);
    }

    /**
     * Comprimă poza ca JPEG. Transparența se aplatizează peste alb: JPEG nu are
     * canal alfa, iar fără asta zonele transparente ale unui PNG ies negre.
     */
    private static byte[] toJpeg(Bitmap bitmap) throws IOException {
        Bitmap opaque = bitmap;
        boolean recycleOpaque = false;
        if (bitmap.hasAlpha()) {
            opaque = Bitmap.createBitmap(
                    bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(opaque);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(bitmap, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            recycleOpaque = true;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) {
                throw new IOException("Could not compress the image");
            }
            return buffer.toByteArray();
        } finally {
            if (recycleOpaque) {
                opaque.recycle();
            }
        }
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
