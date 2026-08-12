package com.noads.printer.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.noads.printer.PrinterApp;
import com.noads.printer.R;
import com.noads.printer.discovery.PrinterDiscovery;
import com.noads.printer.model.Printer;
import com.noads.printer.model.PrinterRepository;
import com.noads.printer.print.PrintSource;

import java.util.List;

/**
 * Printer list plus the entry points for choosing something to print.
 *
 * <p>Discovery runs only while this screen is visible, so the multicast lock is
 * not held in the background.
 */
public class MainActivity extends AppCompatActivity implements PrinterAdapter.Listener {

    private PrinterRepository repository;
    private PrinterDiscovery discovery;
    private PrinterAdapter adapter;

    private SwipeRefreshLayout swipeRefresh;
    private View emptyState;
    private TextView emptyStateMessage;

    private final Runnable repositoryObserver = this::refreshList;

    private ActivityResultLauncher<String[]> pickDocument;
    private ActivityResultLauncher<String[]> pickImages;

    /** A share intent held until the user has picked a printer. */
    @Nullable
    private PrintSource pendingSource;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        repository = PrinterApp.from(this).printers();
        discovery = new PrinterDiscovery(this);
        discovery.setListener(new DiscoveryListener());

        adapter = new PrinterAdapter(this);
        RecyclerView list = findViewById(R.id.printer_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        // Fereastră edge-to-edge: ultima imprimantă din listă ar sta altfel sub
        // butoanele de navigație. Bara de sus e acoperită de fitsSystemWindows
        // pe AppBarLayout.
        SystemBars.padBottom(list);

        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setOnRefreshListener(this::rescan);

        emptyState = findViewById(R.id.empty_state);
        emptyStateMessage = findViewById(R.id.empty_state_message);

        findViewById(R.id.action_print_document).setOnClickListener(v -> choosePrintable(
                new String[]{"application/pdf", "text/*"}));
        findViewById(R.id.action_print_photo).setOnClickListener(v -> choosePhotos());
        findViewById(R.id.action_print_web).setOnClickListener(v -> promptForUrl());
        findViewById(R.id.action_add_printer).setOnClickListener(v ->
                startActivity(new Intent(this, AddPrinterActivity.class)));

        registerPickers();
        handleIncomingIntent(getIntent());
    }

