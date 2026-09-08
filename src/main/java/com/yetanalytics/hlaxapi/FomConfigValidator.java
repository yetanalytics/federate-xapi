package com.yetanalytics.hlaxapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.ExpressionWalker;
import com.yetanalytics.hlaxapi.config.model.LookupExpression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.InlineInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.LookupInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.ParseResult;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.PreviousInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.QueryInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.StatementInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.TriggerInjection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Validates the FOM references contained in one statement trigger. */
@Component
public class FomConfigValidator {

    private final FOMXML fomXml;
    private final FomCatalog fomCatalog;
    private final ObjectMapper mapper = new ObjectMapper();

    public FomConfigValidator(FOMXML fomXml, FomCatalog fomCatalog) {
        this.fomXml = fomXml;
        this.fomCatalog = fomCatalog;
    }

    public void validate(StatementTrigger trigger) {
        Map<String, ObjectLookup> lookups =
                trigger.lookups == null ? Map.of() : trigger.lookups;
        ValidationSource eventSource = new ValidationSource(trigger, lookups, null);

        validateExpressionSources(trigger.criteria, eventSource);
        lookups.forEach((alias, lookup) -> {
            ObjectLookup definition = requireLookupClass(alias, lookup);
            validateExpressionSources(
                    definition.criteria,
                    new ValidationSource(trigger, lookups, definition.clazz));
        });

        if (trigger.statement == null) {
            return;
        }
        try {
            validateStatementNode(mapper.readTree(trigger.statement), eventSource, List.of());
        } catch (JsonProcessingException ignored) {
            // Statement parsing failures remain the responsibility of the
            // existing template-rendering validation pass.
        }
    }

