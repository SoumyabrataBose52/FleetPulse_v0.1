package com.fleetpulse.gateway.controller;

import com.fleetpulse.gateway.config.GatewayProperties;
import com.fleetpulse.gateway.exception.PrecheckException;
import com.fleetpulse.gateway.exception.UnauthorizedMtlsException;
import com.fleetpulse.gateway.service.IngestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.cert.X509Certificate;
import java.util.Set;

/**
 * §4.4 & §9 High-Throughput HTTP NDJSON Telematics Ingest Controller.
 */
@RestController
@RequestMapping("/v1/ingest")
public class IngestController {

    private static final Set<String> VALID_OEMS = Set.of("A", "B", "C", "D", "E");

    private final IngestService ingestService;
    private final GatewayProperties properties;

    public IngestController(IngestService ingestService, GatewayProperties properties) {
        this.ingestService = ingestService;
        this.properties = properties;
    }

    @PostMapping(value = "/batch", consumes = {"application/x-ndjson", "application/json", "text/plain", "*/*"})
    public ResponseEntity<BatchResponse> ingestBatch(
        @RequestBody String body,
        @RequestHeader(value = "X-OEM", required = false) String oem,
        @RequestHeader(value = "traceparent", required = false) String traceparent,
        @RequestHeader(value = "X-Client-Cert", required = false) String clientCertHeader,
        HttpServletRequest request
    ) {
        // 1. OEM Header validation (§4.4)
        if (oem == null || !VALID_OEMS.contains(oem.trim().toUpperCase())) {
            throw new PrecheckException("Header 'X-OEM' is required and must be one of: A, B, C, D, E");
        }
        String normalizedOem = oem.trim().toUpperCase();

        // 2. mTLS Authorization Check (§10.1)
        if (properties.mtls().enabled()) {
            verifyMtlsClient(request, clientCertHeader);
        }

        // 3. Process Batch
        int acceptedCount = ingestService.processBatch(body, normalizedOem, traceparent);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(new BatchResponse(acceptedCount, 0));
    }

    private void verifyMtlsClient(HttpServletRequest request, String clientCertHeader) {
        String clientCn = null;

        // Try extracting from Servlet request X509 certificate attribute
        X509Certificate[] certs = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (certs != null && certs.length > 0) {
            String dn = certs[0].getSubjectX500Principal().getName();
            clientCn = extractCnFromDn(dn);
        }

        // Fallback to proxy header (e.g. Traefik mTLS pass-through header)
        if (clientCn == null && clientCertHeader != null && !clientCertHeader.isBlank()) {
            clientCn = clientCertHeader.trim();
        }

        if (clientCn == null || !properties.mtls().allowedCns().contains(clientCn)) {
            throw new UnauthorizedMtlsException("Unauthorized mTLS client certificate CN: " + clientCn);
        }
    }

    private String extractCnFromDn(String dn) {
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=") || trimmed.startsWith("cn=")) {
                return trimmed.substring(3).trim();
            }
        }
        return null;
    }

    public record BatchResponse(int accepted_count, int rejected_count) {}
}
