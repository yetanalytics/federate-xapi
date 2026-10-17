package com.yetanalytics.hlaxapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.injection.TestInjectionContext;
import com.yetanalytics.xapi.util.StatementValidator;
import java.util.List;

/** Shared FOM, rendering, and xAPI validation pipeline for one trigger. */
public final class TriggerValidationEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TriggerValidationEngine() {
    }

    public static Result validate(
            StatementTrigger trigger,
            FomConfigValidator fomValidator,
            TriggerProcessor triggerProcessor,
            StatementValidator statementValidator) {
        try {
            fomValidator.validate(trigger);
        } catch (RuntimeException e) {
            return Result.failure("injection", e.getMessage(), e, false);
        }

        TriggerProcessor.TriggerProcessingResult rendering = triggerProcessor.renderTemplateForValidation(
                trigger,
                new TestInjectionContext(trigger.type, trigger.clazz));
        if (rendering == null || !rendering.success()) {
            Throwable error = rendering == null
                    ? new IllegalArgumentException("Statement template did not produce a result")
                    : rendering.error();
            return Result.failure("render", error.getMessage(), error, false);
        }

        JsonNode rendered;
        try {
            rendered = MAPPER.readTree(rendering.statement());
        } catch (Exception e) {
            return Result.failure("render", e.getMessage(), e, false);
        }
        if (!trigger.skipValidation) {
            var validation = statementValidator.validateStatement(rendering.statement());
            if (!validation.isValid()) {
                List<String> errors = validation.getErrors().stream().sorted().toList();
                return Result.failure("xapi", String.join("; ", errors), null, true, rendered);
            }
        }
        return new Result(true, true, null, null, null, rendered);
    }

    public record Result(
            boolean valid,
            boolean complete,
            String stage,
            String message,
            Throwable cause,
            JsonNode renderedStatement) {

        private static Result failure(
                String stage,
                String message,
                Throwable cause,
                boolean complete) {
            return failure(stage, message, cause, complete, null);
        }

        private static Result failure(
                String stage,
                String message,
                Throwable cause,
                boolean complete,
                JsonNode renderedStatement) {
            return new Result(false, complete, stage, message, cause, renderedStatement);
        }
    }
}
