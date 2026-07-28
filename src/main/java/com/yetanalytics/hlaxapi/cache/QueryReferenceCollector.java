package com.yetanalytics.hlaxapi.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.ExpressionWalker;
import com.yetanalytics.hlaxapi.config.model.LogicalExpression;
import com.yetanalytics.hlaxapi.config.model.LookupExpression;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.InlineInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.LookupInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.ParseResult;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.PreviousInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.QueryInjection;
import com.yetanalytics.hlaxapi.injection.StatementInjectionParser.StatementInjection;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QueryReferenceCollector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record ReferenceState(
            Map<String, Set<String>> references,
            Map<String, String> lookupClasses,
            String activeCacheClass,
            String previousClass) {
    }

    private static final ExpressionWalker.Visitor<ReferenceState> REFERENCE_VISITOR =
            new ExpressionWalker.Visitor<>() {
                @Override
                public void visit(Expression expression, ReferenceState state) {
                    switch (expression) {
                        case Criterion ignored -> {
                        }
                        case LogicalExpression ignored -> {
                        }
                        case LookupExpression lookup -> addTarget(
                                state.references,
                                state.lookupClasses.get(lookup.alias),
                                lookup.target);
                        case PreviousExpression previous ->
                            addTarget(state.references, state.previousClass, previous.target);
                        case QueryExpression query -> addTarget(state.references, query.clazz, query.target);
                        case Target target -> addTarget(state.references, state.activeCacheClass, target);
                        case TriggerExpression ignored -> {
                        }
                        case ValueExpression ignored -> {
                        }
                    }
                }

                @Override
                public ReferenceState stateForChild(
                        Expression parent,
                        ExpressionWalker.Child child,
                        ReferenceState state) {
                    String activeCacheClass = switch (child.role()) {
                        case QUERY_FILTER -> ((QueryExpression) parent).clazz;
                        case LEFT, RIGHT, OPERAND -> state.activeCacheClass;
                    };
                    return new ReferenceState(
                            state.references,
                            state.lookupClasses,
                            activeCacheClass,
                            state.previousClass);
                }
            };

    private QueryReferenceCollector() {
    }

    public static Map<String, Set<String>> collect(List<StatementTrigger> triggers) {
        Map<String, Set<String>> references = new LinkedHashMap<>();
        if (triggers == null) {
            return references;
        }
        for (StatementTrigger trigger : triggers) {
            if (trigger == null) {
                continue;
            }
            Map<String, String> lookupClasses = collectLookupDefinitions(trigger, references);
            String previousClass = trigger.type == StatementTrigger.Type.OBJECT_UPDATE
                    ? trigger.clazz
                    : null;
            collectExpressionReferences(
                    trigger.criteria,
                    references,
                    lookupClasses,
                    null,
                    previousClass);
            if (trigger.statement == null) {
                continue;
            }
            try {
                collectFromNode(
                        MAPPER.readTree(trigger.statement),
                        references,
                        lookupClasses,
                        previousClass);
            } catch (IOException ignored) {
                // Bad statement JSON is handled by TriggerProcessor at runtime.
            }
        }
        return copyReferences(references);
    }

    private static Map<String, String> collectLookupDefinitions(
            StatementTrigger trigger,
            Map<String, Set<String>> references) {
        Map<String, String> lookupClasses = new LinkedHashMap<>();
        if (trigger.lookups == null) {
            return lookupClasses;
        }
        trigger.lookups.forEach((alias, lookup) -> {
            if (lookup == null || lookup.clazz == null || lookup.clazz.isBlank()) {
                return;
            }
            lookupClasses.put(alias, lookup.clazz);
            collectExpressionReferences(
                    lookup.criteria,
                    references,
                    lookupClasses,
                    lookup.clazz,
                    null);
        });
        return lookupClasses;
    }

    private static void collectExpressionReferences(
            Expression expression,
            Map<String, Set<String>> references,
            Map<String, String> lookupClasses,
            String activeCacheClass,
            String previousClass) {
        ExpressionWalker.walk(
                expression,
                new ReferenceState(
                        references,
                        lookupClasses,
                        activeCacheClass,
                        previousClass),
                REFERENCE_VISITOR);
    }

    private static void collectFromNode(
            JsonNode node,
            Map<String, Set<String>> references,
            Map<String, String> lookupClasses,
            String previousClass) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                collectFromNode(child, references, lookupClasses, previousClass);
            }
            return;
        }
        if (node.isArray()) {
            ParseResult parsed = StatementInjectionParser.parse(node);
            if (parsed.valid()) {
                collectInjection(
                        parsed.injection(),
                        references,
                        lookupClasses,
                        previousClass);
                return;
            }
            for (JsonNode child : node) {
                collectFromNode(child, references, lookupClasses, previousClass);
            }
            return;
        }
        if (node.isTextual()) {
            for (InlineInjection inline : StatementInjectionParser.findInline(node.asText())) {
                if (inline.result().valid()) {
                    collectInjection(
                            inline.result().injection(),
                            references,
                            lookupClasses,
                            previousClass);
                }
            }
        }
    }

    private static void collectInjection(
            StatementInjection injection,
            Map<String, Set<String>> references,
            Map<String, String> lookupClasses,
            String previousClass) {
        if (injection instanceof QueryInjection queryInjection) {
            collectQuery(queryInjection, references);
        } else if (injection instanceof LookupInjection lookupInjection) {
            collectLookup(lookupInjection, references, lookupClasses);
        } else if (injection instanceof PreviousInjection previousInjection) {
            addTarget(references, previousClass, previousInjection.target());
        }
    }

    private static void collectQuery(QueryInjection query, Map<String, Set<String>> references) {
        collectQueryReference(query.className(), query.target(), query.criteria(), references);
    }

    private static void collectQueryReference(
            String className,
            Target target,
            Expression criteria,
            Map<String, Set<String>> references) {
        if (className == null || className.isBlank()) {
            return;
        }

        addTarget(references, className, target);
        collectExpressionReferences(criteria, references, Map.of(), className, null);
    }

    private static void collectLookup(
            LookupInjection lookup,
            Map<String, Set<String>> references,
            Map<String, String> lookupClasses) {
        String className = lookupClasses.get(lookup.alias());
        if (className == null || className.isBlank()) {
            return;
        }

        addTarget(references, className, lookup.target());
    }

    private static void addTarget(Map<String, Set<String>> references, String className, Target target) {
        if (className == null || className.isBlank()) {
            return;
        }
        String topLevelAttribute = target == null ? null : FomCatalog.topLevelTargetPart(target.parts);
        if (topLevelAttribute == null || topLevelAttribute.isBlank()) {
            return;
        }
        references.computeIfAbsent(className, ignored -> new LinkedHashSet<>()).add(topLevelAttribute);
    }

    private static Map<String, Set<String>> copyReferences(Map<String, Set<String>> references) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        references.forEach((className, attributes) -> copy.put(className, Set.copyOf(attributes)));
        return Map.copyOf(copy);
    }
}
