package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.HLAEncodingTestSupport;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import com.yetanalytics.hlaxapi.InjectionHandler;
import com.yetanalytics.hlaxapi.SimulationConfig;
import com.yetanalytics.hlaxapi.TriggerProcessor;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.ComparisonOperator;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.LogicalExpression;
import com.yetanalytics.hlaxapi.config.model.LogicalOperator;
import com.yetanalytics.hlaxapi.config.model.ObjectCacheConfig;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TrackedObject;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import com.yetanalytics.hlaxapi.injection.InteractionInjectionContext;
import com.yetanalytics.hlaxapi.injection.ObjectInjectionContext;
import hla.rti1516e.encoding.DataElement;
import hla.rti1516e.encoding.EncoderException;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.encoding.HLAfixedRecord;
import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

abstract class ObjectCachePersistenceTest {

    protected final EncoderFactory encoderFactory = new HLA1516eEncoderFactory();
    protected final HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(encoderFactory);
    protected final FOMXML fomXml = new FOMXML(
            new SimulationConfig(null, null, null, null, "config/HlaFedereplFOM.xml"),
            decoderRegistry);
    protected final FomCatalog catalog = new FomCatalog(fomXml);
    private final FOMXML dynamicArrayFomXml = new FOMXML(
            new SimulationConfig(null, null, null, null, "src/test/resources/config/ObjectCacheTestFOM.xml"),
            decoderRegistry);
    private final FomCatalog dynamicArrayCatalog = new FomCatalog(dynamicArrayFomXml);

    @Test
    void initializesSchemaAndSeedsFomMetadata() throws SQLException {
        try (ObjectCache cache = newCache()) {
            assertEquals(1, scalarLong(cache, "SELECT schema_version FROM object_cache_metadata"));
            assertTrue(count(cache, "SELECT COUNT(*) FROM fom_object_class") > 0);
            assertTrue(count(cache, "SELECT COUNT(*) FROM fom_attribute WHERE path_key = 'Position.X'") > 0);
        }
    }

    @Test
    void upsertsLatestScalarObjectStateAndKeepsRawBytes() throws SQLException {
        byte[] firstHunger = encoded(encoderFactory.createHLAinteger32BE(75));
        byte[] secondHunger = encoded(encoderFactory.createHLAinteger32BE(40));

        try (ObjectCache cache = newCache()) {
            cache.discoverObject("object-1", "Rabbit One", "Rabbit");
            cache.reflectAttributeValue("object-1", "Rabbit", "Hunger", firstHunger);
            cache.reflectAttributeValue("object-1", "Rabbit", "Hunger", secondHunger);

            assertEquals(40, cache.findCurrentValue("object-1", "Hunger").orElseThrow().value());
            assertEquals(1, count(cache, """
                    SELECT COUNT(*)
                    FROM object_attribute_current c
                    JOIN fom_attribute a ON a.id = c.attribute_id
                    WHERE a.path_key = 'Hunger'
                    """));
            assertArrayEquals(secondHunger, rawBytes(cache, "Hunger"));
        }
    }

    @Test
    void storesOneMultiAttributeReflectionWithSharedObservationMetadata() throws SQLException {
        byte[] entityId = encoded(encoderFactory.createHLAASCIIstring("rabbit-one"));
        byte[] hunger = encoded(encoderFactory.createHLAinteger32BE(75));
        byte[] position = position(12, 8);

        try (ObjectCache cache = newCache()) {
            cache.reflectAttributeValues(
                    "object-1",
                    "Rabbit",
                    Map.of(
                            "EntityId", entityId,
                            "Hunger", hunger,
                            "Position", position));

            assertEquals("rabbit-one", cache.findCurrentValue("object-1", "EntityId").orElseThrow().value());
            assertEquals(75, cache.findCurrentValue("object-1", "Hunger").orElseThrow().value());
            assertEquals(12, cache.findCurrentValue("object-1", "Position.X").orElseThrow().value());
            assertEquals(8, cache.findCurrentValue("object-1", "Position.Y").orElseThrow().value());
            assertEquals(1, count(cache, "SELECT COUNT(DISTINCT observed_at) FROM object_attribute_current"));
            assertEquals(1, count(cache, "SELECT COUNT(DISTINCT sequence) FROM object_attribute_current"));
            assertEquals(1, count(cache, "SELECT COUNT(*) FROM object_instance WHERE object_handle = 'object-1'"));
        }
    }

