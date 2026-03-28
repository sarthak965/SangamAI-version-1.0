package com.sangam.ai.common.exception;

import com.sangam.ai.common.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.reactive.function.client.ClientResponseExtensionsKt;

import java.util.stream.Collectors;

/**
 * @RestControllerAdvice means this class watches ALL controllers.
 * When any controller (or anything it calls) throws an exception,
 * Spring routes it here instead of crashing with a 500 error.
 *
 * Each @ExceptionHandler method handles one specific exception type.
 * You return a clean ResponseEntity with the right HTTP status code
 * and a consistent JSON body using our ApiResponse wrapper.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles @Valid failures — when request body fields fail validation.
     * For example: blank username, invalid email format, short password.
     * Collects ALL field errors into one readable message.
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message));
    }

    /**
     * Handles wrong password or user not found during login.
     * Returns 401 Unauthorized.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(
            BadCredentialsException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles business rule violations — like "username already taken".
     * We throw IllegalArgumentException in AuthService for these cases.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Catch-all for anything we didn't anticipate.
     * Returns 500 Internal Server Error.
     * We intentionally hide the internal message from the client
     * for security — we don't want to leak implementation details.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(SecurityException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage()));
    }
}