    private void registerPickers() {
        pickDocument = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    takePersistablePermission(uri);
                    PrintSource source = PrintSource.fromUri(this, uri);
                    if (source == null) {
                        showMessage(getString(R.string.error_unsupported_file));
                        return;
                    }
                    startPrintFlow(source);
                });

        pickImages = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris == null || uris.isEmpty()) {
                        return;
                    }
                    for (Uri uri : uris) {
                        takePersistablePermission(uri);
                    }
                    startPrintFlow(PrintSource.fromImages(this, uris));
                });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.addObserver(repositoryObserver);
        refreshList();
        startDiscovery();
    }

    @Override
    protected void onStop() {
        super.onStop();
        repository.removeObserver(repositoryObserver);
        discovery.stop();
        swipeRefresh.setRefreshing(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        discovery.setListener(null);
    }

    /* ------------------------------------------------------------------ */
    /* Discovery                                                          */
    /* ------------------------------------------------------------------ */

    private void startDiscovery() {
        swipeRefresh.setRefreshing(true);
        discovery.start();
        // mDNS has no "done" event; stop the spinner once responses have had
        // time to arrive so the UI does not spin forever on an empty network.
        swipeRefresh.postDelayed(() -> swipeRefresh.setRefreshing(false), 5_000);
    }

    private void rescan() {
        repository.clearDiscovered();
        discovery.restart();
        swipeRefresh.postDelayed(() -> swipeRefresh.setRefreshing(false), 5_000);
    }

    private final class DiscoveryListener implements PrinterDiscovery.Listener {
        @Override
        public void onPrinterFound(@NonNull Printer printer) {
            repository.onDiscovered(printer);
        }

        @Override
        public void onPrinterLost(@NonNull String serviceName) {
            repository.onLost(serviceName);
        }

        @Override
        public void onDiscoveryFailed(@NonNull String message) {
            swipeRefresh.setRefreshing(false);
            showMessage(message);
        }
    }

    private void refreshList() {
        List<Printer> printers = repository.all();
        adapter.submit(printers, repository.lastSelectedUri());

        boolean empty = printers.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyStateMessage.setText(discovery.isRunning()
                ? R.string.empty_searching
                : R.string.empty_not_searching);
    }

    /* ------------------------------------------------------------------ */
    /* Printer selection                                                  */
    /* ------------------------------------------------------------------ */

    @Override
    public void onPrinterClicked(@NonNull Printer printer) {
        repository.setLastSelectedUri(printer.uri);
        refreshList();

        if (pendingSource != null) {
            PrintSource source = pendingSource;
            pendingSource = null;
            openPrintJob(source, printer);
        } else {
            showMessage(getString(R.string.printer_selected, printer.name));
        }
    }

    @Override
    public void onPrinterLongClicked(@NonNull Printer printer) {
        if (!repository.isManual(printer.uri)) {
            showMessage(getString(R.string.cannot_remove_discovered));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.remove_printer_title)
                .setMessage(getString(R.string.remove_printer_message, printer.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.remove, (d, which) -> {
                    repository.remove(printer.uri);
                    if (printer.uri.equals(repository.lastSelectedUri())) {
                        repository.setLastSelectedUri(null);
                    }
                    refreshList();
                })
                .show();
    }

    /* ------------------------------------------------------------------ */
    /* Choosing what to print                                             */
    /* ------------------------------------------------------------------ */

    private void choosePrintable(String[] mimeTypes) {
        pickDocument.launch(mimeTypes);
    }

    private void choosePhotos() {
        pickImages.launch(new String[]{"image/*"});
    }

    private void promptForUrl() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.url_hint);
        int padding = getResources().getDimensionPixelSize(R.dimen.dialog_padding);
        input.setPadding(padding, padding, padding, padding);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.print_web_page)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.next, (d, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) {
                        showMessage(getString(R.string.error_empty_url));
                        return;
                    }
                    startPrintFlow(PrintSource.fromUrl(url));
                })
                .show();
    }

    /** Routes to the job screen, asking for a printer first if none is chosen. */
    private void startPrintFlow(@NonNull PrintSource source) {
        Printer printer = repository.defaultPrinter();
        if (printer == null) {
            pendingSource = source;
            showMessage(getString(R.string.select_a_printer_first));
            return;
        }
        openPrintJob(source, printer);
    }

    private void openPrintJob(@NonNull PrintSource source, @NonNull Printer printer) {
        startActivity(PrintJobActivity.intentFor(this, source, printer.uri));
    }

    /* ------------------------------------------------------------------ */
    /* Incoming share intents                                             */
    /* ------------------------------------------------------------------ */

    private void handleIncomingIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (action == null) {
            return;
        }

        Uri uri = null;
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null) {
                String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (text != null && !text.trim().isEmpty()) {
                    String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
                    startPrintFlow(PrintSource.fromText(text,
                            subject != null && !subject.isEmpty()
                                    ? subject
                                    : getString(R.string.shared_text)));
                    // Consumed; do not process again on rotation.
                    intent.setAction(null);
                    return;
                }
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        }

        if (uri == null) {
            return;
        }

        PrintSource source = PrintSource.fromUri(this, uri);
        if (source == null) {
            showMessage(getString(R.string.error_unsupported_file));
        } else {
            startPrintFlow(source);
        }
        intent.setAction(null);
    }

    /**
     * Keeps read access alive past this activity so the job screen can still
     * open the file. Providers may refuse, which is fine for a same-session read.
     */
    private void takePersistablePermission(@NonNull Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException notPersistable) {
            // Non-persistable grants still work for the lifetime of the task.
        }
    }

    /* ------------------------------------------------------------------ */
    /* Menu                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_rescan) {
            swipeRefresh.setRefreshing(true);
            rescan();
            return true;
        }
        if (id == R.id.menu_add_printer) {
            startActivity(new Intent(this, AddPrinterActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showMessage(@NonNull String message) {
        Snackbar.make(findViewById(R.id.root), message, Snackbar.LENGTH_LONG).show();
    }
}
