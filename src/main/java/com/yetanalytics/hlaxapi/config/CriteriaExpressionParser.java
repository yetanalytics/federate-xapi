package com.yetanalytics.hlaxapi.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yetanalytics.hlaxapi.config.model.ComparisonOperator;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.InjectionType;
import com.yetanalytics.hlaxapi.config.model.LogicalExpression;
import com.yetanalytics.hlaxapi.config.model.LogicalOperator;
import com.yetanalytics.hlaxapi.config.model.LookupExpression;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import java.util.ArrayList;
import java.util.List;

/** Parses criteria JSON into the expression model used by every criteria context. */
public final class CriteriaExpressionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CriteriaExpressionParser() {
    }

    public static Expression parse(JsonNode node) {
        if (node == null || node.isNull()) {
            return new ValueExpression(null);
        }
        if (!node.isArray()) {
            return new ValueExpression(MAPPER.convertValue(node, Object.class));
        }
        if (node.isEmpty()) {
            throw new IllegalArgumentException("expression arrays must not be empty");
        }

        InjectionType injectionType = injectionType(node);
        if (injectionType != null) {
            return parseInjection(node, injectionType);
        }

        if (isComparison(node)) {
            ComparisonOperator operator = ComparisonOperator.fromString(node.get(1).asText());
            return new Criterion(parse(node.get(0)), operator, parse(node.get(2)));
        }

        if (containsLogicalOperator(node)) {
            return parseLogical(node);
        }

        return parseTarget(node);
    }

    public static Expression parseNullable(JsonNode node) {
        return node == null || node.isNull() ? null : parse(node);
    }

    private static Expression parseInjection(JsonNode node, InjectionType type) {
        return switch (type) {
            case TRIGGER -> {
                requireArity(node, 2, type);
                yield new TriggerExpression(parseTarget(node.get(1)));
            }
            case PREVIOUS -> {
                requireArity(node, 2, type);
                yield new PreviousExpression(parseTarget(node.get(1)));
            }
            case QUERY -> {
                requireArity(node, 4, type);
                String className = requireText(node.get(1), "query class");
                Target target = parseTarget(node.get(2));
                Expression criteria = parseNullable(node.get(3));
                yield new QueryExpression(className, target, criteria);
            }
            case LOOKUP -> {
                requireArity(node, 3, type);
                String alias = requireText(node.get(1), "lookup alias");
                yield new LookupExpression(alias, parseTarget(node.get(2)));
            }
        };
    }

    private static LogicalExpression parseLogical(JsonNode node) {
        if (node.size() < 3 || node.size() % 2 == 0) {
            throw new IllegalArgumentException(
                    "logical expressions must alternate operands and operators");
        }
        LogicalOperator operator = null;
        List<Expression> operands = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            if (index % 2 == 0) {
                operands.add(parse(node.get(index)));
                continue;
            }
            JsonNode operatorNode = node.get(index);
            LogicalOperator next = operatorNode.isTextual()
                    ? LogicalOperator.fromString(operatorNode.asText())
                    : null;
            if (next == null) {
                throw new IllegalArgumentException("invalid logical operator at index " + index);
            }
            if (operator != null && operator != next) {
                throw new IllegalArgumentException(
                        "mixed logical operators require an explicitly nested expression");
            }
            operator = next;
        }
        return new LogicalExpression(operator, List.copyOf(operands));
    }

    /**
     * Parse a target path represented by a non-empty JSON array. Path parts must
     * be strings or non-negative integer indexes.
     */
    public static Target parseTarget(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("target must be a non-empty array");
        }
        List<Object> parts = new ArrayList<>();
        for (JsonNode part : node) {
            if (part.isTextual()) {
                parts.add(part.asText());
            } else if (part.isIntegralNumber() && part.canConvertToInt()) {
                int index = part.asInt();
                if (index < 0) {
                    throw new IllegalArgumentException("target indexes must be non-negative");
                }
                parts.add(index);
            } else {
                throw new IllegalArgumentException("target parts must be strings or integer indexes");
            }
        }
        return new Target(List.copyOf(parts));
    }

    private static boolean isComparison(JsonNode node) {
        return node.size() == 3
                && node.get(1).isTextual()
                && ComparisonOperator.fromString(node.get(1).asText()) != null;
    }

    private static boolean containsLogicalOperator(JsonNode node) {
        for (int index = 1; index < node.size(); index += 2) {
            if (node.get(index).isTextual()
                    && LogicalOperator.fromString(node.get(index).asText()) != null) {
                return true;
            }
        }
        return false;
    }

    private static InjectionType injectionType(JsonNode node) {
        return node.get(0).isTextual()
                ? InjectionType.fromString(node.get(0).asText())
                : null;
    }

    private static void requireArity(JsonNode node, int expected, InjectionType type) {
        if (node.size() != expected) {
            throw new IllegalArgumentException(
                    type + " criteria expression requires exactly " + expected + " elements");
        }
    }

    private static String requireText(JsonNode node, String description) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(description + " must be a non-empty string");
        }
        return node.asText();
    }
}
