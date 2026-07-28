package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import com.yetanalytics.hlaxapi.SimulationConfig;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.ObjectCacheConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.TrackedObject;
import java.util.List;
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
        trackedRabbit.clazz = "Rabbit";
        trackedRabbit.attributes = List.of("Hunger");
        ObjectCacheConfig cacheConfig = new ObjectCacheConfig();
        cacheConfig.trackedObjects = List.of(trackedRabbit);
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(ancestorQuery);
        config.objectCacheConfig = cacheConfig;

        ObjectSubscriptionPlan plan = ObjectSubscriptionPlan.from(config, catalog);

        assertEquals(Set.of("FirstName"), plan.cacheSubscriptions().get("SimEntity"));
        assertEquals(Set.of("Hunger"), plan.cacheSubscriptions().get("Rabbit"));
        assertTrue(plan.eventSubscriptions().isEmpty());
        assertEquals(Set.of("FirstName"), plan.effectiveAttributes("SimEntity"));
        assertEquals(Set.of("FirstName", "Hunger"), plan.effectiveAttributes("Rabbit"));
        assertEquals(Set.of("FirstName"), plan.effectiveAttributes("Wolf"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.subscriptions().put("Wolf", Set.of("Hunger")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.subscriptions().get("Rabbit").add("EntityId"));
    }
}
