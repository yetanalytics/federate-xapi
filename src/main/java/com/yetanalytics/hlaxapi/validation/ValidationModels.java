package com.yetanalytics.hlaxapi.validation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Transport models for the stateless validation API. */
public final class ValidationModels {

    private ValidationModels() {
    }

    public enum ValidationScope {
        AUTHORING,
        EXPORT;

        @JsonCreator
        public static ValidationScope fromJson(String value) {
            if (value == null) {
                return null;
            }
            for (ValidationScope scope : values()) {
                if (scope.jsonValue().equalsIgnoreCase(value.trim())) {
                    return scope;
                }
            }
            throw new IllegalArgumentException("scope must be authoring or export");
        }

        @JsonValue
        public String jsonValue() {
            return name().toLowerCase();
        }
    }

    public record ValidationRequest(ValidationScope scope, String fom, JsonNode config) {
    }

    public record CapabilitiesResponse(
            boolean ready,
            String apiVersion,
            String validatorVersion,
            List<String> supportedConfigVersions,
            List<String> features) {
    }

    public record ValidationResponse(
            boolean valid,
            boolean complete,
            String apiVersion,
            String validatorVersion,
            String configVersion,
            List<String> supportedConfigVersions,
            FomAnalysis fom,
            List<Diagnostic> diagnostics,
            List<TriggerAnalysis> triggers,
            SubscriptionAnalysis subscriptions) {
    }

    public record FomAnalysis(boolean valid, FomCatalogResponse catalog) {
    }

    public record FomCatalogResponse(
            ModelInfo model,
            List<ObjectClassInfo> objectClasses,
            List<InteractionClassInfo> interactionClasses) {
    }

    public record ModelInfo(String name, String version) {
    }

    public record ObjectClassInfo(
            String name,
            String localName,
            String parentName,
            List<ObjectAttributeInfo> attributes) {
    }

    public record ObjectAttributeInfo(
            String name,
            String declaredOn,
            String dataType,
            List<TargetInfo> targets) {
    }

    public record InteractionClassInfo(
            String name,
            String localName,
            String parentName,
            List<InteractionParameterInfo> parameters) {
    }

    public record InteractionParameterInfo(
            String name,
            String declaredOn,
            String dataType,
            List<TargetInfo> targets) {
    }

    public record TargetInfo(
            List<Object> path,
            String pathText,
            String dataType,
            String primitiveType,
            String valueType,
            boolean leaf) {
    }

    public record Diagnostic(
            String severity,
            String code,
            String stage,
            String path,
            Integer triggerIndex,
            String message) {
    }

    public record TriggerAnalysis(
            int index,
            boolean valid,
            boolean complete,
            List<Diagnostic> diagnostics,
            JsonNode renderedStatement) {
    }

    public record SubscriptionAnalysis(
            List<SubscriptionEntry> cacheInferred,
            List<SubscriptionEntry> eventInferred,
            List<SubscriptionEntry> explicit,
            List<SubscriptionEntry> effective) {
    }

    public record SubscriptionEntry(
            @JsonProperty("class") String clazz,
            List<String> attributes) {
    }

    public record ProblemResponse(String type, String title, int status, String detail) {
    }
}
