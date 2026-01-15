package com.company.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Small controller that exposes a deterministic health endpoint.
 * Design note: Return a simple JSON object; avoid heavy logic here.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "checks", Map.of("memory", "ok")
        );
    }
}
