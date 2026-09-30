package com.fleetpulse.gateway.exception;

/**
 * Thrown when client certificate verification fails (§10.1).
 */
public class UnauthorizedMtlsException extends RuntimeException {
    public UnauthorizedMtlsException(String message) {
        super(message);
    }
}
