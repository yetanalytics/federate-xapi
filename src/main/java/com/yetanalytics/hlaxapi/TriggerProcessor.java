package com.yetanalytics.hlaxapi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.cache.ValueResolution;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.injection.InjectionContext;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser;
import com.yetanalytics.hlaxapi.injection.TestInjectionContext;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.InjectionOptions;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.InlineInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.LookupInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.ParseResult;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.PreviousInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.QueryInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.StatementInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.TriggerInjection;

@Component
public class TriggerProcessor {

    private static final Logger logger = LogManager.getLogger(TriggerProcessor.class);

    @Autowired
    private InjectionHandler injectionHandler;

    @Autowired
    private XapiConfig xapiConfig;

    @Autowired
    private FomCatalog fomCatalog;

    public TriggerProcessor() {
    }

    // For tests and non-Spring code, allow injection of a custom InjectionHandler
    public TriggerProcessor(InjectionHandler injectionHandler) {
        this.injectionHandler = injectionHandler;
    }

    // For tests and non-Spring code that exercise trigger dispatch.
    public TriggerProcessor(
            XapiConfig xapiConfig,
            InjectionHandler injectionHandler,
            FomCatalog fomCatalog) {
        this.xapiConfig = xapiConfig;
        this.injectionHandler = injectionHandler;
        this.fomCatalog = fomCatalog;
    }

    public record TriggerProcessingResult(String statement, boolean matched, boolean success, Throwable error) {

        private static TriggerProcessingResult emitted(String statement) {
            return new TriggerProcessingResult(statement, true, true, null);
        }

        private static TriggerProcessingResult skipped() {
            return new TriggerProcessingResult(null, false, true, null);
        }

        private static TriggerProcessingResult failed(Throwable error) {
            return new TriggerProcessingResult(null, false, false, error);
        }
    }

    public record StagedStatement(StatementTrigger trigger, String statement) {
    }

    public List<StagedStatement> stage(InjectionContext context) {
        if (xapiConfig.statementTriggers == null) {
            return List.of();
        }
        StatementTrigger.Type eventType = context.eventType();
        String hlaClass = context.getHlaClass();
        List<StagedStatement> statements = new ArrayList<>();
        for (StatementTrigger trigger : xapiConfig.statementTriggers) {
            if (!matchesTrigger(trigger, eventType, hlaClass)) {
                continue;
            }
            try {
                logger.trace("Processing {} trigger for {}", eventType, hlaClass);
                TriggerProcessingResult result = processTrigger(trigger, context);
                if (result == null) {
                    logger.error("Trigger {}.{} did not produce a processing result", eventType, hlaClass);
                } else if (result.success() && result.matched()) {
                    statements.add(new StagedStatement(trigger, result.statement()));
                } else if (!result.success()) {
                    logger.error("Error processing trigger {}.{}", eventType, hlaClass, result.error());
                }
            } catch (RuntimeException e) {
                logger.error("Error processing trigger {}.{}", eventType, hlaClass, e);
            }
        }
        return List.copyOf(statements);
    }

    public boolean hasMatchingTrigger(
            StatementTrigger.Type eventType,
            String hlaClass) {
        if (xapiConfig.statementTriggers == null) {
            return false;
        }
        return xapiConfig.statementTriggers.stream()
                .anyMatch(trigger -> matchesTrigger(trigger, eventType, hlaClass));
    }

    private boolean matchesTrigger(
            StatementTrigger trigger,
            StatementTrigger.Type eventType,
            String hlaClass) {
        return trigger != null
                && trigger.type == eventType
                && matchesClass(eventType, trigger.clazz, hlaClass);
    }

    private boolean matchesClass(
            StatementTrigger.Type eventType,
            String configuredClass,
            String actualClass) {
        return eventType.isObjectEvent()
                ? fomCatalog.isSameOrDescendant(actualClass, configuredClass)
                : Objects.equals(configuredClass, actualClass);
    }

    public void enqueue(List<StagedStatement> statements, Consumer<String> statementSink) {
        for (StagedStatement statement : statements) {
            try {
                statementSink.accept(statement.statement());
            } catch (RuntimeException e) {
                StatementTrigger trigger = statement.trigger();
                logger.error("Error enqueueing statement for trigger {}.{}",
                        trigger.type,
                        trigger.clazz,
                        e);
            }
        }
    }

