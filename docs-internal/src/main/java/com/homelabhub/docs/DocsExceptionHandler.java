package com.homelabhub.docs;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
class DocsExceptionHandler {

    @ExceptionHandler(DocsException.class)
    ResponseEntity<Map<String, Object>> docs(DocsException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        body.put("message", e.getMessage());
        body.putAll(e.extra());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler({DocSchema.InvalidDocException.class, BundleReader.BadBundleException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid", "message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, BlobStore.TooLargeException.class})
    ResponseEntity<Map<String, Object>> tooLarge(Exception e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "too_large",
                "message", e instanceof BlobStore.TooLargeException ? e.getMessage() : "The upload is too large."));
    }
}
