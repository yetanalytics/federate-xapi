package com.yetanalytics.hlaxapi.injection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yetanalytics.hlaxapi.config.CriteriaExpressionParser;
import com.yetanalytics.hlaxapi.config.CriteriaExpressionValidator;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.InjectionType;
import com.yetanalytics.hlaxapi.config.model.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical parser for whole-node and inline statement injection syntax.
 */
public final class StatementInjectionParser {

    private static final Pattern INLINE_PLACEHOLDER = Pattern.compile("<<(.+?)>>", Pattern.DOTALL);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StatementInjectionParser() {
    }

    /**
     * Inspect a JSON node and, when it starts with a known injection token, parse
     * it into a typed injection.
     */
    public static ParseResult parse(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || !node.get(0).isTextual()) {
            return ParseResult.notInjection();
        }

        InjectionType type = InjectionType.fromString(node.get(0).asText());
        if (type == null) {
            return ParseResult.notInjection();
        }

        try {
            return switch (type) {
                case TRIGGER -> node.size() < 2
                        ? ParseResult.malformed(type)
                        : ParseResult.valid(new TriggerInjection(
                                CriteriaExpressionParser.parseTarget(node.get(1)),
                                options(node, 2)));
                case PREVIOUS -> node.size() < 2
                        ? ParseResult.malformed(type)
                        : ParseResult.valid(new PreviousInjection(
                                CriteriaExpressionParser.parseTarget(node.get(1)),
                                options(node, 2)));
                case QUERY -> node.size() < 4
                        ? ParseResult.malformed(type)
                        : ParseResult.valid(new QueryInjection(
                                node.get(1).asText(),
                                CriteriaExpressionParser.parseTarget(node.get(2)),
                                expression(node.get(3)),
                                options(node, 4)));
                case LOOKUP -> node.size() < 3
                        ? ParseResult.malformed(type)
                        : ParseResult.valid(new LookupInjection(
                                node.get(1).asText(),
                                CriteriaExpressionParser.parseTarget(node.get(2)),
                                options(node, 3)));
            };
        } catch (RuntimeException e) {
            return ParseResult.malformed(type);
        }
    }

    /**
     * Find all inline {@code <<...>>} placeholders in encounter order. Invalid
     * JSON and non-injection placeholders are returned as non-injections so a
     * rendering caller can preserve their original text.
     */
    public static List<InlineInjection> findInline(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<InlineInjection> injections = new ArrayList<>();
        Matcher matcher = INLINE_PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            ParseResult result;
            try {
                result = parse(MAPPER.readTree(matcher.group(1)));
            } catch (Exception e) {
                result = ParseResult.notInjection();
            }
            injections.add(new InlineInjection(
                    matcher.start(),
                    matcher.end(),
                    matcher.group(0),
                    result));
        }
        return List.copyOf(injections);
    }

    private static Expression expression(JsonNode node) {
        Expression expression = CriteriaExpressionParser.parseNullable(node);
        CriteriaExpressionValidator.validateCacheFilter(expression);
        return expression;
    }

    private static InjectionOptions options(JsonNode node, int index) {
        if (node.size() <= index || !node.get(index).isObject()) {
            return InjectionOptions.DEFAULT;
        }
        JsonNode options = node.get(index);
        return new InjectionOptions(
                options.path(InjectionOptions.NULLABLE_KEY).asBoolean(false),
                options.path(InjectionOptions.REQUIRED_KEY).asBoolean(true));
    }

    public sealed interface StatementInjection
            permits TriggerInjection, PreviousInjection, QueryInjection, LookupInjection {

        InjectionType type();

        Target target();

        InjectionOptions options();
    }

    public record TriggerInjection(Target target, InjectionOptions options) implements StatementInjection {

        @Override
        public InjectionType type() {
            return InjectionType.TRIGGER;
        }
    }

    public record PreviousInjection(Target target, InjectionOptions options) implements StatementInjection {

        @Override
        public InjectionType type() {
            return InjectionType.PREVIOUS;
        }
    }

    public record QueryInjection(
            String className,
            Target target,
            Expression criteria,
            InjectionOptions options) implements StatementInjection {

        @Override
        public InjectionType type() {
            return InjectionType.QUERY;
        }
    }

    public record LookupInjection(
            String alias,
            Target target,
            InjectionOptions options) implements StatementInjection {

        @Override
        public InjectionType type() {
            return InjectionType.LOOKUP;
        }
    }

    public record InjectionOptions(boolean nullable, boolean required) {

        public static final String NULLABLE_KEY = "nullable";
        public static final String REQUIRED_KEY = "required";
        public static final InjectionOptions DEFAULT = new InjectionOptions(false, true);
    }

    public record ParseResult(
            boolean recognized,
            InjectionType type,
            StatementInjection injection) {

        public static ParseResult notInjection() {
            return new ParseResult(false, null, null);
        }

        public static ParseResult malformed(InjectionType type) {
            return new ParseResult(true, type, null);
        }

        public static ParseResult valid(StatementInjection injection) {
            return new ParseResult(true, injection.type(), injection);
        }

        public boolean valid() {
            return injection != null;
        }
    }

    public record InlineInjection(
            int start,
            int end,
            String source,
            ParseResult result) {
    }
}
