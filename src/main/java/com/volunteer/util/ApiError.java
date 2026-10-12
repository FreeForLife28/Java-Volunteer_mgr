package com.volunteer.util;

/** Thrown anywhere in a servlet to return a JSON error with an HTTP status. */
public class ApiError extends RuntimeException {
    public final int status;

    public ApiError(int status, String message) {
        super(message);
        this.status = status;
    }
}
