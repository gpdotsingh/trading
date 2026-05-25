package com.trading.ibcfd.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not found: " + ex.getResourcePath()));
    }

    /** Capital.com returned 429 — rate limited. Surface it clearly instead of a generic 500. */
    @ExceptionHandler(HttpClientErrorException.TooManyRequests.class)
    public ResponseEntity<Map<String, String>> handleRateLimit(HttpClientErrorException.TooManyRequests ex) {
        log.warn("Broker API rate limit (429): {}", ex.getResponseBodyAsString());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Broker API rate limit reached — wait a moment and retry",
                             "detail", ex.getResponseBodyAsString()));
    }

    /** Saxo / Capital.com returned a 5xx — log concisely, don't dump the stack. */
    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<Map<String, String>> handleBrokerServerError(HttpServerErrorException ex) {
        String body = ex.getResponseBodyAsString();
        boolean isTimeout = body.contains("TimeoutException") || body.contains("timeout");
        if (isTimeout) {
            log.warn("Broker API timeout ({}): {}", ex.getStatusCode(), body);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Broker API timed out — try again in a moment", "detail", body));
        }
        log.error("Broker API error ({}): {}", ex.getStatusCode(), body);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Broker API error: " + ex.getStatusCode(), "detail", body));
    }

    /** Local socket/read timeout — RestTemplate timed out waiting for the broker. */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> handleTimeout(ResourceAccessException ex) {
        boolean isTimeout = ex.getCause() instanceof SocketTimeoutException;
        log.warn("Broker request failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", isTimeout
                        ? "Request to broker timed out — the sim may be slow, try again"
                        : "Could not reach broker: " + ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        log.error("IllegalState: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(Map.of("error", errors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
    }
}
