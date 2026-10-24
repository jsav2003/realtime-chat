package com.jsav.chat.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
    Map<String, String> fields = new LinkedHashMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
    return ResponseEntity.badRequest()
        .body(new ApiError(400, "validation_failed", "Datos inválidos", fields));
  }

  @ExceptionHandler(UnauthorizedException.class)
  ResponseEntity<ApiError> unauthorized(UnauthorizedException e) {
    return build(HttpStatus.UNAUTHORIZED, "unauthorized", e.getMessage());
  }

  @ExceptionHandler(ForbiddenException.class)
  ResponseEntity<ApiError> forbidden(ForbiddenException e) {
    return build(HttpStatus.FORBIDDEN, "forbidden", e.getMessage());
  }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ApiError> notFound(NotFoundException e) {
    return build(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  ResponseEntity<ApiError> conflict(ConflictException e) {
    return build(HttpStatus.CONFLICT, "conflict", e.getMessage());
  }

  private static ResponseEntity<ApiError> build(HttpStatus status, String error, String message) {
    return ResponseEntity.status(status).body(ApiError.of(status.value(), error, message));
  }
}
