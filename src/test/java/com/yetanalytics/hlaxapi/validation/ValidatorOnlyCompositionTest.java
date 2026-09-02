package com.yetanalytics.hlaxapi.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.FomConfigValidator;
import com.yetanalytics.hlaxapi.StandardHlaTypeRegistry;
import com.yetanalytics.hlaxapi.TriggerProcessor;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.cache.ObjectSubscriptionAnalysis;
import com.yetanalytics.hlaxapi.config.ConfigParser;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.injection.TestInjectionResolver;
import com.yetanalytics.hlaxapi.injection.TestInjectionContext;
import com.yetanalytics.xapi.util.StatementValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidatorOnlyCompositionTest {

    private static final Path FIXTURES = Path.of("src/test/resources/validation/golden");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validatesInMemoryWithoutFederateRuntimeServices() throws Exception {
        JsonNode request = request("valid-request.json");
        ValidationComposition composition = composition(request);

        List<String> failures = validateAll(composition);
        ObjectSubscriptionAnalysis subscriptions = ObjectSubscriptionAnalysis.analyze(
                composition.config(),
                composition.catalog());

        assertTrue(failures.isEmpty(), failures.toString());
        assertFalse(subscriptions.effectiveRequirements().isEmpty());
        assertEquals(
                List.of("Count", "EntityId"),
                subscriptions.effectiveRequirements().get("BaseEntity.TrackedEntity").stream()
                        .sorted()
                        .toList());
    }

    @Test
    void isolatesIndependentTriggerFailuresAndGoldenDocumentsAreJson() throws Exception {
        JsonNode request = request("invalid-request.json");
        ValidationComposition composition = composition(request);

        List<String> failures = validateAll(composition);

        assertEquals(2, failures.size(), failures.toString());
        assertTrue(request("valid-response.json").path("valid").asBoolean());
        assertFalse(request("invalid-response.json").path("valid").asBoolean());
    }

    private JsonNode request(String name) throws Exception {
        return MAPPER.readTree(Files.readString(FIXTURES.resolve(name)));
    }

    private ValidationComposition composition(JsonNode request) throws Exception {
        StandardHlaTypeRegistry typeRegistry = new StandardHlaTypeRegistry();
        FOMXML fomXml = FOMXML.fromXml(request.path("fom").asText(), typeRegistry);
        FomCatalog catalog = new FomCatalog(fomXml);
        XapiConfig config = ConfigParser.fromJson(MAPPER.writeValueAsString(request.path("config"))).parse();

        return new ValidationComposition(
                config,
                catalog,
                new FomConfigValidator(fomXml, catalog),
                new TriggerProcessor(new TestInjectionResolver(fomXml, catalog, typeRegistry)),
                new StatementValidator());
    }

    private List<String> validateAll(ValidationComposition composition) {
        List<String> failures = new ArrayList<>();
        for (StatementTrigger trigger : composition.config().statementTriggers) {
            try {
                composition.fomValidator().validate(trigger);
                TriggerProcessor.TriggerProcessingResult rendered =
                        composition.triggerProcessor().renderTemplateForValidation(
                                trigger,
                                new TestInjectionContext(trigger.type, trigger.clazz));
                if (!rendered.success()) {
                    failures.add(rendered.error().getMessage());
                } else if (!trigger.skipValidation
                        && !composition.statementValidator().validateStatement(rendered.statement()).isValid()) {
                    failures.add("invalid xAPI statement");
                }
            } catch (RuntimeException e) {
                failures.add(e.getMessage());
            }
        }
        return failures;
    }

    private record ValidationComposition(
            XapiConfig config,
            FomCatalog catalog,
            FomConfigValidator fomValidator,
            TriggerProcessor triggerProcessor,
            StatementValidator statementValidator) {
    }
}
