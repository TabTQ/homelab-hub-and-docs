package com.homelabhub.pub;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PublicControllerTest {

    private static final Path SNAPSHOT_DIR = createDir();

    private static Path createDir() {
        try {
            return Files.createTempDirectory("pub-controller-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hub.snapshot.dir", () -> SNAPSHOT_DIR.toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SnapshotStore snapshotStore;

    @BeforeEach
    void clean() throws IOException {
        snapshotStore.reset();
        Files.deleteIfExists(SNAPSHOT_DIR.resolve("snapshot.json"));
    }

    private void writeValidSnapshot() throws IOException {
        Files.writeString(SNAPSHOT_DIR.resolve("snapshot.json"),
                "{\"schemaVersion\":1,\"generatedAt\":\"2026-01-01T00:00:00Z\",\"data\":{\"hosts\":[]}}");
    }

    @Test
    void portfolioReturns200WithHeadersAndBodyWhenValid() throws Exception {
        writeValidSnapshot();
        String expectedBody = Files.readString(SNAPSHOT_DIR.resolve("snapshot.json"));

        mockMvc.perform(get("/api/public/portfolio"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().bytes(expectedBody.getBytes(StandardCharsets.UTF_8)))
                .andExpect(header().string(HttpHeaders.ETAG, matchesPattern("^\".{16}\"$")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=30"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void portfolioReturns304WhenIfNoneMatchMatches() throws Exception {
        writeValidSnapshot();

        MvcResult first = mockMvc.perform(get("/api/public/portfolio"))
                .andExpect(status().isOk())
                .andReturn();
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(get("/api/public/portfolio").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(content().string(""));
    }

    @Test
    void portfolioReturns503WhenNoSnapshot() throws Exception {
        mockMvc.perform(get("/api/public/portfolio"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("snapshot_unavailable"));
    }

    @Test
    void portfolioRejectsPostWith405() throws Exception {
        mockMvc.perform(post("/api/public/portfolio"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unknownRouteReturns404() throws Exception {
        mockMvc.perform(get("/api/public/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void healthReturnsOkWithSnapshotFlagAndSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/public/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.snapshot").value(false))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}