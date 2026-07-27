package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.cache.ValueResolution;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.injection.ObjectInjectionContext;
import com.yetanalytics.hlaxapi.injection.TestInjectionContext;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class ObjectInjectionHandlerTest {

    private static final String OBJECT_FOM = "src/test/resources/object-update-fom.xml";
    private static final String SIMULATION_FOM = "config/HlaFedereplFOM.xml";

    @Test
    void objectContextCarriesClassHandleAndIncomingAttributes() {
        byte[] count = HLAEncodingTestSupport.int32(4, ByteOrder.BIG_ENDIAN);
        ObjectInjectionContext context =
                new ObjectInjectionContext("TrackedEntity", "object-17", Map.of("Count", count));

        assertEquals("TrackedEntity", context.getHlaClass());
        assertEquals("object-17", context.getObjectHandle());
        assertSame(count, context.getAttributeMap().get("Count"));
    }

    @Test
    void decodesInheritedPrimitiveFixedRecordAndArrayPaths() {
        InjectionHandler handler = handler(OBJECT_FOM);
        byte[] position = position(12, 18);
        byte[] history = HLAEncodingTestSupport.variableArray(position(1, 2), position(3, 4));
        ObjectInjectionContext context = new ObjectInjectionContext(
                "TrackedEntity",
                "object-17",
                Map.of(
                        "EntityId", HLAEncodingTestSupport.asciiString("entity-17"),
                        "Position", position,
                        "PositionHistory", history));

        assertEquals(
                "entity-17",
                handler.handleTrigger(target("EntityId"), context).value());
        assertEquals(
                18,
                handler.handleTrigger(target("Position", "Y"), context).value());
        assertEquals(
                3,
                handler.handleTrigger(target("PositionHistory", 1, "X"), context).value());
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.InjectionHandler"})
    void reportsAbsentAndMalformedObjectAttributesAsMissingValues() {
        InjectionHandler handler = handler(OBJECT_FOM);

        ValueResolution absent = handler.handleTrigger(
                target("Count"),
                new ObjectInjectionContext("TrackedEntity", "object-17", Map.of()));
        ValueResolution malformed = handler.handleTrigger(
                target("Count"),
                new ObjectInjectionContext(
                        "TrackedEntity",
                        "object-17",
                        Map.of("Count", new byte[] {1})));

        assertEquals(ValueResolution.Status.MISSING_VALUE, absent.status());
        assertEquals(ValueResolution.Status.MISSING_VALUE, malformed.status());
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    void validatesObjectUpdateTargetsAgainstInheritedObjectAttributes() {
        TriggerProcessor processor = new TriggerProcessor(handler(OBJECT_FOM));
        StatementTrigger valid = trigger("""
                {"object":{"id":["trigger",["EntityId"]]}}
                """);
        StatementTrigger wrongType = trigger("""
                {"object":{"id":["trigger",["Count"]]}}
                """);
        TestInjectionContext context =
                new TestInjectionContext(StatementTrigger.Type.OBJECT_UPDATE, "TrackedEntity");

        TriggerProcessor.TriggerProcessingResult validResult =
                processor.renderTemplateForValidation(valid, context);
        TriggerProcessor.TriggerProcessingResult wrongTypeResult =
                processor.renderTemplateForValidation(wrongType, context);

        assertTrue(validResult.success());
        assertTrue(validResult.statement().contains("https://example.com/object"));
        assertFalse(wrongTypeResult.success());
    }

    @Test
    void interactionValidationRemainsTheDefault() {
        TriggerProcessor processor = new TriggerProcessor(handler(SIMULATION_FOM));
        StatementTrigger interaction = trigger("""
                {"result":{"score":{"raw":["trigger",["StepNumber"]]}}}
                """);

        TriggerProcessor.TriggerProcessingResult result = processor.renderTemplateForValidation(
                interaction,
                new TestInjectionContext("StepCompleted"));

        assertTrue(result.success());
        assertTrue(result.statement().contains("\"raw\":0.5"));
    }

    private InjectionHandler handler(String fomPath) {
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        FOMXML fomXml = new FOMXML(
                new SimulationConfig(null, null, null, null, fomPath),
                decoderRegistry);
        InjectionHandler handler = new InjectionHandler();
        handler.setFomXml(fomXml);
        handler.setHLADecoderRegistry(decoderRegistry);
        handler.setFomCatalog(new FomCatalog(fomXml));
        return handler;
    }

    private StatementTrigger trigger(String statement) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.OBJECT_UPDATE;
        trigger.clazz = "TrackedEntity";
        trigger.statement = statement;
        return trigger;
    }

    private Target target(Object... parts) {
        return new Target(List.of(parts));
    }

    private byte[] position(int x, int y) {
        return HLAEncodingTestSupport.fixedRecord(
                HLAEncodingTestSupport.int32(x, ByteOrder.BIG_ENDIAN),
                HLAEncodingTestSupport.int32(y, ByteOrder.BIG_ENDIAN));
    }
}
