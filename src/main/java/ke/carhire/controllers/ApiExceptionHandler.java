package ke.carhire.controllers;

import java.util.Map;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.*;
import ke.carhire.services.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> domain(ApiError e) {
    return ResponseEntity.status(e.status())
        .body(
            Map.of(
                "category",
                e.status() == HttpStatus.CONFLICT ? "Conflict" : "Validation",
                "message",
                e.getMessage(),
                "detail",
                e.detail()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("category", "Validation", "message", "Invalid request format"));
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<?> integrity(Exception e) {
    return ResponseEntity.status(409)
        .body(
            Map.of(
                "category",
                "Integrity",
                "message",
                "A unique value or referenced record conflicts with this change"));
  }
}
