package com.yetanalytics.hlaxapi.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.FomConfigValidator;
import com.yetanalytics.hlaxapi.FomConfigValidator.FomReferenceException;
import com.yetanalytics.hlaxapi.StandardHlaTypeRegistry;
import com.yetanalytics.hlaxapi.TriggerProcessor;
import com.yetanalytics.hlaxapi.TriggerValidationEngine;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.cache.ObjectSubscriptionAnalysis;
import com.yetanalytics.hlaxapi.config.ConfigParser;
import com.yetanalytics.hlaxapi.config.ConfigVersions;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.TrackedObject;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser;
import com.yetanalytics.hlaxapi.injection.TestInjectionResolver;
import com.yetanalytics.hlaxapi.validation.ValidationModels.Diagnostic;
import com.yetanalytics.hlaxapi.validation.ValidationModels.FomAnalysis;
import com.yetanalytics.hlaxapi.validation.ValidationModels.FomCatalogResponse;
import com.yetanalytics.hlaxapi.validation.ValidationModels.InteractionClassInfo;
import com.yetanalytics.hlaxapi.validation.ValidationModels.InteractionParameterInfo;
import com.yetanalytics.hlaxapi.validation.ValidationModels.ModelInfo;
import com.yetanalytics.hlaxapi.validation.ValidationModels.ObjectAttributeInfo;
import com.yetanalytics.hlaxapi.validation.ValidationModels.ObjectClassInfo;
import com.yetanalytics.hlaxapi.validation.ValidationModels.SubscriptionAnalysis;
import com.yetanalytics.hlaxapi.validation.ValidationModels.SubscriptionEntry;
import com.yetanalytics.hlaxapi.validation.ValidationModels.TargetInfo;
import com.yetanalytics.hlaxapi.validation.ValidationModels.TriggerAnalysis;
import com.yetanalytics.hlaxapi.validation.ValidationModels.ValidationRequest;
import com.yetanalytics.hlaxapi.validation.ValidationModels.ValidationResponse;
import com.yetanalytics.xapi.util.StatementValidator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.xpath.XPathExpressionException;

/** Stateless composition of the authoritative Java validators. */
public final class ValidationCore {

    public static final String API_VERSION = "v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SubscriptionAnalysis EMPTY_SUBSCRIPTIONS =
            new SubscriptionAnalysis(List.of(), List.of(), List.of(), List.of());

    public ValidationResponse validate(ValidationRequest request) {
        requireEnvelope(request);
        List<Diagnostic> diagnostics = new ArrayList<>();

        StandardHlaTypeRegistry typeRegistry = new StandardHlaTypeRegistry();
        FOMXML fomXml = null;
        FomCatalog catalog = null;
        FomAnalysis fomAnalysis;
        try {
            fomXml = FOMXML.fromXml(request.fom(), typeRegistry);
            catalog = new FomCatalog(fomXml);
            fomAnalysis = new FomAnalysis(true, catalogResponse(fomXml, catalog));
        } catch (RuntimeException e) {
            diagnostics.add(diagnostic(
                    "FOM_INVALID", "fom", "/fom", null, rootMessage(e)));
            fomAnalysis = new FomAnalysis(false, null);
        }

        XapiConfig config = null;
        String configVersion = configuredVersion(request.config());
        try {
            validateConfigShape(request.config());
            config = ConfigParser.fromJson(MAPPER.writeValueAsString(request.config())).parse();
            configVersion = config.configVersion;
        } catch (Exception e) {
            diagnostics.add(diagnostic(
                    configErrorCode(e), "config", configErrorPath(e), null, rootMessage(e)));
        }

        List<TriggerAnalysis> triggers = new ArrayList<>();
        SubscriptionAnalysis subscriptions = EMPTY_SUBSCRIPTIONS;
        boolean complete = fomXml != null && config != null;
        if (fomXml != null && catalog != null && config != null) {
            triggers.addAll(validateTriggers(config, request.config(), fomXml, catalog, typeRegistry));
            for (TriggerAnalysis trigger : triggers) {
                diagnostics.addAll(trigger.diagnostics());
                complete &= trigger.complete();
            }
            try {
                subscriptions = subscriptionAnalysis(
                        configWithValidatedTriggers(config, triggers), catalog);
            } catch (RuntimeException e) {
                diagnostics.add(diagnostic(
                        "SUBSCRIPTION_ANALYSIS_FAILED",
                        "subscription",
                        "/objectCache",
                        null,
                        rootMessage(e)));
                complete = false;
            }
            if (request.scope() == ValidationModels.ValidationScope.EXPORT) {
                validateExport(config, diagnostics);
            }
        }

        boolean valid = diagnostics.stream().noneMatch(item -> "error".equals(item.severity()));
        return new ValidationResponse(
                valid,
                complete,
                API_VERSION,
                validatorVersion(),
                configVersion,
                ConfigVersions.SUPPORTED.stream().sorted().toList(),
                fomAnalysis,
                List.copyOf(diagnostics),
                List.copyOf(triggers),
                subscriptions);
    }