    public void dispatch(
            InjectionContext context,
            Consumer<String> statementSink) {
        enqueue(stage(context), statementSink);
    }

    public TriggerProcessingResult processTrigger(StatementTrigger trigger, InjectionContext context) {
        return processTrigger(trigger, context, true);
    }

    public TriggerProcessingResult renderTemplateForValidation(StatementTrigger trigger, InjectionContext context) {
        return processTrigger(trigger, context, false);
    }

    private TriggerProcessingResult processTrigger(
            StatementTrigger trigger,
            InjectionContext context,
            boolean evaluateCriteria) {
        if (trigger == null || trigger.statement == null) {
            return null;
        }
        if (context == null) {
            return TriggerProcessingResult.failed(
                    new IllegalArgumentException("Injection context is required"));
        }
        if (trigger.type != context.eventType()) {
            return TriggerProcessingResult.failed(new IllegalArgumentException(
                    "Trigger type " + trigger.type
                            + " does not match injection context type " + context.eventType()));
        }
        ObjectMapper mapper = new ObjectMapper();
        try {
            LazyLookupContext lookups = new LazyLookupContext(injectionHandler, context, trigger.lookups);
            if (evaluateCriteria
                    && !new TriggerCriteriaMatcher(injectionHandler).matches(trigger.criteria, context, lookups)) {
                logger.trace("Skipping trigger {}.{} because criteria did not match", trigger.type, trigger.clazz);
                return TriggerProcessingResult.skipped();
            }
            JsonNode stmtNode = mapper.readTree(trigger.statement);
            context.setObjectType(getObjectType(stmtNode));
            JsonNode processed = processNode(stmtNode, context, mapper, lookups, List.of());

            String output = mapper.writeValueAsString(processed);
            logger.trace("Processed statement output: {}", output);
            return TriggerProcessingResult.emitted(output);
        } catch (Exception e) {
            logger.error("Could not process trigger {}.{}: {}", trigger.type, trigger.clazz, e.getMessage(), e);
            return TriggerProcessingResult.failed(e);
        }
    }

    public String getObjectType(JsonNode stmtNode) {
        try {
            return stmtNode.get("object").get("objectType").asText();
        } catch (Exception e) {
            // if there is any problem whatsoever either the statement is invalid
            // which is not the concern of this function or the objectType is null
            // which should be reflected.
            return null;
        }

    }

    private JsonNode processNode(
            JsonNode node,
            InjectionContext context,
            ObjectMapper mapper,
            LazyLookupContext lookups,
            List<Object> statementPath) throws JsonProcessingException {
        if (node == null || node.isNull())
            return node;

        if (node.isObject()) {
            // no injection possible, just process children
            ObjectNode out = mapper.createObjectNode();
            Iterator<String> fields = node.fieldNames();
            while(fields.hasNext()){
                String field = fields.next();
                JsonNode child = node.get(field);
                JsonNode processedChild = processNode(
                        child,
                        context,
                        mapper,
                        lookups,
                        appendPath(statementPath, field));
                out.set(field, processedChild);
            }
            return out;
        }

        if (node.isArray()) {
            ParseResult parsed = StatementInjectionParser.parse(node);
            if (parsed.recognized()) {
                return parsed.valid()
                        ? handleInjection(parsed.injection(), context, mapper, false, lookups, statementPath)
                        : NullNode.instance;
            }
            ArrayNode out = mapper.createArrayNode();
            for (int index = 0; index < node.size(); index++) {
                out.add(processNode(
                        node.get(index),
                        context,
                        mapper,
                        lookups,
                        appendPath(statementPath, index)));
            }
            return out;
        }

        if (node.isTextual()) {
            String txt = node.asText();
            List<InlineInjection> injections = StatementInjectionParser.findInline(txt);
            if (injections.isEmpty()) {
                return node;
            }

            StringBuilder rendered = new StringBuilder();
            int cursor = 0;
            for (InlineInjection inline : injections) {
                rendered.append(txt, cursor, inline.start());
                JsonNode repNode = null;
                if (inline.result().valid()) {
                    repNode = handleInjection(
                            inline.result().injection(),
                            context,
                            mapper,
                            true,
                            lookups,
                            statementPath);
                } else if (inline.result().recognized()) {
                    repNode = NullNode.instance;
                }
                if (repNode == null) {
                    rendered.append(inline.source());
                } else {
                    String replacementText = repNode.isValueNode()
                            ? repNode.asText()
                            : mapper.writeValueAsString(repNode);
                    //if this is a validation run, just short circuit the embedded injection
                    // and return an appropriate example datatype. We cannot validate the
                    // correctness of composite strings
                    if (context instanceof TestInjectionContext) {
                        return TextNode.valueOf(replacementText);
                    }
                    rendered.append(replacementText);
                }
                cursor = inline.end();
            }
            rendered.append(txt, cursor, txt.length());
            return TextNode.valueOf(rendered.toString());
        }

        return node;
    }

