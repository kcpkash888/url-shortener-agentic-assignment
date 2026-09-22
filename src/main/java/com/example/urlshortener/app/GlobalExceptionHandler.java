package com.example.urlshortener.app;

import com.example.urlshortener.app.exceptions.AliasConflictException;
import com.example.urlshortener.app.exceptions.LinkExpiredException;
import com.example.urlshortener.app.exceptions.LinkInactiveException;
import com.example.urlshortener.app.exceptions.LinkNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LinkNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(LinkNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler({LinkExpiredException.class, LinkInactiveException.class})
    public ResponseEntity<Map<String, String>> handleGone(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(AliasConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(AliasConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", e.getMessage()));
    }

    // Mapped to 422 (rather than Spring's default 400) to stay API-compatible
    // with the Python/FastAPI port, which uses Pydantic's conventional 422
    // for request validation failures.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList());
        return ResponseEntity.status(422).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(422).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler({
            com.example.urlshortener.app.exceptions.UnsafeTargetException.class,
            com.example.urlshortener.app.exceptions.ReservedAliasException.class
    })
    public ResponseEntity<Map<String, String>> handleUnsafe(RuntimeException e) {
        return ResponseEntity.status(422).body(Map.of("detail", e.getMessage()));
    }
}
