package com.homelabhub.snapshot;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wrapper around the public portfolio snapshot written by hub-internal and read by hub-public.
 * The payload stays a JsonNode until the portfolio schema is final (plan phase P5).
 */
public record SnapshotEnvelope(int schemaVersion, String generatedAt, JsonNode data) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