    @Test
    void loadsCurrentObjectSnapshotWithTopLevelRawValuesAndMetadata() {
        byte[] entityId = encoded(encoderFactory.createHLAASCIIstring("rabbit-one"));
        byte[] hunger = encoded(encoderFactory.createHLAinteger32BE(75));
        byte[] position = position(12, 8);
        byte[] history = positionHistory(position(1, 2), position(3, 4));

        try (ObjectCache cache = newCache(
                "object-snapshot",
                enabledConfig(),
                dynamicArrayCatalog,
                dynamicArrayFomXml)) {
            cache.discoverObject("object-1", "Rabbit One", "Rabbit");
            cache.reflectAttributeValues(
                    "object-1",
                    "Rabbit",
                    Map.of(
                            "EntityId", entityId,
                            "Hunger", hunger,
                            "Position", position,
                            "PositionHistory", history));

            ObjectSnapshot snapshot = cache.findCurrentObjectSnapshot("object-1").orElseThrow();

            assertEquals("object-1", snapshot.objectHandle());
            assertEquals("Rabbit One", snapshot.objectName());
            assertEquals("Rabbit", snapshot.className());
            assertEquals(4, snapshot.attributes().size());
            assertArrayEquals(entityId, snapshot.attributes().get("EntityId"));
            assertArrayEquals(hunger, snapshot.attributes().get("Hunger"));
            assertArrayEquals(position, snapshot.attributes().get("Position"));
            assertArrayEquals(history, snapshot.attributes().get("PositionHistory"));

            cache.removeObject("object-1");

            assertTrue(cache.findCurrentObjectSnapshot("object-1").isEmpty());
        }
    }

