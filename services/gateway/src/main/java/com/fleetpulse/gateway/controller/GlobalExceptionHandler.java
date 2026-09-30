package com.fleetpulse.gateway.controller;

import com.fleetpulse.gateway.exception.BackpressureException;
import com.fleetpulse.gateway.exception.PrecheckException;
import com.fleetpulse.gateway.exception.UnauthorizedMtlsException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * §9 Global Exception Handler returning RFC 7807 problem details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BackpressureException.class)
    public ResponseEntity<ProblemDetail> handleBackpressure(BackpressureException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        detail.setTitle("Ingestion Rate Exceeded");
        detail.setType(URI.create("urn:fleetpulse:error:backpressure"));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "1");

        return new ResponseEntity<>(detail, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(PrecheckException.class)
    public ResponseEntity<ProblemDetail> handlePrecheck(PrecheckException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Precheck Validation Failed");
        detail.setType(URI.create("urn:fleetpulse:error:precheck-failed"));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(detail);
    }

    @ExceptionHandler(UnauthorizedMtlsException.class)
    public ResponseEntity<ProblemDetail> handleUnauthorizedMtls(UnauthorizedMtlsException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        detail.setTitle("mTLS Client Certificate Rejected");
        detail.setType(URI.create("urn:fleetpulse:error:unauthorized-client"));

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(detail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneral(Exception ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        detail.setTitle("Internal Ingest Error");
        detail.setType(URI.create("urn:fleetpulse:error:internal-error"));

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(detail);
    }
}
