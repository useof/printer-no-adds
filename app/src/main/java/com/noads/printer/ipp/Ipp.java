package com.noads.printer.ipp;

/**
 * Constants from RFC 8010 (IPP encoding) and RFC 8011 (IPP semantics).
 */
public final class Ipp {

    private Ipp() {
    }

    /* ---- Protocol version ---- */
    public static final byte VERSION_MAJOR = 2;
    public static final byte VERSION_MINOR = 0;

    /* ---- Operation ids ---- */
    public static final int OP_PRINT_JOB = 0x0002;
    public static final int OP_VALIDATE_JOB = 0x0004;
    public static final int OP_CANCEL_JOB = 0x0008;
    public static final int OP_GET_JOB_ATTRIBUTES = 0x0009;
    public static final int OP_GET_JOBS = 0x000A;
    public static final int OP_GET_PRINTER_ATTRIBUTES = 0x000B;

    /* ---- Delimiter tags ---- */
    public static final int TAG_OPERATION_ATTRIBUTES = 0x01;
    public static final int TAG_JOB_ATTRIBUTES = 0x02;
    public static final int TAG_END_OF_ATTRIBUTES = 0x03;
    public static final int TAG_PRINTER_ATTRIBUTES = 0x04;
    public static final int TAG_UNSUPPORTED_ATTRIBUTES = 0x05;

    /* ---- Out-of-band value tags ---- */
    public static final int TAG_UNSUPPORTED_VALUE = 0x10;
    public static final int TAG_NO_VALUE = 0x12;

    /* ---- Value tags ---- */
    public static final int TAG_INTEGER = 0x21;
    public static final int TAG_BOOLEAN = 0x22;
    public static final int TAG_ENUM = 0x23;
    public static final int TAG_OCTET_STRING = 0x30;
    public static final int TAG_DATE_TIME = 0x31;
    public static final int TAG_RESOLUTION = 0x32;
    public static final int TAG_RANGE_OF_INTEGER = 0x33;
    public static final int TAG_BEG_COLLECTION = 0x34;
    public static final int TAG_TEXT_WITH_LANGUAGE = 0x35;
    public static final int TAG_NAME_WITH_LANGUAGE = 0x36;
    public static final int TAG_END_COLLECTION = 0x37;
    public static final int TAG_TEXT_WITHOUT_LANGUAGE = 0x41;
    public static final int TAG_NAME_WITHOUT_LANGUAGE = 0x42;
    public static final int TAG_KEYWORD = 0x44;
    public static final int TAG_URI = 0x45;
    public static final int TAG_URI_SCHEME = 0x46;
    public static final int TAG_CHARSET = 0x47;
    public static final int TAG_NATURAL_LANGUAGE = 0x48;
    public static final int TAG_MIME_MEDIA_TYPE = 0x49;
    public static final int TAG_MEMBER_ATTR_NAME = 0x4A;

    /* ---- Status codes ---- */
    public static final int STATUS_OK = 0x0000;
    public static final int STATUS_OK_IGNORED_ATTRIBUTES = 0x0001;
    public static final int STATUS_OK_CONFLICTING_ATTRIBUTES = 0x0002;

    /** Every status below 0x0100 is a success variant. */
    public static boolean isSuccess(int statusCode) {
        return statusCode < 0x0100;
    }

    public static String statusName(int statusCode) {
        switch (statusCode) {
            case STATUS_OK: return "successful-ok";
            case STATUS_OK_IGNORED_ATTRIBUTES: return "successful-ok-ignored-or-substituted-attributes";
            case STATUS_OK_CONFLICTING_ATTRIBUTES: return "successful-ok-conflicting-attributes";
            case 0x0400: return "client-error-bad-request";
            case 0x0401: return "client-error-forbidden";
            case 0x0402: return "client-error-not-authenticated";
            case 0x0403: return "client-error-not-authorized";
            case 0x0404: return "client-error-not-possible";
            case 0x0405: return "client-error-timeout";
            case 0x0406: return "client-error-not-found";
            case 0x0407: return "client-error-gone";
            case 0x0408: return "client-error-request-entity-too-large";
            case 0x0409: return "client-error-request-value-too-long";
            case 0x040A: return "client-error-document-format-not-supported";
            case 0x040B: return "client-error-attributes-or-values-not-supported";
            case 0x040C: return "client-error-uri-scheme-not-supported";
            case 0x040D: return "client-error-charset-not-supported";
            case 0x040E: return "client-error-conflicting-attributes";
            case 0x040F: return "client-error-compression-not-supported";
            case 0x0410: return "client-error-compression-error";
            case 0x0411: return "client-error-document-format-error";
            case 0x0412: return "client-error-document-access-error";
            case 0x0500: return "server-error-internal-error";
            case 0x0501: return "server-error-operation-not-supported";
            case 0x0502: return "server-error-service-unavailable";
            case 0x0503: return "server-error-version-not-supported";
            case 0x0504: return "server-error-device-error";
            case 0x0505: return "server-error-temporary-error";
            case 0x0506: return "server-error-not-accepting-jobs";
            case 0x0507: return "server-error-busy";
            case 0x0508: return "server-error-job-canceled";
            case 0x0509: return "server-error-multiple-document-jobs-not-supported";
            default: return String.format("unknown-status-0x%04X", statusCode);
        }
    }

    /* ---- printer-state (RFC 8011 5.4.11) ---- */
    public static final int PRINTER_STATE_IDLE = 3;
    public static final int PRINTER_STATE_PROCESSING = 4;
    public static final int PRINTER_STATE_STOPPED = 5;

    /* ---- job-state (RFC 8011 5.3.7) ---- */
    public static final int JOB_STATE_PENDING = 3;
    public static final int JOB_STATE_PENDING_HELD = 4;
    public static final int JOB_STATE_PROCESSING = 5;
    public static final int JOB_STATE_PROCESSING_STOPPED = 6;
    public static final int JOB_STATE_CANCELED = 7;
    public static final int JOB_STATE_ABORTED = 8;
    public static final int JOB_STATE_COMPLETED = 9;

    public static boolean isJobFinished(int jobState) {
        return jobState >= JOB_STATE_CANCELED;
    }
}
