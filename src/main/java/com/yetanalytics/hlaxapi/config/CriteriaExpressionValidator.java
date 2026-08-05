package com.yetanalytics.hlaxapi.config;

import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.ExpressionWalker;
import com.yetanalytics.hlaxapi.config.model.LogicalExpression;
import com.yetanalytics.hlaxapi.config.model.LookupExpression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import java.util.Map;

/** Enforces the value sources permitted by each criteria evaluation context. */
public final class CriteriaExpressionValidator {

    private enum Context {
        TRIGGER,
        CACHE_FILTER
    }

    private record ValidationState(
            Context context,
            Map<String, ObjectLookup> lookupDefinitions,
            StatementTrigger.Type triggerType,
            String location) {
    }

    private static final ExpressionWalker.Visitor<ValidationState> VALIDATION_VISITOR =
            new ExpressionWalker.Visitor<>() {
                @Override
                public void visit(Expression expression, ValidationState state) {
                    switch (expression) {
                        case Criterion ignored -> {
                        }
                        case LogicalExpression ignored -> {
                        }
                        case LookupExpression lookup -> validateLookup(lookup, state);
                        case PreviousExpression previous -> validatePrevious(previous, state);
                        case QueryExpression query -> validateQuery(query, state);
                        case Target target -> validateTarget(target, state);
                        case TriggerExpression ignored -> {
                        }
                        case ValueExpression ignored -> {
                        }
                    }
                }

                @Override
                public ValidationState stateForChild(
                        Expression parent,
                        ExpressionWalker.Child child,
                        ValidationState state) {
                    Context childContext = child.role() == ExpressionWalker.ChildRole.QUERY_FILTER
                            ? Context.CACHE_FILTER
                            : state.context;
                    String childLocation = switch (child.role()) {
                        case LEFT -> state.location + ".left";
                        case RIGHT -> state.location + ".right";
                        case OPERAND -> state.location + "[" + child.index() + "]";
                        case QUERY_FILTER -> state.location + ".queryFilter";
                    };
                    return new ValidationState(
                            childContext,
                            state.lookupDefinitions,
                            state.triggerType,
                            childLocation);
                }
            };

    private CriteriaExpressionValidator() {
    }

    public static void validateTrigger(Expression criteria, Map<String, ObjectLookup> lookupDefinitions) {
        validateTrigger(criteria, lookupDefinitions, StatementTrigger.Type.INTERACTION);
    }

    public static void validateTrigger(
            Expression criteria,
            Map<String, ObjectLookup> lookupDefinitions,
            StatementTrigger.Type triggerType) {
        Map<String, ObjectLookup> definitions = lookupDefinitions == null ? Map.of() : lookupDefinitions;
        ExpressionWalker.walk(
                criteria,
                new ValidationState(Context.TRIGGER, definitions, triggerType, "criteria"),
                VALIDATION_VISITOR);
    }

    public static void validateCacheFilter(Expression criteria) {
        ExpressionWalker.walk(
                criteria,
                new ValidationState(Context.CACHE_FILTER, Map.of(), null, "criteria"),
                VALIDATION_VISITOR);
    }

    private static void validateTarget(Target target, ValidationState state) {
        if (state.context == Context.TRIGGER) {
            throw new IllegalArgumentException(
                    state.location + " contains bare target " + target.parts
                            + "; use [\"trigger\", [...]] for incoming event values");
        }
    }

    private static void validateQuery(QueryExpression query, ValidationState state) {
        if (state.context != Context.TRIGGER) {
            throw unsupported(query, state);
        }
    }

    private static void validatePrevious(PreviousExpression previous, ValidationState state) {
        if (state.context != Context.TRIGGER
                || state.triggerType != StatementTrigger.Type.OBJECT_UPDATE) {
            throw unsupported(previous, state);
        }
    }

    private static void validateLookup(LookupExpression lookup, ValidationState state) {
        if (state.context != Context.TRIGGER) {
            throw unsupported(lookup, state);
        }

        ObjectLookup definition = state.lookupDefinitions.get(lookup.alias);
        if (definition == null) {
            throw new IllegalArgumentException(
                    state.location + " references unknown lookup alias '" + lookup.alias + "'");
        }
        if (definition.clazz == null || definition.clazz.isBlank()) {
            throw new IllegalArgumentException(
                    state.location + " references lookup alias '" + lookup.alias + "' without a class");
        }
    }

    private static IllegalArgumentException unsupported(Expression expression, ValidationState state) {
        return new IllegalArgumentException(
                state.location + " contains unsupported " + expression.getClass().getSimpleName());
    }
}
