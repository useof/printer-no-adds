package com.noads.printer.ui;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Ține conținutul în afara barelor de sistem.
 *
 * <p>Cu {@code targetSdk 35} fereastra e edge-to-edge din oficiu pe Android 15+:
 * sistemul nu mai lasă loc pentru bara de stare și cea de navigație, deci un
 * buton lipit de marginea de jos ajunge sub butoanele de navigație și nu mai
 * poate fi apăsat. Aici se adaugă spațiul lipsă ca padding.
 *
 * <p>Padding-ul inițial din layout e reținut și adunat de fiecare dată, ca
 * aplicările repetate (rotire, apariția tastaturii) să nu-l cumuleze.
 */
final class SystemBars {

    private SystemBars() {
    }

    /** Adaugă înălțimea barei de navigație sub {@code view}. */
    static void padBottom(@NonNull View view) {
        apply(view, false, true);
    }

    /** Adaugă înălțimea barei de stare deasupra lui {@code view}. */
    static void padTop(@NonNull View view) {
        apply(view, true, false);
    }

    private static void apply(@NonNull View view, boolean top, boolean bottom) {
        int initialTop = view.getPaddingTop();
        int initialBottom = view.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    v.getPaddingLeft(),
                    top ? initialTop + bars.top : initialTop,
                    v.getPaddingRight(),
                    bottom ? initialBottom + bars.bottom : initialBottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}
