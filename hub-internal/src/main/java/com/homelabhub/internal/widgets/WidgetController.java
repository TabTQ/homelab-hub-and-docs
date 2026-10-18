package com.homelabhub.internal.widgets;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WidgetController {

    private final WidgetService service;

    WidgetController(WidgetService service) {
        this.service = service;
    }

    @GetMapping("/api/widgets/item/{itemId:.+}")
    ResponseEntity<Map<String, Object>> item(@PathVariable String itemId) {
        return service.forItem(itemId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/widgets/info/{type}")
    ResponseEntity<Map<String, Object>> info(@PathVariable String type) {
        return service.forInfo(type).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
