package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.TriggerProcessor.TriggerProcessingResult;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.injection.InteractionInjectionContext;
import com.yetanalytics.hlaxapi.injection.ObjectCreateInjectionContext;
import com.yetanalytics.hlaxapi.injection.ObjectDeleteInjectionContext;
import com.yetanalytics.hlaxapi.injection.ObjectUpdateInjectionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class TriggerProcessorDispatchTest {

    private final FomCatalog catalog = new FomCatalog(new FOMXML(
            new SimulationConfig(null, null, null, null, "config/HlaFedereplFOM.xml"),
            new HLADecoderRegistry(new HLA1516eEncoderFactory())));

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    void matchesExactlyStagesOnceAndIsolatesProcessingAndEnqueueFailures() {
        StatementTrigger first = trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity.Rabbit", "first");
        StatementTrigger wrongType = trigger(StatementTrigger.Type.INTERACTION, "SimEntity.Rabbit", "wrong-type");
        StatementTrigger wrongClass = trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity.Wolf", "wrong-class");
        StatementTrigger skipped = trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity.Rabbit", "skip");
        StatementTrigger failed = trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity.Rabbit", "fail");
        StatementTrigger throwsException = trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity.Rabbit", "throw");
        StatementTrigger second = trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity.Rabbit", "second");
        XapiConfig config = new XapiConfig();
        config.statementTriggers =
                List.of(first, wrongType, wrongClass, skipped, failed, throwsException, second);
        ControlledTriggerProcessor processor = new ControlledTriggerProcessor(config, catalog);

        List<TriggerProcessor.StagedStatement> staged = processor.stage(
                new ObjectUpdateInjectionContext("SimEntity.Rabbit", "object-1", Map.of()));

        assertEquals(List.of("first", "second"),
                staged.stream().map(TriggerProcessor.StagedStatement::statement).toList());
        assertEquals(List.of("first", "skip", "fail", "throw", "second"), processor.processed);

        List<String> enqueued = new ArrayList<>();
        processor.enqueue(staged, statement -> {
            if ("first".equals(statement)) {
                throw new IllegalStateException("first enqueue failed");
            }
            enqueued.add(statement);
        });

        assertEquals(List.of("second"), enqueued);
    }

    @Test
    void interactionEventsUseTheSameProcessorWithoutMatchingObjectTriggers() {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity.Rabbit", "object"),
                trigger(StatementTrigger.Type.INTERACTION, "SimEntity", "ancestor-interaction"),
                trigger(StatementTrigger.Type.INTERACTION, "SimEntity.Rabbit", "interaction"));
        TriggerProcessor processor = new ControlledTriggerProcessor(config, catalog);
        List<String> enqueued = new ArrayList<>();

        processor.dispatch(
                new InteractionInjectionContext("SimEntity.Rabbit", Map.of()),
                enqueued::add);

        assertEquals(List.of("interaction"), enqueued);
    }

    @Test
    void lifecycleEventsMatchTheirTypeAndFomHierarchy() {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(
                trigger(StatementTrigger.Type.OBJECT_CREATE, "SimEntity", "sim-entity-create"),
                trigger(StatementTrigger.Type.OBJECT_CREATE, "SimEntity.Rabbit", "rabbit-create"),
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity.Rabbit", "rabbit-update"),
                trigger(StatementTrigger.Type.OBJECT_DELETE, "SimEntity", "sim-entity-delete"),
                trigger(StatementTrigger.Type.OBJECT_DELETE, "SimEntity.Rabbit", "rabbit-delete"),
                trigger(StatementTrigger.Type.OBJECT_DELETE, "SimEntity.Wolf", "wolf-delete"));
        TriggerProcessor processor = new ControlledTriggerProcessor(config, catalog);
        List<String> createStatements = processor
                .stage(new ObjectCreateInjectionContext("SimEntity.Rabbit", "object-1", Map.of()))
                .stream()
                .map(TriggerProcessor.StagedStatement::statement)
                .toList();
        List<String> deleteStatements = processor
                .stage(new ObjectDeleteInjectionContext("SimEntity.Rabbit", "object-1", Map.of()))
                .stream()
                .map(TriggerProcessor.StagedStatement::statement)
                .toList();

        assertEquals(
                List.of("sim-entity-create", "rabbit-create"),
                createStatements);
        assertEquals(
                List.of("sim-entity-delete", "rabbit-delete"),
                deleteStatements);
    }

    @Test
    void objectUpdateForBaseClassMatchesConcreteDescendant() {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity", "sim-entity-update"),
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity.Rabbit", "rabbit-update"),
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "SimEntity.Wolf", "wolf-update"),
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "MissingObject", "unknown-update"));
        TriggerProcessor processor = new ControlledTriggerProcessor(config, catalog);
        ObjectUpdateInjectionContext rabbit =
                new ObjectUpdateInjectionContext("SimEntity.Rabbit", "object-1", Map.of());

        List<String> updateStatements = processor
                .stage(rabbit)
                .stream()
                .map(TriggerProcessor.StagedStatement::statement)
                .toList();

        assertEquals(
                List.of("sim-entity-update", "rabbit-update"),
                updateStatements);
    }

    @Test
    void runtimeContextTypesAreFixed() {
        assertEquals(
                StatementTrigger.Type.INTERACTION,
                new InteractionInjectionContext().eventType());
        assertEquals(
                StatementTrigger.Type.OBJECT_CREATE,
                new ObjectCreateInjectionContext().eventType());
        assertEquals(
                StatementTrigger.Type.OBJECT_UPDATE,
                new ObjectUpdateInjectionContext().eventType());
        assertEquals(
                StatementTrigger.Type.OBJECT_DELETE,
                new ObjectDeleteInjectionContext().eventType());
    }

    @Test
    void rejectsTriggerAndContextTypeMismatch() {
        TriggerProcessingResult result = new TriggerProcessor(new InjectionHandler()).processTrigger(
                trigger(StatementTrigger.Type.OBJECT_DELETE, "SimEntity.Rabbit", "{}"),
                new InteractionInjectionContext("SimEntity.Rabbit", Map.of()));

        assertFalse(result.success());
        assertInstanceOf(IllegalArgumentException.class, result.error());
    }

    private StatementTrigger trigger(StatementTrigger.Type type, String className, String statement) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = type;
        trigger.clazz = className;
        trigger.statement = statement;
        return trigger;
    }

    private static final class ControlledTriggerProcessor extends TriggerProcessor {

        private final List<String> processed = new ArrayList<>();

        private ControlledTriggerProcessor(XapiConfig config, FomCatalog catalog) {
            super(config, new InjectionHandler(), catalog);
        }

        @Override
        public TriggerProcessingResult processTrigger(
                StatementTrigger trigger,
                com.yetanalytics.hlaxapi.injection.InjectionContext context) {
            processed.add(trigger.statement);
            return switch (trigger.statement) {
                case "skip" -> new TriggerProcessingResult(null, false, true, null);
                case "fail" -> new TriggerProcessingResult(
                        null,
                        false,
                        false,
                        new IllegalArgumentException("failed"));
                case "throw" -> throw new IllegalStateException("thrown");
                default -> new TriggerProcessingResult(trigger.statement, true, true, null);
            };
        }
    }
}
