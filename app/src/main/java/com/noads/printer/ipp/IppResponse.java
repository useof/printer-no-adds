package com.noads.printer.ipp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed IPP response (RFC 8010 section 3).
 *
 * <p>Attributes from every group are merged into one map keyed by attribute
 * name; the individual groups are also kept in {@link #groups} for callers that
 * need to tell one job's attributes from another's (Get-Jobs).
 */
public final class IppResponse {

    public final int versionMajor;
    public final int versionMinor;
    public final int statusCode;
    public final int requestId;

    /** One entry per attribute group, in wire order. */
    public final List<Group> groups = new ArrayList<>();

    private final Map<String, IppAttribute> merged = new LinkedHashMap<>();

    public static final class Group {
        public final int delimiterTag;
        public final Map<String, IppAttribute> attributes = new LinkedHashMap<>();

        Group(int delimiterTag) {
            this.delimiterTag = delimiterTag;
        }

        @Nullable
        public IppAttribute get(String name) {
            return attributes.get(name);
        }

        @Nullable
        public String getString(String name) {
            IppAttribute a = attributes.get(name);
            return a == null ? null : a.firstString();
        }

        public int getInt(String name, int fallback) {
            IppAttribute a = attributes.get(name);
            return a == null ? fallback : a.firstInt(fallback);
        }
    }

    private IppResponse(int versionMajor, int versionMinor, int statusCode, int requestId) {
        this.versionMajor = versionMajor;
        this.versionMinor = versionMinor;
        this.statusCode = statusCode;
        this.requestId = requestId;
    }

    public boolean isSuccess() {
        return Ipp.isSuccess(statusCode);
    }

    public String statusName() {
        return Ipp.statusName(statusCode);
    }

    @Nullable
    public IppAttribute get(String name) {
        return merged.get(name);
    }

    @Nullable
    public String getString(String name) {
        IppAttribute a = merged.get(name);
        return a == null ? null : a.firstString();
    }

    public int getInt(String name, int fallback) {
        IppAttribute a = merged.get(name);
        return a == null ? fallback : a.firstInt(fallback);
    }

    public boolean getBoolean(String name, boolean fallback) {
        IppAttribute a = merged.get(name);
        return a == null ? fallback : a.firstBoolean(fallback);
    }

    @NonNull
    public List<String> getStrings(String name) {
        IppAttribute a = merged.get(name);
        return a == null ? new ArrayList<>() : a.allStrings();
    }

    /** Returns the first group carrying the given delimiter tag, or null. */
    @Nullable
    public Group group(int delimiterTag) {
        for (Group g : groups) {
            if (g.delimiterTag == delimiterTag) {
                return g;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Parsing                                                            */
    /* ------------------------------------------------------------------ */

    public static IppResponse parse(InputStream rawIn) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        int major = in.readUnsignedByte();
        int minor = in.readUnsignedByte();
        int status = in.readUnsignedShort();
        int reqId = in.readInt();

        IppResponse response = new IppResponse(major, minor, status, reqId);
        Group current = null;

        // Collections nest: each begCollection pushes a new member map that
        // subsequent memberAttrName/value pairs fill in.
        Deque<Map<String, IppAttribute>> collectionStack = new ArrayDeque<>();
        String pendingMemberName = null;
        IppAttribute lastAttribute = null;

        while (true) {
            int tag;
            try {
                tag = in.readUnsignedByte();
            } catch (EOFException truncated) {
                // Some printers omit the terminating tag; keep what was parsed.
                break;
            }

            if (tag == Ipp.TAG_END_OF_ATTRIBUTES) {
                break;
            }

            if (tag <= 0x0F) { // delimiter tag
                current = new Group(tag);
                response.groups.add(current);
                lastAttribute = null;
                continue;
            }

            int nameLength = in.readUnsignedShort();
            String name = nameLength == 0 ? null : readString(in, nameLength);
            int valueLength = in.readUnsignedShort();

            if (tag == Ipp.TAG_MEMBER_ATTR_NAME) {
                pendingMemberName = readString(in, valueLength);
                lastAttribute = null;
                continue;
            }

            if (tag == Ipp.TAG_BEG_COLLECTION) {
                skip(in, valueLength); // always zero-length per RFC 8010
                Map<String, IppAttribute> members = new LinkedHashMap<>();
                IppValue collectionValue = new IppValue(Ipp.TAG_BEG_COLLECTION, members);
                lastAttribute = store(response, current, collectionStack.peek(),
                        name, pendingMemberName, collectionValue, lastAttribute);
                pendingMemberName = null;
                collectionStack.push(members);
                continue;
            }

            if (tag == Ipp.TAG_END_COLLECTION) {
                skip(in, valueLength);
                if (!collectionStack.isEmpty()) {
                    collectionStack.pop();
                }
                pendingMemberName = null;
                lastAttribute = null;
                continue;
            }

            IppValue value = new IppValue(tag, decode(in, tag, valueLength));
            lastAttribute = store(response, current, collectionStack.peek(),
                    name, pendingMemberName, value, lastAttribute);
            pendingMemberName = null;
        }

        return response;
    }

    /**
     * Files a decoded value under the right owner. A value with no name is an
     * additional value for {@code lastAttribute}; inside a collection the name
     * comes from the preceding memberAttrName tag.
     *
     * @return the attribute the value landed in, to serve as the next
     *         additional-value target.
     */
    @Nullable
    private static IppAttribute store(IppResponse response,
                                      @Nullable Group group,
                                      @Nullable Map<String, IppAttribute> collection,
                                      @Nullable String name,
                                      @Nullable String memberName,
                                      IppValue value,
                                      @Nullable IppAttribute lastAttribute) {
        if (name == null && memberName == null) {
            if (lastAttribute != null) {
                lastAttribute.values.add(value);
            }
            return lastAttribute;
        }

        String key = memberName != null ? memberName : name;
        IppAttribute attribute;
        if (collection != null) {
            attribute = collection.get(key);
            if (attribute == null) {
                attribute = new IppAttribute(key);
                collection.put(key, attribute);
            }
        } else {
            attribute = new IppAttribute(key);
            if (group != null) {
                group.attributes.put(key, attribute);
            }
            // Later groups win only if the name is new, so printer attributes
            // are not overwritten by an unsupported-attributes echo.
            if (!response.merged.containsKey(key)) {
                response.merged.put(key, attribute);
            }
        }
        attribute.values.add(value);
        return attribute;
    }

    @Nullable
    private static Object decode(DataInputStream in, int tag, int length) throws IOException {
        switch (tag) {
            case Ipp.TAG_INTEGER:
            case Ipp.TAG_ENUM:
                if (length != 4) {
                    skip(in, length);
                    return null;
                }
                return in.readInt();

            case Ipp.TAG_BOOLEAN:
                if (length != 1) {
                    skip(in, length);
                    return null;
                }
                return in.readUnsignedByte() != 0;

            case Ipp.TAG_RANGE_OF_INTEGER:
                if (length != 8) {
                    skip(in, length);
                    return null;
                }
                return new int[]{in.readInt(), in.readInt()};

            case Ipp.TAG_RESOLUTION:
                if (length != 9) {
                    skip(in, length);
                    return null;
                }
                return new int[]{in.readInt(), in.readInt(), in.readUnsignedByte()};

            case Ipp.TAG_TEXT_WITH_LANGUAGE:
            case Ipp.TAG_NAME_WITH_LANGUAGE: {
                // [langLen(2) lang][textLen(2) text] - the language is dropped.
                byte[] raw = readFully(in, length);
                if (raw.length < 4) {
                    return "";
                }
                int langLen = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
                int textOffset = 2 + langLen + 2;
                if (textOffset > raw.length) {
                    return "";
                }
                int textLen = ((raw[textOffset - 2] & 0xFF) << 8) | (raw[textOffset - 1] & 0xFF);
                textLen = Math.min(textLen, raw.length - textOffset);
                return new String(raw, textOffset, textLen, StandardCharsets.UTF_8);
            }

            case Ipp.TAG_UNSUPPORTED_VALUE:
            case Ipp.TAG_NO_VALUE:
                skip(in, length);
                return null;

            case Ipp.TAG_DATE_TIME:
            case Ipp.TAG_OCTET_STRING:
                return readFully(in, length);

            default:
                // Everything else in the text/keyword/uri family is a UTF-8 string.
                return readString(in, length);
        }
    }

    private static String readString(DataInputStream in, int length) throws IOException {
        return new String(readFully(in, length), StandardCharsets.UTF_8);
    }

    private static byte[] readFully(DataInputStream in, int length) throws IOException {
        if (length < 0) {
            throw new IOException("Negative IPP value length: " + length);
        }
        byte[] buf = new byte[length];
        in.readFully(buf);
        return buf;
    }

    private static void skip(DataInputStream in, int length) throws IOException {
        if (length > 0) {
            in.skipBytes(length);
        }
    }
}
