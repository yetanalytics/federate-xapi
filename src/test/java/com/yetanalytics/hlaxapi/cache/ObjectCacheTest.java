package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void disabledWhenNoQueryInjectionsAndDoesNotOpenSqlite(@TempDir Path tempDir) {
        Path databasePath = tempDir.resolve("disabled.sqlite");

        try (ObjectCache cache = new ObjectCache(
                new XapiConfig(),
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + databasePath)) {
            cache.discoverObject("object-1", "Rabbit One", "SimEntity.Rabbit");
            cache.reflectAttributeValue("object-1", "SimEntity.Rabbit", "Hunger", encoded(encoderFactory.createHLAinteger32BE(
                    75)));
            cache.removeObject("object-1");

            assertFalse(cache.isEnabled());
            assertTrue(cache.subscriptions().isEmpty());
            assertFalse(cache.findFirstValue("SimEntity.Rabbit", new Target(List.of("Hunger")), null).isPresent());
            assertFalse(Files.exists(databasePath));
        }
    }

    @Test
    void disabledCacheDoesNotRequireConnectionSettings() {
        try (ObjectCache cache = new ObjectCache(new XapiConfig(), catalog, fomXml, decoderRegistry)) {
            assertFalse(cache.isEnabled());
        }
    }

    @Test
    void objectUpdateSubscriptionsDoNotEnableCacheAndIncludeInheritedAttributes(@TempDir Path tempDir) {
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

            assertFalse(cache.isEnabled());
            assertTrue(cache.cacheSubscriptions().isEmpty());
            assertEquals(rabbitAttributes, cache.eventSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.subscriptions().get("SimEntity.Rabbit"));
            assertTrue(cache.hasSubscriptions());
            assertFalse(Files.exists(databasePath));
        }
    }

    @Test
    void objectUpdatePreviousReferencesEnableOnlyTheirCacheAttributes(@TempDir Path tempDir) {
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

            assertTrue(cache.isEnabled());
            assertEquals(Set.of("Hunger", "Position"), cache.cacheSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.eventSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.subscriptions().get("SimEntity.Rabbit"));
        }
    }

    @Test
    void objectCreateSubscriptionsDoNotEnableCacheAndIncludeInheritedAttributes(@TempDir Path tempDir) {
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

            assertFalse(cache.isEnabled());
            assertTrue(cache.cacheSubscriptions().isEmpty());
            assertEquals(rabbitAttributes, cache.eventSubscriptions().get("SimEntity.Rabbit"));
            assertFalse(Files.exists(databasePath));
        }
    }

    @Test
    void objectDeleteSubscriptionsEnableCacheForAllInheritedAttributes(@TempDir Path tempDir) {
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

            assertTrue(cache.isEnabled());
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

            assertTrue(cache.isEnabled());
            assertEquals(Set.of("EntityId", "Hunger"), cache.cacheSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.eventSubscriptions().get("SimEntity.Rabbit"));
            assertEquals(rabbitAttributes, cache.subscriptions().get("SimEntity.Rabbit"));
        }
    }

    @Test
    void retainsUnknownObjectUpdateClassForSubscriptionErrorHandling() {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(objectUpdateTrigger("MissingObject"));

        try (ObjectCache cache = new ObjectCache(config, catalog, fomXml, decoderRegistry)) {
            assertFalse(cache.isEnabled());
            assertEquals(Set.of("*"), cache.eventSubscriptions().get("MissingObject"));
            assertEquals(Set.of("*"), cache.subscriptions().get("MissingObject"));
        }
    }

    @Test
    void enabledWhenQueryInjectionsExistAndCanQueryReflectedValues(@TempDir Path tempDir) {
        Path databasePath = tempDir.resolve("enabled.sqlite");

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

            assertTrue(cache.isEnabled());
            assertEquals(Set.of("EntityId", "Hunger"), cache.subscriptions().get("SimEntity.Rabbit"));
            assertEquals(
                    "rabbit-one",
                    cache.findFirstValue("SimEntity.Rabbit", new Target(List.of("EntityId")), criteria).orElseThrow());
            assertTrue(Files.exists(databasePath));
        }
    }

    @Test
    void enabledWhenQueryAppearsOnlyInTriggerCriteria(@TempDir Path tempDir) {
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
            assertTrue(cache.isEnabled());
            assertEquals(Set.of("EntityId", "Hunger"), cache.subscriptions().get("SimEntity.Rabbit"));
        }
    }

    @Test
    void enabledWhenTrackedObjectsExistWithoutQueryInjections(@TempDir Path tempDir) {
        Path databasePath = tempDir.resolve("tracked.sqlite");

        try (ObjectCache cache = new ObjectCache(
                configWithTrackedObject("SimEntity.Rabbit", List.of("EntityId", "Hunger"), false),
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + databasePath)) {
            assertTrue(cache.isEnabled());
            assertEquals(Set.of("EntityId", "Hunger"), cache.subscriptions().get("SimEntity.Rabbit"));
            assertTrue(Files.exists(databasePath));
        }
    }

    @Test
    void closeIsIdempotentAndDisablesCache(@TempDir Path tempDir) {
        ObjectCache cache = new ObjectCache(
                configWithQuery(),
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("close.sqlite"));

        assertTrue(cache.isEnabled());
        cache.close();
        cache.close();

        assertFalse(cache.isEnabled());
        assertTrue(cache.currentObjects("SimEntity.Rabbit").isEmpty());
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
