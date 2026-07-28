package com.yetanalytics.hlaxapi.config.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ExpressionWalkerTest {

    @Test
    void walksEveryEvaluatedExpressionChildInPreOrder() {
        Fixture fixture = fixture();
        List<Expression> visited = new ArrayList<>();

        ExpressionWalker.walk(fixture.root, visited::add);

        assertEquals(
                List.of(
                        fixture.root,
                        fixture.outerCriterion,
                        fixture.trigger,
                        fixture.query,
                        fixture.filterCriterion,
                        fixture.filterTarget,
                        fixture.filterValue,
                        fixture.lookup),
                visited);
        assertFalse(visited.contains(fixture.triggerTarget));
        assertFalse(visited.contains(fixture.queryTarget));
        assertFalse(visited.contains(fixture.lookupTarget));
    }

    @Test
    void propagatesCallerStateByChildRole() {
        Fixture fixture = fixture();
        Map<Expression, String> paths = new IdentityHashMap<>();

        ExpressionWalker.walk(fixture.root, "criteria", new ExpressionWalker.Visitor<>() {
            @Override
            public void visit(Expression expression, String path) {
                paths.put(expression, path);
            }

            @Override
            public String stateForChild(Expression parent, ExpressionWalker.Child child, String path) {
                return switch (child.role()) {
                    case LEFT -> path + ".left";
                    case RIGHT -> path + ".right";
                    case OPERAND -> path + "[" + child.index() + "]";
                    case QUERY_FILTER -> path + ".queryFilter";
                };
            }
        });

        assertEquals("criteria", paths.get(fixture.root));
        assertEquals("criteria[0]", paths.get(fixture.outerCriterion));
        assertEquals("criteria[0].left", paths.get(fixture.trigger));
        assertEquals("criteria[0].right", paths.get(fixture.query));
        assertEquals("criteria[0].right.queryFilter", paths.get(fixture.filterCriterion));
        assertEquals("criteria[0].right.queryFilter.left", paths.get(fixture.filterTarget));
        assertEquals("criteria[0].right.queryFilter.right", paths.get(fixture.filterValue));
        assertEquals("criteria[1]", paths.get(fixture.lookup));
    }

    @Test
    void rewritesBottomUpWithoutMutatingTheSourceTree() {
        Target firstTriggerTarget = target("FirstId");
        TriggerExpression firstTrigger = new TriggerExpression(firstTriggerTarget);
        ValueExpression originalRight = new ValueExpression(10);
        Criterion outerCriterion = new Criterion(firstTrigger, ComparisonOperator.EQ, originalRight);

        Target queryTarget = target("Result");
        Target filterTarget = target("EntityId");
        TriggerExpression filterTrigger = new TriggerExpression(target("DesiredId"));
        Criterion queryFilter = new Criterion(filterTarget, ComparisonOperator.EQ, filterTrigger);
        QueryExpression query = new QueryExpression("Entity", queryTarget, queryFilter);
        LogicalExpression root = new LogicalExpression(LogicalOperator.AND, List.of(outerCriterion, query));

        Expression rewritten = ExpressionWalker.rewrite(root, expression -> {
            if (expression instanceof TriggerExpression trigger) {
                return new ValueExpression("resolved:" + trigger.target.parts.get(0));
            }
            return expression;
        });

        LogicalExpression rewrittenRoot = assertInstanceOf(LogicalExpression.class, rewritten);
        Criterion rewrittenOuter = assertInstanceOf(Criterion.class, rewrittenRoot.operands.get(0));
        ValueExpression rewrittenFirst = assertInstanceOf(ValueExpression.class, rewrittenOuter.left);
        QueryExpression rewrittenQuery = assertInstanceOf(QueryExpression.class, rewrittenRoot.operands.get(1));
        Criterion rewrittenFilter = assertInstanceOf(Criterion.class, rewrittenQuery.criteria);
        ValueExpression rewrittenFilterValue = assertInstanceOf(ValueExpression.class, rewrittenFilter.right);

        assertEquals("resolved:FirstId", rewrittenFirst.value);
        assertEquals("resolved:DesiredId", rewrittenFilterValue.value);
        assertSame(originalRight, rewrittenOuter.right);
        assertSame(queryTarget, rewrittenQuery.target);
        assertSame(filterTarget, rewrittenFilter.left);

        assertSame(firstTrigger, outerCriterion.left);
        assertSame(filterTrigger, queryFilter.right);
        assertSame(root, ExpressionWalker.rewrite(root, UnaryOperator.identity()));
    }

    @Test
    void handlesNullRootsAndRejectsNullRewriteResults() {
        AtomicInteger visits = new AtomicInteger();

        ExpressionWalker.walk(null, ignored -> visits.incrementAndGet());

        assertEquals(0, visits.get());
        assertNull(ExpressionWalker.rewrite(null, UnaryOperator.identity()));
        assertThrows(
                NullPointerException.class,
                () -> ExpressionWalker.rewrite(new ValueExpression(true), ignored -> null));
    }

    @Test
    void treatsPreviousAsAValueSourceWithoutWalkingItsTarget() {
        Target target = target("Hunger");
        PreviousExpression previous = new PreviousExpression(target);
        List<Expression> visited = new ArrayList<>();

        ExpressionWalker.walk(previous, visited::add);

        assertEquals(List.of(previous), visited);
        assertFalse(visited.contains(target));
        assertSame(previous, ExpressionWalker.rewrite(previous, UnaryOperator.identity()));
    }

    private static Fixture fixture() {
        Target triggerTarget = target("Score");
        TriggerExpression trigger = new TriggerExpression(triggerTarget);

        Target queryTarget = target("Size");
        Target filterTarget = target("WorldId");
        ValueExpression filterValue = new ValueExpression(7);
        Criterion filterCriterion = new Criterion(filterTarget, ComparisonOperator.EQ, filterValue);
        QueryExpression query = new QueryExpression("World", queryTarget, filterCriterion);

        Criterion outerCriterion = new Criterion(trigger, ComparisonOperator.GTE, query);
        Target lookupTarget = target("MinimumSize");
        LookupExpression lookup = new LookupExpression("subject", lookupTarget);
        LogicalExpression root = new LogicalExpression(LogicalOperator.AND, List.of(outerCriterion, lookup));

        return new Fixture(
                root,
                outerCriterion,
                trigger,
                triggerTarget,
                query,
                queryTarget,
                filterCriterion,
                filterTarget,
                filterValue,
                lookup,
                lookupTarget);
    }

    private static Target target(String part) {
        return new Target(List.of(part));
    }

    private record Fixture(
            LogicalExpression root,
            Criterion outerCriterion,
            TriggerExpression trigger,
            Target triggerTarget,
            QueryExpression query,
            Target queryTarget,
            Criterion filterCriterion,
            Target filterTarget,
            ValueExpression filterValue,
            LookupExpression lookup,
            Target lookupTarget) {
    }
}
