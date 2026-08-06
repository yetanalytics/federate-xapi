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
            validateStatementNode(mapper.readTree(trigger.statement), eventSource);
        } catch (JsonProcessingException ignored) {
            // Statement parsing failures remain the responsibility of the
            // existing template-rendering validation pass.
        }
    }

    private void validateStatementNode(JsonNode node, ValidationSource source) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                validateStatementNode(child, source);
            }
            return;
        }
        if (node.isArray()) {
            ParseResult parsed = StatementInjectionParser.parse(node);
            if (parsed.valid()) {
                validateInjection(parsed.injection(), source);
                return;
            }
            if (parsed.recognized()) {
                return;
            }
            for (JsonNode child : node) {
                validateStatementNode(child, source);
            }
            return;
        }
        if (node.isTextual()) {
            validateInlineInjections(node.asText(), source);
        }
    }

    private void validateInlineInjections(String text, ValidationSource source) {
        for (InlineInjection inline : StatementInjectionParser.findInline(text)) {
            if (inline.result().valid()) {
                validateInjection(inline.result().injection(), source);
                return;
            }
            if (inline.result().recognized()) {
                return;
            }
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
}