    private JsonNode handleInjection(
            StatementInjection injection,
            InjectionContext context,
            ObjectMapper mapper,
            Boolean embedded,
            LazyLookupContext lookups,
            List<Object> statementPath) {
        List<Object> previousPath = context.getStatementPath();
        context.setStatementPath(statementPath);
        context.setEmbedded(embedded);
        try {
            if (injection instanceof TriggerInjection triggerInjection) {
                return renderResolution(
                        injectionHandler.handleTrigger(triggerInjection.target(), context),
                        triggerInjection.options(),
                        injectionDescription(triggerInjection, null),
                        embedded,
                        mapper);
            } else if (injection instanceof PreviousInjection previousInjection) {
                return renderResolution(
                        injectionHandler.handlePrevious(previousInjection.target(), context),
                        previousInjection.options(),
                        injectionDescription(previousInjection, null),
                        embedded,
                        mapper);
            } else if (injection instanceof QueryInjection queryInjection) {
                return renderResolution(
                        injectionHandler.handleQuery(
                                queryInjection.className(),
                                queryInjection.target(),
                                queryInjection.criteria(),
                                context),
                        queryInjection.options(),
                        injectionDescription(queryInjection, queryInjection.className()),
                        embedded,
                        mapper);
            } else if (injection instanceof LookupInjection lookupInjection) {
                return renderResolution(
                        lookups.value(lookupInjection.alias(), lookupInjection.target()),
                        lookupInjection.options(),
                        injectionDescription(lookupInjection, lookupInjection.alias()),
                        embedded,
                        mapper);
            }
        } catch (Exception e) {
            logger.error("Error handling statement injection", e);
            throw e;
        } finally {
            context.setStatementPath(previousPath);
        }
        return NullNode.instance;
    }

    private List<Object> appendPath(List<Object> statementPath, Object part) {
        java.util.ArrayList<Object> childPath = new java.util.ArrayList<>(statementPath);
        childPath.add(part);
        return List.copyOf(childPath);
    }

    /**
     * Embedded injections are always rendered as text within the containing string.
     * Whole-node injections preserve the replacement's JSON shape.
     *
     * @param replacement
     * @param embedded
     * @param mapper
     * @return actual string to put in result
     */
    private JsonNode render(Object replacement, Boolean embedded, ObjectMapper mapper) {
        if (Boolean.TRUE.equals(embedded)) {
            return TextNode.valueOf(replacement.toString());
        }
        return mapper.valueToTree(replacement);
    }

    private JsonNode renderResolution(
            ValueResolution resolution,
            InjectionOptions options,
            String description,
            Boolean embedded,
            ObjectMapper mapper) {
        if (!resolution.present()) {
            if (!options.required()) {
                return NullNode.instance;
            }
            throw new RequiredInjectionException(description + " failed: " + resolution.status());
        }
        Object replacement = resolution.value();
        if (replacement == null) {
            if (options.required() && !options.nullable()) {
                throw new RequiredInjectionException(description + " failed: unexpected null value");
            }
            return NullNode.instance;
        }
        return render(replacement, embedded, mapper);
    }

    private String injectionDescription(StatementInjection injection, String scope) {
        StringBuilder description = new StringBuilder(injection.type().toString());
        if (scope != null && !scope.isBlank()) {
            description.append("(").append(scope).append(")");
        }
        description.append(" target ");
        Target target = injection.target();
        description.append(target == null ? "<null>" : target.parts);
        return description.toString();
    }

    private static class RequiredInjectionException extends RuntimeException {
        RequiredInjectionException(String message) {
            super(message);
        }
    }

}
