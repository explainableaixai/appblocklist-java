package com.alphaquantum.appblocklist;

/**
 * Base class for every error raised by {@link AppBlockListClient}. Unchecked,
 * so it passes through Spring, lambdas and streams without wrapping.
 *
 * <p>Subclasses map to the API's status codes:
 * {@link InvalidIdException} (400), {@link UnauthorizedException} (401),
 * {@link QuotaExceededException} (403) and {@link ServerException} (500).
 * A plain {@code AppBlockListException} with status 0 means a network problem
 * or a missing API key; any other status is one the API does not document.
 * A 404 is never an exception: it is an {@link AppInfo} with
 * {@code isFound() == false}.
 */
public class AppBlockListException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int statusCode;

    public AppBlockListException(int statusCode, String message) {
        this(statusCode, message, null);
    }

    public AppBlockListException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP status of the answer, or 0 when no answer was received. */
    public int getStatusCode() {
        return statusCode;
    }

    /** True when retrying later can succeed (5xx or network errors). */
    public boolean isTemporary() {
        return statusCode == 0 && getCause() != null || statusCode >= 500;
    }

    /** HTTP 400: the id is missing or not a bundle ID, App Store ID or package name. */
    public static class InvalidIdException extends AppBlockListException {
        private static final long serialVersionUID = 1L;

        public InvalidIdException(String message) {
            super(400, message);
        }
    }

    /** HTTP 401: missing or invalid API key. */
    public static class UnauthorizedException extends AppBlockListException {
        private static final long serialVersionUID = 1L;

        public UnauthorizedException(String message) {
            super(401, message);
        }
    }

    /** HTTP 403: the key has used its monthly lookups. */
    public static class QuotaExceededException extends AppBlockListException {
        private static final long serialVersionUID = 1L;
        private final Long monthlyLookups;

        public QuotaExceededException(String message, Long monthlyLookups) {
            super(403, message);
            this.monthlyLookups = monthlyLookups;
        }

        /** The "monthly_lookups" value from the answer, or null if absent. */
        public Long getMonthlyLookups() {
            return monthlyLookups;
        }
    }

    /** HTTP 500: temporary error on the service side. */
    public static class ServerException extends AppBlockListException {
        private static final long serialVersionUID = 1L;

        public ServerException(int statusCode, String message) {
            super(statusCode, message);
        }
    }
}
