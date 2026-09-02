package com.rch.mdm;

/**
 * Thrown for any failure while talking to the Headwind MDM REST API:
 * transport errors, non-2xx responses, or unexpected payloads.
 *
 * <p>Replaces the {@code requests.exceptions.HTTPError} / {@code ValueError}
 * handling of the original Python script.</p>
 */
public class MdmException extends Exception {

    /** HTTP status code, or -1 when the failure happened before a response was received. */
    private final int statusCode;
    /** Raw response body, when available. */
    private final String responseBody;

    public MdmException(String message) {
        this(message, -1, null, null);
    }

    public MdmException(String message, Throwable cause) {
        this(message, -1, null, cause);
    }

    public MdmException(String message, int statusCode, String responseBody) {
        this(message, statusCode, responseBody, null);
    }

    public MdmException(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
