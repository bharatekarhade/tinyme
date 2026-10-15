package com.tinyme.auth.controller;

import com.tinyme.auth.service.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class AuthProblemHandler {
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ProblemDetail> handle(AuthException exception) {
        var problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setTitle(exception.status().getReasonPhrase());
        return ResponseEntity.status(exception.status()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        if (detail.isBlank()) detail = "Invalid request";
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> responseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        var problem = ProblemDetail.forStatusAndDetail(status, exception.getReason());
        problem.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).body(problem);
    }
}
