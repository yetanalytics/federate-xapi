package com.yetanalytics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import com.yetanalytics.hlaxapi.HLAEncodingTestSupport;
import com.yetanalytics.hlaxapi.InjectionHandler;
import com.yetanalytics.hlaxapi.SimulationConfig;
import com.yetanalytics.hlaxapi.TriggerProcessor;
import com.yetanalytics.hlaxapi.cache.CachedObject;
import com.yetanalytics.hlaxapi.cache.ValueResolution;
import com.yetanalytics.hlaxapi.config.ConfigParser;
import com.yetanalytics.hlaxapi.config.ConfigVersions;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.ComparisonOperator;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.LookupExpression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.injection.InjectionContext;
import com.yetanalytics.hlaxapi.injection.InteractionInjectionContext;

import hla.rti1516e.ParameterHandle;
import hla.rti1516e.ParameterHandleValueMap;
import hla.rti1516e.encoding.ByteWrapper;

public class ConfigParserTest {

    private static final Logger logger = LogManager.getLogger(ConfigParserTest.class);

    final static String CONFIG_STATEMENT_RESULT = "{\"actor\":{\"objectType\":\"Agent\",\"name\":\"c5988e0e-c521-4ff7-ba83-df1a63eb72bf\",\"account\":{\"homePage\":\"https://homepage.system.io\",\"name\":\"Mr. c5988e0e-c521-4ff7-ba83-df1a63eb72bf\"}},\"context\":{\"extensions\":{\"https://yetanalytics.com/extensions/from-x\":4,\"https://yetanalytics.com/extensions/from-y\":12}},\"result\":{\"response\":\"[5, 13]\"}}";

