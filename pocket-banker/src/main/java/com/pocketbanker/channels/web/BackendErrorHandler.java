package com.pocketbanker.channels.web;

import com.pocketbanker.contracts.BackendException;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(
    assignableTypes = {
      BackendController.class,
      AppGatewayController.class,
      AppAuthController.class,
      AppPerceptionController.class,
      WebChannelController.class,
      McpController.class,
      TelegramController.class,
      OperationalController.class
    })
public class BackendErrorHandler {
  @ExceptionHandler(com.pocketbanker.perception.PerceptionException.class)
  public ResponseEntity<?> perception(com.pocketbanker.perception.PerceptionException e) {
    return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(BackendException.class)
  public ResponseEntity<?> known(BackendException e) {
    return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(Map.of("error", e.getReason() == null ? "request_rejected" : e.getReason()));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    ConstraintViolationException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class
  })
  public ResponseEntity<?> input(Exception e) {
    for (Throwable c = e; c != null; c = c.getCause())
      if (c instanceof PerceptionBodyLimitFilter.BodyTooLarge)
        return ResponseEntity.status(413).body(Map.of("error", "request_body_too_large"));
    return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    return ResponseEntity.internalServerError().body(Map.of("error", "backend_request_failed"));
  }
}
