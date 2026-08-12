package com.noads.printer.ipp;

import java.io.IOException;

/** An IPP-level failure: the transport succeeded but the printer said no. */
public class IppException extends IOException {

    public final int statusCode;

    public IppException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public static IppException from(String operation, IppResponse response) {
        String detail = response.getString("status-message");
        StringBuilder sb = new StringBuilder(operation)
                .append(" failed: ")
                .append(response.statusName());
        if (detail != null && !detail.isEmpty()) {
            sb.append(" (").append(detail).append(')');
        }
        return new IppException(response.statusCode, sb.toString());
    }
}
