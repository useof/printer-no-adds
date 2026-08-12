package com.noads.printer.ipp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** A named IPP attribute. Every attribute is multi-valued; most carry one value. */
public final class IppAttribute {

    public final String name;
    public final List<IppValue> values = new ArrayList<>(1);

    public IppAttribute(String name) {
        this.name = name;
    }

    @Nullable
    public IppValue first() {
        return values.isEmpty() ? null : values.get(0);
    }

    @Nullable
    public String firstString() {
        IppValue v = first();
        return v == null ? null : v.asString();
    }

    public int firstInt(int fallback) {
        IppValue v = first();
        return v == null ? fallback : v.asInt(fallback);
    }

    public boolean firstBoolean(boolean fallback) {
        IppValue v = first();
        return v == null ? fallback : v.asBoolean(fallback);
    }

    @NonNull
    public List<String> allStrings() {
        List<String> out = new ArrayList<>(values.size());
        for (IppValue v : values) {
            String s = v.asString();
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public boolean contains(String candidate) {
        for (IppValue v : values) {
            if (candidate.equals(v.asString())) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    @Override
    public String toString() {
        return name + "=" + values;
    }
}
