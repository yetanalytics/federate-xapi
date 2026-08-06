package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.TriggerProcessor.TriggerProcessingResult;
import com.yetanalytics.hlaxapi.cache.CachedObject;
import com.yetanalytics.hlaxapi.cache.ValueResolution;
import com.yetanalytics.hlaxapi.config.model.ComparisonOperator;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.LogicalExpression;
import com.yetanalytics.hlaxapi.config.model.LogicalOperator;
import com.yetanalytics.hlaxapi.config.model.LookupExpression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import com.yetanalytics.hlaxapi.injection.InjectionContext;
import com.yetanalytics.hlaxapi.injection.InteractionInjectionContext;
import com.yetanalytics.hlaxapi.injection.TestInjectionContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TriggerProcessorCriteriaTest {

    @Test
    void absentCriteriaMatchesAndFalseCriteriaSkipsCleanly() {
        TriggerProcessingResult unconditional = new TriggerProcessor(new InjectionHandler())
                .processTrigger(trigger(null, "{}"), context());
        TriggerProcessingResult skipped = new TriggerProcessor(new InjectionHandler())
                .processTrigger(trigger(new ValueExpression(false), "{}"), context());

        assertTrue(unconditional.success());
        assertTrue(unconditional.matched());
        assertTrue(skipped.success());
        assertFalse(skipped.matched());
        assertNull(skipped.statement());
    }

    @Test
    void eventCriteriaShortCircuitBeforeUnusedLookup() {
        AtomicInteger lookupLoads = new AtomicInteger();
        InjectionHandler handler = new InjectionHandler() {
            @Override
            public ValueResolution handleTrigger(Target target, InjectionContext context) {
                return ValueResolution.present(4);
            }

            @Override
            public Optional<CachedObject> resolveLookup(ObjectLookup lookup, InjectionContext context) {
                lookupLoads.incrementAndGet();
                return Optional.empty();
            }
        };
        Expression criteria = new LogicalExpression(
                LogicalOperator.AND,
                List.of(
                        new Criterion(
                                new TriggerExpression(target("Score")),
                                ComparisonOperator.GT,
                                new ValueExpression(10)),
                        new Criterion(
                                new LookupExpression("subject", target("Score")),
                                ComparisonOperator.GT,
                                new ValueExpression(10))));
        StatementTrigger trigger = trigger(criteria, "{\"actor\":{\"name\":[\"lookup\",\"subject\",[\"Name\"]]}}");
        trigger.lookups = Map.of("subject", lookup("SimEntity.Rabbit"));

        TriggerProcessingResult result = new TriggerProcessor(handler).processTrigger(trigger, context());

        assertTrue(result.success());
        assertFalse(result.matched());
        assertEquals(0, lookupLoads.get());
    }

    @Test
    void oneLookupObjectIsSharedByCriteriaAndStatementRendering() {
        AtomicInteger lookupLoads = new AtomicInteger();
        CachedObject rabbit = new CachedObject(7, "handle-7", "rabbit-7", "SimEntity.Rabbit");
        InjectionHandler handler = new InjectionHandler() {
            @Override
            public Optional<CachedObject> resolveLookup(ObjectLookup lookup, InjectionContext context) {
                lookupLoads.incrementAndGet();
                return Optional.of(rabbit);
            }

            @Override
            public ValueResolution handleLookup(CachedObject object, Target target, InjectionContext context) {
                assertEquals(rabbit, object);
                return target.parts.equals(List.of("Hunger"))
                        ? ValueResolution.present(75)
                        : ValueResolution.present("rabbit-7");
            }
        };
        StatementTrigger trigger = trigger(
                new Criterion(
                        new LookupExpression("subject", target("Hunger")),
                        ComparisonOperator.GT,
                        new ValueExpression(50)),
                "{\"actor\":{\"name\":[\"lookup\",\"subject\",[\"EntityId\"]]}}");
        trigger.lookups = Map.of("subject", lookup("SimEntity.Rabbit"));

        TriggerProcessingResult result = new TriggerProcessor(handler).processTrigger(trigger, context());

        assertTrue(result.matched());
        assertEquals(1, lookupLoads.get());
        assertTrue(result.statement().contains("rabbit-7"));
    }

    @Test
    void missingLookupIsMemoizedAndMissingQueryComparesAsNull() {
        AtomicInteger lookupLoads = new AtomicInteger();
        InjectionHandler handler = new InjectionHandler() {
            @Override
            public Optional<CachedObject> resolveLookup(ObjectLookup lookup, InjectionContext context) {
                lookupLoads.incrementAndGet();
                return Optional.empty();
            }

            @Override
            public ValueResolution handleLookup(CachedObject object, Target target, InjectionContext context) {
                return ValueResolution.missingObject();
            }

            @Override
            public ValueResolution handleQuery(
                    String clazz,
                    Target target,
                    Expression criteria,
                    InjectionContext context) {
                return ValueResolution.missingObject();
            }
        };
        StatementTrigger trigger = trigger(
                new LogicalExpression(
                        LogicalOperator.AND,
                        List.of(
                                equalsNull(new LookupExpression("subject", target("First"))),
                                equalsNull(new LookupExpression("subject", target("Second"))),
                                equalsNull(new QueryExpression("SimEntity.Rabbit", target("Nickname"), null)))),
                "{}");
        trigger.lookups = Map.of("subject", lookup("SimEntity.Rabbit"));

        TriggerProcessingResult result = new TriggerProcessor(handler).processTrigger(trigger, context());

        assertTrue(result.matched());
        assertEquals(1, lookupLoads.get());
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    void criteriaFailuresAreReportedAndValidationRenderingDoesNotEvaluateCriteria() {
        InjectionHandler handler = new InjectionHandler() {
            @Override
            public ValueResolution handleTrigger(Target target, InjectionContext context) {
                throw new IllegalStateException("cannot decode event value");
            }
        };
        StatementTrigger trigger = trigger(
                new TriggerExpression(target("Broken")),
                "{\"result\":{\"success\":true}}");
        TriggerProcessor processor = new TriggerProcessor(handler);

        TriggerProcessingResult failed = processor.processTrigger(trigger, context());
        TriggerProcessingResult validation = processor.renderTemplateForValidation(
                trigger,
                new TestInjectionContext("TestInteraction"));

        assertFalse(failed.success());
        assertNotNull(failed.error());
        assertTrue(validation.success());
        assertTrue(validation.matched());
    }

    private static Criterion equalsNull(Expression expression) {
        return new Criterion(expression, ComparisonOperator.EQ, new ValueExpression(null));
    }

    private static Target target(String name) {
        return new Target(List.of(name));
    }

    private static ObjectLookup lookup(String className) {
        ObjectLookup lookup = new ObjectLookup();
        lookup.clazz = className;
        return lookup;
    }

    private static StatementTrigger trigger(Expression criteria, String statement) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.INTERACTION;
        trigger.clazz = "TestInteraction";
        trigger.criteria = criteria;
        trigger.statement = statement;
        return trigger;
    }

    private static InteractionInjectionContext context() {
        return new InteractionInjectionContext("TestInteraction", Map.of());
    }
}
