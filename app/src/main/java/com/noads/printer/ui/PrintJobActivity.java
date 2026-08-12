package com.noads.printer.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.noads.printer.PrinterApp;
import com.noads.printer.R;
import com.noads.printer.ipp.Ipp;
import com.noads.printer.ipp.JobOptions;
import com.noads.printer.model.Printer;
import com.noads.printer.model.PrinterCapabilities;
import com.noads.printer.model.PrinterRepository;
import com.noads.printer.print.DocumentPreparer;
import com.noads.printer.print.PrintJobManager;
import com.noads.printer.print.PrintSource;
import com.noads.printer.render.PageGeometry;
import com.noads.printer.util.DocumentUtils;
import com.noads.printer.util.MediaNames;
import com.noads.printer.util.PageRanges;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Preview, options, and submission for one document.
 *
 * <p>Order matters: capabilities come first because the printer's default media
 * decides the page size the converters render at.
 */
public class PrintJobActivity extends AppCompatActivity {

    private static final String EXTRA_KIND = "kind";
    private static final String EXTRA_URIS = "uris";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TEXT = "text";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_SIZE = "size";
    private static final String EXTRA_PRINTER_URI = "printer_uri";

    /** Builds the intent that carries a source across to this screen. */
    public static Intent intentFor(@NonNull Context context,
                                   @NonNull PrintSource source,
                                   @NonNull String printerUri) {
        Intent intent = new Intent(context, PrintJobActivity.class);
        intent.putExtra(EXTRA_KIND, source.kind.name());
        intent.putParcelableArrayListExtra(EXTRA_URIS, new ArrayList<>(source.uris));
        intent.putExtra(EXTRA_URL, source.url);
        intent.putExtra(EXTRA_TEXT, source.inlineText);
        intent.putExtra(EXTRA_NAME, source.displayName);
        intent.putExtra(EXTRA_SIZE, source.sizeBytes);
        intent.putExtra(EXTRA_PRINTER_URI, printerUri);
        // Pass the read grant along so the job screen can open the document.
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private PrinterRepository repository;
    private PrintJobManager jobManager;
    private DocumentPreparer preparer;
    private PdfPreviewRenderer previewRenderer;

    private Printer printer;
    private PrintSource source;

    @Nullable
    private PrinterCapabilities capabilities;
    @Nullable
    private File preparedPdf;
    @Nullable
    private PrintJobManager.Submission submission;

    private int pageCount;
    private int currentPage;
    private boolean jobInFlight;

    /** The media the current PDF was rendered for, so a change can trigger a redo. */
    @Nullable
    private String renderedForMedia;

    /** Same, for the page orientation. */
    private int renderedForOrientation;

    private ViewGroup root;
    private ViewGroup webContainer;
    private ImageView previewImage;
    private TextView previewPageLabel;
    private ImageButton previousPageButton;
    private ImageButton nextPageButton;
    private ProgressBar previewProgress;

    private TextView documentTitle;
    private TextView documentSubtitle;
    private TextView printerNameView;
    private TextView printerStateView;

    private EditText copiesInput;
    private EditText pageRangeInput;
    private Spinner mediaSpinner;
    private Spinner orientationSpinner;
    private Spinner sidesSpinner;
    private Spinner colorSpinner;
    private Spinner qualitySpinner;

    private View optionsPanel;
    private MaterialButton printButton;
    private ProgressBar uploadProgress;
    private TextView statusView;

    private final List<Integer> orientationValues = Arrays.asList(
            JobOptions.ORIENTATION_PORTRAIT, JobOptions.ORIENTATION_LANDSCAPE);

    private List<String> mediaValues = new ArrayList<>();
    private List<String> sidesValues = new ArrayList<>();
    private List<String> colorValues = new ArrayList<>();
    private List<Integer> qualityValues = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_print_job);

        repository = PrinterApp.from(this).printers();
        jobManager = PrinterApp.from(this).jobs();
        preparer = new DocumentPreparer(this);
        previewRenderer = new PdfPreviewRenderer();

