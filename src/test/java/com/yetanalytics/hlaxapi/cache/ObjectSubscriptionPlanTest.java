package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import com.yetanalytics.hlaxapi.SimulationConfig;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.ComparisonOperator;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.ObjectCacheConfig;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TrackedObject;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class ObjectSubscriptionPlanTest {

    private final FOMXML fomXml = new FOMXML(
            new SimulationConfig(null, null, null, null, "config/HlaFedereplFOM.xml"),
            new HLADecoderRegistry(new HLA1516eEncoderFactory()));
    private final FomCatalog catalog = new FomCatalog(fomXml);

    @Test
    void combinesDirectAndAncestorRequirementsWithoutMutatingThePlan() {
        StatementTrigger ancestorQuery = new StatementTrigger();
        ancestorQuery.statement = """
                {"name":["query","SimEntity",["FirstName"],null]}
                """;
        TrackedObject trackedRabbit = new TrackedObject();
        trackedRabbit.clazz = "SimEntity.Rabbit";
        trackedRabbit.attributes = List.of("Hunger");
        ObjectCacheConfig cacheConfig = new ObjectCacheConfig();
        cacheConfig.trackedObjects = List.of(trackedRabbit);
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(ancestorQuery);
        config.objectCacheConfig = cacheConfig;

        ObjectSubscriptionPlan plan = ObjectSubscriptionPlan.from(config, catalog);

        assertEquals(Set.of("FirstName"), plan.cacheSubscriptions().get("SimEntity"));
        assertEquals(Set.of("FirstName"), plan.cacheSubscriptions().get("SimEntity.Carrot"));
        assertEquals(Set.of("FirstName", "Hunger"), plan.cacheSubscriptions().get("SimEntity.Rabbit"));
        assertEquals(Set.of("FirstName"), plan.cacheSubscriptions().get("SimEntity.Wolf"));
        assertTrue(plan.eventSubscriptions().isEmpty());
        assertEquals(Set.of("FirstName"), plan.effectiveAttributes("SimEntity"));
        assertEquals(Set.of("FirstName", "Hunger"), plan.effectiveAttributes("SimEntity.Rabbit"));
        assertEquals(Set.of("FirstName"), plan.effectiveAttributes("SimEntity.Wolf"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.subscriptions().put("SimEntity.Wolf", Set.of("Hunger")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.subscriptions().get("SimEntity.Rabbit").add("EntityId"));
    }

    @Test
    void lifecycleBaseClassHasNoCacheSpecificRequirements() {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(
                objectTrigger(StatementTrigger.Type.OBJECT_CREATE, "SimEntity"),
                objectTrigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity"),
                objectTrigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity"));

        ObjectSubscriptionPlan plan = ObjectSubscriptionPlan.from(config, catalog);

        assertTrue(plan.cacheSubscriptions().isEmpty());
        assertCompleteHierarchy(plan.eventSubscriptions());
        assertCompleteHierarchy(plan.subscriptions());
    }

    @Test
    void deleteBaseClassCachesCompleteStateForEveryDescendant() {
        XapiConfig config = new XapiConfig();
        config.statementTriggers =
                List.of(objectTrigger(StatementTrigger.Type.OBJECT_DELETE, "SimEntity"));

        ObjectSubscriptionPlan plan = ObjectSubscriptionPlan.from(config, catalog);

        assertCompleteHierarchy(plan.cacheSubscriptions());
        assertCompleteHierarchy(plan.eventSubscriptions());
        assertCompleteHierarchy(plan.subscriptions());
    }

    @Test
    void previousAndLookupReferencesExpandOnlyTheirAttributesAcrossDescendants() {
        StatementTrigger trigger =
                objectTrigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity");
        trigger.statement = """
                {
                  "oldPosition":["previous",["Position"]],
                  "firstName":["lookup","entity",["FirstName"]]
                }
                """;
        ObjectLookup lookup = new ObjectLookup();
        lookup.clazz = "SimEntity";
        lookup.criteria = new Criterion(
                new Target(List.of("EntityId")),
                ComparisonOperator.EQ,
                new ValueExpression("entity-one"));
        trigger.lookups = Map.of("entity", lookup);
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(trigger);

        ObjectSubscriptionPlan plan = ObjectSubscriptionPlan.from(config, catalog);

        for (FomCatalog.ObjectClassDef clazz :
                catalog.objectClassAndDescendants("SimEntity")) {
            assertEquals(
                    Set.of("EntityId", "FirstName", "Position"),
                    plan.cacheSubscriptions().get(clazz.hlaName()));
        }
        assertCompleteHierarchy(plan.eventSubscriptions());
    }

    @Test
    void trackedBaseClassExpandsExplicitAndAllAttributeRequirements() {
        TrackedObject explicit = new TrackedObject();
        explicit.clazz = "SimEntity";
        explicit.attributes = List.of("EntityId", "Position");
        ObjectCacheConfig explicitCache = new ObjectCacheConfig();
        explicitCache.trackedObjects = List.of(explicit);
        XapiConfig explicitConfig = new XapiConfig();
        explicitConfig.objectCacheConfig = explicitCache;

        ObjectSubscriptionPlan explicitPlan =
                ObjectSubscriptionPlan.from(explicitConfig, catalog);

        for (FomCatalog.ObjectClassDef clazz :
                catalog.objectClassAndDescendants("SimEntity")) {
            assertEquals(
                    Set.of("EntityId", "Position"),
                    explicitPlan.cacheSubscriptions().get(clazz.hlaName()));
        }

        TrackedObject all = new TrackedObject();
        all.clazz = "SimEntity";
        all.allAttributes = true;
        ObjectCacheConfig allCache = new ObjectCacheConfig();
        allCache.trackedObjects = List.of(all);
        XapiConfig allConfig = new XapiConfig();
        allConfig.objectCacheConfig = allCache;

        ObjectSubscriptionPlan allPlan =
                ObjectSubscriptionPlan.from(allConfig, catalog);

        assertCompleteHierarchy(allPlan.cacheSubscriptions());
    }

    private StatementTrigger objectTrigger(
            StatementTrigger.Type type,
            String className) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = type;
        trigger.clazz = className;
        trigger.statement = "{}";
        return trigger;
    }

    private void assertCompleteHierarchy(Map<String, Set<String>> subscriptions) {
        for (FomCatalog.ObjectClassDef clazz :
                catalog.objectClassAndDescendants("SimEntity")) {
            assertEquals(
                    Set.copyOf(clazz.topLevelAttributeNames()),
                    subscriptions.get(clazz.hlaName()));
        }
    }
}
