package com.fleetpulse.gateway.exception;

/**
 * Thrown when gateway bounded queue passes high watermark threshold (§4.6).
 * Translates directly to HTTP 429 Too Many Requests with Retry-After header.
 */
public class BackpressureException extends RuntimeException {
    public BackpressureException(String message) {
        super(message);
    }
}
