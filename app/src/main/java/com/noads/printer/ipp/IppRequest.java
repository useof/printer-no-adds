package com.noads.printer.ipp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Builds the binary body of an IPP request (RFC 8010 section 3).
 *
 * <p>Layout: {@code version(2) operation-id(2) request-id(4) attribute-groups
 * end-of-attributes-tag}. Document data, when present, is appended by the
 * transport after this header.
 */
public final class IppRequest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
    private final DataOutputStream out = new DataOutputStream(buffer);
    private boolean finished;

    public IppRequest(int operationId, int requestId) {
        try {
            out.writeByte(Ipp.VERSION_MAJOR);
            out.writeByte(Ipp.VERSION_MINOR);
            out.writeShort(operationId);
            out.writeInt(requestId);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Opens an attribute group, e.g. {@link Ipp#TAG_OPERATION_ATTRIBUTES}. */
    public IppRequest group(int delimiterTag) {
        write(() -> out.writeByte(delimiterTag));
        return this;
    }

    /**
     * Writes the two attributes every IPP request must start with, in this exact
     * order (RFC 8011 4.1.4).
     */
    public IppRequest standardOperationAttributes() {
        group(Ipp.TAG_OPERATION_ATTRIBUTES);
        attr(Ipp.TAG_CHARSET, "attributes-charset", "utf-8");
        attr(Ipp.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en");
        return this;
    }

    public IppRequest attr(int valueTag, String name, String value) {
        write(() -> {
            writeTagAndName(valueTag, name);
            writeBytes(value.getBytes(StandardCharsets.UTF_8));
        });
        return this;
    }

    /** Appends an extra value to the attribute written immediately before. */
    public IppRequest additionalValue(int valueTag, String value) {
        write(() -> {
            out.writeByte(valueTag);
            out.writeShort(0); // zero-length name marks an additional value
            writeBytes(value.getBytes(StandardCharsets.UTF_8));
        });
        return this;
    }

    public IppRequest attr(int valueTag, String name, int value) {
        write(() -> {
            writeTagAndName(valueTag, name);
            out.writeShort(4);
            out.writeInt(value);
        });
        return this;
    }

    public IppRequest attrBoolean(String name, boolean value) {
        write(() -> {
            writeTagAndName(Ipp.TAG_BOOLEAN, name);
            out.writeShort(1);
            out.writeByte(value ? 1 : 0);
        });
        return this;
    }

    /**
     * Writes a rangeOfInteger. A null {@code name} appends the range as an
     * additional value of the preceding attribute, which is how multiple
     * {@code page-ranges} are expressed.
     */
    public IppRequest attrRange(@Nullable String name, int lower, int upper) {
        write(() -> {
            if (name == null) {
                out.writeByte(Ipp.TAG_RANGE_OF_INTEGER);
                out.writeShort(0);
            } else {
                writeTagAndName(Ipp.TAG_RANGE_OF_INTEGER, name);
            }
            out.writeShort(8);
            out.writeInt(lower);
            out.writeInt(upper);
        });
        return this;
    }

    /** Requests a specific set of attributes back, as a multi-valued keyword. */
    public IppRequest requestedAttributes(String... names) {
        if (names.length == 0) {
            return this;
        }
        attr(Ipp.TAG_KEYWORD, "requested-attributes", names[0]);
        for (int i = 1; i < names.length; i++) {
            additionalValue(Ipp.TAG_KEYWORD, names[i]);
        }
        return this;
    }

    /** Closes the message. Idempotent. */
    public IppRequest end() {
        if (!finished) {
            finished = true;
            write(() -> out.writeByte(Ipp.TAG_END_OF_ATTRIBUTES));
        }
        return this;
    }

    @NonNull
    public byte[] toByteArray() {
        end();
        return buffer.toByteArray();
    }

    private void writeTagAndName(int valueTag, String name) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        out.writeByte(valueTag);
        out.writeShort(nameBytes.length);
        out.write(nameBytes);
    }

    private void writeBytes(byte[] value) throws IOException {
        out.writeShort(value.length);
        out.write(value);
    }

    private interface Writer {
        void write() throws IOException;
    }

    private void write(Writer w) {
        try {
            w.write();
        } catch (IOException impossible) {
            // ByteArrayOutputStream never throws.
            throw new AssertionError(impossible);
        }
    }
}
