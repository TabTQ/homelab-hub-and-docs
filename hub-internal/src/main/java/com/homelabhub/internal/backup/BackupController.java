package com.homelabhub.internal.backup;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BackupController {

    private final BackupService service;

    BackupController(BackupService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/backups")
    List<String> list() throws IOException {
        return service.list().stream().map(p -> p.getFileName().toString()).toList();
    }

    @PostMapping("/api/admin/backups")
    ResponseEntity<Map<String, String>> create() throws IOException {
        return ResponseEntity.ok(Map.of("file", service.backupNow().getFileName().toString()));
    }
}
