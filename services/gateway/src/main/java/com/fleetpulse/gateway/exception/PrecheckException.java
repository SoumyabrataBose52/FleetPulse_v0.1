package com.fleetpulse.gateway.exception;

/**
 * Thrown when incoming payload fails syntactic or size prechecks (§4.4).
 */
public class PrecheckException extends RuntimeException {
    public PrecheckException(String message) {
        super(message);
    }
}
