package com.noads.printer.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.noads.printer.PrinterApp;
import com.noads.printer.R;
import com.noads.printer.model.Printer;
import com.noads.printer.model.PrinterCapabilities;
import com.noads.printer.print.PrintJobManager;

/**
 * Adds a printer by address, for the ones mDNS never surfaces (different VLAN,
 * mDNS disabled, or a print server).
 */
public class AddPrinterActivity extends AppCompatActivity {

    private TextInputEditText hostInput;
    private TextInputEditText portInput;
    private TextInputEditText pathInput;
    private TextInputEditText nameInput;
    private CheckBox secureCheckbox;

    private MaterialButton testButton;
    private MaterialButton saveButton;
    private ProgressBar progress;
    private TextView statusView;

    private PrintJobManager jobManager;

    /** Set once a test succeeds, so Save stores the verified details. */
    @Nullable
    private PrinterCapabilities verified;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_printer);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        jobManager = PrinterApp.from(this).jobs();

        hostInput = findViewById(R.id.input_host);
        portInput = findViewById(R.id.input_port);
        pathInput = findViewById(R.id.input_path);
        nameInput = findViewById(R.id.input_name);
        secureCheckbox = findViewById(R.id.checkbox_secure);
        testButton = findViewById(R.id.button_test);
        saveButton = findViewById(R.id.button_save);
        progress = findViewById(R.id.progress);
        statusView = findViewById(R.id.status);

        portInput.setText(String.valueOf(Printer.DEFAULT_IPP_PORT));
        pathInput.setText(R.string.default_resource_path);

        testButton.setOnClickListener(v -> testConnection());
        saveButton.setOnClickListener(v -> save());
    }

    @Nullable
    private Printer buildPrinter() {
        String host = text(hostInput);
        if (host.isEmpty()) {
            setStatus(getString(R.string.error_host_required), true);
            return null;
        }

        int port = Printer.DEFAULT_IPP_PORT;
        String portText = text(portInput);
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                setStatus(getString(R.string.error_bad_port), true);
                return null;
            }
            if (port < 1 || port > 65535) {
                setStatus(getString(R.string.error_bad_port), true);
                return null;
            }
        }

        String path = text(pathInput);
        String name = text(nameInput);
        if (name.isEmpty()) {
            name = host;
        }

        return Printer.fromService(name, host, port, path,
                secureCheckbox.isChecked(), null, null, true);
    }

    private void testConnection() {
        Printer printer = buildPrinter();
        if (printer == null) {
            return;
        }
        verified = null;
        setBusy(true);
        setStatus(getString(R.string.testing_connection, printer.uri), false);

        jobManager.loadCapabilities(printer, new PrintJobManager.CapabilitiesCallback() {
            @Override
            public void onCapabilities(@NonNull PrinterCapabilities capabilities) {
                setBusy(false);
                verified = capabilities;
                StringBuilder sb = new StringBuilder(getString(R.string.printer_reachable));
                if (capabilities.makeAndModel != null) {
                    sb.append('\n').append(capabilities.makeAndModel);
                }
                sb.append('\n').append(capabilities.describeState());
                if (capabilities.chooseFormatForPdf() == null) {
                    sb.append("\n\n").append(getString(R.string.warning_no_pdf_support));
                }
                setStatus(sb.toString(), false);

                if (text(nameInput).isEmpty() && capabilities.name != null) {
                    nameInput.setText(capabilities.name);
                }
            }

            @Override
            public void onFailed(@NonNull Exception error) {
                setBusy(false);
                setStatus(getString(R.string.printer_unreachable, describe(error)), true);
            }
        });
    }

    private void save() {
        Printer printer = buildPrinter();
        if (printer == null) {
            return;
        }
        if (verified != null) {
            printer = printer.withDetails(
                    text(nameInput).isEmpty() ? verified.name : null,
                    verified.makeAndModel,
                    verified.location);
        }
        PrinterApp.from(this).printers().addManual(printer);
        PrinterApp.from(this).printers().setLastSelectedUri(printer.uri);
        finish();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        testButton.setEnabled(!busy);
        saveButton.setEnabled(!busy);
    }

    private void setStatus(@NonNull String message, boolean isError) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(message);
        statusView.setTextColor(getColor(isError ? R.color.error : R.color.on_surface_variant));
    }

    @NonNull
    private static String text(@Nullable TextInputEditText input) {
        return input == null || input.getText() == null ? "" : input.getText().toString().trim();
    }

    @NonNull
    static String describe(@NonNull Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
