package com.homelabhub.docs.github;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The portfolio's GitHub Pages panel and the static site download. Under /api/admin, so the Docs login applies. */
@RestController
@RequestMapping("/api/admin/portfolio")
class PortfolioGitHubController {

    private final PortfolioGitHub portfolio;

    PortfolioGitHubController(PortfolioGitHub portfolio) {
        this.portfolio = portfolio;
    }

    @GetMapping("/github/status")
    Map<String, Object> status() {
        return portfolio.status();
    }

    @PostMapping("/github/deploy")
    Map<String, Object> deploy() {
        return portfolio.deploy();
    }

    @PostMapping("/github/remove")
    Map<String, Object> remove() {
        return portfolio.remove();
    }

    @GetMapping("/export.zip")
    ResponseEntity<byte[]> exportZip() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"portfolio-site.zip\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(portfolio.exportZip());
    }
}
