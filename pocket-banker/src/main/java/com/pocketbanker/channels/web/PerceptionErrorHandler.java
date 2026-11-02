package com.pocketbanker.channels.web;

import com.pocketbanker.perception.PerceptionException;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = PerceptionController.class)
public class PerceptionErrorHandler {
  @ExceptionHandler(com.pocketbanker.contracts.BackendException.class)
  public ResponseEntity<?> backend(com.pocketbanker.contracts.BackendException e) {
    return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(PerceptionException.class)
  public ResponseEntity<?> known(PerceptionException e) {
    return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(Map.of("error", e.getReason() == null ? "request_rejected" : e.getReason()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HttpMessageNotReadableException.class
  })
  public ResponseEntity<?> invalid(Exception e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof PerceptionBodyLimitFilter.BodyTooLarge) {
        return ResponseEntity.status(413).body(Map.of("error", "request_body_too_large"));
      }
    }
    return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    // Don't echo validation values, SQL bind values, MIME data, or credentials.
    return ResponseEntity.internalServerError().body(Map.of("error", "perception_request_failed"));
  }
}
