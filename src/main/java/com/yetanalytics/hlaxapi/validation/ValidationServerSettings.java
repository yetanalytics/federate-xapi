package com.yetanalytics.hlaxapi.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validator HTTP settings loaded from optional JSON, then overridden by environment variables. */
public record ValidationServerSettings(
        String bindAddress,
        int port,
        long maxRequestBytes,
        List<String> corsAllowedOrigins) {

    public static final String CONFIG_ENV = "VALIDATOR_CONFIG";
    public static final String DEFAULT_CONFIG = "config/validation-server.json";

    public ValidationServerSettings {
        corsAllowedOrigins = List.copyOf(corsAllowedOrigins);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Validator port must be between 0 and 65535");
        }
        if (maxRequestBytes < 1) {
            throw new IllegalArgumentException("Validator maxRequestBytes must be positive");
        }
    }

    public static ValidationServerSettings load() {
        return load(System.getenv());
    }

    static ValidationServerSettings load(Map<String, String> environment) {
        String configPath = environment.getOrDefault(CONFIG_ENV, DEFAULT_CONFIG);
        JsonNode json = readOptionalConfig(Path.of(configPath));
        String address = environment.getOrDefault(
                "VALIDATOR_BIND_ADDRESS", json.path("bindAddress").asText("127.0.0.1"));
        int port = integerSetting(environment, "VALIDATOR_PORT", json, "port", 8081);
        long maxBytes = longSetting(
                environment, "VALIDATOR_MAX_REQUEST_BYTES", json, "maxRequestBytes", 5 * 1024 * 1024L);
        List<String> origins = environment.containsKey("VALIDATOR_CORS_ALLOWED_ORIGINS")
                ? commaSeparated(environment.get("VALIDATOR_CORS_ALLOWED_ORIGINS"))
                : stringList(json.get("corsAllowedOrigins"));
        return new ValidationServerSettings(address, port, maxBytes, origins);
    }

    private static JsonNode readOptionalConfig(Path path) {
        if (!Files.exists(path)) {
            return new ObjectMapper().createObjectNode();
        }
        try {
            JsonNode json = new ObjectMapper().readTree(path.toFile());
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException("Validator config must be a JSON object: " + path);
            }
            return json;
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read validator config " + path, e);
        }
    }

    private static int integerSetting(
            Map<String, String> environment,
            String environmentKey,
            JsonNode json,
            String jsonKey,
            int defaultValue) {
        String value = environment.get(environmentKey);
        return value == null ? json.path(jsonKey).asInt(defaultValue) : Integer.parseInt(value);
    }

    private static long longSetting(
            Map<String, String> environment,
            String environmentKey,
            JsonNode json,
            String jsonKey,
            long defaultValue) {
        String value = environment.get(environmentKey);
        return value == null ? json.path(jsonKey).asLong(defaultValue) : Long.parseLong(value);
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                result.add(value.asText().trim());
            }
        });
        return result;
    }

    private static List<String> commaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return result;
    }
}