        bindViews();

        source = readSource(getIntent());
        String printerUri = getIntent().getStringExtra(EXTRA_PRINTER_URI);
        printer = printerUri == null ? null : repository.byUri(printerUri);

        if (source == null || printer == null) {
            showFatal(getString(R.string.error_job_setup));
            return;
        }

        documentTitle.setText(source.displayName);
        documentSubtitle.setText(describeSource(source));
        printerNameView.setText(printer.name);
        printerStateView.setText(R.string.checking_printer);

        loadCapabilities();
    }

    private void bindViews() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // Fereastra e edge-to-edge (targetSdk 35): fără asta bara de sus intră sub
        // bara de stare, iar butonul de print sub butoanele de navigație.
        SystemBars.padTop(toolbar);
        SystemBars.padBottom(findViewById(R.id.action_bar_container));

        root = findViewById(R.id.root);
        webContainer = findViewById(R.id.web_render_container);
        previewImage = findViewById(R.id.preview_image);
        previewPageLabel = findViewById(R.id.preview_page_label);
        previousPageButton = findViewById(R.id.preview_previous);
        nextPageButton = findViewById(R.id.preview_next);
        previewProgress = findViewById(R.id.preview_progress);

        documentTitle = findViewById(R.id.document_title);
        documentSubtitle = findViewById(R.id.document_subtitle);
        printerNameView = findViewById(R.id.printer_name);
        printerStateView = findViewById(R.id.printer_state);

        copiesInput = findViewById(R.id.input_copies);
        pageRangeInput = findViewById(R.id.input_page_range);
        mediaSpinner = findViewById(R.id.spinner_media);
        orientationSpinner = findViewById(R.id.spinner_orientation);
        sidesSpinner = findViewById(R.id.spinner_sides);
        colorSpinner = findViewById(R.id.spinner_color);
        qualitySpinner = findViewById(R.id.spinner_quality);

        optionsPanel = findViewById(R.id.options_panel);
        printButton = findViewById(R.id.button_print);
        uploadProgress = findViewById(R.id.upload_progress);
        statusView = findViewById(R.id.status);

        previousPageButton.setOnClickListener(v -> showPage(currentPage - 1));
        nextPageButton.setOnClickListener(v -> showPage(currentPage + 1));
        printButton.setOnClickListener(v -> onPrintClicked());
        printButton.setEnabled(false);

        findViewById(R.id.button_change_printer).setOnClickListener(v -> finish());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (submission != null) {
            // Stops the polling loop. The job itself stays on the printer -
            // leaving this screen should not silently cancel a print.
            submission.cancel();
        }
        previewRenderer.close();
        preparer.shutdown();
    }

    /* ------------------------------------------------------------------ */
    /* Step 1: capabilities                                               */
    /* ------------------------------------------------------------------ */

    private void loadCapabilities() {
        jobManager.loadCapabilities(printer, new PrintJobManager.CapabilitiesCallback() {
            @Override
            public void onCapabilities(@NonNull PrinterCapabilities loaded) {
                capabilities = loaded;
                repository.updateDetails(printer.uri, loaded.name,
                        loaded.makeAndModel, loaded.location);

                String state = loaded.describeState();
                String supplies = loaded.describeSupplies();
                printerStateView.setText(supplies.isEmpty() ? state : state + "\n" + supplies);
                populateOptions(loaded);

                if (!loaded.acceptingJobs) {
                    setStatus(getString(R.string.printer_not_accepting_jobs), true);
                }
                prepareDocument();
            }

            @Override
            public void onFailed(@NonNull Exception error) {
                // The printer may simply not answer Get-Printer-Attributes.
                // Fall back to sane defaults rather than blocking the user.
                printerStateView.setText(getString(R.string.printer_unreachable_short));
                setStatus(getString(R.string.capabilities_failed,
                        AddPrinterActivity.describe(error)), true);
                populateOptions(null);
                prepareDocument();
            }
        });
    }

    private void populateOptions(@Nullable PrinterCapabilities caps) {
        optionsPanel.setVisibility(View.VISIBLE);
        copiesInput.setText("1");

        mediaValues = new ArrayList<>();
        List<String> mediaLabels = new ArrayList<>();
        if (caps != null && !caps.media.isEmpty()) {
            for (String media : caps.media) {
                mediaValues.add(media);
                mediaLabels.add(MediaNames.friendly(media));
            }
        } else {
            mediaValues.add("iso_a4_210x297mm");
            mediaLabels.add(MediaNames.friendly("iso_a4_210x297mm"));
            mediaValues.add("na_letter_8.5x11in");
            mediaLabels.add(MediaNames.friendly("na_letter_8.5x11in"));
        }
        setSpinner(mediaSpinner, mediaLabels,
                indexOf(mediaValues, caps == null ? null : caps.defaultMedia));

        // Ambele orientări sunt oferite mereu: pentru documentele generate aici
        // orientarea e aplicată la randare, deci nu depinde de ce raportează
        // imprimanta.
        setSpinner(orientationSpinner,
                Arrays.asList(getString(R.string.orientation_portrait),
                        getString(R.string.orientation_landscape)),
                0);

        sidesValues = new ArrayList<>();
        List<String> sidesLabels = new ArrayList<>();
        List<String> supportedSides = caps != null && !caps.sides.isEmpty()
                ? caps.sides
                : Collections.singletonList(JobOptions.SIDES_ONE_SIDED);
        for (String side : supportedSides) {
            sidesValues.add(side);
            sidesLabels.add(MediaNames.sidesLabel(side));
        }
        setSpinner(sidesSpinner, sidesLabels,
                indexOf(sidesValues, caps == null ? null : caps.defaultSides));

        colorValues = new ArrayList<>();
        List<String> colorLabels = new ArrayList<>();
        if (caps != null && !caps.colorModes.isEmpty()) {
            for (String mode : caps.colorModes) {
                colorValues.add(mode);
                colorLabels.add(MediaNames.colorModeLabel(mode));
            }
        } else {
            colorValues.add(JobOptions.COLOR_AUTO);
            colorLabels.add(MediaNames.colorModeLabel(JobOptions.COLOR_AUTO));
            colorValues.add(JobOptions.COLOR_MONOCHROME);
            colorLabels.add(MediaNames.colorModeLabel(JobOptions.COLOR_MONOCHROME));
        }
        setSpinner(colorSpinner, colorLabels,
                indexOf(colorValues, caps == null ? null : caps.defaultColorMode));

        qualityValues = new ArrayList<>();
        List<String> qualityLabels = new ArrayList<>();
        List<Integer> supportedQualities = caps != null && !caps.qualities.isEmpty()
                ? caps.qualities
                : Arrays.asList(JobOptions.QUALITY_DRAFT,
                        JobOptions.QUALITY_NORMAL, JobOptions.QUALITY_HIGH);
        for (int quality : supportedQualities) {
            qualityValues.add(quality);
            qualityLabels.add(MediaNames.qualityLabel(quality));
        }
        int normalIndex = Math.max(0, qualityValues.indexOf(JobOptions.QUALITY_NORMAL));
        setSpinner(qualitySpinner, qualityLabels, normalIndex);

        watchRenderOptions();
    }

    /**
     * Images, text, and web pages are rendered at a specific page size and
     * orientation, so changing either means the PDF has to be built again. A PDF
     * chosen by the user is sent as-is and is left alone — for it, the
     * orientation only travels as {@code orientation-requested}.
     */
    private void watchRenderOptions() {
        AdapterView.OnItemSelectedListener listener =
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view,
                                               int position, long id) {
                        onRenderOptionChanged();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                };
        mediaSpinner.setOnItemSelectedListener(listener);
        orientationSpinner.setOnItemSelectedListener(listener);
    }

    private void onRenderOptionChanged() {
        if (renderedForMedia == null || source.kind == PrintSource.Kind.PDF) {
            // Nothing rendered yet, or nothing to re-render.
            return;
        }
        String media = selectedMedia();
        if (media == null) {
            return;
        }
        if (media.equals(renderedForMedia) && selectedOrientation() == renderedForOrientation) {
            return;
        }
        prepareDocument();
    }

    private int selectedOrientation() {
        int position = orientationSpinner.getSelectedItemPosition();
        return position >= 0 && position < orientationValues.size()
                ? orientationValues.get(position)
                : JobOptions.ORIENTATION_PORTRAIT;
    }

    private void setSpinner(Spinner spinner, List<String> labels, int selectedIndex) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (selectedIndex >= 0 && selectedIndex < labels.size()) {
            spinner.setSelection(selectedIndex);
        }
        spinner.setEnabled(labels.size() > 1);
    }

    private static int indexOf(List<String> values, @Nullable String candidate) {
        if (candidate == null) {
            return 0;
        }
        int index = values.indexOf(candidate);
        return index >= 0 ? index : 0;
    }

    /* ------------------------------------------------------------------ */
    /* Step 2: prepare the PDF                                            */
    /* ------------------------------------------------------------------ */

    private void prepareDocument() {
        previewProgress.setVisibility(View.VISIBLE);
        printButton.setEnabled(false);
        setStatus(getString(R.string.preparing_document), false);

        renderedForMedia = selectedMedia();
        renderedForOrientation = selectedOrientation();
        PageGeometry geometry = PageGeometry.forMedia(renderedForMedia);
        geometry = renderedForOrientation == JobOptions.ORIENTATION_LANDSCAPE
                ? geometry.landscape()
                : geometry.portrait();
        preparer.prepare(source, geometry, webContainer, new DocumentPreparer.Callback() {
            @Override
            public void onPrepared(@NonNull File pdf) {
                preparedPdf = pdf;
                clearStatus();
                openPreview(pdf);
            }

            @Override
            public void onFailed(@NonNull Exception error) {
                previewProgress.setVisibility(View.GONE);
                showFatal(getString(R.string.error_preparing,
                        AddPrinterActivity.describe(error)));
            }
        });
    }

    private void openPreview(File pdf) {
        previewRenderer.open(pdf, new PdfPreviewRenderer.OpenCallback() {
            @Override
            public void onOpened(int pages) {
                pageCount = pages;
                previewProgress.setVisibility(View.GONE);
                printButton.setEnabled(true);
                documentSubtitle.setText(getResources().getQuantityString(
                        R.plurals.page_count, pages, pages)
                        + " · " + DocumentUtils.formatSize(pdf.length()));
                showPage(0);
            }

            @Override
            public void onFailed(@NonNull Exception error) {
                previewProgress.setVisibility(View.GONE);
                // A PDF that will not render locally may still print, so this
                // only disables the preview rather than the whole screen.
                previewPageLabel.setText(R.string.preview_unavailable);
                printButton.setEnabled(true);
            }
        });
    }

    private void showPage(int index) {
        if (index < 0 || index >= pageCount) {
            return;
        }
        currentPage = index;
        previousPageButton.setEnabled(index > 0);
        nextPageButton.setEnabled(index < pageCount - 1);
        previewPageLabel.setText(getString(R.string.page_of, index + 1, pageCount));

        int width = previewImage.getWidth();
        if (width <= 0) {
            // First layout has not happened yet; retry once it has.
            previewImage.post(() -> showPage(index));
            return;
        }

        previewRenderer.renderPage(index, width, new PdfPreviewRenderer.PageCallback() {
            @Override
            public void onPageRendered(int pageIndex, @NonNull Bitmap bitmap) {
                if (pageIndex == currentPage) {
                    previewImage.setImageBitmap(bitmap);
                } else {
                    bitmap.recycle();
                }
            }

            @Override
            public void onFailed(@NonNull Exception error) {
                previewPageLabel.setText(R.string.preview_unavailable);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Step 3: print                                                      */
    /* ------------------------------------------------------------------ */

    private void onPrintClicked() {
        if (jobInFlight) {
            cancelCurrentJob();
            return;
        }
        if (preparedPdf == null) {
            return;
        }

        JobOptions options;
        try {
            options = buildOptions();
        } catch (PageRanges.InvalidRangeException e) {
            pageRangeInput.setError(e.getMessage());
            return;
        }

        String format = capabilities != null
                ? capabilities.chooseFormatForPdf()
                : PrinterCapabilities.FORMAT_PDF;
        if (format == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.unsupported_printer_title)
                    .setMessage(R.string.unsupported_printer_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        setJobInFlight(true);
        uploadProgress.setVisibility(View.VISIBLE);
        uploadProgress.setIndeterminate(true);

        submission = jobManager.submit(printer, preparedPdf, format, options, new JobListener());
    }

    /** While a job is in flight the print button doubles as the cancel button. */
    private void setJobInFlight(boolean inFlight) {
        jobInFlight = inFlight;
        printButton.setEnabled(true);
        printButton.setText(inFlight ? R.string.cancel_job : R.string.print_now);
    }

    private void cancelCurrentJob() {
        if (submission == null) {
            return;
        }
        printButton.setEnabled(false);
        setStatus(getString(R.string.stage_cancelling), false);

        jobManager.cancel(submission, (cancelled, error) -> {
            submission = null;
            uploadProgress.setVisibility(View.GONE);
            setJobInFlight(false);
            if (cancelled) {
                setStatus(getString(R.string.job_canceled), false);
            } else {
                // The printer may have already committed the pages to paper.
                setStatus(getString(R.string.error_cancelling,
                        error == null ? "" : AddPrinterActivity.describe(error)), true);
            }
        });
    }

    private JobOptions buildOptions() throws PageRanges.InvalidRangeException {
        JobOptions options = new JobOptions();
        options.jobName = source.jobName();
        options.copies = parseCopies();
        options.media = selectedMedia();
        options.sides = selected(sidesValues, sidesSpinner, JobOptions.SIDES_ONE_SIDED);
        options.colorMode = selected(colorValues, colorSpinner, JobOptions.COLOR_AUTO);

        options.orientation = selectedOrientation();

        int qualityIndex = qualitySpinner.getSelectedItemPosition();
        options.quality = qualityIndex >= 0 && qualityIndex < qualityValues.size()
                ? qualityValues.get(qualityIndex)
                : JobOptions.QUALITY_NORMAL;

        pageRangeInput.setError(null);
        options.pageRanges = PageRanges.parse(
                pageRangeInput.getText().toString(), pageCount);
        return options;
    }

    private int parseCopies() {
        try {
            int copies = Integer.parseInt(copiesInput.getText().toString().trim());
            int max = capabilities != null ? capabilities.maxCopies : 99;
            return Math.max(1, Math.min(copies, max));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Nullable
    private String selectedMedia() {
        return selected(mediaValues, mediaSpinner, null);
    }

    @Nullable
    private <T> T selected(List<T> values, Spinner spinner, @Nullable T fallback) {
        int index = spinner.getSelectedItemPosition();
        return index >= 0 && index < values.size() ? values.get(index) : fallback;
    }

    private final class JobListener implements PrintJobManager.JobListener {
        @Override
        public void onStage(@NonNull PrintJobManager.Stage stage) {
            switch (stage) {
                case CONTACTING_PRINTER:
                    setStatus(getString(R.string.stage_contacting), false);
                    break;
                case VALIDATING:
                    setStatus(getString(R.string.stage_validating), false);
                    break;
                case UPLOADING:
                    setStatus(getString(R.string.stage_uploading), false);
                    break;
                case WAITING_FOR_PRINTER:
                    uploadProgress.setIndeterminate(true);
                    setStatus(getString(R.string.stage_waiting), false);
                    break;
                case DONE:
                    uploadProgress.setVisibility(View.GONE);
                    submission = null;
                    setJobInFlight(false);
                    break;
            }
        }

        @Override
        public void onUploadProgress(int percent) {
            uploadProgress.setIndeterminate(false);
            uploadProgress.setProgress(percent);
        }

        @Override
        public void onSubmitted(int jobId) {
            Snackbar.make(root, jobId > 0
                            ? getString(R.string.job_submitted_with_id, jobId)
                            : getString(R.string.job_submitted),
                    Snackbar.LENGTH_LONG).show();
        }

        @Override
        public void onJobStateChanged(int jobState, @Nullable String reason) {
            setStatus(describeJobState(jobState, reason),
                    jobState == Ipp.JOB_STATE_ABORTED || jobState == Ipp.JOB_STATE_CANCELED);
        }

        @Override
        public void onFailed(@NonNull Exception error) {
            uploadProgress.setVisibility(View.GONE);
            submission = null;
            setJobInFlight(false);
            setStatus(getString(R.string.error_printing,
                    AddPrinterActivity.describe(error)), true);
        }
    }

    private String describeJobState(int jobState, @Nullable String reason) {
        String base;
        switch (jobState) {
            case Ipp.JOB_STATE_PENDING:
                base = getString(R.string.job_pending);
                break;
            case Ipp.JOB_STATE_PENDING_HELD:
                base = getString(R.string.job_held);
                break;
            case Ipp.JOB_STATE_PROCESSING:
                base = getString(R.string.job_processing);
                break;
            case Ipp.JOB_STATE_PROCESSING_STOPPED:
                base = getString(R.string.job_stopped);
                break;
            case Ipp.JOB_STATE_CANCELED:
                base = getString(R.string.job_canceled);
                break;
            case Ipp.JOB_STATE_ABORTED:
                base = getString(R.string.job_aborted);
                break;
            case Ipp.JOB_STATE_COMPLETED:
                base = getString(R.string.job_completed);
                break;
            default:
                base = getString(R.string.job_unknown_state);
                break;
        }
        if (reason != null && !reason.isEmpty() && !"none".equals(reason)) {
            return base + " · " + reason.replace('-', ' ');
        }
        return base;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    @Nullable
    private PrintSource readSource(Intent intent) {
        String kindName = intent.getStringExtra(EXTRA_KIND);
        if (kindName == null) {
            return null;
        }
        PrintSource.Kind kind;
        try {
            kind = PrintSource.Kind.valueOf(kindName);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String name = intent.getStringExtra(EXTRA_NAME);
        if (name == null) {
            name = getString(R.string.untitled_document);
        }

        switch (kind) {
            case WEB_PAGE: {
                String url = intent.getStringExtra(EXTRA_URL);
                return url == null ? null : PrintSource.fromUrl(url);
            }
            case TEXT: {
                String text = intent.getStringExtra(EXTRA_TEXT);
                if (text != null) {
                    return PrintSource.fromText(text, name);
                }
                return firstUriSource(intent);
            }
            default:
                return firstUriSource(intent);
        }
    }

    @Nullable
    private PrintSource firstUriSource(Intent intent) {
        ArrayList<Uri> uris = intent.getParcelableArrayListExtra(EXTRA_URIS);
        if (uris == null || uris.isEmpty()) {
            return null;
        }
        String kindName = intent.getStringExtra(EXTRA_KIND);
        if (PrintSource.Kind.IMAGES.name().equals(kindName)) {
            return PrintSource.fromImages(this, uris);
        }
        return PrintSource.fromUri(this, uris.get(0));
    }

    private String describeSource(PrintSource source) {
        if (source.kind == PrintSource.Kind.WEB_PAGE) {
            return source.url == null ? "" : source.url;
        }
        return source.sizeBytes > 0 ? DocumentUtils.formatSize(source.sizeBytes) : "";
    }

    private void setStatus(@NonNull String message, boolean isError) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(message);
        statusView.setTextColor(getColor(isError ? R.color.error : R.color.on_surface_variant));
    }

    private void clearStatus() {
        statusView.setVisibility(View.GONE);
    }

    private void showFatal(@NonNull String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (d, which) -> finish())
                .show();
    }
}
