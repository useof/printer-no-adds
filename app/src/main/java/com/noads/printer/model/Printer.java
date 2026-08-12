package com.noads.printer.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/** A printer the user can send jobs to, either discovered or added by hand. */
public final class Printer {

    public static final int DEFAULT_IPP_PORT = 631;

    /** Stable identity: the printer URI. Two entries with the same URI are one printer. */
    @NonNull
    public final String uri;

    @NonNull
    public final String name;

    @NonNull
    public final String host;

    public final int port;

    /** Model string from the mDNS TXT record or {@code printer-make-and-model}. */
    @Nullable
    public final String makeAndModel;

    @Nullable
    public final String location;

    /** True when the printer was typed in by the user rather than discovered. */
    public final boolean manual;

    public Printer(@NonNull String uri,
                   @NonNull String name,
                   @NonNull String host,
                   int port,
                   @Nullable String makeAndModel,
                   @Nullable String location,
                   boolean manual) {
        this.uri = uri;
        this.name = name;
        this.host = host;
        this.port = port;
        this.makeAndModel = makeAndModel;
        this.location = location;
        this.manual = manual;
    }

    /**
     * Builds a printer from a host plus the mDNS {@code rp} resource path.
     *
     * @param resourcePath e.g. {@code ipp/print}; a missing value falls back to
     *                     the IPP Everywhere default.
     */
    public static Printer fromService(@NonNull String name,
                                      @NonNull String host,
                                      int port,
                                      @Nullable String resourcePath,
                                      boolean secure,
                                      @Nullable String makeAndModel,
                                      @Nullable String location,
                                      boolean manual) {
        String path = resourcePath == null || resourcePath.trim().isEmpty()
                ? "ipp/print"
                : resourcePath.trim();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String scheme = secure ? "ipps" : "ipp";
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String uri = scheme + "://" + authority + ":" + port + "/" + path;
        return new Printer(uri, name, host, port, makeAndModel, location, manual);
    }

    public boolean isSecure() {
        return uri.startsWith("ipps://");
    }

    /** Second line in the printer list: model, then location, then address. */
    @NonNull
    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (makeAndModel != null && !makeAndModel.isEmpty()) {
            sb.append(makeAndModel);
        }
        if (location != null && !location.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(location);
        }
        if (sb.length() == 0) {
            sb.append(host);
            if (port != DEFAULT_IPP_PORT) {
                sb.append(':').append(port);
            }
        }
        return sb.toString();
    }

    /** Returns a copy carrying details learned from Get-Printer-Attributes. */
    public Printer withDetails(@Nullable String newName,
                               @Nullable String newMakeAndModel,
                               @Nullable String newLocation) {
        return new Printer(uri,
                newName != null && !newName.isEmpty() ? newName : name,
                host,
                port,
                newMakeAndModel != null && !newMakeAndModel.isEmpty() ? newMakeAndModel : makeAndModel,
                newLocation != null && !newLocation.isEmpty() ? newLocation : location,
                manual);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("uri", uri);
        o.put("name", name);
        o.put("host", host);
        o.put("port", port);
        o.putOpt("makeAndModel", makeAndModel);
        o.putOpt("location", location);
        o.put("manual", manual);
        return o;
    }

    public static Printer fromJson(JSONObject o) throws JSONException {
        return new Printer(
                o.getString("uri"),
                o.getString("name"),
                o.getString("host"),
                o.optInt("port", DEFAULT_IPP_PORT),
                o.optString("makeAndModel", null),
                o.optString("location", null),
                o.optBoolean("manual", false));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Printer)) return false;
        return uri.equals(((Printer) other).uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri);
    }

    @NonNull
    @Override
    public String toString() {
        return name + " <" + uri + ">";
    }
}
