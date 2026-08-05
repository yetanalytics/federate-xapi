package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import com.yetanalytics.hlaxapi.SimulationConfig;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.ComparisonOperator;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.ObjectCacheConfig;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TrackedObject;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import hla.rti1516e.encoding.DataElement;
import hla.rti1516e.encoding.EncoderException;
import hla.rti1516e.encoding.EncoderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class ObjectCacheTest {

    private final EncoderFactory encoderFactory = new HLA1516eEncoderFactory();
    private final HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(encoderFactory);
    private final FOMXML fomXml = new FOMXML(
            new SimulationConfig(null, null, null, null, "config/HlaFedereplFOM.xml"),
            decoderRegistry);
    private final FomCatalog catalog = new FomCatalog(fomXml);

    @Test
    void alwaysOnWithoutSubscriptionsOpensSqliteAndStoresDirectCalls(@TempDir Path tempDir) {
        Path databasePath = tempDir.resolve("always-on.sqlite");

        try (ObjectCache cache = new ObjectCache(
                new XapiConfig(),
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + databasePath)) {
            assertTrue(cache.subscriptions().isEmpty());
            assertTrue(cache.currentObjects("SimEntity.Rabbit").isEmpty());
            assertTrue(Files.exists(databasePath));

            cache.discoverObject("object-1", "Rabbit One", "SimEntity.Rabbit");
            cache.reflectAttributeValue("object-1", "SimEntity.Rabbit", "Hunger", encoded(encoderFactory.createHLAinteger32BE(
                    75)));

            assertEquals(
                    75,
                    cache.findFirstValue("SimEntity.Rabbit", new Target(List.of("Hunger")), null).orElseThrow());

            cache.removeObject("object-1");
            assertTrue(cache.currentObjects("SimEntity.Rabbit").isEmpty());
        }
    }

    @Test
    void emptyConfigFailsConstructionWhenCacheCannotOpen(@TempDir Path tempDir) {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("missing").resolve("cache.sqlite");

        assertThrows(
                IllegalStateException.class,
                () -> new ObjectCache(
                        new XapiConfig(),
                        catalog,
                        fomXml,
                        decoderRegistry,
                        jdbcUrl));
    }

    @Test
    void objectUpdateSubscriptionsInitializeCacheAndIncludeInheritedAttributes(@TempDir Path tempDir) {
        Path databasePath = tempDir.resolve("object-update-only.sqlite");
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(objectUpdateTrigger("SimEntity.Rabbit"));

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + databasePath)) {
            Set<String> rabbitAttributes =
                    Set.copyOf(catalog.objectClass("SimEntity.Rabbit").orElseThrow().topLevelAttributeNames());

            assertTrue(cache.cacheSubscriptions().isEmpty());
            assertEquals(rabbitAttributes, cache.eventSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.subscriptions().get("SimEntity.Rabbit"));
            assertTrue(cache.hasSubscriptions());
            assertTrue(Files.exists(databasePath));
        }
    }

    @Test
    void objectUpdatePreviousReferencesSelectOnlyTheirCacheAttributes(@TempDir Path tempDir) {
        StatementTrigger trigger = objectUpdateTrigger("SimEntity.Rabbit");
        trigger.statement = """
                {
                  "oldHunger":["previous",["Hunger"]],
                  "oldX":"<<[\\"previous\\",[\\"Position\\",\\"X\\"]]>>"
                }
                """;
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(trigger);

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("object-update-previous.sqlite"))) {
            Set<String> rabbitAttributes =
                    Set.copyOf(catalog.objectClass("SimEntity.Rabbit").orElseThrow().topLevelAttributeNames());

            assertEquals(Set.of("Hunger", "Position"), cache.cacheSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.eventSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.subscriptions().get("SimEntity.Rabbit"));
        }
    }

    @Test
    void objectCreateSubscriptionsInitializeCacheAndIncludeInheritedAttributes(@TempDir Path tempDir) {
        Path databasePath = tempDir.resolve("object-create-only.sqlite");
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(objectTrigger(StatementTrigger.Type.OBJECT_CREATE, "SimEntity.Rabbit"));

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + databasePath)) {
            Set<String> rabbitAttributes =
                    Set.copyOf(catalog.objectClass("SimEntity.Rabbit").orElseThrow().topLevelAttributeNames());

            assertTrue(cache.cacheSubscriptions().isEmpty());
            assertEquals(rabbitAttributes, cache.eventSubscriptions().get("SimEntity.Rabbit"));
            assertTrue(Files.exists(databasePath));
        }
    }

    @Test
    void objectDeleteSubscriptionsCacheAllInheritedAttributes(@TempDir Path tempDir) {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(objectTrigger(StatementTrigger.Type.OBJECT_DELETE, "SimEntity.Rabbit"));

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("object-delete.sqlite"))) {
            Set<String> rabbitAttributes =
                    Set.copyOf(catalog.objectClass("SimEntity.Rabbit").orElseThrow().topLevelAttributeNames());

            assertEquals(rabbitAttributes, cache.cacheSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.eventSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.subscriptions().get("SimEntity.Rabbit"));
        }
    }

    @Test
    void objectUpdateSubscriptionsMergeWithoutChangingCacheRequirements(@TempDir Path tempDir) {
        XapiConfig config = configWithQuery();
        config.statementTriggers = List.of(
                config.statementTriggers.get(0),
                objectUpdateTrigger("SimEntity.Rabbit"),
                objectUpdateTrigger("SimEntity.Rabbit"));

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("object-update-merged.sqlite"))) {
            Set<String> rabbitAttributes =
                    Set.copyOf(catalog.objectClass("SimEntity.Rabbit").orElseThrow().topLevelAttributeNames());

            assertEquals(Set.of("EntityId", "Hunger"), cache.cacheSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.eventSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.subscriptions().get("SimEntity.Rabbit"));
        }
    }

    @Test
    void retainsUnknownObjectUpdateClassForSubscriptionErrorHandling(@TempDir Path tempDir) {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(objectUpdateTrigger("MissingObject"));

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("unknown-class.sqlite"))) {
            assertEquals(Set.of("*"), cache.eventSubscriptions().get("MissingObject"));
            assertEquals(Set.of("*"), cache.subscriptions().get("MissingObject"));
        }
    }

    @Test
    void queryInjectionsCanQueryReflectedValues(@TempDir Path tempDir) {
        Path databasePath = tempDir.resolve("query.sqlite");

        try (ObjectCache cache = new ObjectCache(
                configWithQuery(),
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + databasePath)) {
            cache.discoverObject("object-1", "Rabbit One", "SimEntity.Rabbit");
            cache.reflectAttributeValue("object-1", "SimEntity.Rabbit", "EntityId", encoded(encoderFactory
                    .createHLAASCIIstring("rabbit-one")));
            cache.reflectAttributeValue("object-1", "SimEntity.Rabbit", "Hunger", encoded(encoderFactory.createHLAinteger32BE(
                    75)));

            Criterion criteria = new Criterion(
                    new Target(List.of("Hunger")),
                    ComparisonOperator.GT,
                    new ValueExpression(50));

            assertEquals(Set.of("EntityId", "Hunger"), cache.subscriptions().get("SimEntity.Rabbit"));
            assertEquals(
                    "rabbit-one",
                    cache.findFirstValue("SimEntity.Rabbit", new Target(List.of("EntityId")), criteria).orElseThrow());
            assertTrue(Files.exists(databasePath));
        }
    }

    @Test
    void queryInTriggerCriteriaSelectsRequiredSubscriptions(@TempDir Path tempDir) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.statement = "{}";
        trigger.criteria = new Criterion(
                new QueryExpression(
                        "SimEntity.Rabbit",
                        new Target(List.of("EntityId")),
                        new Criterion(
                                new Target(List.of("Hunger")),
                                ComparisonOperator.GT,
                                new ValueExpression(50))),
                ComparisonOperator.NEQ,
                new ValueExpression(null));
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(trigger);

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("criteria-query.sqlite"))) {
            assertEquals(Set.of("EntityId", "Hunger"), cache.subscriptions().get("SimEntity.Rabbit"));
        }
    }

    @Test
    void trackedObjectsSelectSubscriptionsWithoutQueryInjections(@TempDir Path tempDir) {
        Path databasePath = tempDir.resolve("tracked.sqlite");

        try (ObjectCache cache = new ObjectCache(
                configWithTrackedObject("SimEntity.Rabbit", List.of("EntityId", "Hunger"), false),
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + databasePath)) {
            assertEquals(Set.of("EntityId", "Hunger"), cache.subscriptions().get("SimEntity.Rabbit"));
            assertTrue(Files.exists(databasePath));
        }
    }

    @Test
    void closeIsIdempotent(@TempDir Path tempDir) {
        ObjectCache cache = new ObjectCache(
                configWithQuery(),
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("close.sqlite"));

        cache.close();
        cache.close();
    }

    @Test
    void trackedObjectAllAttributesExpandsTopLevelFomAttributes(@TempDir Path tempDir) {
        try (ObjectCache cache = new ObjectCache(
                configWithTrackedObject("SimEntity.Rabbit", null, true),
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("all-attrs.sqlite"))) {
            assertEquals(
                    Set.of("EntityId", "EntityType", "Position", "Hunger"),
                    stableAttributes(cache.subscriptions().get("SimEntity.Rabbit"), "EntityId", "EntityType", "Position",
                            "Hunger"));
        }
    }

    @Test
    void trackedObjectWildcardExpandsEveryObjectClassWithAllAttributes(@TempDir Path tempDir) {
        try (ObjectCache cache = new ObjectCache(
                configWithTrackedObject("*", null, true),
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("wildcard.sqlite"))) {
            assertEquals(
                    Set.of("WorldId", "Size", "StepNumber", "CarrotCount", "RabbitCount", "WolfCount"),
                    cache.subscriptions().get("World"));
            assertEquals(
                    Set.of("EntityId", "EntityType", "Position"),
                    stableAttributes(cache.subscriptions().get("SimEntity"), "EntityId", "EntityType", "Position"));
            assertEquals(
                    Set.of("EntityId", "EntityType", "Position", "Hunger"),
                    stableAttributes(cache.subscriptions().get("SimEntity.Rabbit"), "EntityId", "EntityType", "Position",
                            "Hunger"));
            assertFalse(cache.subscriptions().containsKey("HLAobjectRoot"));
        }
    }

    @Test
    void trackedObjectsMergeWithQueryInjections(@TempDir Path tempDir) {
        XapiConfig config = configWithQuery();
        config.objectCacheConfig = objectCacheConfig(trackedObject("SimEntity.Rabbit", List.of("Position"), false));

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("merged.sqlite"))) {
            assertEquals(Set.of("EntityId", "Hunger", "Position"), cache.subscriptions().get("SimEntity.Rabbit"));
        }
    }

    private XapiConfig configWithQuery() {
        StatementTrigger trigger = new StatementTrigger();
        trigger.statement = """
                {"actor":{"name":["query","SimEntity.Rabbit",["EntityId"],[["Hunger"],">",50]]}}
                """;

        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(trigger);
        return config;
    }

    private XapiConfig configWithTrackedObject(String className, List<String> attributes, boolean allAttributes) {
        XapiConfig config = new XapiConfig();
        config.objectCacheConfig = objectCacheConfig(trackedObject(className, attributes, allAttributes));
        return config;
    }

    private StatementTrigger objectUpdateTrigger(String className) {
        return objectTrigger(StatementTrigger.Type.OBJECT_UPDATE, className);
    }

    private StatementTrigger objectTrigger(StatementTrigger.Type type, String className) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = type;
        trigger.clazz = className;
        trigger.statement = "{}";
        return trigger;
    }

    private ObjectCacheConfig objectCacheConfig(TrackedObject... trackedObjects) {
        ObjectCacheConfig objectCacheConfig = new ObjectCacheConfig();
        objectCacheConfig.trackedObjects = List.of(trackedObjects);
        return objectCacheConfig;
    }

    private TrackedObject trackedObject(String className, List<String> attributes, boolean allAttributes) {
        TrackedObject trackedObject = new TrackedObject();
        trackedObject.clazz = className;
        trackedObject.attributes = attributes;
        trackedObject.allAttributes = allAttributes;
        return trackedObject;
    }

    private byte[] encoded(DataElement element) {
        try {
            return element.toByteArray();
        } catch (EncoderException e) {
            throw new IllegalStateException("Could not encode test value", e);
        }
    }

    private Set<String> stableAttributes(Set<String> attributes, String... expected) {
        Set<String> stable = Set.of(expected);
        assertTrue(attributes.containsAll(stable));
        return stable;
    }
}
