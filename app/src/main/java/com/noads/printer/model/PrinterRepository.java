package com.noads.printer.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The set of printers the UI shows: manually added ones (persisted) merged with
 * whatever mDNS has found this session (not persisted, since addresses move).
 *
 * <p>Main-thread only.
 */
public final class PrinterRepository {

    private static final String TAG = "PrinterRepository";
    private static final String PREFS = "printers";
    private static final String KEY_MANUAL = "manual_printers";
    private static final String KEY_LAST_SELECTED = "last_selected_uri";

    private final SharedPreferences prefs;

    /** uri -> printer, insertion-ordered so the list does not jump around. */
    private final Map<String, Printer> manual = new LinkedHashMap<>();
    private final Map<String, Printer> discovered = new LinkedHashMap<>();

    private final List<Runnable> observers = new ArrayList<>();

    public PrinterRepository(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadManual();
    }

    public void addObserver(@NonNull Runnable observer) {
        observers.add(observer);
    }

    public void removeObserver(@NonNull Runnable observer) {
        observers.remove(observer);
    }

    private void notifyChanged() {
        for (Runnable observer : new ArrayList<>(observers)) {
            observer.run();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Reading                                                            */
    /* ------------------------------------------------------------------ */

    /** Manual printers first (the user asked for those), then discovered ones. */
    @NonNull
    public List<Printer> all() {
        Map<String, Printer> merged = new LinkedHashMap<>(manual);
        for (Map.Entry<String, Printer> entry : discovered.entrySet()) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableList(new ArrayList<>(merged.values()));
    }

    @Nullable
    public Printer byUri(@NonNull String uri) {
        Printer p = manual.get(uri);
        return p != null ? p : discovered.get(uri);
    }


    /* ------------------------------------------------------------------ */
    /* Discovery feed                                                     */
    /* ------------------------------------------------------------------ */

    public void onDiscovered(@NonNull Printer printer) {
        Printer previous = discovered.put(printer.uri, printer);
        if (!printer.equals(previous)) {
            notifyChanged();
        }
    }

    public void onLost(@NonNull String serviceName) {
        boolean removed = false;
        // mDNS reports the service name; match it against what was stored.
        for (Map.Entry<String, Printer> entry : new ArrayList<>(discovered.entrySet())) {
            if (entry.getValue().name.equals(serviceName)) {
                discovered.remove(entry.getKey());
                removed = true;
            }
        }
        if (removed) {
            notifyChanged();
        }
    }

    public void clearDiscovered() {
        if (!discovered.isEmpty()) {
            discovered.clear();
            notifyChanged();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Manual printers                                                    */
    /* ------------------------------------------------------------------ */

    public void addManual(@NonNull Printer printer) {
        manual.put(printer.uri, printer);
        saveManual();
        notifyChanged();
    }

    public void remove(@NonNull String uri) {
        boolean changed = manual.remove(uri) != null;
        changed |= discovered.remove(uri) != null;
        if (changed) {
            saveManual();
            notifyChanged();
        }
    }

    public boolean isManual(@NonNull String uri) {
        return manual.containsKey(uri);
    }

    /** Folds details from Get-Printer-Attributes back into the stored entry. */
    public void updateDetails(@NonNull String uri,
                              @Nullable String name,
                              @Nullable String makeAndModel,
                              @Nullable String location) {
        Printer existing = byUri(uri);
        if (existing == null) {
            return;
        }
        Printer updated = existing.withDetails(name, makeAndModel, location);
        // Printer.equals compares only the URI, so the display fields are
        // compared here to avoid redrawing the list for no reason.
        if (updated.name.equals(existing.name)
                && Objects.equals(updated.makeAndModel, existing.makeAndModel)
                && Objects.equals(updated.location, existing.location)) {
            return;
        }
        if (manual.containsKey(uri)) {
            manual.put(uri, updated);
            saveManual();
        } else {
            discovered.put(uri, updated);
        }
        notifyChanged();
    }

    /* ------------------------------------------------------------------ */
    /* Selection                                                          */
    /* ------------------------------------------------------------------ */

    @Nullable
    public String lastSelectedUri() {
        return prefs.getString(KEY_LAST_SELECTED, null);
    }

    public void setLastSelectedUri(@Nullable String uri) {
        prefs.edit().putString(KEY_LAST_SELECTED, uri).apply();
    }

    /** The remembered printer if it is still known, otherwise the first one. */
    @Nullable
    public Printer defaultPrinter() {
        String uri = lastSelectedUri();
        if (uri != null) {
            Printer remembered = byUri(uri);
            if (remembered != null) {
                return remembered;
            }
        }
        List<Printer> all = all();
        return all.isEmpty() ? null : all.get(0);
    }

    /* ------------------------------------------------------------------ */
    /* Persistence                                                        */
    /* ------------------------------------------------------------------ */

    private void loadManual() {
        String raw = prefs.getString(KEY_MANUAL, null);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                Printer printer = Printer.fromJson(object);
                manual.put(printer.uri, printer);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Stored printers were unreadable; starting empty", e);
            prefs.edit().remove(KEY_MANUAL).apply();
        }
    }

    private void saveManual() {
        JSONArray array = new JSONArray();
        try {
            for (Printer printer : manual.values()) {
                array.put(printer.toJson());
            }
            prefs.edit().putString(KEY_MANUAL, array.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Could not save printers", e);
        }
    }
}