    @Test
    public void parsesAndValidatesConfigVersionFromMemory() throws IOException {
        XapiConfig current = ConfigParser.fromJson("{\"configVersion\":\"1.0\"}").parse();
        XapiConfig legacy = ConfigParser.fromJson("{}").parse();

        assertEquals(ConfigVersions.CURRENT, current.configVersion);
        assertEquals(ConfigVersions.CURRENT, legacy.configVersion);
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigParser.fromJson("{\"configVersion\":\"2.0\"}").parse());
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigParser.fromJson("{\"configVersion\":1}").parse());
    }

    @Test
    public void parsesConfigFile() throws IOException {
        XapiConfig config = ConfigParser.fromFile("src/test/resources/config-test.json").parse();

        SimulationConfig simConfig = new SimulationConfig(null, null, null, null,
            "config/HlaFedereplFOM.xml");
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        InjectionHandler ih = new InjectionHandler();
        ih.setFomXml(new FOMXML(simConfig, decoderRegistry));
        ih.setHLADecoderRegistry(decoderRegistry);


        TriggerProcessor triggerProcessor = new TriggerProcessor(ih);

        Map<String, byte[]> paramMap = new HashMap<String, byte[]>();
        paramMap.put("EntityId", HLAEncodingTestSupport.asciiString("c5988e0e-c521-4ff7-ba83-df1a63eb72bf"));

        byte[] fromX = HLAEncodingTestSupport.int32(4, ByteOrder.BIG_ENDIAN);
        byte[] fromY = HLAEncodingTestSupport.int32(12, ByteOrder.BIG_ENDIAN);
        paramMap.put("FromPosition", HLAEncodingTestSupport.fixedRecord(fromX, fromY));

        byte[] toX = HLAEncodingTestSupport.int32(5, ByteOrder.BIG_ENDIAN);
        byte[] toY = HLAEncodingTestSupport.int32(13, ByteOrder.BIG_ENDIAN);
        paramMap.put("ToPosition", HLAEncodingTestSupport.fixedRecord(toX, toY));


        InteractionInjectionContext injectionContext = new InteractionInjectionContext("EntityMoved", paramMap);

        assertNotNull(config);
        assertNotNull(config.statementTriggers);
        assertEquals(1, config.statementTriggers.size());
        config.statementTriggers.forEach(trigger -> {
            assertNotNull(trigger.type);
            assertNotNull(trigger.criteria);
            assertNotNull(trigger.clazz);
            assertNotNull(trigger.statement);

            String statement = triggerProcessor.processTrigger(trigger, injectionContext).statement();
            assertEquals(statement, CONFIG_STATEMENT_RESULT);

        });

        assertEquals(config.lrsConfig.batch, 4);
        assertEquals(config.lrsConfig.host, "host string");
        assertEquals(config.lrsConfig.key, "key string");
        assertEquals(config.lrsConfig.secret, "secret string");
        assertNotNull(config.lrsConfig);
    }

    @Test
    public void parsesTriggerLookups(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("xapi-config.json");
        Files.writeString(configPath, """
                {
                    "statementTriggers": [
                        {
                            "type": "Interaction",
                            "class": "EntityAte",
                            "lookups": {
                                "predator": {
                                    "class": "SimEntity",
                                    "criteria": [["EntityId"], "=", ["trigger", ["PredatorId"]]]
                                }
                            },
                            "statement": {"actor": {"name": ["lookup", "predator", ["EntityType"]]}}
                        }
                    ]
                }
                """);

        XapiConfig config = ConfigParser.fromFile(configPath.toString()).parse();

        assertEquals(1, config.statementTriggers.size());
        ObjectLookup lookup = config.statementTriggers.get(0).lookups.get("predator");
        assertNotNull(lookup);
        assertEquals("SimEntity", lookup.clazz);
        assertTrue(lookup.criteria instanceof Criterion);
        Criterion criterion = (Criterion) lookup.criteria;
        assertTrue(criterion.left instanceof Target);
        assertTrue(criterion.right instanceof TriggerExpression);
    }

    @Test
    public void parsesObjectLifecycleTriggerTypes(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("object-lifecycle-config.json");
        Files.writeString(configPath, """
                {
                  "statementTriggers": [
                    {"type":"ObjectCreate","class":"SimEntity.Rabbit","statement":{}},
                    {"type":"objectUpdate","class":"SimEntity.Rabbit","statement":{}},
                    {"type":"OBJECTDELETE","class":"SimEntity.Rabbit","statement":{}}
                  ]
                }
                """);

        List<StatementTrigger.Type> types = ConfigParser.fromFile(configPath.toString()).parse()
                .statementTriggers.stream()
                .map(trigger -> trigger.type)
                .toList();

        assertEquals(
                List.of(
                        StatementTrigger.Type.OBJECT_CREATE,
                        StatementTrigger.Type.OBJECT_UPDATE,
                        StatementTrigger.Type.OBJECT_DELETE),
                types);
    }

    @Test
    public void parsesQueriesAndLookupsInTriggerCriteria(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("xapi-config.json");
        Files.writeString(configPath, """
                {
                  "statementTriggers": [{
                    "type": "Interaction",
                    "class": "EntityAte",
                    "lookups": {"subject": {"class": "SimEntity"}},
                    "criteria": [
                      ["query", "World", ["Size"], [["WorldId"], "=", ["trigger", ["WorldId"]]]],
                      ">",
                      ["lookup", "subject", ["MinimumWorldSize"]]
                    ],
                    "statement": {}
                  }]
                }
                """);

        Criterion criteria = (Criterion) ConfigParser.fromFile(configPath.toString()).parse()
                .statementTriggers.get(0).criteria;

        assertTrue(criteria.left instanceof QueryExpression);
        assertTrue(criteria.right instanceof LookupExpression);
    }

    @Test
    public void parsesPreviousCriteriaOnlyForObjectUpdate(@TempDir Path tempDir) throws IOException {
        Path validPath = tempDir.resolve("valid-previous.json");
        Files.writeString(validPath, """
                {
                  "statementTriggers": [{
                    "type": "ObjectUpdate",
                    "class": "SimEntity.Rabbit",
                    "criteria": [["previous", ["Hunger"]], "<", ["trigger", ["Hunger"]]],
                    "statement": {}
                  }]
                }
                """);

        assertNotNull(ConfigParser.fromFile(validPath.toString()).parse()
                .statementTriggers.get(0).criteria);

        for (String type : List.of("Interaction", "ObjectCreate", "ObjectDelete")) {
            Path invalidPath = tempDir.resolve(type + "-previous.json");
            Files.writeString(invalidPath, """
                    {
                      "statementTriggers": [{
                        "type": "%s",
                        "class": "Rabbit",
                        "criteria": [["previous", ["Hunger"]], "<", 10],
                        "statement": {}
                      }]
                    }
                    """.formatted(type));

            assertThrows(
                    IllegalArgumentException.class,
                    () -> ConfigParser.fromFile(invalidPath.toString()).parse(),
                    type);
        }
    }

    @Test
    public void rejectsBareEventTargetsInTriggerCriteria(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("xapi-config.json");
        Files.writeString(configPath, """
                {
                  "statementTriggers": [{
                    "type": "Interaction",
                    "class": "EntityAte",
                    "criteria": [["Score"], ">", 10],
                    "statement": {}
                  }]
                }
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigParser.fromFile(configPath.toString()).parse());

        assertTrue(error.getMessage().contains("statementTriggers[0].criteria"));
        assertTrue(error.getMessage().contains("use [\"trigger\""));
    }

    @Test
    public void parsesObjectCacheTrackedObjects(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("xapi-config.json");
        Files.writeString(configPath, """
                {
                    "objectCache": {
                        "trackedObjects": [
                            {"class": "SimEntity.Rabbit", "attributes": ["EntityId", "Hunger"]},
                            {"class": "World", "allAttributes": true},
                            {"class": "*", "allAttributes": true}
                        ]
                    }
                }
                """);

        XapiConfig config = ConfigParser.fromFile(configPath.toString()).parse();

        assertNotNull(config.objectCacheConfig);
        assertNotNull(config.objectCacheConfig.trackedObjects);
        assertEquals(3, config.objectCacheConfig.trackedObjects.size());
        assertEquals("SimEntity.Rabbit", config.objectCacheConfig.trackedObjects.get(0).clazz);
        assertEquals(List.of("EntityId", "Hunger"), config.objectCacheConfig.trackedObjects.get(0).attributes);
        assertTrue(config.objectCacheConfig.trackedObjects.get(1).allAttributes);
        assertEquals("*", config.objectCacheConfig.trackedObjects.get(2).clazz);
        assertTrue(config.objectCacheConfig.trackedObjects.get(2).allAttributes);
    }

    @Test
    public void fixedRecordHelperConcatenatesEncodedFieldsInOrder() {
        byte[] first = HLAEncodingTestSupport.int32(12, ByteOrder.BIG_ENDIAN);
        byte[] second = HLAEncodingTestSupport.asciiString("abc");

        byte[] record = HLAEncodingTestSupport.fixedRecord(first, second);
        byte[] expected = ByteBuffer.allocate(first.length + second.length)
                .order(ByteOrder.BIG_ENDIAN)
                .put(first)
                .put(second)
                .array();

        assertArrayEquals(expected, record);
    }

    @Test
    public void handlesFixedRecordFieldAccess() {
        SimulationConfig simConfig = new SimulationConfig(null, null, null, null,
                "config/HlaFedereplFOM.xml");
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        InjectionHandler ih = new InjectionHandler();
        ih.setFomXml(new FOMXML(simConfig, decoderRegistry));
        ih.setHLADecoderRegistry(decoderRegistry);

        byte[] gridPosition = java.nio.ByteBuffer.allocate(Integer.BYTES * 2)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putInt(42)
                .putInt(84)
                .array();

        Map<String, byte[]> paramMap = new java.util.HashMap<>();
        paramMap.put("FromPosition", gridPosition);

        InteractionInjectionContext injectionContext = new InteractionInjectionContext("EntityMoved", paramMap);
        com.yetanalytics.hlaxapi.config.model.Target target = new com.yetanalytics.hlaxapi.config.model.Target(java.util.List.of("FromPosition", "X"));

        ValueResolution result = ih.handleTrigger(target, injectionContext);
        assertEquals(42, result.value());
    }

    @Test
    public void handlesFixedRecordFieldAccessInsideArray() {
        // Test array of fixed records
        SimulationConfig simConfig = new SimulationConfig(null, null, null, null,
                "config/HlaFedereplFOM.xml");
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        InjectionHandler ih = new InjectionHandler();
        ih.setFomXml(new FOMXML(simConfig, decoderRegistry));
        ih.setHLADecoderRegistry(decoderRegistry);

        byte[] gridPosition = java.nio.ByteBuffer.allocate(Integer.BYTES * 2)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putInt(42)
                .putInt(84)
                .array();

        byte[] arrayBytes = HLAEncodingTestSupport.variableArray(gridPosition);

        Map<String, byte[]> paramMap = new java.util.HashMap<>();
        paramMap.put("LocationHistory", arrayBytes);

        InteractionInjectionContext injectionContext = new InteractionInjectionContext("EntityMoved", paramMap);
        com.yetanalytics.hlaxapi.config.model.Target target = new com.yetanalytics.hlaxapi.config.model.Target(
            java.util.List.of("LocationHistory", 0, "X"));

        ValueResolution result = ih.handleTrigger(target, injectionContext);
        assertEquals(42, result.value());
    }

    @Test
    public void handlesFixedRecordGridPositionFieldAccess() {
        SimulationConfig simConfig = new SimulationConfig(null, null, null, null,
                "config/HlaFedereplFOM.xml");
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        InjectionHandler ih = new InjectionHandler();
        ih.setFomXml(new FOMXML(simConfig, decoderRegistry));
        ih.setHLADecoderRegistry(decoderRegistry);

        byte[] gridPosition = java.nio.ByteBuffer.allocate(Integer.BYTES * 2)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putInt(5)
                .putInt(7)
                .array();

        Map<String, byte[]> paramMap = new java.util.HashMap<>();
        paramMap.put("ToPosition", gridPosition);

        InteractionInjectionContext injectionContext = new InteractionInjectionContext("EntityMoved", paramMap);
        com.yetanalytics.hlaxapi.config.model.Target xTarget = new com.yetanalytics.hlaxapi.config.model.Target(java.util.List.of("ToPosition", "X"));
        com.yetanalytics.hlaxapi.config.model.Target yTarget = new com.yetanalytics.hlaxapi.config.model.Target(java.util.List.of("ToPosition", "Y"));

        ValueResolution xResult = ih.handleTrigger(xTarget, injectionContext);
        ValueResolution yResult = ih.handleTrigger(yTarget, injectionContext);

        assertEquals(5, xResult.value());
        assertEquals(7, yResult.value());
    }

    @Test
    public void inlinePlaceholderProcessing() throws IOException {
        // Setup using HlaFedereplFOM
        SimulationConfig simConfig = new SimulationConfig(null, null, null, null,
                "config/HlaFedereplFOM.xml");
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        InjectionHandler ih = new InjectionHandler();
        ih.setFomXml(new FOMXML(simConfig, decoderRegistry));
        ih.setHLADecoderRegistry(decoderRegistry);

        TriggerProcessor triggerProcessor = new TriggerProcessor(ih);

        Map<String, byte[]> paramMap = new HashMap<String, byte[]>();
        paramMap.put("EntityId", HLAEncodingTestSupport.asciiString("entity-uuid-123"));

        InteractionInjectionContext injectionContext = new InteractionInjectionContext("EntityMoved", paramMap);

        // Statement contains an inline placeholder in a string using <<...>> containing JSON array
        String stmt = "{\"actor\":{\"name\":\"predator-<<[\\\"trigger\\\", [\\\"EntityId\\\"]]>>-prey\"}}";

        com.yetanalytics.hlaxapi.config.model.StatementTrigger st = new com.yetanalytics.hlaxapi.config.model.StatementTrigger();
        st.type = StatementTrigger.Type.INTERACTION;
        st.statement = stmt;

        String out = triggerProcessor.processTrigger(st, injectionContext).statement();
        assertNotNull(out);
        // Expect the placeholder to be replaced by the parameter value inside the string
        assertTrue(out.contains("predator-entity-uuid-123-prey"));
    }

    @Test
    public void inlinePlaceholderProcessingHandlesMultiplePlaceholders() throws IOException {
        SimulationConfig simConfig = new SimulationConfig(null, null, null, null,
            "config/HlaFedereplFOM.xml");
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        InjectionHandler ih = new InjectionHandler();
        ih.setFomXml(new FOMXML(simConfig, decoderRegistry));
        ih.setHLADecoderRegistry(decoderRegistry);

        TriggerProcessor triggerProcessor = new TriggerProcessor(ih);

        Map<String, byte[]> paramMap = new java.util.HashMap<String, byte[]>();
        paramMap.put("EntityId", HLAEncodingTestSupport.asciiString("entity-001"));

        InteractionInjectionContext injectionContext = new InteractionInjectionContext("EntityMoved", paramMap);

        String stmt = "{\"actor\":{\"name\":\"from=<<[\\\"trigger\\\", [\\\"EntityId\\\"]]>>, to=<<[\\\"trigger\\\", [\\\"EntityId\\\"]]>>\"}}";
        com.yetanalytics.hlaxapi.config.model.StatementTrigger st = new com.yetanalytics.hlaxapi.config.model.StatementTrigger();
        st.type = StatementTrigger.Type.INTERACTION;
        st.statement = stmt;

        String out = triggerProcessor.processTrigger(st, injectionContext).statement();
        assertNotNull(out);
        assertTrue(out.contains("from=entity-001, to=entity-001"));
    }

    @Test
    public void inlinePlaceholderKeepsWholeValueAsStringWhenInjectionReturnsStructuredData() {
        InjectionHandler ih = new InjectionHandler() {
            @Override
            public ValueResolution handleTrigger(Target t, InjectionContext context) {
                return ValueResolution.present(List.of("alpha", "beta"));
            }
        };

        TriggerProcessor triggerProcessor = new TriggerProcessor(ih);
        InteractionInjectionContext injectionContext = new InteractionInjectionContext("CyberEvent", new HashMap<>());

        String stmt = "{\"actor\":{\"name\":\"<<[\\\"trigger\\\", [\\\"Description\\\"]]>>\"}}";
        com.yetanalytics.hlaxapi.config.model.StatementTrigger st = new com.yetanalytics.hlaxapi.config.model.StatementTrigger();
        st.type = StatementTrigger.Type.INTERACTION;
        st.statement = stmt;

        String out = triggerProcessor.processTrigger(st, injectionContext).statement();
        assertNotNull(out);
        assertTrue(out.contains("\"name\":\"[alpha, beta]\""));
    }

    @Test
    public void exposesStatementPathForWholeNodeInjections() {
        List<List<Object>> paths = new ArrayList<>();
        InjectionHandler ih = new InjectionHandler() {
            @Override
            public ValueResolution handleTrigger(Target t, InjectionContext context) {
                paths.add(context.getStatementPath());
                return ValueResolution.present("value");
            }
        };
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.INTERACTION;
        trigger.statement = """
                {
                  "actor": {"name": ["trigger", ["Name"]]},
                  "context": {"extensions": {"https://yetanalytics.com/extensions/from-x": [["trigger", ["FromPosition", "X"]], 4]}
                  }
                }
                """;

        String output = new TriggerProcessor(ih).processTrigger(
                trigger,
                new InteractionInjectionContext("EntityAte", new HashMap<>()))
                .statement();

        assertNotNull(output);
        assertEquals(List.of(
                List.of("actor", "name"),
                List.of("context", "extensions", "https://yetanalytics.com/extensions/from-x", 0)), paths);
    }

    @Test
    public void lookupInjectionResolvesAliasOnceAndReusesObject() {
        AtomicInteger resolveCount = new AtomicInteger();
        CachedObject matchedObject = new CachedObject(7, "object-7", "Predator", "SimEntity");
        InjectionHandler ih = new InjectionHandler() {
            @Override
            public Optional<CachedObject> resolveLookup(ObjectLookup lookup, InjectionContext context) {
                resolveCount.incrementAndGet();
                return Optional.of(matchedObject);
            }

            @Override
            public ValueResolution handleLookup(CachedObject object, Target attrTarget, InjectionContext context) {
                assertEquals(matchedObject, object);
                if (attrTarget.parts.equals(List.of("EntityId"))) {
                    return ValueResolution.present("predator-1");
                }
                if (attrTarget.parts.equals(List.of("EntityType"))) {
                    return ValueResolution.present("Wolf");
                }
                return ValueResolution.missingValue();
            }
        };

        TriggerProcessor triggerProcessor = new TriggerProcessor(ih);
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.INTERACTION;
        ObjectLookup lookup = new ObjectLookup();
        lookup.clazz = "SimEntity";
        lookup.criteria = new Criterion(
                new Target(List.of("EntityId")),
                ComparisonOperator.EQ,
                new TriggerExpression(new Target(List.of("PredatorId"))));
        trigger.lookups = Map.of("predator", lookup);
        trigger.statement = """
                {
                    "actor": {
                        "account": {"name": ["lookup", "predator", ["EntityId"]]},
                        "name": "the <<[\\"lookup\\", \\"predator\\", [\\"EntityType\\"]]>>"
                    }
                }
                """;

        String out = triggerProcessor.processTrigger(
                trigger,
                new InteractionInjectionContext("EntityAte", new HashMap<>()))
                .statement();

        assertNotNull(out);
        assertEquals(1, resolveCount.get());
        assertTrue(out.contains("\"name\":\"predator-1\""));
        assertTrue(out.contains("\"name\":\"the Wolf\""));
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    public void lookupInjectionMissingObjectHonorsRequiredOption() {
        InjectionHandler ih = new InjectionHandler() {
            @Override
            public Optional<CachedObject> resolveLookup(ObjectLookup lookup, InjectionContext context) {
                return Optional.empty();
            }
        };

        StatementTrigger trigger = lookupTrigger("[\"lookup\", \"predator\", [\"EntityId\"]]");

        String out = new TriggerProcessor(ih).processTrigger(
                trigger,
                new InteractionInjectionContext("EntityAte", new HashMap<>()))
                .statement();

        assertNull(out);

        String optionalOut = new TriggerProcessor(ih).processTrigger(
                lookupTrigger("[\"lookup\", \"predator\", [\"EntityId\"], {\"required\": false}]"),
                new InteractionInjectionContext("EntityAte", new HashMap<>()))
                .statement();

        assertNotNull(optionalOut);
        assertTrue(optionalOut.contains("\"name\":null"));
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    public void lookupInjectionMissingValueHonorsRequiredButNotNullable() {
        CachedObject matchedObject = new CachedObject(7, "object-7", "Predator", "SimEntity");
        InjectionHandler ih = new InjectionHandler() {
            @Override
            public Optional<CachedObject> resolveLookup(ObjectLookup lookup, InjectionContext context) {
                return Optional.of(matchedObject);
            }

            @Override
            public ValueResolution handleLookup(CachedObject object, Target attrTarget, InjectionContext context) {
                return ValueResolution.missingValue();
            }
        };

        StatementTrigger trigger = lookupTrigger("[\"lookup\", \"predator\", [\"Nickname\"], {\"nullable\": true}]");

        String out = new TriggerProcessor(ih).processTrigger(
                trigger,
                new InteractionInjectionContext("EntityAte", new HashMap<>()))
                .statement();

        assertNull(out);

        StatementTrigger optionalTrigger = lookupTrigger(
                "[\"lookup\", \"predator\", [\"Nickname\"], {\"required\": false}]");
        String optionalOut = new TriggerProcessor(ih).processTrigger(
                optionalTrigger,
                new InteractionInjectionContext("EntityAte", new HashMap<>()))
                .statement();

        assertNotNull(optionalOut);
        assertTrue(optionalOut.contains("\"name\":null"));
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    public void lookupInjectionPresentNullAbortsByDefaultAndRendersWhenNullable() {
        CachedObject matchedObject = new CachedObject(7, "object-7", "Predator", "SimEntity");
        InjectionHandler ih = new InjectionHandler() {
            @Override
            public Optional<CachedObject> resolveLookup(ObjectLookup lookup, InjectionContext context) {
                return Optional.of(matchedObject);
            }

            @Override
            public ValueResolution handleLookup(CachedObject object, Target attrTarget, InjectionContext context) {
                return ValueResolution.present(null);
            }
        };
        TriggerProcessor triggerProcessor = new TriggerProcessor(ih);

        assertNull(triggerProcessor.processTrigger(
                lookupTrigger("[\"lookup\", \"predator\", [\"Nickname\"]]"),
                new InteractionInjectionContext("EntityAte", new HashMap<>())).statement());

        StatementTrigger nullableTrigger = lookupTrigger(
                "\"the <<[\\\"lookup\\\", \\\"predator\\\", [\\\"Nickname\\\"], {\\\"nullable\\\": true}]>>\"");
        String out = triggerProcessor.processTrigger(
                nullableTrigger,
                new InteractionInjectionContext("EntityAte", new HashMap<>()))
                .statement();

        assertNotNull(out);
        assertTrue(out.contains("\"name\":\"the null\""));

        StatementTrigger optionalTrigger = lookupTrigger(
                "\"optional <<[\\\"lookup\\\", \\\"predator\\\", [\\\"Nickname\\\"], {\\\"required\\\": false}]>>\"");
        String optionalOut = triggerProcessor.processTrigger(
                optionalTrigger,
                new InteractionInjectionContext("EntityAte", new HashMap<>()))
                .statement();

        assertNotNull(optionalOut);
        assertTrue(optionalOut.contains("\"name\":\"optional null\""));
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    public void queryAndThisMissingValuesHonorRequiredOption() {
        InjectionHandler ih = new InjectionHandler() {
            @Override
            public ValueResolution handleQuery(
                    String clazz,
                    Target attrTarget,
                    Expression criteria,
                    InjectionContext context) {
                return ValueResolution.missingObject();
            }

            @Override
            public ValueResolution handleTrigger(Target t, InjectionContext context) {
                return ValueResolution.missingValue();
            }
        };
        TriggerProcessor triggerProcessor = new TriggerProcessor(ih);

        assertNull(triggerProcessor.processTrigger(
                statementTrigger("{\"actor\":{\"name\":[\"query\",\"Rabbit\",[\"EntityId\"],[[\"Hunger\"],\">\",50]]}}"),
                new InteractionInjectionContext("EntityAte", new HashMap<>())).statement());
        assertNull(triggerProcessor.processTrigger(
                statementTrigger("{\"actor\":{\"name\":[\"trigger\",[\"MissingParam\"]]}}"),
                new InteractionInjectionContext("EntityAte", new HashMap<>())).statement());

        String optionalQueryOut = triggerProcessor.processTrigger(
                statementTrigger(
                        "{\"actor\":{\"name\":[\"query\",\"Rabbit\",[\"EntityId\"],[[\"Hunger\"],\">\",50],{\"required\":false}]}}"),
                new InteractionInjectionContext("EntityAte", new HashMap<>())).statement();
        String optionalThisOut = triggerProcessor.processTrigger(
                statementTrigger(
                        "{\"actor\":{\"name\":\"value=<<[\\\"trigger\\\",[\\\"MissingParam\\\"],{\\\"required\\\":false}]>>\"}}"),
                new InteractionInjectionContext("EntityAte", new HashMap<>())).statement();

        assertNotNull(optionalQueryOut);
        assertTrue(optionalQueryOut.contains("\"name\":null"));
        assertNotNull(optionalThisOut);
        assertTrue(optionalThisOut.contains("\"name\":\"value=null\""));
    }

    private StatementTrigger lookupTrigger(String nameExpression) {
        StatementTrigger trigger = statementTrigger("{\"actor\":{\"name\":" + nameExpression + "}}");
        ObjectLookup lookup = new ObjectLookup();
        lookup.clazz = "SimEntity";
        lookup.criteria = new Criterion(
                new Target(List.of("EntityId")),
                ComparisonOperator.EQ,
                new TriggerExpression(new Target(List.of("PredatorId"))));
        trigger.lookups = Map.of("predator", lookup);
        return trigger;
    }

    private StatementTrigger statementTrigger(String statement) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.INTERACTION;
        trigger.statement = statement;
        return trigger;
    }

    // Stub implementations of HLA ParameterHandle and ParameterHandleValueMap for
    // testing
    class TestParameterHandle implements ParameterHandle {
        private final String name;

        TestParameterHandle(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TestParameterHandle
                    && name.equals(((TestParameterHandle) o).name);
        }

        @Override
        public int encodedLength() {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'encodedLength'");
        }

        @Override
        public void encode(byte[] buffer, int offset) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'encode'");
        }
    }

    class TestParameterHandleValueMap extends java.util.HashMap<ParameterHandle, byte[]>
            implements ParameterHandleValueMap {

        @Override
        public ByteWrapper getValueReference(ParameterHandle key) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'getValueReference'");
        }

        @Override
        public ByteWrapper getValueReference(ParameterHandle key, ByteWrapper byteWrapper) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'getValueReference'");
        }
    }
}