    private void validateStatementNode(JsonNode node, ValidationSource source, List<Object> path) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                validateStatementNode(field.getValue(), source, append(path, field.getKey()));
            }
            return;
        }
        if (node.isArray()) {
            ParseResult parsed = StatementInjectionParser.parse(node);
            if (parsed.valid()) {
                validateInjectionAtPath(parsed.injection(), source, path);
                return;
            }
            if (parsed.recognized()) {
                return;
            }
            for (int index = 0; index < node.size(); index++) {
                validateStatementNode(node.get(index), source, append(path, index));
            }
            return;
        }
        if (node.isTextual()) {
            validateInlineInjections(node.asText(), source, path);
        }
    }

    private void validateInlineInjections(String text, ValidationSource source, List<Object> path) {
        for (InlineInjection inline : StatementInjectionParser.findInline(text)) {
            if (inline.result().valid()) {
                validateInjectionAtPath(inline.result().injection(), source, path);
                return;
            }
            if (inline.result().recognized()) {
                return;
            }
        }
    }

    private void validateInjectionAtPath(
            StatementInjection injection,
            ValidationSource source,
            List<Object> path) {
        try {
            validateInjection(injection, source);
        } catch (IllegalArgumentException e) {
            throw new FomReferenceException(pointer(path), e.getMessage(), e);
        }
    }

    private void validateInjection(StatementInjection injection, ValidationSource source) {
        if (injection instanceof TriggerInjection triggerInjection) {
            requireEventTargetDefinition(
                    source.trigger().clazz,
                    triggerInjection.target(),
                    source.trigger().type.isObjectEvent(),
                    "trigger");
        } else if (injection instanceof PreviousInjection previousInjection) {
            requirePreviousTarget(source.trigger(), previousInjection.target());
        } else if (injection instanceof QueryInjection queryInjection) {
            requireObjectTargetDefinition(
                    queryInjection.className(),
                    queryInjection.target(),
                    "query");
            validateExpressionSources(
                    queryInjection.criteria(),
                    new ValidationSource(source.trigger(), Map.of(), queryInjection.className()));
        } else if (injection instanceof LookupInjection lookupInjection) {
            ObjectLookup definition = requireLookupClass(
                    lookupInjection.alias(),
                    source.lookups().get(lookupInjection.alias()));
            requireObjectTargetDefinition(
                    definition.clazz,
                    lookupInjection.target(),
                    "lookup(" + lookupInjection.alias() + ")");
        }
    }

    private void validateExpressionSources(Expression expression, ValidationSource initialState) {
        ExpressionWalker.walk(
                expression,
                initialState,
                new ExpressionWalker.Visitor<>() {
                    @Override
                    public void visit(Expression candidate, ValidationSource state) {
                        if (candidate instanceof TriggerExpression trigger) {
                            StatementTrigger event = state.trigger();
                            requireEventTargetDefinition(
                                    event.clazz,
                                    trigger.target,
                                    event.type.isObjectEvent(),
                                    "trigger");
                        } else if (candidate instanceof PreviousExpression previous) {
                            requirePreviousTarget(state.trigger(), previous.target);
                        } else if (candidate instanceof QueryExpression query) {
                            requireObjectTargetDefinition(query.clazz, query.target, "query");
                        } else if (candidate instanceof LookupExpression lookup) {
                            ObjectLookup definition = requireLookupClass(
                                    lookup.alias,
                                    state.lookups().get(lookup.alias));
                            requireObjectTargetDefinition(
                                    definition.clazz,
                                    lookup.target,
                                    "lookup(" + lookup.alias + ")");
                        } else if (candidate instanceof Target target) {
                            if (state.cacheClass() == null) {
                                throw new IllegalArgumentException(
                                        "bare target " + target.parts
                                                + " is not scoped to a cache class");
                            }
                            requireObjectTargetDefinition(state.cacheClass(), target, "cache");
                        }
                    }

                    @Override
                    public ValidationSource stateForChild(
                            Expression parent,
                            ExpressionWalker.Child child,
                            ValidationSource state) {
                        String cacheClass = child.role() == ExpressionWalker.ChildRole.QUERY_FILTER
                                ? ((QueryExpression) parent).clazz
                                : state.cacheClass();
                        return new ValidationSource(
                                state.trigger(),
                                state.lookups(),
                                cacheClass);
                    }
                });
    }

    private void requirePreviousTarget(StatementTrigger trigger, Target target) {
        if (trigger.type != StatementTrigger.Type.OBJECT_UPDATE) {
            throw new IllegalArgumentException(
                    "previous values are only available to ObjectUpdate triggers");
        }
        requireObjectTargetDefinition(trigger.clazz, target, "previous");
    }

    private void requireEventTargetDefinition(
            String hlaClass,
            Target target,
            boolean objectEvent,
            String source) {
        boolean exists = objectEvent
                ? objectTargetExists(hlaClass, target)
                : interactionTargetExists(hlaClass, target);
        if (!exists) {
            throw missingTarget(source, hlaClass, target);
        }
    }

    private void requireObjectTargetDefinition(String hlaClass, Target target, String source) {
        if (!objectTargetExists(hlaClass, target)) {
            throw missingTarget(source, hlaClass, target);
        }
    }

    private boolean interactionTargetExists(String hlaClass, Target target) {
        return target != null
                && fomXml.checkInteractionParameterPath(hlaClass, target.parts).exists;
    }

    private boolean objectTargetExists(String hlaClass, Target target) {
        if (target == null) {
            return false;
        }
        return fomCatalog.objectClass(hlaClass)
                .filter(clazz -> clazz.attribute(FomCatalog.targetPath(target.parts)).isPresent())
                .filter(clazz -> clazz.attribute(FomCatalog.topLevelTargetPart(target.parts)).isPresent())
                .isPresent();
    }

    private IllegalArgumentException missingTarget(String source, String hlaClass, Target target) {
        return new IllegalArgumentException(
                source + " target "
                        + (target == null ? "<null>" : target.parts)
                        + " does not exist on FOM class "
                        + hlaClass);
    }

    private ObjectLookup requireLookupClass(String alias, ObjectLookup lookup) {
        if (lookup == null || lookup.clazz == null || lookup.clazz.isBlank()) {
            throw new IllegalArgumentException(
                    "lookup alias '" + alias + "' does not define an object class");
        }
        if (fomCatalog.objectClass(lookup.clazz).isEmpty()) {
            throw new IllegalArgumentException(
                    "lookup alias '" + alias + "' references unknown FOM class " + lookup.clazz);
        }
        return lookup;
    }

    private record ValidationSource(
            StatementTrigger trigger,
            Map<String, ObjectLookup> lookups,
            String cacheClass) {
    }

    private static List<Object> append(List<Object> path, Object part) {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>(path);
        result.add(part);
        return List.copyOf(result);
    }

    private static String pointer(List<Object> path) {
        StringBuilder pointer = new StringBuilder();
        for (Object part : path) {
            pointer.append('/').append(part.toString().replace("~", "~0").replace("/", "~1"));
        }
        return pointer.toString();
    }

    public static final class FomReferenceException extends IllegalArgumentException {

        private final String statementPointer;

        public FomReferenceException(String statementPointer, String message, Throwable cause) {
            super(message, cause);
            this.statementPointer = statementPointer;
        }

        public String statementPointer() {
            return statementPointer;
        }
    }
}
