package com.yetanalytics.hlaxapi.config.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Traverses and rewrites the evaluated children of an expression tree. */
public final class ExpressionWalker {

    /** Identifies how a child expression is related to its parent. */
    public enum ChildRole {
        LEFT,
        RIGHT,
        OPERAND,
        QUERY_FILTER
    }

    /** A child expression and its position within the parent. */
    public record Child(Expression expression, ChildRole role, int index) {

        public Child {
            Objects.requireNonNull(role, "role");
            if (role == ChildRole.OPERAND && index < 0) {
                throw new IllegalArgumentException("operand children require a non-negative index");
            }
            if (role != ChildRole.OPERAND && index != -1) {
                throw new IllegalArgumentException("only operand children have an index");
            }
        }
    }

    /** Receives each expression and optionally derives state for each child edge. */
    @FunctionalInterface
    public interface Visitor<S> {

        void visit(Expression expression, S state);

        default S stateForChild(Expression parent, Child child, S state) {
            return state;
        }
    }

    private ExpressionWalker() {
    }

    /** Walks an expression tree in pre-order. A null root produces no visits. */
    public static void walk(Expression root, Consumer<? super Expression> visitor) {
        Objects.requireNonNull(visitor, "visitor");
        walk(root, null, (expression, ignored) -> visitor.accept(expression));
    }

    /** Walks an expression tree in pre-order while propagating caller-defined state. */
    public static <S> void walk(Expression root, S initialState, Visitor<S> visitor) {
        Objects.requireNonNull(visitor, "visitor");
        walkInternal(root, initialState, visitor);
    }

    /**
     * Rewrites an expression tree bottom-up without mutating the source tree.
     * Unchanged branches retain their original object identity.
     */
    public static Expression rewrite(Expression root, UnaryOperator<Expression> rewriter) {
        Objects.requireNonNull(rewriter, "rewriter");
        return rewriteInternal(root, rewriter);
    }

    private static <S> void walkInternal(Expression expression, S state, Visitor<S> visitor) {
        if (expression == null) {
            return;
        }

        visitor.visit(expression, state);
        for (Child child : children(expression)) {
            if (child.expression() == null) {
                continue;
            }
            S childState = visitor.stateForChild(expression, child, state);
            walkInternal(child.expression(), childState, visitor);
        }
    }

    private static Expression rewriteInternal(Expression expression, UnaryOperator<Expression> rewriter) {
        if (expression == null) {
            return null;
        }

        List<Child> children = children(expression);
        List<Expression> rewrittenChildren = new ArrayList<>(children.size());
        boolean changed = false;
        for (Child child : children) {
            Expression rewrittenChild = rewriteInternal(child.expression(), rewriter);
            rewrittenChildren.add(rewrittenChild);
            changed |= rewrittenChild != child.expression();
        }

        Expression rebuilt = changed ? rebuild(expression, rewrittenChildren) : expression;
        return Objects.requireNonNull(rewriter.apply(rebuilt), "rewriter returned null");
    }

    private static List<Child> children(Expression expression) {
        return switch (expression) {
            case Criterion criterion -> List.of(
                    new Child(criterion.left, ChildRole.LEFT, -1),
                    new Child(criterion.right, ChildRole.RIGHT, -1));
            case LogicalExpression logical -> {
                List<Child> children = new ArrayList<>(logical.operands.size());
                for (int index = 0; index < logical.operands.size(); index++) {
                    children.add(new Child(logical.operands.get(index), ChildRole.OPERAND, index));
                }
                yield children;
            }
            case QueryExpression query -> List.of(new Child(query.criteria, ChildRole.QUERY_FILTER, -1));
            case LookupExpression ignored -> List.of();
            case PreviousExpression ignored -> List.of();
            case Target ignored -> List.of();
            case TriggerExpression ignored -> List.of();
            case ValueExpression ignored -> List.of();
        };
    }

    private static Expression rebuild(Expression expression, List<Expression> children) {
        return switch (expression) {
            case Criterion criterion -> new Criterion(children.get(0), criterion.operator, children.get(1));
            case LogicalExpression logical -> new LogicalExpression(logical.operator, List.copyOf(children));
            case QueryExpression query -> new QueryExpression(query.clazz, query.target, children.get(0));
            case LookupExpression lookup -> lookup;
            case PreviousExpression previous -> previous;
            case Target target -> target;
            case TriggerExpression trigger -> trigger;
            case ValueExpression value -> value;
        };
    }
}
