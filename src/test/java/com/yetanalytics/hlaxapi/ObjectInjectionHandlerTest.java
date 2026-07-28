package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.cache.ValueResolution;
import com.yetanalytics.hlaxapi.config.model.ComparisonOperator;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
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
    @SuppressTestLogging({
        "com.yetanalytics.hlaxapi.InjectionHandler",
        "com.yetanalytics.hlaxapi.TriggerProcessor"
    })
    void optionalMalformedNestedValuesRenderNullForUpdateAndDelete() {
        TriggerProcessor processor = new TriggerProcessor(handler(OBJECT_FOM));
        Map<String, byte[]> malformedAttributes = Map.of(
                "Position", new byte[] {1},
                "PositionHistory", new byte[] {0, 0, 0, 1, 1});

        for (StatementTrigger.Type type : List.of(
                StatementTrigger.Type.OBJECT_UPDATE,
                StatementTrigger.Type.OBJECT_DELETE)) {
            assertOptionalMalformedValue(
                    processor,
                    type,
                    "[\"Position\",\"X\"]",
                    malformedAttributes);
            assertOptionalMalformedValue(
                    processor,
                    type,
                    "[\"PositionHistory\",0,\"X\"]",
                    malformedAttributes);
        }
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    void validatesEveryObjectEventTargetAgainstInheritedObjectAttributes() {
        TriggerProcessor processor = new TriggerProcessor(handler(OBJECT_FOM));
        for (StatementTrigger.Type type : List.of(
                StatementTrigger.Type.OBJECT_CREATE,
                StatementTrigger.Type.OBJECT_UPDATE,
                StatementTrigger.Type.OBJECT_DELETE)) {
            StatementTrigger valid = trigger("""
                    {"object":{"id":["trigger",["EntityId"]]}}
                    """);
            valid.type = type;
            StatementTrigger wrongType = trigger("""
                    {"object":{"id":["trigger",["Count"]]}}
                    """);
            wrongType.type = type;
            TestInjectionContext context = new TestInjectionContext(type, "TrackedEntity");

            TriggerProcessor.TriggerProcessingResult validResult =
                    processor.renderTemplateForValidation(valid, context);
            TriggerProcessor.TriggerProcessingResult wrongTypeResult =
                    processor.renderTemplateForValidation(wrongType, context);

            assertTrue(validResult.success());
            assertTrue(validResult.statement().contains("https://example.com/object"));
            assertFalse(wrongTypeResult.success());
        }
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    void rejectsMissingObjectTargetsInStatementsAndCriteriaEvenWhenOptional() {
        TriggerProcessor processor = new TriggerProcessor(handler(OBJECT_FOM));
        TestInjectionContext context =
                new TestInjectionContext(StatementTrigger.Type.OBJECT_UPDATE, "TrackedEntity");
        StatementTrigger missingTrigger = trigger("""
                {"missing":["trigger",["NotAnAttribute"],{"required":false}]}
                """);
        StatementTrigger missingPrevious = trigger("""
                {"missing":["previous",["NotAnAttribute"],{"required":false}]}
                """);
        StatementTrigger missingTriggerCriterion = trigger("{}", new Criterion(
                new TriggerExpression(target("NotAnAttribute")),
                ComparisonOperator.EQ,
                new ValueExpression(1)));
        StatementTrigger missingPreviousCriterion = trigger("{}", new Criterion(
                new PreviousExpression(target("NotAnAttribute")),
                ComparisonOperator.EQ,
                new ValueExpression(1)));

        assertFalse(processor.renderTemplateForValidation(missingTrigger, context).success());
        assertFalse(processor.renderTemplateForValidation(missingPrevious, context).success());
        assertFalse(processor.renderTemplateForValidation(missingTriggerCriterion, context).success());
        assertFalse(processor.renderTemplateForValidation(missingPreviousCriterion, context).success());
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    void validatesQueryAndLookupPathsAgainstTheirReferencedObjectClasses() {
        TriggerProcessor processor = new TriggerProcessor(handler(OBJECT_FOM));
        TestInjectionContext context =
                new TestInjectionContext(StatementTrigger.Type.OBJECT_UPDATE, "TrackedEntity");
        StatementTrigger valid = trigger("""
                {
                  "result":{"score":{"raw":["query","BaseEntity",["Position","X"],null]}},
                  "lookup":["lookup","base",["EntityId"]]
                }
                """);
        valid.lookups = Map.of("base", lookup("BaseEntity", new Criterion(
                target("Position", "Y"),
                ComparisonOperator.GT,
                new ValueExpression(0))));
        StatementTrigger wrongQueryDatatype = trigger("""
                {"result":{"score":{"raw":["query","BaseEntity",["EntityId"],null]}}}
                """);
        StatementTrigger missingQueryTarget = trigger("""
                {"value":["query","BaseEntity",["NotAnAttribute"],null,{"required":false}]}
                """);
        StatementTrigger missingQueryCriterion = trigger("""
                {"value":["query","BaseEntity",["EntityId"],[["NotAnAttribute"],"=",1]]}
                """);
        StatementTrigger missingLookupTarget = trigger("""
                {"value":["lookup","base",["NotAnAttribute"],{"required":false}]}
                """);
        missingLookupTarget.lookups = Map.of("base", lookup("BaseEntity", null));
        StatementTrigger missingLookupCriterion = trigger("{}");
        missingLookupCriterion.lookups = Map.of("base", lookup("BaseEntity", new Criterion(
                target("NotAnAttribute"),
                ComparisonOperator.EQ,
                new ValueExpression(1))));

        assertTrue(processor.renderTemplateForValidation(valid, context).success());
        assertFalse(processor.renderTemplateForValidation(wrongQueryDatatype, context).success());
        assertFalse(processor.renderTemplateForValidation(missingQueryTarget, context).success());
        assertFalse(processor.renderTemplateForValidation(missingQueryCriterion, context).success());
        assertFalse(processor.renderTemplateForValidation(missingLookupTarget, context).success());
        assertFalse(processor.renderTemplateForValidation(missingLookupCriterion, context).success());
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    void interactionValidationRemainsTheDefault() {
        TriggerProcessor processor = new TriggerProcessor(handler(SIMULATION_FOM));
        StatementTrigger interaction = trigger("""
                {"result":{"score":{"raw":["trigger",["StepNumber"]]}}}
                """);
        interaction.type = StatementTrigger.Type.INTERACTION;
        interaction.clazz = "StepCompleted";

        TriggerProcessor.TriggerProcessingResult result = processor.renderTemplateForValidation(
                interaction,
                new TestInjectionContext("StepCompleted"));
        StatementTrigger missing = trigger("""
                {"missing":["trigger",["NotAParameter"],{"required":false}]}
                """);
        missing.type = StatementTrigger.Type.INTERACTION;
        missing.clazz = "StepCompleted";

        assertTrue(result.success());
        assertTrue(result.statement().contains("\"raw\":0.5"));
        assertFalse(processor.renderTemplateForValidation(
                missing,
                new TestInjectionContext("StepCompleted")).success());
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    void validatesPreviousOnlyForObjectUpdateTemplates() {
        TriggerProcessor processor = new TriggerProcessor(handler(OBJECT_FOM));
        for (StatementTrigger.Type type : List.of(
                StatementTrigger.Type.OBJECT_UPDATE,
                StatementTrigger.Type.INTERACTION,
                StatementTrigger.Type.OBJECT_CREATE,
                StatementTrigger.Type.OBJECT_DELETE)) {
            StatementTrigger trigger = trigger("""
                    {"oldCount":["previous",["Count"]]}
                    """);
            trigger.type = type;

            TriggerProcessor.TriggerProcessingResult result =
                    processor.renderTemplateForValidation(
                            trigger,
                            new TestInjectionContext(type, "TrackedEntity"));

            assertEquals(type == StatementTrigger.Type.OBJECT_UPDATE, result.success(), type.toString());
        }
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
        return trigger(statement, null);
    }

    private StatementTrigger trigger(
            String statement,
            Expression criteria) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.OBJECT_UPDATE;
        trigger.clazz = "TrackedEntity";
        trigger.criteria = criteria;
        trigger.statement = statement;
        return trigger;
    }

    private ObjectLookup lookup(
            String className,
            Expression criteria) {
        ObjectLookup lookup = new ObjectLookup();
        lookup.clazz = className;
        lookup.criteria = criteria;
        return lookup;
    }

    private void assertOptionalMalformedValue(
            TriggerProcessor processor,
            StatementTrigger.Type type,
            String target,
            Map<String, byte[]> attributes) {
        StatementTrigger trigger = trigger("""
                {"value":["trigger",%s,{"required":false}]}
                """.formatted(target));
        trigger.type = type;

        TriggerProcessor.TriggerProcessingResult result = processor.processTrigger(
                trigger,
                new ObjectInjectionContext("TrackedEntity", "object-17", attributes));

        assertTrue(result.success(), type + " " + target);
        assertEquals("{\"value\":null}", result.statement(), type + " " + target);
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
