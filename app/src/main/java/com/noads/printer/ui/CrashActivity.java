package com.noads.printer.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Shows the stack trace of a crash so it can be read (and copied) without a PC.
 *
 * <p>Runs in the {@code :crash} process — see the manifest — because the process
 * that crashed is on its way out. Everything here is built in code and extends
 * plain {@link Activity} rather than AppCompat: the report must render even when
 * the app's own theme, resources or start-up code are what broke.
 */
public final class CrashActivity extends Activity {

    private static final String EXTRA_TRACE = "com.noads.printer.extra.TRACE";

    @NonNull
    public static Intent intentFor(@NonNull Context context, @NonNull String trace) {
        return new Intent(context, CrashActivity.class)
                .putExtra(EXTRA_TRACE, trace)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String trace = getIntent() != null ? getIntent().getStringExtra(EXTRA_TRACE) : null;
        if (trace == null) {
            trace = "(no stack trace)";
        }

        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Aplicația s-a oprit");
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Copiază textul de mai jos și trimite-l — conține cauza exactă.");
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, padding / 2, 0, padding / 2);
        root.addView(hint);

        TextView body = new TextView(this);
        body.setText(trace);
        body.setTextColor(Color.BLACK);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setTextIsSelectable(true);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        String finalTrace = trace;
        Button copy = new Button(this);
        copy.setText("Copiază");
        copy.setOnClickListener((View v) -> {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("crash", finalTrace));
                Toast.makeText(this, "Copiat", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.gravity = Gravity.END;
        root.addView(copy, buttonParams);

        setContentView(root);
    }
}
