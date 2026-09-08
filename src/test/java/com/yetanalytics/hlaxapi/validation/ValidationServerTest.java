package com.yetanalytics.hlaxapi.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

class ValidationServerTest {

    private static final Path FIXTURES = Path.of("src/test/resources/validation/golden");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static ConfigurableApplicationContext context;
    private static String baseUrl;

    @BeforeAll
    static void startServer() {
        ValidationServerSettings settings = new ValidationServerSettings(
                "127.0.0.1", 0, 100_000, List.of("http://localhost:5173"));
        context = ValidationServerApplication.run(settings, new String[0]);
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void exposesCapabilitiesAndGeneratedOpenApi() throws Exception {
        JsonNode capabilities = json(get("/api/v1/validation/capabilities", null));
        assertTrue(capabilities.path("ready").asBoolean());
        assertEquals("v1", capabilities.path("apiVersion").asText());

        JsonNode openApi = json(get("/openapi.json", null));
        assertTrue(openApi.path("paths").has("/api/v1/validation"));
        assertTrue(openApi.path("paths").has("/api/v1/validation/capabilities"));
    }

    @Test
    void validatesRequestsAndKeepsTriggerFailuresIndependent() throws Exception {
        JsonNode valid = json(post(fixture("valid-request.json")));
        assertEquals(MAPPER.readTree(fixture("valid-response.json")), valid);
        assertTrue(valid.path("valid").asBoolean());
        assertTrue(valid.path("triggers").get(0).path("valid").asBoolean());
        assertFalse(valid.path("fom").path("catalog").path("interactionClasses").isEmpty());

        JsonNode invalid = json(post(fixture("invalid-request.json")));
        assertEquals(MAPPER.readTree(fixture("invalid-response.json")), invalid);
        assertFalse(invalid.path("valid").asBoolean());
        assertEquals(2, invalid.path("triggers").size());
        assertEquals(2, invalid.path("diagnostics").size());
        assertEquals("FOM_TARGET_NOT_FOUND", invalid.path("diagnostics").get(0).path("code").asText());
        assertEquals("FOM_CLASS_NOT_FOUND", invalid.path("diagnostics").get(1).path("code").asText());
    }

    @Test
    void reportsBadJsonEnforcesLimitsAndAppliesCors() throws Exception {
        HttpResponse<String> malformed = post("{");
        assertEquals(400, malformed.statusCode());
        assertEquals(400, MAPPER.readTree(malformed.body()).path("status").asInt());

        HttpResponse<String> oversized = post(" ".repeat(100_001));
        assertEquals(413, oversized.statusCode());

        HttpResponse<String> cors = get(
                "/api/v1/validation/capabilities", "http://localhost:5173");
        assertEquals("http://localhost:5173", cors.headers()
                .firstValue("Access-Control-Allow-Origin")
                .orElse(null));
    }

    @Test
    void environmentOverridesJsonSettings() {
        ValidationServerSettings settings = ValidationServerSettings.load(Map.of(
                ValidationServerSettings.CONFIG_ENV, "/path/that/does/not/exist.json",
                "VALIDATOR_PORT", "9090",
                "VALIDATOR_BIND_ADDRESS", "0.0.0.0",
                "VALIDATOR_MAX_REQUEST_BYTES", "2048",
                "VALIDATOR_CORS_ALLOWED_ORIGINS", "https://one.example, https://two.example"));
        assertEquals(9090, settings.port());
        assertEquals("0.0.0.0", settings.bindAddress());
        assertEquals(2048, settings.maxRequestBytes());
        assertEquals(List.of("https://one.example", "https://two.example"), settings.corsAllowedOrigins());
    }

    private static HttpResponse<String> get(String path, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (origin != null) {
            request.header("Origin", origin);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/validation"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(FIXTURES.resolve(name));
    }
}