    private static void requireEnvelope(ValidationRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Request body is required");
        }
        if (request.scope() == null) {
            throw new InvalidRequestException("scope is required");
        }
        if (request.fom() == null) {
            throw new InvalidRequestException("fom is required");
        }
        if (request.config() == null || !request.config().isObject()) {
            throw new InvalidRequestException("config must be a JSON object");
        }
    }

    private static void validateConfigShape(JsonNode config) {
        JsonNode triggers = config.get("statementTriggers");
        if (triggers == null || !triggers.isArray()) {
            throw new IllegalArgumentException("statementTriggers must be an array");
        }
        for (int index = 0; index < triggers.size(); index++) {
            if (!triggers.get(index).isObject()) {
                throw new IllegalArgumentException(
                        "statementTriggers[" + index + "] must be an object");
            }
        }
    }

    private List<TriggerAnalysis> validateTriggers(
            XapiConfig config,
            JsonNode rawConfig,
            FOMXML fomXml,
            FomCatalog catalog,
            StandardHlaTypeRegistry typeRegistry) {
        if (config.statementTriggers == null) {
            return List.of();
        }
        Set<String> interactions = new LinkedHashSet<>();
        fomXml.interactionClassDefinitions().forEach(definition -> interactions.add(definition.name()));
        FomConfigValidator fomValidator = new FomConfigValidator(fomXml, catalog);
        TriggerProcessor processor = new TriggerProcessor(new TestInjectionResolver(fomXml, catalog, typeRegistry));
        StatementValidator statementValidator = new StatementValidator();
        List<TriggerAnalysis> analyses = new ArrayList<>();

        for (int index = 0; index < config.statementTriggers.size(); index++) {
            StatementTrigger trigger = config.statementTriggers.get(index);
            String basePath = "/statementTriggers/" + index;
            List<Diagnostic> triggerDiagnostics = new ArrayList<>();
            boolean triggerComplete = true;

            validateTriggerHeader(trigger, catalog, interactions, index, basePath, triggerDiagnostics);
            JsonNode rawTrigger = rawConfig.path("statementTriggers").path(index);
            scanMalformedInjections(rawTrigger.get("statement"), basePath + "/statement", index, triggerDiagnostics);

            JsonNode rendered = null;
            if (triggerDiagnostics.isEmpty()) {
                TriggerValidationEngine.Result result = TriggerValidationEngine.validate(
                        trigger, fomValidator, processor, statementValidator);
                rendered = result.renderedStatement();
                if (!result.valid()) {
                    String diagnosticPath = basePath + "/statement";
                    if (result.cause() instanceof FomReferenceException reference) {
                        diagnosticPath += reference.statementPointer();
                    }
                    triggerDiagnostics.add(diagnostic(
                            triggerErrorCode(result),
                            result.stage(),
                            diagnosticPath,
                            index,
                            result.message()));
                    triggerComplete = result.complete();
                }
            } else {
                triggerComplete = false;
            }
            analyses.add(new TriggerAnalysis(
                    index,
                    triggerDiagnostics.isEmpty(),
                    triggerComplete,
                    List.copyOf(triggerDiagnostics),
                    rendered));
        }
        return List.copyOf(analyses);
    }

    private static void validateTriggerHeader(
            StatementTrigger trigger,
            FomCatalog catalog,
            Set<String> interactions,
            int index,
            String basePath,
            List<Diagnostic> diagnostics) {
        if (trigger == null || trigger.type == null) {
            diagnostics.add(diagnostic(
                    "TRIGGER_TYPE_INVALID",
                    "config",
                    basePath + "/type",
                    index,
                    "Trigger type must be Interaction, ObjectCreate, ObjectUpdate, or ObjectDelete"));
            return;
        }
        if (trigger.clazz == null || trigger.clazz.isBlank()) {
            diagnostics.add(diagnostic(
                    "TRIGGER_CLASS_REQUIRED", "config", basePath + "/class", index, "Trigger class is required"));
            return;
        }
        boolean exists = trigger.type.isObjectEvent()
                ? catalog.objectClass(trigger.clazz).isPresent()
                : interactions.contains(trigger.clazz);
        if (!exists) {
            diagnostics.add(diagnostic(
                    "FOM_CLASS_NOT_FOUND",
                    "fom",
                    basePath + "/class",
                    index,
                    (trigger.type.isObjectEvent() ? "Object" : "Interaction")
                            + " class " + trigger.clazz + " does not exist in the FOM"));
        }
        if (trigger.statement == null) {
            diagnostics.add(diagnostic(
                    "STATEMENT_REQUIRED",
                    "config",
                    basePath + "/statement",
                    index,
                    "Statement template is required"));
        }
    }

    private static void scanMalformedInjections(
            JsonNode node,
            String path,
            int triggerIndex,
            List<Diagnostic> diagnostics) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            var parsed = StatementInjectionParser.parse(node);
            if (parsed.recognized()) {
                if (!parsed.valid()) {
                    diagnostics.add(diagnostic(
                            "INJECTION_MALFORMED",
                            "injection",
                            path,
                            triggerIndex,
                            "Malformed " + parsed.type().name().toLowerCase() + " injection"));
                }
                return;
            }
            for (int index = 0; index < node.size(); index++) {
                scanMalformedInjections(node.get(index), path + "/" + index, triggerIndex, diagnostics);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(field -> scanMalformedInjections(
                    field.getValue(), path + "/" + escapePointer(field.getKey()), triggerIndex, diagnostics));
        } else if (node.isTextual()) {
            for (var inline : StatementInjectionParser.findInline(node.asText())) {
                if (inline.result().recognized() && !inline.result().valid()) {
                    diagnostics.add(diagnostic(
                            "INJECTION_MALFORMED",
                            "injection",
                            path,
                            triggerIndex,
                            "Malformed inline " + inline.result().type().name().toLowerCase() + " injection"));
                }
            }
        }
    }

    private FomCatalogResponse catalogResponse(FOMXML fomXml, FomCatalog catalog) {
        FOMXML.ModelIdentification identification = fomXml.modelIdentification();
        ModelInfo model = identification == null
                ? null
                : new ModelInfo(identification.name(), identification.version());
        Map<String, FOMXML.ObjectClassDefinition> objectDefinitions = new LinkedHashMap<>();
        fomXml.objectClassDefinitions().forEach(definition -> objectDefinitions.put(definition.name(), definition));
        List<ObjectClassInfo> objects = catalog.objectClasses().stream()
                .filter(clazz -> !"HLAobjectRoot".equals(clazz.hlaName()))
                .map(clazz -> objectClassInfo(clazz, objectDefinitions, fomXml))
                .toList();
        List<InteractionClassInfo> interactions = interactionClasses(fomXml);
        return new FomCatalogResponse(model, objects, interactions);
    }

    private ObjectClassInfo objectClassInfo(
            FomCatalog.ObjectClassDef clazz,
            Map<String, FOMXML.ObjectClassDefinition> definitions,
            FOMXML fomXml) {
        Map<String, List<FomCatalog.FomAttribute>> grouped = new LinkedHashMap<>();
        clazz.attributes().forEach(attribute -> grouped
                .computeIfAbsent(attribute.attributeName(), ignored -> new ArrayList<>())
                .add(attribute));
        List<ObjectAttributeInfo> attributes = new ArrayList<>();
        grouped.forEach((name, flattened) -> {
            String declaredOn = objectDeclaration(clazz.hlaName(), name, definitions);
            String dataType = flattened.get(0).dataType();
            attributes.add(new ObjectAttributeInfo(
                    name,
                    declaredOn,
                    dataType,
                    targetInfos(fomXml, List.of(name), dataType)));
        });
        return new ObjectClassInfo(
                clazz.hlaName(), localName(clazz.hlaName()), clazz.parentName(), List.copyOf(attributes));
    }

    private List<InteractionClassInfo> interactionClasses(FOMXML fomXml) {
        Map<String, FOMXML.InteractionClassDefinition> definitions = new LinkedHashMap<>();
        fomXml.interactionClassDefinitions().forEach(definition -> definitions.put(definition.name(), definition));
        List<InteractionClassInfo> result = new ArrayList<>();
        for (FOMXML.InteractionClassDefinition definition : definitions.values()) {
            if ("HLAinteractionRoot".equals(definition.name())) {
                continue;
            }
            Map<String, ParameterSource> parameters = new LinkedHashMap<>();
            collectInteractionParameters(definition, definitions, parameters);
            List<InteractionParameterInfo> parameterInfos = parameters.values().stream()
                    .map(parameter -> new InteractionParameterInfo(
                            parameter.name(),
                            parameter.declaredOn(),
                            parameter.dataType(),
                            targetInfos(fomXml, List.of(parameter.name()), parameter.dataType())))
                    .toList();
            result.add(new InteractionClassInfo(
                    definition.name(),
                    localName(definition.name()),
                    definition.parentName(),
                    parameterInfos));
        }
        return List.copyOf(result);
    }

    private static void collectInteractionParameters(
            FOMXML.InteractionClassDefinition definition,
            Map<String, FOMXML.InteractionClassDefinition> definitions,
            Map<String, ParameterSource> result) {
        FOMXML.InteractionClassDefinition parent = definitions.get(definition.parentName());
        if (parent != null) {
            collectInteractionParameters(parent, definitions, result);
        }
        definition.parameters().forEach(parameter -> result.put(
                parameter.name(),
                new ParameterSource(parameter.name(), definition.name(), parameter.dataType())));
    }

    private List<TargetInfo> targetInfos(FOMXML fomXml, List<Object> path, String dataType) {
        try {
            String primitive = fomXml.resolvePrimitiveType(dataType);
            if (primitive != null) {
                return List.of(new TargetInfo(
                        path, pathText(path), dataType, primitive, valueType(primitive), true));
            }
            if (fomXml.isFixedRecordType(dataType)) {
                List<TargetInfo> targets = new ArrayList<>();
                targets.add(new TargetInfo(path, pathText(path), dataType, null, "object", false));
                for (FOMXML.FixedRecordField field : fomXml.getFixedRecordFields(dataType)) {
                    List<Object> childPath = new ArrayList<>(path);
                    childPath.add(field.name);
                    targets.addAll(targetInfos(fomXml, childPath, field.dataType));
                }
                return List.copyOf(targets);
            }
            if (fomXml.isArrayType(dataType)) {
                List<TargetInfo> targets = new ArrayList<>();
                targets.add(new TargetInfo(path, pathText(path), dataType, null, "array", false));
                List<Object> childPath = new ArrayList<>(path);
                childPath.add(0);
                targets.addAll(targetInfos(fomXml, childPath, fomXml.getArrayElementType(dataType)));
                return List.copyOf(targets);
            }
            return List.of(new TargetInfo(path, pathText(path), dataType, null, "unknown", true));
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException("Could not inspect FOM datatype " + dataType, e);
        }
    }

    private static SubscriptionAnalysis subscriptionAnalysis(XapiConfig config, FomCatalog catalog) {
        ObjectSubscriptionAnalysis analysis = ObjectSubscriptionAnalysis.analyze(config, catalog);
        Map<String, Set<String>> explicit = explicitSubscriptions(config, catalog);
        Map<String, Set<String>> inferredCache = subtract(analysis.cacheRequirements(), explicit);
        return new SubscriptionAnalysis(
                entries(inferredCache),
                entries(analysis.eventRequirements()),
                entries(explicit),
                entries(analysis.effectiveRequirements()));
    }

    private static XapiConfig configWithValidatedTriggers(
            XapiConfig source,
            List<TriggerAnalysis> analyses) {
        XapiConfig filtered = new XapiConfig();
        filtered.configVersion = source.configVersion;
        filtered.lrsConfig = source.lrsConfig;
        filtered.objectCacheConfig = source.objectCacheConfig;
        filtered.statementTriggers = analyses.stream()
                .filter(TriggerAnalysis::valid)
                .map(analysis -> source.statementTriggers.get(analysis.index()))
                .toList();
        return filtered;
    }

    private static Map<String, Set<String>> explicitSubscriptions(XapiConfig config, FomCatalog catalog) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (config.objectCacheConfig == null || config.objectCacheConfig.trackedObjects == null) {
            return result;
        }
        for (TrackedObject tracked : config.objectCacheConfig.trackedObjects) {
            if (tracked == null || tracked.clazz == null) {
                continue;
            }
            Collection<FomCatalog.ObjectClassDef> classes = "*".equals(tracked.clazz)
                    ? catalog.objectClasses()
                    : catalog.objectClassAndDescendants(tracked.clazz);
            for (FomCatalog.ObjectClassDef clazz : classes) {
                Collection<String> attributes = tracked.allAttributes
                        ? clazz.topLevelAttributeNames()
                        : tracked.attributes;
                if (attributes != null) {
                    result.computeIfAbsent(clazz.hlaName(), ignored -> new LinkedHashSet<>()).addAll(attributes);
                }
            }
        }
        return result;
    }

    private static Map<String, Set<String>> subtract(
            Map<String, Set<String>> source,
            Map<String, Set<String>> removed) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        source.forEach((clazz, attributes) -> {
            Set<String> remaining = new LinkedHashSet<>(attributes);
            remaining.removeAll(removed.getOrDefault(clazz, Set.of()));
            if (!remaining.isEmpty()) {
                result.put(clazz, remaining);
            }
        });
        return result;
    }

    private static List<SubscriptionEntry> entries(Map<String, Set<String>> subscriptions) {
        return subscriptions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new SubscriptionEntry(
                        entry.getKey(), entry.getValue().stream().sorted().toList()))
                .toList();
    }

    private static void validateExport(XapiConfig config, List<Diagnostic> diagnostics) {
        if (config.lrsConfig == null) {
            diagnostics.add(diagnostic(
                    "LRS_REQUIRED_FOR_EXPORT",
                    "export",
                    "/lrs",
                    null,
                    "An lrs section is required for export validation"));
            return;
        }
        requireExportText(config.lrsConfig.host, "host", diagnostics);
        requireExportText(config.lrsConfig.key, "key", diagnostics);
        requireExportText(config.lrsConfig.secret, "secret", diagnostics);
    }

    private static void requireExportText(String value, String field, List<Diagnostic> diagnostics) {
        if (value == null || value.isBlank()) {
            diagnostics.add(diagnostic(
                    "LRS_FIELD_REQUIRED_FOR_EXPORT",
                    "export",
                    "/lrs/" + field,
                    null,
                    "lrs." + field + " is required for export validation"));
        }
    }

    private static String objectDeclaration(
            String className,
            String attributeName,
            Map<String, FOMXML.ObjectClassDefinition> definitions) {
        FOMXML.ObjectClassDefinition current = definitions.get(className);
        while (current != null) {
            if (current.attributes().stream().anyMatch(attribute -> attribute.name().equals(attributeName))) {
                return current.name();
            }
            current = definitions.get(current.parentName());
        }
        return className;
    }

    private static String configuredVersion(JsonNode config) {
        JsonNode version = config == null ? null : config.get("configVersion");
        return version != null && version.isTextual() ? version.asText() : ConfigVersions.CURRENT;
    }

    private static String configErrorCode(Exception error) {
        return rootMessage(error).contains("configVersion") ? "CONFIG_VERSION_UNSUPPORTED" : "CONFIG_INVALID";
    }

    private static String configErrorPath(Exception error) {
        String message = rootMessage(error);
        if (message.contains("configVersion")) {
            return "/configVersion";
        }
        int start = message.indexOf("statementTriggers[");
        if (start >= 0) {
            int end = message.indexOf(']', start);
            if (end > start) {
                return "/statementTriggers/" + message.substring(start + 18, end);
            }
        }
        return "";
    }

    private static String triggerErrorCode(TriggerValidationEngine.Result result) {
        if ("xapi".equals(result.stage())) {
            return "XAPI_STATEMENT_INVALID";
        }
        if ("render".equals(result.stage())) {
            return "STATEMENT_RENDER_FAILED";
        }
        String message = result.message() == null ? "" : result.message();
        return message.contains("does not exist") ? "FOM_TARGET_NOT_FOUND" : "FOM_REFERENCE_INVALID";
    }

    private static Diagnostic diagnostic(
            String code,
            String stage,
            String path,
            Integer triggerIndex,
            String message) {
        return new Diagnostic("error", code, stage, path, triggerIndex, message);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String localName(String canonicalName) {
        int separator = canonicalName.lastIndexOf('.');
        return separator < 0 ? canonicalName : canonicalName.substring(separator + 1);
    }

    private static String pathText(List<Object> path) {
        StringBuilder text = new StringBuilder();
        for (Object part : path) {
            if (part instanceof Number number) {
                text.append('[').append(number.intValue()).append(']');
            } else {
                if (text.length() > 0) {
                    text.append('.');
                }
                text.append(part);
            }
        }
        return text.toString();
    }

    private static String valueType(String primitive) {
        Class<?> type = new StandardHlaTypeRegistry().getClassForType(primitive);
        if (type == Boolean.class) {
            return "boolean";
        }
        if (Number.class.isAssignableFrom(type)) {
            return "number";
        }
        return "string";
    }

    private static String escapePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String validatorVersion() {
        String version = ValidationCore.class.getPackage().getImplementationVersion();
        return version == null ? "1.0-SNAPSHOT" : version;
    }

    private record ParameterSource(String name, String declaredOn, String dataType) {
    }

    public static final class InvalidRequestException extends IllegalArgumentException {

        public InvalidRequestException(String message) {
            super(message);
        }
    }
}