    @Test
    void validatesTheCompleteReflectionBeforeWriting() {
        byte[] oldHunger = encoded(encoderFactory.createHLAinteger32BE(40));
        byte[] newHunger = encoded(encoderFactory.createHLAinteger32BE(75));

        try (ObjectCache cache = newCache()) {
            cache.reflectAttributeValue("object-1", "Rabbit", "Hunger", oldHunger);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> cache.reflectAttributeValues(
                            "object-1",
                            "Rabbit",
                            Map.of(
                                    "Hunger", newHunger,
                                    "NotInTheFom", new byte[] { 1 })));

            assertEquals(40, cache.findCurrentValue("object-1", "Hunger").orElseThrow().value());
        }
    }

    @Test
    void ignoresEmptyReflections() throws SQLException {
        try (ObjectCache cache = newCache()) {
            cache.reflectAttributeValues("object-1", "Rabbit", Map.of());

            assertEquals(0, count(cache, "SELECT COUNT(*) FROM object_instance"));
            assertEquals(0, count(cache, "SELECT COUNT(*) FROM object_attribute_current"));
        }
    }

    @Test
    void rollsBackEveryExistingValueWhenAReflectionFails() {
        byte[] oldEntityId = encoded(encoderFactory.createHLAASCIIstring("rabbit-old"));
        byte[] oldHunger = encoded(encoderFactory.createHLAinteger32BE(40));
        byte[] newHunger = encoded(encoderFactory.createHLAinteger32BE(75));

        try (ObjectCache cache = newCache()) {
            cache.reflectAttributeValues(
                    "object-1",
                    "Rabbit",
                    Map.of(
                            "EntityId", oldEntityId,
                            "Hunger", oldHunger));
            FomCatalog.ObjectClassDef rabbit = catalog.objectClass("Rabbit").orElseThrow();
            ReflectedAttributeValues hunger = new ReflectedAttributeValues(
                    "Hunger",
                    List.of(new DecodedAttributeValue(
                            "Hunger",
                            "HLAinteger32BE",
                            "HLAinteger32BE",
                            75,
                            newHunger,
                            true)));
            ReflectedAttributeValues entityId = new ReflectedAttributeValues(
                    "EntityId",
                    List.of(new DecodedAttributeValue(
                            "EntityId",
                            "HLAASCIIstring",
                            "HLAASCIIstring",
                            new Object(),
                            oldEntityId,
                            true)));

            assertThrows(
                    IllegalStateException.class,
                    () -> cache.store().replaceCurrentValues(
                            "object-1",
                            rabbit,
                            List.of(hunger, entityId),
                            "2026-07-27T00:00:00Z",
                            2));

            assertEquals("rabbit-old", cache.findCurrentValue("object-1", "EntityId").orElseThrow().value());
            assertEquals(40, cache.findCurrentValue("object-1", "Hunger").orElseThrow().value());
        }
    }

    @Test
    void rollsBackObjectValuesAndDynamicMetadataWhenAReflectionFails() throws SQLException {
        try (ObjectCache cache = newCache(
                "reflection-rollback",
                enabledConfig(),
                dynamicArrayCatalog,
                dynamicArrayFomXml)) {
            FomCatalog.ObjectClassDef rabbit = dynamicArrayCatalog.objectClass("Rabbit").orElseThrow();
            byte[] encodedValue = encoded(encoderFactory.createHLAinteger32BE(1));
            ReflectedAttributeValues positionHistory = new ReflectedAttributeValues(
                    "PositionHistory",
                    List.of(
                            new DecodedAttributeValue(
                                    "PositionHistory[0].X",
                                    "HLAinteger32BE",
                                    "HLAinteger32BE",
                                    1,
                                    encodedValue,
                                    true),
                            new DecodedAttributeValue(
                                    "PositionHistory[0].Y",
                                    "HLAinteger32BE",
                                    "HLAinteger32BE",
                                    new Object(),
                                    encodedValue,
                                    true)));

            assertThrows(
                    IllegalStateException.class,
                    () -> cache.store().replaceCurrentValues(
                            "object-rollback",
                            rabbit,
                            List.of(positionHistory),
                            "2026-07-27T00:00:00Z",
                            1));

            assertEquals(
                    0,
                    count(cache, "SELECT COUNT(*) FROM object_instance WHERE object_handle = 'object-rollback'"));
            assertEquals(
                    0,
                    count(cache, """
                            SELECT COUNT(*)
                            FROM fom_attribute
                            WHERE path_key LIKE 'PositionHistory[0].%'
                            """));
            assertEquals(0, count(cache, "SELECT COUNT(*) FROM object_attribute_current"));
        }
    }

    @Test
    void flattensFixedRecordValuesToNestedCurrentRows() {
        byte[] position = position(12, 8);

        try (ObjectCache cache = newCache()) {
            cache.discoverObject("object-1", "Rabbit One", "Rabbit");
            cache.reflectAttributeValue("object-1", "Rabbit", "Position", position);

            assertEquals(12, cache.findCurrentValue("object-1", "Position.X").orElseThrow().value());
            assertEquals(8, cache.findCurrentValue("object-1", "Position.Y").orElseThrow().value());
            assertTrue(cache.findCurrentValue("object-1", "Position").orElseThrow().value() instanceof java.util.Map);
        }
    }

    @Test
    void replacesDynamicArrayPathsWhenArrayShrinksAndReusesMetadata() throws SQLException {
        byte[] hunger = encoded(encoderFactory.createHLAinteger32BE(75));

        try (ObjectCache cache = newCache(
                "dynamic-array",
                enabledConfig(),
                dynamicArrayCatalog,
                dynamicArrayFomXml)) {
            cache.discoverObject("object-1", "Rabbit One", "Rabbit");
            cache.reflectAttributeValue("object-1", "Rabbit", "Hunger", hunger);
            cache.reflectAttributeValue(
                    "object-1",
                    "Rabbit",
                    "PositionHistory",
                    positionHistory(position(1, 2), position(3, 4)));

            assertEquals(2, ((List<?>) cache.findCurrentValue("object-1", "PositionHistory")
                            .orElseThrow()
                            .value())
                    .size());
            assertEquals(1, cache.findCurrentValue("object-1", "PositionHistory[0].X")
                    .orElseThrow()
                    .value());
            assertEquals(4, cache.findCurrentValue("object-1", "PositionHistory[1].Y")
                    .orElseThrow()
                    .value());
            assertEquals(5, currentValueCount(cache, "PositionHistory"));
            long secondElementXId = attributeId(cache, "PositionHistory[1].X");

            cache.reflectAttributeValue(
                    "object-1",
                    "Rabbit",
                    "PositionHistory",
                    positionHistory(position(9, 10)));

            assertEquals(1, ((List<?>) cache.findCurrentValue("object-1", "PositionHistory")
                            .orElseThrow()
                            .value())
                    .size());
            assertEquals(9, cache.findCurrentValue("object-1", "PositionHistory[0].X")
                    .orElseThrow()
                    .value());
            assertFalse(cache.findCurrentValue("object-1", "PositionHistory[1].X").isPresent());
            assertFalse(cache.findCurrentValue("object-1", "PositionHistory[1].Y").isPresent());
            assertEquals(3, currentValueCount(cache, "PositionHistory"));
            assertEquals(75, cache.findCurrentValue("object-1", "Hunger").orElseThrow().value());
            assertEquals(secondElementXId, attributeId(cache, "PositionHistory[1].X"));

            cache.reflectAttributeValue(
                    "object-1",
                    "Rabbit",
                    "PositionHistory",
                    positionHistory(position(11, 12), position(13, 14)));

            assertEquals(13, cache.findCurrentValue("object-1", "PositionHistory[1].X")
                    .orElseThrow()
                    .value());
            assertEquals(5, currentValueCount(cache, "PositionHistory"));
            assertEquals(secondElementXId, attributeId(cache, "PositionHistory[1].X"));
        }
    }

    @Test
    void queryServiceEvaluatesCriteriaAndExcludesRemovedObjects() {
        try (ObjectCache cache = newCache()) {
            cache.discoverObject("object-1", "Rabbit One", "Rabbit");
            cache.reflectAttributeValue("object-1", "Rabbit", "EntityId", encoded(encoderFactory.createHLAASCIIstring(
                    "rabbit-one")));
            cache.reflectAttributeValue("object-1", "Rabbit", "Hunger", encoded(encoderFactory.createHLAinteger32BE(75)));
            cache.reflectAttributeValue("object-1", "Rabbit", "Position", position(12, 8));

            cache.discoverObject("object-2", "Rabbit Two", "Rabbit");
            cache.reflectAttributeValue("object-2", "Rabbit", "EntityId", encoded(encoderFactory.createHLAASCIIstring(
                    "rabbit-two")));
            cache.reflectAttributeValue("object-2", "Rabbit", "Hunger", encoded(encoderFactory.createHLAinteger32BE(20)));
            cache.reflectAttributeValue("object-2", "Rabbit", "Position", position(20, 5));

            Criterion hungerCriteria = new Criterion(
                    new Target(List.of("Hunger")),
                    ComparisonOperator.GT,
                    new ValueExpression(50));
            Criterion xCriteria = new Criterion(
                    new Target(List.of("Position", "X")),
                    ComparisonOperator.LT,
                    new ValueExpression(15));
            LogicalExpression criteria = new LogicalExpression(LogicalOperator.AND, List.of(hungerCriteria, xCriteria));

            assertEquals(
                    List.of("rabbit-one"),
                    cache.queryService().findValues("Rabbit", new Target(List.of("EntityId")), hungerCriteria));
            assertEquals(
                    List.of(8),
                    cache.queryService().findValues("Rabbit", new Target(List.of("Position", "Y")), criteria));
            CachedObject matched = cache.queryService().findFirstObject("Rabbit", criteria).orElseThrow();
            assertEquals("object-1", matched.objectHandle());
            assertEquals(
                    8,
                    cache.queryService().findValue(matched, new Target(List.of("Position", "Y"))).orElseThrow());

            cache.removeObject("object-1");

            assertFalse(cache.queryService().findFirstValue("Rabbit", new Target(List.of("Hunger")), criteria)
                    .isPresent());
        }
    }

    @Test
    void baseClassQueryFindsObjectsCachedAsDescendantClasses() {
        try (ObjectCache cache = newCache()) {
            cache.discoverObject("entity-1", "Entity One", "SimEntity");
            cache.discoverObject("rabbit-1", "Rabbit One", "Rabbit");
            cache.reflectAttributeValues(
                    "rabbit-1",
                    "Rabbit",
                    Map.of(
                            "EntityId", encoded(encoderFactory.createHLAASCIIstring("rabbit-one")),
                            "FirstName", encoded(encoderFactory.createHLAunicodeString("Alice"))));
            cache.discoverObject("wolf-1", "Wolf One", "Wolf");
            Criterion entityId = new Criterion(
                    new Target(List.of("EntityId")),
                    ComparisonOperator.EQ,
                    new ValueExpression("rabbit-one"));

            CachedObject matched =
                    cache.queryService().findFirstObject("SimEntity", entityId).orElseThrow();

            assertEquals("Rabbit", matched.className());
            assertEquals(
                    "Alice",
                    cache.queryService()
                            .findValue(matched, new Target(List.of("FirstName")))
                            .orElseThrow());
            assertEquals(
                    List.of("entity-1", "rabbit-1", "wolf-1"),
                    cache.currentObjects("SimEntity").stream()
                            .map(CachedObject::objectHandle)
                            .toList());
            assertEquals(
                    List.of("rabbit-1"),
                    cache.currentObjects("Rabbit").stream()
                            .map(CachedObject::objectHandle)
                            .toList());

            cache.removeObject("wolf-1");

            assertEquals(
                    List.of("entity-1", "rabbit-1"),
                    cache.currentObjects("SimEntity").stream()
                            .map(CachedObject::objectHandle)
                            .toList());
        }
    }

    @Test
    void entityAteLookupFindsRabbitThroughSimEntityBaseClass() throws Exception {
        try (ObjectCache cache = newCache()) {
            cache.reflectAttributeValues(
                    "rabbit-1",
                    "Rabbit",
                    Map.of(
                            "EntityId", encoded(encoderFactory.createHLAASCIIstring("rabbit-one")),
                            "FirstName", encoded(encoderFactory.createHLAunicodeString("Alice"))));
            ObjectLookup predator = new ObjectLookup();
            predator.clazz = "SimEntity";
            predator.criteria = new Criterion(
                    new Target(List.of("EntityId")),
                    ComparisonOperator.EQ,
                    new TriggerExpression(new Target(List.of("PredatorId"))));
            StatementTrigger trigger = new StatementTrigger();
            trigger.type = StatementTrigger.Type.INTERACTION;
            trigger.clazz = "EntityAte";
            trigger.lookups = Map.of("predator", predator);
            trigger.statement = "{\"predator\":[\"lookup\",\"predator\",[\"FirstName\"]]}";
            InjectionHandler injectionHandler = new InjectionHandler();
            injectionHandler.setFomXml(fomXml);
            injectionHandler.setHLADecoderRegistry(decoderRegistry);
            injectionHandler.setFomCatalog(catalog);
            setField(injectionHandler, "objectCache", cache);

            TriggerProcessor.TriggerProcessingResult result =
                    new TriggerProcessor(injectionHandler).processTrigger(
                            trigger,
                            new InteractionInjectionContext(
                                    "EntityAte",
                                    Map.of(
                                            "PredatorId",
                                            encoded(encoderFactory.createHLAASCIIstring("rabbit-one")))));

            assertTrue(result.success());
            assertEquals("{\"predator\":\"Alice\"}", result.statement());
        }
    }

    @Test
    void previousResolutionSupportsNestedArraysCachedNullAndMissingValues() throws Exception {
        try (ObjectCache cache = newCache(
                "previous-resolution",
                enabledConfig(),
                dynamicArrayCatalog,
                dynamicArrayFomXml)) {
            cache.reflectAttributeValues(
                    "rabbit-1",
                    "Rabbit",
                    Map.of(
                            "Position", position(12, 8),
                            "PositionHistory", positionHistory(position(1, 2), position(3, 4)),
                            "Hunger", new byte[] {1}));
            InjectionHandler injectionHandler = new InjectionHandler();
            injectionHandler.setFomXml(dynamicArrayFomXml);
            injectionHandler.setHLADecoderRegistry(decoderRegistry);
            injectionHandler.setFomCatalog(dynamicArrayCatalog);
            setField(injectionHandler, "objectCache", cache);
            ObjectInjectionContext context =
                    new ObjectInjectionContext("Rabbit", "rabbit-1", Map.of());
            context.setTriggerType(StatementTrigger.Type.OBJECT_UPDATE);

            ValueResolution nested = injectionHandler.handlePrevious(
                    new Target(List.of("Position", "X")),
                    context);
            ValueResolution array = injectionHandler.handlePrevious(
                    new Target(List.of("PositionHistory", 1, "Y")),
                    context);
            ValueResolution cachedNull = injectionHandler.handlePrevious(
                    new Target(List.of("Hunger")),
                    context);
            ValueResolution missing = injectionHandler.handlePrevious(
                    new Target(List.of("EntityId")),
                    context);

            assertEquals(ValueResolution.Status.PRESENT, nested.status());
            assertEquals(12, nested.value());
            assertEquals(ValueResolution.Status.PRESENT, array.status());
            assertEquals(4, array.value());
            assertEquals(ValueResolution.Status.PRESENT, cachedNull.status());
            assertNull(cachedNull.value());
            assertEquals(ValueResolution.Status.MISSING_VALUE, missing.status());

            StatementTrigger nullablePrevious = new StatementTrigger();
            nullablePrevious.type = StatementTrigger.Type.OBJECT_UPDATE;
            nullablePrevious.clazz = "Rabbit";
            nullablePrevious.statement =
                    "{\"oldHunger\":[\"previous\",[\"Hunger\"],{\"nullable\":true}]}";
            TriggerProcessor.TriggerProcessingResult rendered =
                    new TriggerProcessor(injectionHandler).processTrigger(
                            nullablePrevious,
                            context);

            assertTrue(rendered.success());
            assertEquals("{\"oldHunger\":null}", rendered.statement());

            cache.removeObject("rabbit-1");

            assertEquals(
                    ValueResolution.Status.MISSING_VALUE,
                    injectionHandler.handlePrevious(
                                    new Target(List.of("Position", "X")),
                                    context)
                            .status());
        }
    }

    @Test
    void queryServiceDistinguishesPresentNullFromMissingValue() {
        try (ObjectCache cache = newCache()) {
            cache.discoverObject("object-1", "Rabbit One", "Rabbit");
            cache.reflectAttributeValue("object-1", "Rabbit", "Hunger", new byte[] { 1 });
            CachedObject matched = cache.queryService().findFirstObject("Rabbit", null).orElseThrow();

            ValueResolution presentNull = cache.queryService().findValueResolution(
                    matched,
                    new Target(List.of("Hunger")));
            ValueResolution missingValue = cache.queryService().findValueResolution(
                    matched,
                    new Target(List.of("Position", "X")));

            assertEquals(ValueResolution.Status.PRESENT, presentNull.status());
            assertNull(presentNull.value());
            assertEquals(ValueResolution.Status.MISSING_VALUE, missingValue.status());
        }
    }

    @Test
    void persistentCacheStartsFreshOnInitialization() throws SQLException {
        try (ObjectCache cache = newCache("fresh-start")) {
            cache.discoverObject("object-1", "Rabbit One", "Rabbit");
            cache.reflectAttributeValue("object-1", "Rabbit", "Hunger",
                    encoded(encoderFactory.createHLAinteger32BE(75)));

            assertEquals(1, count(cache, "SELECT COUNT(*) FROM object_instance"));
            assertEquals(1, count(cache, "SELECT COUNT(*) FROM object_attribute_current"));
        }

        try (ObjectCache cache = newCache("fresh-start")) {
            assertEquals(0, count(cache, "SELECT COUNT(*) FROM object_instance"));
            assertEquals(0, count(cache, "SELECT COUNT(*) FROM object_attribute_current"));
            assertTrue(count(cache, "SELECT COUNT(*) FROM fom_object_class") > 0);
        }
    }

    protected ObjectCache newCache() {
        return newCache("default");
    }

    protected ObjectCache newCache(String name) {
        return newCache(name, enabledConfig(), catalog, fomXml);
    }

    protected abstract ObjectCache newCache(
            String name,
            XapiConfig config,
            FomCatalog cacheCatalog,
            FOMXML cacheFomXml);

    protected XapiConfig enabledConfig() {
        TrackedObject trackedObject = new TrackedObject();
        trackedObject.clazz = "Rabbit";
        trackedObject.allAttributes = true;
        ObjectCacheConfig objectCacheConfig = new ObjectCacheConfig();
        objectCacheConfig.trackedObjects = List.of(trackedObject);
        XapiConfig config = new XapiConfig();
        config.objectCacheConfig = objectCacheConfig;
        return config;
    }

    protected byte[] position(int x, int y) {
        HLAfixedRecord record = encoderFactory.createHLAfixedRecord();
        record.add(encoderFactory.createHLAinteger32BE(x));
        record.add(encoderFactory.createHLAinteger32BE(y));
        return encoded(record);
    }

    protected byte[] positionHistory(byte[]... positions) {
        return HLAEncodingTestSupport.variableArray(positions);
    }

    protected byte[] encoded(DataElement element) {
        try {
            return element.toByteArray();
        } catch (EncoderException e) {
            throw new IllegalStateException("Could not encode test value", e);
        }
    }

    protected long count(ObjectCache cache, String sql) throws SQLException {
        return scalarLong(cache, sql);
    }

    protected long scalarLong(ObjectCache cache, String sql) throws SQLException {
        try (PreparedStatement statement = cache.connection().prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private void setField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    protected byte[] rawBytes(ObjectCache cache, String pathKey) throws SQLException {
        String sql = """
                SELECT c.raw_bytes
                FROM object_attribute_current c
                JOIN fom_attribute a ON a.id = c.attribute_id
                WHERE a.path_key = ?
                """;
        try (PreparedStatement statement = cache.connection().prepareStatement(sql)) {
            statement.setString(1, pathKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getBytes(1) : null;
            }
        }
    }

    private long currentValueCount(ObjectCache cache, String attributeName) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM object_attribute_current c
                JOIN fom_attribute a ON a.id = c.attribute_id
                WHERE a.attribute_name = ?
                """;
        try (PreparedStatement statement = cache.connection().prepareStatement(sql)) {
            statement.setString(1, attributeName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private long attributeId(ObjectCache cache, String pathKey) throws SQLException {
        try (PreparedStatement statement = cache.connection()
                        .prepareStatement("SELECT id FROM fom_attribute WHERE path_key = ?")) {
            statement.setString(1, pathKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }
}
