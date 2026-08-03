package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import com.yetanalytics.hlaxapi.HLAEncodingTestSupport;
import com.yetanalytics.hlaxapi.HlaInterfaceImpl;
import com.yetanalytics.hlaxapi.InjectionHandler;
import com.yetanalytics.hlaxapi.SimulationConfig;
import com.yetanalytics.hlaxapi.TriggerProcessor;
import com.yetanalytics.hlaxapi.XapiClient;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.ComparisonOperator;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.LrsConfig;
import com.yetanalytics.hlaxapi.config.model.LogicalExpression;
import com.yetanalytics.hlaxapi.config.model.LogicalOperator;
import com.yetanalytics.hlaxapi.config.model.ObjectCacheConfig;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TrackedObject;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import com.yetanalytics.xapi.util.StatementValidator;
import hla.rti1516e.AttributeHandle;
import hla.rti1516e.AttributeHandleSet;
import hla.rti1516e.AttributeHandleValueMap;
import hla.rti1516e.ObjectClassHandle;
import hla.rti1516e.ObjectInstanceHandle;
import hla.rti1516e.RTIambassador;
import hla.rti1516e.exceptions.ObjectInstanceNotKnown;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.portico.impl.hla1516e.types.HLA1516eAttributeHandleSetFactory;
import org.portico.impl.hla1516e.types.HLA1516eAttributeHandleValueMap;
import org.portico.impl.hla1516e.types.HLA1516eHandle;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class HlaObjectSubscriptionTest {

    private final HLADecoderRegistry decoderRegistry =
            new HLADecoderRegistry(new HLA1516eEncoderFactory());
    private final FOMXML fomXml = new FOMXML(
            new SimulationConfig(null, null, null, null, "config/HlaFedereplFOM.xml"),
            decoderRegistry);
    private final FomCatalog catalog = new FomCatalog(fomXml);

    @Test
    void eventOnlyConfigurationSubscribesRequestsAndProcessesReflections() throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(objectUpdateTrigger("SimEntity.Rabbit"));
        Set<String> expectedAttributes =
                Set.copyOf(catalog.objectClass("SimEntity.Rabbit").orElseThrow().topLevelAttributeNames());

        try (ObjectCache cache = new ObjectCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface = hlaInterface(cache, rti.proxy(), config, xapiClient);

            subscribeObjectClasses(hlaInterface);

            assertFalse(cache.isEnabled());
            assertEquals(List.of(new ObjectSubscription("SimEntity.Rabbit", expectedAttributes)), rti.subscriptions);

            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(91);
            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit One");

            assertEquals(1, rti.requests.size());
            assertEquals(rabbit, rti.requests.get(0).objectHandle());
            assertEquals(expectedAttributes, rti.requests.get(0).attributes());

            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");
            AttributeHandleValueMap reflection = new HLA1516eAttributeHandleValueMap();
            reflection.put(hunger, HLAEncodingTestSupport.int32(12, ByteOrder.BIG_ENDIAN));
            hlaInterface.reflectAttributeValues(rabbit, reflection, null, null, null, null);

            assertEquals(1, rti.knownClassResolutions);
            assertEquals(1, rti.attributeNameResolutions);
            assertTrue(cache.currentObjects("SimEntity.Rabbit").isEmpty());
            assertEquals(List.of("{}"), xapiClient.statements);
        }
    }

    @Test
    void firstReflectionDispatchesObjectCreateAndObjectUpdateThenOnlyUpdates() throws Exception {
        StatementTrigger create = objectTrigger(
                StatementTrigger.Type.OBJECT_CREATE,
                "SimEntity.Rabbit",
                """
                {"event":"create","hunger":["trigger",["Hunger"]]}
                """);
        StatementTrigger update = objectTrigger(
                StatementTrigger.Type.OBJECT_UPDATE,
                "SimEntity.Rabbit",
                """
                {"event":"update","hunger":["trigger",["Hunger"]]}
                """);
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(create, update);

        try (ObjectCache cache = new ObjectCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface = hlaInterface(
                    cache,
                    rti.proxy(),
                    config,
                    xapiClient,
                    injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(96);
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");

            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Create");
            reflect(hlaInterface, rabbit, hunger, 12);
            reflect(hlaInterface, rabbit, hunger, 13);

            assertFalse(cache.isEnabled());
            assertEquals(
                    List.of(
                            "{\"event\":\"create\",\"hunger\":12}",
                            "{\"event\":\"update\",\"hunger\":12}",
                            "{\"event\":\"update\",\"hunger\":13}"),
                    xapiClient.statements);
        }
    }

    @Test
    void concreteLifecycleCallbacksDispatchEachMatchingAncestorAndConcreteTriggerOnce(
            @TempDir Path tempDir) throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(
                objectTrigger(
                        StatementTrigger.Type.OBJECT_CREATE,
                        "SimEntity",
                        "{\"event\":\"sim-entity-create\"}"),
                objectTrigger(
                        StatementTrigger.Type.OBJECT_CREATE,
                        "SimEntity.Rabbit",
                        "{\"event\":\"rabbit-create\"}"),
                objectTrigger(
                        StatementTrigger.Type.OBJECT_CREATE,
                        "SimEntity.Wolf",
                        "{\"event\":\"wolf-create\"}"),
                objectTrigger(
                        StatementTrigger.Type.OBJECT_UPDATE,
                        "SimEntity",
                        "{\"event\":\"sim-entity-update\"}"),
                objectTrigger(
                        StatementTrigger.Type.OBJECT_UPDATE,
                        "SimEntity.Rabbit",
                        "{\"event\":\"rabbit-update\"}"),
                objectTrigger(
                        StatementTrigger.Type.OBJECT_UPDATE,
                        "SimEntity.Wolf",
                        "{\"event\":\"wolf-update\"}"),
                objectTrigger(
                        StatementTrigger.Type.OBJECT_DELETE,
                        "SimEntity",
                        "{\"event\":\"sim-entity-delete\"}"),
                objectTrigger(
                        StatementTrigger.Type.OBJECT_DELETE,
                        "SimEntity.Rabbit",
                        "{\"event\":\"rabbit-delete\"}"),
                objectTrigger(
                        StatementTrigger.Type.OBJECT_DELETE,
                        "SimEntity.Wolf",
                        "{\"event\":\"wolf-delete\"}"));

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("polymorphic-lifecycle.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface = hlaInterface(
                    cache,
                    rti.proxy(),
                    config,
                    xapiClient,
                    injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(109);
            AttributeHandleValueMap firstReflection = new HLA1516eAttributeHandleValueMap();
            firstReflection.put(
                    rti.attributeHandle(rabbitClass, "EntityId"),
                    HLAEncodingTestSupport.asciiString("rabbit-polymorphic"));
            firstReflection.put(
                    rti.attributeHandle(rabbitClass, "Hunger"),
                    HLAEncodingTestSupport.int32(12, ByteOrder.BIG_ENDIAN));

            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Polymorphic");
            hlaInterface.reflectAttributeValues(
                    rabbit,
                    firstReflection,
                    null,
                    null,
                    null,
                    null);
            reflect(
                    hlaInterface,
                    rabbit,
                    rti.attributeHandle(rabbitClass, "Hunger"),
                    13);
            hlaInterface.removeObjectInstance(rabbit, null, null, null);

            assertEquals(
                    List.of(
                            "{\"event\":\"sim-entity-create\"}",
                            "{\"event\":\"rabbit-create\"}",
                            "{\"event\":\"sim-entity-update\"}",
                            "{\"event\":\"rabbit-update\"}",
                            "{\"event\":\"sim-entity-update\"}",
                            "{\"event\":\"rabbit-update\"}",
                            "{\"event\":\"sim-entity-delete\"}",
                            "{\"event\":\"rabbit-delete\"}"),
                    xapiClient.statements);
        }
    }

    @Test
    void emptyReflectionDoesNotDispatchCacheOrConsumePendingCreate() throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(
                objectTrigger(
                        StatementTrigger.Type.OBJECT_CREATE,
                        "SimEntity.Rabbit",
                        "{\"event\":\"create\"}"),
                objectTrigger(
                        StatementTrigger.Type.OBJECT_UPDATE,
                        "SimEntity.Rabbit",
                        "{\"event\":\"update\"}"));

        try (RecordingReflectionCache cache =
                new RecordingReflectionCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient);
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(106);
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");

            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Empty");
            hlaInterface.reflectAttributeValues(
                    rabbit,
                    new HLA1516eAttributeHandleValueMap(),
                    null,
                    null,
                    null,
                    null);

            assertTrue(xapiClient.statements.isEmpty());
            assertEquals(0, cache.reflectionCalls);

            reflect(hlaInterface, rabbit, hunger, 12);

            assertEquals(1, cache.reflectionCalls);
            assertEquals(
                    List.of("{\"event\":\"create\"}", "{\"event\":\"update\"}"),
                    xapiClient.statements);
        }
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.HlaInterfaceImpl"})
    void failedCacheProcessingRetainsPendingCreateForTheNextReflection() throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(objectTrigger(
                StatementTrigger.Type.OBJECT_CREATE,
                "SimEntity.Rabbit",
                """
                {"hunger":["trigger",["Hunger"]]}
                """));

        try (ObjectCache cache =
                new FailOnceReflectionCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface = hlaInterface(
                    cache,
                    rti.proxy(),
                    config,
                    xapiClient,
                    injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(97);
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");

            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Retry");
            reflect(hlaInterface, rabbit, hunger, 12);
            reflect(hlaInterface, rabbit, hunger, 13);
            reflect(hlaInterface, rabbit, hunger, 14);

            assertEquals(List.of("{\"hunger\":13}"), xapiClient.statements);
        }
    }

    @Test
    void deletionBeforeFirstReflectionClearsPendingCreate() throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers =
                List.of(objectTrigger(StatementTrigger.Type.OBJECT_CREATE, "SimEntity.Rabbit", "{}"));

        try (ObjectCache cache = new ObjectCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient, injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(98);
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");

            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Gone");
            hlaInterface.removeObjectInstance(rabbit, null, null, null);
            reflect(hlaInterface, rabbit, hunger, 12);

            assertTrue(xapiClient.statements.isEmpty());
        }
    }

    @Test
    @SuppressTestLogging({
        "com.yetanalytics.hlaxapi.TriggerProcessor"
    })
    void firstReflectionConsumesCreateEvenWhenRequiredValuesAreMissing() throws Exception {
        StatementTrigger required = objectTrigger(
                StatementTrigger.Type.OBJECT_CREATE,
                "SimEntity.Rabbit",
                "{\"entityId\":[\"trigger\",[\"EntityId\"]]}");
        StatementTrigger optional = objectTrigger(
                StatementTrigger.Type.OBJECT_CREATE,
                "SimEntity.Rabbit",
                "{\"entityId\":[\"trigger\",[\"EntityId\"],{\"required\":false}]}");
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(required, optional);

        try (ObjectCache cache = new ObjectCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient, injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(105);

            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Partial");
            reflect(hlaInterface, rabbit, rti.attributeHandle(rabbitClass, "Hunger"), 12);
            AttributeHandleValueMap secondReflection = new HLA1516eAttributeHandleValueMap();
            secondReflection.put(
                    rti.attributeHandle(rabbitClass, "EntityId"),
                    HLAEncodingTestSupport.asciiString("rabbit-partial"));
            hlaInterface.reflectAttributeValues(rabbit, secondReflection, null, null, null, null);

            assertEquals(List.of("{\"entityId\":null}"), xapiClient.statements);
        }
    }

    @Test
    void objectDeleteUsesFinalSnapshotAndEnqueuesAfterRemoval(@TempDir Path tempDir) throws Exception {
        StatementTrigger delete = objectTrigger(
                StatementTrigger.Type.OBJECT_DELETE,
                "SimEntity.Rabbit",
                """
                {
                  "entityId":["trigger",["EntityId"]],
                  "hunger":["trigger",["Hunger"]],
                  "x":["trigger",["Position","X"]],
                  "queried":["query","SimEntity.Rabbit",["Hunger"],null],
                  "lookedUp":["lookup","rabbit",["Hunger"]]
                }
                """);
        delete.criteria = comparison("Hunger", ComparisonOperator.GT, 10);
        ObjectLookup lookup = new ObjectLookup();
        lookup.clazz = "SimEntity.Rabbit";
        lookup.criteria = new Criterion(
                new Target(List.of("EntityId")),
                ComparisonOperator.EQ,
                new ValueExpression("rabbit-delete"));
        delete.lookups = Map.of("rabbit", lookup);
        StatementTrigger skipped = objectTrigger(
                StatementTrigger.Type.OBJECT_DELETE,
                "SimEntity.Rabbit",
                "{\"skipped\":true}");
        skipped.criteria = comparison("Hunger", ComparisonOperator.GT, 20);
        StatementTrigger wrongClass = objectTrigger(
                StatementTrigger.Type.OBJECT_DELETE,
                "SimEntity.Wolf",
                "{\"wrongClass\":true}");
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(delete, skipped, wrongClass);

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("object-delete-dispatch.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(99);
            AtomicReference<Boolean> removedAtEnqueue = new AtomicReference<>(false);
            RecordingXapiClient xapiClient = new RecordingXapiClient(statement ->
                    removedAtEnqueue.set(cache.currentObjects("SimEntity.Rabbit").isEmpty()));
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient, injectionHandler(cache));
            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Delete");
            AttributeHandleValueMap reflection = new HLA1516eAttributeHandleValueMap();
            reflection.put(
                    rti.attributeHandle(rabbitClass, "EntityId"),
                    HLAEncodingTestSupport.asciiString("rabbit-delete"));
            reflection.put(
                    rti.attributeHandle(rabbitClass, "Hunger"),
                    HLAEncodingTestSupport.int32(17, ByteOrder.BIG_ENDIAN));
            reflection.put(
                    rti.attributeHandle(rabbitClass, "Position"),
                    HLAEncodingTestSupport.fixedRecord(
                            HLAEncodingTestSupport.int32(4, ByteOrder.BIG_ENDIAN),
                            HLAEncodingTestSupport.int32(7, ByteOrder.BIG_ENDIAN)));
            hlaInterface.reflectAttributeValues(rabbit, reflection, null, null, null, null);

            hlaInterface.removeObjectInstance(rabbit, null, null, null);
            hlaInterface.removeObjectInstance(rabbit, null, null, null);

            assertTrue(removedAtEnqueue.get());
            assertEquals(
                    List.of(
                            "{\"entityId\":\"rabbit-delete\",\"hunger\":17,\"x\":4,"
                                    + "\"queried\":17,\"lookedUp\":17}"),
                    xapiClient.statements);
            assertTrue(cache.findCurrentObjectSnapshot(rabbit.toString()).isEmpty());
        }
    }

    @Test
    @SuppressTestLogging({
        "com.yetanalytics.hlaxapi.HlaInterfaceImpl",
        "com.yetanalytics.hlaxapi.TriggerProcessor"
    })
    void discoveryRemovalRaceStillDispatchesStaticAndOptionalDeletes(@TempDir Path tempDir) throws Exception {
        StatementTrigger staticDelete =
                objectTrigger(StatementTrigger.Type.OBJECT_DELETE, "SimEntity.Rabbit", "{\"deleted\":true}");
        StatementTrigger requiredMissing = objectTrigger(
                StatementTrigger.Type.OBJECT_DELETE,
                "SimEntity.Rabbit",
                "{\"entityId\":[\"trigger\",[\"EntityId\"]]}");
        StatementTrigger optionalMissing = objectTrigger(
                StatementTrigger.Type.OBJECT_DELETE,
                "SimEntity.Rabbit",
                "{\"entityId\":[\"trigger\",[\"EntityId\"],{\"required\":false}]}");
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(staticDelete, requiredMissing, optionalMissing);

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("object-delete-race.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            rti.failAttributeRequestsForUnknownObjects = true;
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient, injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(100);

            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Brief");
            hlaInterface.removeObjectInstance(rabbit, null, null, null);

            assertEquals(
                    List.of("{\"deleted\":true}", "{\"entityId\":null}"),
                    xapiClient.statements);
            assertTrue(cache.currentObjects("SimEntity.Rabbit").isEmpty());
        }
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.HlaInterfaceImpl"})
    void removalFailureSuppressesStagedDeleteStatements(@TempDir Path tempDir) throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers =
                List.of(objectTrigger(StatementTrigger.Type.OBJECT_DELETE, "SimEntity.Rabbit", "{}"));

        try (ObjectCache cache = new FailingRemovalCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("object-delete-failure.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient, injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(101);
            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Failure");

            hlaInterface.removeObjectInstance(rabbit, null, null, null);

            assertTrue(xapiClient.statements.isEmpty());
            assertEquals(1, cache.currentObjects("SimEntity.Rabbit").size());
        }
    }

    @Test
    void everyLifecycleCallbackOverloadUsesTheCommonPipelines(@TempDir Path tempDir) throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(
                objectTrigger(StatementTrigger.Type.OBJECT_CREATE, "SimEntity.Rabbit", "{\"event\":\"create\"}"),
                objectTrigger(StatementTrigger.Type.OBJECT_DELETE, "SimEntity.Rabbit", "{\"event\":\"delete\"}"));

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("lifecycle-overloads.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient, injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");
            ObjectInstanceHandle first = rti.objectHandle(102);
            ObjectInstanceHandle second = rti.objectHandle(103);
            ObjectInstanceHandle third = rti.objectHandle(104);
            hlaInterface.discoverObjectInstance(first, rabbitClass, "Rabbit First");
            hlaInterface.discoverObjectInstance(second, rabbitClass, "Rabbit Second", null);
            hlaInterface.discoverObjectInstance(third, rabbitClass, "Rabbit Third");
            AttributeHandleValueMap firstReflection = reflection(hunger, 1);
            AttributeHandleValueMap secondReflection = reflection(hunger, 2);
            AttributeHandleValueMap thirdReflection = reflection(hunger, 3);

            hlaInterface.reflectAttributeValues(first, firstReflection, null, null, null, null);
            hlaInterface.reflectAttributeValues(
                    second,
                    secondReflection,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            hlaInterface.reflectAttributeValues(
                    third,
                    thirdReflection,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            hlaInterface.removeObjectInstance(first, null, null, null);
            hlaInterface.removeObjectInstance(second, null, null, null, null, null);
            hlaInterface.removeObjectInstance(third, null, null, null, null, null, null);

            assertEquals(
                    List.of(
                            "{\"event\":\"create\"}",
                            "{\"event\":\"create\"}",
                            "{\"event\":\"create\"}",
                            "{\"event\":\"delete\"}",
                            "{\"event\":\"delete\"}",
                            "{\"event\":\"delete\"}"),
                    xapiClient.statements);
            assertTrue(cache.currentObjects("SimEntity.Rabbit").isEmpty());
        }
    }

    @Test
    @SuppressTestLogging({
        "com.yetanalytics.hlaxapi.TriggerProcessor"
    })
    void eventOnlyReflectionDispatchesMatchingTriggersOnceFromTheCompletePayload() throws Exception {
        StatementTrigger passing = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {"incomingHunger":["trigger",["Hunger"]]}
                """);
        passing.criteria = comparison("Hunger", ComparisonOperator.GT, 10);
        StatementTrigger requiredMissing = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {"entityId":["trigger",["EntityId"]]}
                """);
        StatementTrigger optionalMissing = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {"entityId":["trigger",["EntityId"],{"required":false}]}
                """);
        StatementTrigger skipped = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {"skipped":true}
                """);
        skipped.criteria = comparison("Hunger", ComparisonOperator.GT, 20);
        StatementTrigger wrongClass = objectUpdateTrigger("SimEntity.Wolf", """
                {"wrongClass":true}
                """);
        StatementTrigger wrongType = objectUpdateTrigger("SimEntity.Rabbit", """
                {"wrongType":true}
                """);
        wrongType.type = StatementTrigger.Type.INTERACTION;

        XapiConfig config = new XapiConfig();
        config.statementTriggers =
                List.of(passing, requiredMissing, optionalMissing, skipped, wrongClass, wrongType);

        try (ObjectCache cache = new ObjectCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface = hlaInterface(
                    cache,
                    rti.proxy(),
                    config,
                    xapiClient,
                    injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(93);
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");
            AttributeHandle position = rti.attributeHandle(rabbitClass, "Position");
            AttributeHandleValueMap reflection = new HLA1516eAttributeHandleValueMap();
            reflection.put(hunger, HLAEncodingTestSupport.int32(12, ByteOrder.BIG_ENDIAN));
            reflection.put(position, HLAEncodingTestSupport.fixedRecord(
                    HLAEncodingTestSupport.int32(4, ByteOrder.BIG_ENDIAN),
                    HLAEncodingTestSupport.int32(7, ByteOrder.BIG_ENDIAN)));

            hlaInterface.reflectAttributeValues(rabbit, reflection, null, null, null, null);

            assertFalse(cache.isEnabled());
            assertEquals(2, rti.attributeNameResolutions);
            assertEquals(
                    List.of(
                            "{\"incomingHunger\":12}",
                            "{\"entityId\":null}"),
                    xapiClient.statements);
        }
    }

    @Test
    void cachedQueriesAndLookupsRenderBeforeTheReflectionCommitsAndEnqueueAfterItCommits(
            @TempDir Path tempDir) throws Exception {
        StatementTrigger trigger = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {
                  "incoming":["trigger",["Hunger"]],
                  "queried":["query","SimEntity.Rabbit",["Hunger"],[["EntityId"],"=","rabbit-one"]],
                  "lookedUp":["lookup","rabbit",["Hunger"]]
                }
                """);
        ObjectLookup lookup = new ObjectLookup();
        lookup.clazz = "SimEntity.Rabbit";
        lookup.criteria = new Criterion(
                new Target(List.of("EntityId")),
                ComparisonOperator.EQ,
                new ValueExpression("rabbit-one"));
        trigger.lookups = Map.of("rabbit", lookup);
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(trigger);

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("object-update-query.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(94);
            cache.reflectAttributeValues(
                    rabbit.toString(),
                    "SimEntity.Rabbit",
                    Map.of(
                            "EntityId", HLAEncodingTestSupport.asciiString("rabbit-one"),
                            "Hunger", HLAEncodingTestSupport.int32(5, ByteOrder.BIG_ENDIAN)));
            AtomicReference<Object> hungerAtEnqueue = new AtomicReference<>();
            RecordingXapiClient xapiClient = new RecordingXapiClient(statement -> hungerAtEnqueue.set(
                    cache.findCurrentValue(rabbit.toString(), "Hunger").orElseThrow().value()));
            HlaInterfaceImpl hlaInterface = hlaInterface(
                    cache,
                    rti.proxy(),
                    config,
                    xapiClient,
                    injectionHandler(cache));
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");
            AttributeHandleValueMap reflection = new HLA1516eAttributeHandleValueMap();
            reflection.put(hunger, HLAEncodingTestSupport.int32(19, ByteOrder.BIG_ENDIAN));

            hlaInterface.reflectAttributeValues(rabbit, reflection, null, null, null, null);

            assertTrue(cache.isEnabled());
            assertEquals(19, hungerAtEnqueue.get());
            assertEquals(
                    List.of("{\"incoming\":19,\"queried\":5,\"lookedUp\":5}"),
                    xapiClient.statements);
        }
    }

    @Test
    void previousCriteriaDetectChangesAndThresholdCrossings(@TempDir Path tempDir) throws Exception {
        StatementTrigger changed = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {"event":"changed","old":["previous",["Hunger"]],"new":["trigger",["Hunger"]]}
                """);
        changed.criteria = new Criterion(
                new PreviousExpression(new Target(List.of("Hunger"))),
                ComparisonOperator.NEQ,
                new TriggerExpression(new Target(List.of("Hunger"))));
        StatementTrigger crossed = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {"event":"crossed","old":["previous",["Hunger"]],"new":["trigger",["Hunger"]]}
                """);
        crossed.criteria = new LogicalExpression(
                LogicalOperator.AND,
                List.of(
                        new Criterion(
                                new PreviousExpression(new Target(List.of("Hunger"))),
                                ComparisonOperator.LT,
                                new ValueExpression(20)),
                        new Criterion(
                                new TriggerExpression(new Target(List.of("Hunger"))),
                                ComparisonOperator.GTE,
                                new ValueExpression(20))));
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(changed, crossed);

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("previous-crossing.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient, injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(106);
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");
            cache.reflectAttributeValue(
                    rabbit.toString(),
                    "SimEntity.Rabbit",
                    "Hunger",
                    HLAEncodingTestSupport.int32(10, ByteOrder.BIG_ENDIAN));

            reflect(hlaInterface, rabbit, hunger, 10);
            reflect(hlaInterface, rabbit, hunger, 21);
            reflect(hlaInterface, rabbit, hunger, 22);

            assertEquals(
                    List.of(
                            "{\"event\":\"changed\",\"old\":10,\"new\":21}",
                            "{\"event\":\"crossed\",\"old\":10,\"new\":21}",
                            "{\"event\":\"changed\",\"old\":21,\"new\":22}"),
                    xapiClient.statements);
        }
    }

    @Test
    @SuppressTestLogging({
        "com.yetanalytics.hlaxapi.TriggerProcessor"
    })
    void firstObservationSupportsOptionalPreviousWithoutRetryingRequiredInjections(
            @TempDir Path tempDir) throws Exception {
        StatementTrigger required = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {"event":"required","old":["previous",["Hunger"]],"new":["trigger",["Hunger"]]}
                """);
        StatementTrigger optional = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {
                  "event":"optional",
                  "old":["previous",["Hunger"],{"required":false}],
                  "new":["trigger",["Hunger"]]
                }
                """);
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(required, optional);

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("previous-first-observation.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient, injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(107);
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");
            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Previous");

            reflect(hlaInterface, rabbit, hunger, 5);
            reflect(hlaInterface, rabbit, hunger, 6);

            assertEquals(
                    List.of(
                            "{\"event\":\"optional\",\"old\":null,\"new\":5}",
                            "{\"event\":\"required\",\"old\":5,\"new\":6}",
                            "{\"event\":\"optional\",\"old\":5,\"new\":6}"),
                    xapiClient.statements);
        }
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.HlaInterfaceImpl"})
    void failedReflectionRetainsOnePreviousStateForEveryTrigger(@TempDir Path tempDir) throws Exception {
        StatementTrigger first = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {"trigger":1,"old":["previous",["Hunger"]],"new":["trigger",["Hunger"]]}
                """);
        StatementTrigger second = objectUpdateTrigger(
                "SimEntity.Rabbit",
                """
                {"trigger":2,"old":["previous",["Hunger"]],"new":["trigger",["Hunger"]]}
                """);
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(first, second);

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("previous-rollback.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient, injectionHandler(cache));
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(108);
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");
            AttributeHandle unknown = rti.attributeHandle(rabbitClass, "NotInTheFom");
            cache.reflectAttributeValue(
                    rabbit.toString(),
                    "SimEntity.Rabbit",
                    "Hunger",
                    HLAEncodingTestSupport.int32(5, ByteOrder.BIG_ENDIAN));
            AttributeHandleValueMap failedReflection = new HLA1516eAttributeHandleValueMap();
            failedReflection.put(hunger, HLAEncodingTestSupport.int32(20, ByteOrder.BIG_ENDIAN));
            failedReflection.put(unknown, HLAEncodingTestSupport.int32(1, ByteOrder.BIG_ENDIAN));

            hlaInterface.reflectAttributeValues(rabbit, failedReflection, null, null, null, null);

            assertEquals(5, cache.findCurrentValue(rabbit.toString(), "Hunger").orElseThrow().value());
            assertTrue(xapiClient.statements.isEmpty());

            reflect(hlaInterface, rabbit, hunger, 30);

            assertEquals(
                    List.of(
                            "{\"trigger\":1,\"old\":5,\"new\":30}",
                            "{\"trigger\":2,\"old\":5,\"new\":30}"),
                    xapiClient.statements);
        }
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.HlaInterfaceImpl"})
    void cacheFailureSuppressesAllStatementsStagedForTheReflection(@TempDir Path tempDir) throws Exception {
        XapiConfig config = trackedRabbitConfig(objectUpdateTrigger("SimEntity.Rabbit"));
        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("object-update-cache-failure.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(95);
            cache.reflectAttributeValue(
                    rabbit.toString(),
                    "SimEntity.Rabbit",
                    "Hunger",
                    HLAEncodingTestSupport.int32(5, ByteOrder.BIG_ENDIAN));
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface = hlaInterface(
                    cache,
                    rti.proxy(),
                    config,
                    xapiClient,
                    injectionHandler(cache));
            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");
            AttributeHandle unknown = rti.attributeHandle(rabbitClass, "NotInTheFom");
            AttributeHandleValueMap reflection = new HLA1516eAttributeHandleValueMap();
            reflection.put(hunger, HLAEncodingTestSupport.int32(20, ByteOrder.BIG_ENDIAN));
            reflection.put(unknown, HLAEncodingTestSupport.int32(1, ByteOrder.BIG_ENDIAN));

            hlaInterface.reflectAttributeValues(rabbit, reflection, null, null, null, null);

            assertEquals(5, cache.findCurrentValue(rabbit.toString(), "Hunger").orElseThrow().value());
            assertTrue(xapiClient.statements.isEmpty());
        }
    }

    @Test
    void discoveryCachesMetadataAndRequestsMergedAttributes(@TempDir Path tempDir) throws Exception {
        XapiConfig config = configWithQueryAndObjectUpdate();
        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("discovery.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, new RecordingXapiClient());
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(92);

            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Two");

            assertTrue(cache.isEnabled());
            assertEquals(1, cache.currentObjects("SimEntity.Rabbit").size());
            assertEquals("Rabbit Two", cache.currentObjects("SimEntity.Rabbit").get(0).objectName());
            assertEquals(cache.subscriptions().get("SimEntity.Rabbit"), rti.requests.get(0).attributes());
        }
    }

    @Test
    void discoveryRequestsTheUnionOfChildAndAncestorSubscriptions(@TempDir Path tempDir) throws Exception {
        StatementTrigger simEntityQuery = new StatementTrigger();
        simEntityQuery.statement = """
                {"name":["query","SimEntity",["FirstName"],null]}
                """;
        TrackedObject trackedRabbit = new TrackedObject();
        trackedRabbit.clazz = "SimEntity.Rabbit";
        trackedRabbit.attributes = List.of("Hunger");
        ObjectCacheConfig objectCacheConfig = new ObjectCacheConfig();
        objectCacheConfig.trackedObjects = List.of(trackedRabbit);
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(simEntityQuery);
        config.objectCacheConfig = objectCacheConfig;

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("inherited-discovery.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, new RecordingXapiClient());
            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(107);

            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Inherited");

            assertEquals(Set.of("FirstName"), cache.subscriptions().get("SimEntity"));
            assertEquals(Set.of("FirstName"), cache.subscriptions().get("SimEntity.Carrot"));
            assertEquals(Set.of("FirstName", "Hunger"), cache.subscriptions().get("SimEntity.Rabbit"));
            assertEquals(Set.of("FirstName"), cache.subscriptions().get("SimEntity.Wolf"));
            assertEquals(Set.of("FirstName", "Hunger"), rti.requests.get(0).attributes());
        }
    }

    @Test
    void ancestorQuerySubscribesDescendantsFirstAndCachesConcreteClasses(
            @TempDir Path tempDir) throws Exception {
        StatementTrigger simEntityQuery = new StatementTrigger();
        simEntityQuery.statement = """
                {"entityId":["query","SimEntity",["EntityId"],null]}
                """;
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(simEntityQuery);

        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("concrete-classes.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, new RecordingXapiClient());

            subscribeObjectClasses(hlaInterface);

            assertEquals(
                    List.of("SimEntity.Carrot", "SimEntity.Rabbit", "SimEntity.Wolf", "SimEntity"),
                    rti.subscriptions.stream()
                            .map(ObjectSubscription::className)
                            .toList());
            assertEquals(
                    List.of(
                            "SimEntity.Carrot",
                            "SimEntity.Rabbit",
                            "SimEntity.Wolf",
                            "SimEntity"),
                    rti.objectClassHandleLookups);
            assertTrue(rti.subscriptions.stream()
                    .allMatch(subscription ->
                            subscription.attributes().equals(Set.of("EntityId"))));

            List<String> concreteClasses = List.of("SimEntity.Carrot", "SimEntity.Rabbit", "SimEntity.Wolf");
            for (int i = 0; i < concreteClasses.size(); i++) {
                String className = concreteClasses.get(i);
                ObjectClassHandle classHandle = rti.classHandle(className);
                ObjectInstanceHandle object = rti.objectHandle(110 + i);
                hlaInterface.discoverObjectInstance(
                        object,
                        classHandle,
                        className + " Concrete");
                AttributeHandleValueMap reflection =
                        new HLA1516eAttributeHandleValueMap();
                reflection.put(
                        rti.attributeHandle(classHandle, "EntityId"),
                        HLAEncodingTestSupport.asciiString(
                                className.toLowerCase() + "-concrete"));
                hlaInterface.reflectAttributeValues(
                        object,
                        reflection,
                        null,
                        null,
                        null,
                        null);

                assertEquals(
                        className,
                        cache.findCurrentObjectSnapshot(object.toString())
                                .orElseThrow()
                                .className());
                assertEquals(
                        1,
                        cache.currentObjects(className).size());
            }

            assertEquals(3, rti.requests.size());
            assertTrue(rti.requests.stream()
                    .allMatch(request -> request.attributes().equals(Set.of("EntityId"))));
            assertEquals(
                    concreteClasses,
                    cache.currentObjects("SimEntity").stream()
                            .map(CachedObject::className)
                            .toList());
        }
    }

    @Test
    void duplicateLocalObjectClassesRemainDistinctAcrossRtiCallbacks(
            @TempDir Path tempDir) throws Exception {
        FOMXML ambiguousFomXml = new FOMXML(
                new SimulationConfig(
                        null,
                        null,
                        null,
                        null,
                        "src/test/resources/config/AmbiguousClassNamesFOM.xml"),
                decoderRegistry);
        FomCatalog ambiguousCatalog = new FomCatalog(ambiguousFomXml);
        TrackedObject entityRabbit = new TrackedObject();
        entityRabbit.clazz = "SimEntity.Rabbit";
        entityRabbit.allAttributes = true;
        TrackedObject otherRabbit = new TrackedObject();
        otherRabbit.clazz = "SomeOtherSuperclass.Rabbit";
        otherRabbit.allAttributes = true;
        ObjectCacheConfig cacheConfig = new ObjectCacheConfig();
        cacheConfig.trackedObjects = List.of(entityRabbit, otherRabbit);
        XapiConfig config = new XapiConfig();
        config.objectCacheConfig = cacheConfig;

        try (ObjectCache cache = new ObjectCache(
                config,
                ambiguousCatalog,
                ambiguousFomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("duplicate-local-names.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, new RecordingXapiClient());
            subscribeObjectClasses(hlaInterface);

            ObjectClassHandle entityClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle entity = rti.objectHandle(201);
            hlaInterface.discoverObjectInstance(entity, entityClass, "Entity Rabbit");
            reflect(
                    hlaInterface,
                    entity,
                    rti.attributeHandle(entityClass, "Hunger"),
                    12);

            ObjectClassHandle otherClass = rti.classHandle("SomeOtherSuperclass.Rabbit");
            ObjectInstanceHandle other = rti.objectHandle(202);
            hlaInterface.discoverObjectInstance(other, otherClass, "Other Rabbit");
            reflect(
                    hlaInterface,
                    other,
                    rti.attributeHandle(otherClass, "Speed"),
                    34);

            assertEquals(
                    "SimEntity.Rabbit",
                    cache.findCurrentObjectSnapshot(entity.toString()).orElseThrow().className());
            assertEquals(
                    "SomeOtherSuperclass.Rabbit",
                    cache.findCurrentObjectSnapshot(other.toString()).orElseThrow().className());
            assertEquals(12, cache.findCurrentValue(entity.toString(), "Hunger").orElseThrow().value());
            assertEquals(34, cache.findCurrentValue(other.toString(), "Speed").orElseThrow().value());
        }
    }

    @Test
    void overlappingAncestorAndConcreteSubscriptionsDoNotDuplicateReflectionTriggers()
            throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(
                objectUpdateTrigger(
                        "SimEntity",
                        "{\"event\":\"sim-entity-update\"}"),
                objectUpdateTrigger(
                        "SimEntity.Rabbit",
                        "{\"event\":\"rabbit-update\"}"));

        try (ObjectCache cache = new ObjectCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();
            RecordingXapiClient xapiClient = new RecordingXapiClient();
            HlaInterfaceImpl hlaInterface =
                    hlaInterface(cache, rti.proxy(), config, xapiClient);

            subscribeObjectClasses(hlaInterface);

            ObjectClassHandle rabbitClass = rti.classHandle("SimEntity.Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(113);
            hlaInterface.discoverObjectInstance(
                    rabbit,
                    rabbitClass,
                    "Rabbit Overlap");
            AttributeHandleValueMap reflection =
                    new HLA1516eAttributeHandleValueMap();
            reflection.put(
                    rti.attributeHandle(rabbitClass, "EntityId"),
                    HLAEncodingTestSupport.asciiString("rabbit-overlap"));
            reflection.put(
                    rti.attributeHandle(rabbitClass, "Hunger"),
                    HLAEncodingTestSupport.int32(12, ByteOrder.BIG_ENDIAN));
            hlaInterface.reflectAttributeValues(
                    rabbit,
                    reflection,
                    null,
                    null,
                    null,
                    null);

            assertEquals(1, rti.requests.size());
            assertEquals(2, rti.attributeNameResolutions);
            assertEquals(
                    List.of(
                            "{\"event\":\"sim-entity-update\"}",
                            "{\"event\":\"rabbit-update\"}"),
                    xapiClient.statements);
        }
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.HlaInterfaceImpl"})
    void unknownObjectUpdateClassIsSkippedDuringSubscription() throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(objectUpdateTrigger("MissingObject"));

        try (ObjectCache cache = new ObjectCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();

            subscribeObjectClasses(hlaInterface(
                    cache,
                    rti.proxy(),
                    config,
                    new RecordingXapiClient()));

            assertTrue(rti.subscriptions.isEmpty());
        }
    }

    private XapiConfig configWithQueryAndObjectUpdate() {
        StatementTrigger query = new StatementTrigger();
        query.statement = """
                {"actor":{"name":["query","SimEntity.Rabbit",["EntityId"],[["Hunger"],">",50]]}}
                """;
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(query, objectUpdateTrigger("SimEntity.Rabbit"));
        return config;
    }

    private StatementTrigger objectUpdateTrigger(String className) {
        return objectUpdateTrigger(className, "{}");
    }

    private StatementTrigger objectUpdateTrigger(String className, String statement) {
        return objectTrigger(StatementTrigger.Type.OBJECT_UPDATE, className, statement);
    }

    private StatementTrigger objectTrigger(
            StatementTrigger.Type type,
            String className,
            String statement) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = type;
        trigger.clazz = className;
        trigger.statement = statement;
        return trigger;
    }

    private void reflect(
            HlaInterfaceImpl hlaInterface,
            ObjectInstanceHandle object,
            AttributeHandle attribute,
            int value) throws Exception {
        hlaInterface.reflectAttributeValues(object, reflection(attribute, value), null, null, null, null);
    }

    private AttributeHandleValueMap reflection(AttributeHandle attribute, int value) {
        AttributeHandleValueMap reflectedValues = new HLA1516eAttributeHandleValueMap();
        reflectedValues.put(attribute, HLAEncodingTestSupport.int32(value, ByteOrder.BIG_ENDIAN));
        return reflectedValues;
    }

    private Criterion comparison(String attribute, ComparisonOperator operator, Object value) {
        return new Criterion(
                new TriggerExpression(new Target(List.of(attribute))),
                operator,
                new ValueExpression(value));
    }

    private XapiConfig trackedRabbitConfig(StatementTrigger trigger) {
        TrackedObject trackedRabbit = new TrackedObject();
        trackedRabbit.clazz = "SimEntity.Rabbit";
        trackedRabbit.attributes = List.of("Hunger");
        ObjectCacheConfig cacheConfig = new ObjectCacheConfig();
        cacheConfig.trackedObjects = List.of(trackedRabbit);
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(trigger);
        config.objectCacheConfig = cacheConfig;
        return config;
    }

    private InjectionHandler injectionHandler(ObjectCache cache) throws Exception {
        InjectionHandler handler = new InjectionHandler();
        handler.setFomXml(fomXml);
        handler.setHLADecoderRegistry(decoderRegistry);
        handler.setFomCatalog(catalog);
        setField(handler, "objectCache", cache);
        return handler;
    }

    private HlaInterfaceImpl hlaInterface(
            ObjectCache cache,
            RTIambassador ambassador,
            XapiConfig config,
            XapiClient xapiClient) throws Exception {
        return hlaInterface(
                cache,
                ambassador,
                config,
                xapiClient,
                new InjectionHandler());
    }

    private HlaInterfaceImpl hlaInterface(
            ObjectCache cache,
            RTIambassador ambassador,
            XapiConfig config,
            XapiClient xapiClient,
            InjectionHandler injectionHandler) throws Exception {
        HlaInterfaceImpl hlaInterface = new HlaInterfaceImpl();
        setField(hlaInterface, "objectCache", cache);
        setField(hlaInterface, "ambassador", ambassador);
        setField(hlaInterface, "xapiConfig", config);
        setField(
                hlaInterface,
                "triggerProcessor",
                new TriggerProcessor(config, injectionHandler, catalog));
        setField(hlaInterface, "xapiClient", xapiClient);
        return hlaInterface;
    }

    private void subscribeObjectClasses(HlaInterfaceImpl hlaInterface) throws Exception {
        Method method = HlaInterfaceImpl.class.getDeclaredMethod("subscribeObjectClasses");
        method.setAccessible(true);
        method.invoke(hlaInterface);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record ObjectSubscription(String className, Set<String> attributes) {
    }

    private record AttributeRequest(ObjectInstanceHandle objectHandle, Set<String> attributes) {
    }

    private static final class FailOnceReflectionCache extends ObjectCache {

        private boolean failNextReflection = true;

        private FailOnceReflectionCache(
                XapiConfig config,
                FomCatalog catalog,
                FOMXML fomXml,
                HLADecoderRegistry decoderRegistry) {
            super(config, catalog, fomXml, decoderRegistry);
        }

        @Override
        public synchronized void reflectAttributeValues(
                String objectHandle,
                String className,
                Map<String, byte[]> attributes) {
            if (failNextReflection) {
                failNextReflection = false;
                throw new IllegalStateException("injected reflection failure");
            }
            super.reflectAttributeValues(objectHandle, className, attributes);
        }
    }

    private static final class RecordingReflectionCache extends ObjectCache {

        private int reflectionCalls;

        private RecordingReflectionCache(
                XapiConfig config,
                FomCatalog catalog,
                FOMXML fomXml,
                HLADecoderRegistry decoderRegistry) {
            super(config, catalog, fomXml, decoderRegistry);
        }

        @Override
        public synchronized void reflectAttributeValues(
                String objectHandle,
                String className,
                Map<String, byte[]> attributes) {
            reflectionCalls++;
            super.reflectAttributeValues(objectHandle, className, attributes);
        }
    }

    private static final class FailingRemovalCache extends ObjectCache {

        private FailingRemovalCache(
                XapiConfig config,
                FomCatalog catalog,
                FOMXML fomXml,
                HLADecoderRegistry decoderRegistry,
                String jdbcUrl) {
            super(config, catalog, fomXml, decoderRegistry, jdbcUrl);
        }

        @Override
        public synchronized void removeObject(String objectHandle) {
            throw new IllegalStateException("injected removal failure");
        }
    }

    private static final class RecordingXapiClient extends XapiClient {

        private final List<String> statements = new ArrayList<>();
        private final Consumer<String> onStatement;

        private RecordingXapiClient() {
            this(statement -> {
            });
        }

        private RecordingXapiClient(Consumer<String> onStatement) {
            super(clientConfig(), new StatementValidator());
            this.onStatement = onStatement;
        }

        @Override
        public void sendStatement(String statement) {
            onStatement.accept(statement);
            statements.add(statement);
        }

        private static XapiConfig clientConfig() {
            LrsConfig lrs = new LrsConfig();
            lrs.host = "https://example.com/xapi/";
            lrs.key = "key";
            lrs.secret = "secret";
            lrs.batch = 10;
            lrs.maxRetries = 1;
            XapiConfig config = new XapiConfig();
            config.lrsConfig = lrs;
            return config;
        }
    }

    private static final class RecordingRti implements InvocationHandler {

        private final Map<String, ObjectClassHandle> classes = new LinkedHashMap<>();
        private final Map<ObjectClassHandle, String> classNames = new LinkedHashMap<>();
        private final List<String> objectClassHandleLookups = new ArrayList<>();
        private final Map<String, AttributeHandle> attributes = new LinkedHashMap<>();
        private final Map<AttributeHandle, String> attributeNames = new LinkedHashMap<>();
        private final List<ObjectSubscription> subscriptions = new ArrayList<>();
        private final List<AttributeRequest> requests = new ArrayList<>();
        private int nextClassHandle = 1;
        private int nextAttributeHandle = 1_000;
        private int knownClassResolutions;
        private int attributeNameResolutions;
        private ObjectClassHandle knownClass;
        private boolean failAttributeRequestsForUnknownObjects;

        private RTIambassador proxy() {
            return (RTIambassador) Proxy.newProxyInstance(
                    RTIambassador.class.getClassLoader(),
                    new Class<?>[] {RTIambassador.class},
                    this);
        }

        private ObjectClassHandle classHandle(String className) {
            if (className.startsWith("HLAobjectRoot.")) {
                className = className.substring("HLAobjectRoot.".length());
            }
            ObjectClassHandle handle = classes.computeIfAbsent(
                    className,
                    ignored -> (ObjectClassHandle) new HLA1516eHandle(nextClassHandle++));
            classNames.put(handle, className);
            knownClass = handle;
            return handle;
        }

        private ObjectInstanceHandle objectHandle(int value) {
            return (ObjectInstanceHandle) new HLA1516eHandle(value);
        }

        private AttributeHandle attributeHandle(ObjectClassHandle classHandle, String attributeName) {
            String key = classNames.get(classHandle) + "." + attributeName;
            AttributeHandle handle = attributes.computeIfAbsent(
                    key,
                    ignored -> (AttributeHandle) new HLA1516eHandle(nextAttributeHandle++));
            attributeNames.put(handle, attributeName);
            return handle;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getObjectClassHandle" -> {
                    objectClassHandleLookups.add((String) args[0]);
                    yield classHandle((String) args[0]);
                }
                case "getObjectClassName" -> qualifiedClassName(classNames.get(args[0]));
                case "getAttributeHandleSetFactory" -> new HLA1516eAttributeHandleSetFactory();
                case "getAttributeHandle" -> attributeHandle((ObjectClassHandle) args[0], (String) args[1]);
                case "subscribeObjectClassAttributes" -> {
                    subscriptions.add(new ObjectSubscription(
                            classNames.get(args[0]),
                            names((AttributeHandleSet) args[1])));
                    yield null;
                }
                case "requestAttributeValueUpdate" -> {
                    if (failAttributeRequestsForUnknownObjects) {
                        throw new ObjectInstanceNotKnown("object disappeared");
                    }
                    requests.add(new AttributeRequest(
                            (ObjectInstanceHandle) args[0],
                            names((AttributeHandleSet) args[1])));
                    yield null;
                }
                case "getKnownObjectClassHandle" -> {
                    knownClassResolutions++;
                    yield knownClass;
                }
                case "getAttributeName" -> {
                    attributeNameResolutions++;
                    yield attributeNames.get(args[1]);
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        private String qualifiedClassName(String className) {
            return "HLAobjectRoot".equals(className)
                    ? className
                    : "HLAobjectRoot." + className;
        }

        private Set<String> names(AttributeHandleSet handles) {
            Set<String> names = new LinkedHashSet<>();
            for (AttributeHandle handle : handles) {
                names.add(attributeNames.get(handle));
            }
            return Set.copyOf(names);
        }

        private Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive() || returnType == void.class) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == char.class) {
                return '\0';
            }
            return 0;
        }
    }
}
