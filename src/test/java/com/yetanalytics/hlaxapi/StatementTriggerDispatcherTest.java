package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.TriggerProcessor.TriggerProcessingResult;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.injection.InteractionInjectionContext;
import com.yetanalytics.hlaxapi.injection.ObjectInjectionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class StatementTriggerDispatcherTest {

    private final FomCatalog catalog = new FomCatalog(new FOMXML(
            new SimulationConfig(null, null, null, null, "config/HlaFedereplFOM.xml"),
            new HLADecoderRegistry(new HLA1516eEncoderFactory())));

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.StatementTriggerDispatcher"})
    void matchesExactlyStagesOnceAndIsolatesProcessingAndEnqueueFailures() {
        StatementTrigger first = trigger(StatementTrigger.Type.OBJECT_UPDATE, "Rabbit", "first");
        StatementTrigger wrongType = trigger(StatementTrigger.Type.INTERACTION, "Rabbit", "wrong-type");
        StatementTrigger wrongClass = trigger(StatementTrigger.Type.OBJECT_UPDATE, "Wolf", "wrong-class");
        StatementTrigger skipped = trigger(StatementTrigger.Type.OBJECT_UPDATE, "Rabbit", "skip");
        StatementTrigger failed = trigger(StatementTrigger.Type.OBJECT_UPDATE, "Rabbit", "fail");
        StatementTrigger throwsException = trigger(StatementTrigger.Type.OBJECT_UPDATE, "Rabbit", "throw");
        StatementTrigger second = trigger(StatementTrigger.Type.OBJECT_UPDATE, "Rabbit", "second");
        XapiConfig config = new XapiConfig();
        config.statementTriggers =
                List.of(first, wrongType, wrongClass, skipped, failed, throwsException, second);
        ControlledTriggerProcessor processor = new ControlledTriggerProcessor();
        StatementTriggerDispatcher dispatcher =
                new StatementTriggerDispatcher(config, processor, catalog);

        List<StatementTriggerDispatcher.StagedStatement> staged = dispatcher.stage(
                StatementTrigger.Type.OBJECT_UPDATE,
                "Rabbit",
                new ObjectInjectionContext("Rabbit", "object-1", Map.of()));

        assertEquals(List.of("first", "second"),
                staged.stream().map(StatementTriggerDispatcher.StagedStatement::statement).toList());
        assertEquals(List.of("first", "skip", "fail", "throw", "second"), processor.processed);

        List<String> enqueued = new ArrayList<>();
        dispatcher.enqueue(staged, statement -> {
            if ("first".equals(statement)) {
                throw new IllegalStateException("first enqueue failed");
            }
            enqueued.add(statement);
        });

        assertEquals(List.of("second"), enqueued);
    }

    @Test
    void interactionEventsUseTheSameDispatcherWithoutMatchingObjectTriggers() {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "Rabbit", "object"),
                trigger(StatementTrigger.Type.INTERACTION, "SimEntity", "ancestor-interaction"),
                trigger(StatementTrigger.Type.INTERACTION, "Rabbit", "interaction"));
        StatementTriggerDispatcher dispatcher =
                new StatementTriggerDispatcher(config, new ControlledTriggerProcessor(), catalog);
        List<String> enqueued = new ArrayList<>();

        dispatcher.dispatch(
                StatementTrigger.Type.INTERACTION,
                "Rabbit",
                new InteractionInjectionContext("Rabbit", Map.of()),
                enqueued::add);

        assertEquals(List.of("interaction"), enqueued);
    }

    @Test
    void lifecycleEventsMatchTheirTypeAndFomHierarchy() {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(
                trigger(StatementTrigger.Type.OBJECT_CREATE, "SimEntity", "sim-entity-create"),
                trigger(StatementTrigger.Type.OBJECT_CREATE, "Rabbit", "rabbit-create"),
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "Rabbit", "rabbit-update"),
                trigger(StatementTrigger.Type.OBJECT_DELETE, "SimEntity", "sim-entity-delete"),
                trigger(StatementTrigger.Type.OBJECT_DELETE, "Rabbit", "rabbit-delete"),
                trigger(StatementTrigger.Type.OBJECT_DELETE, "Wolf", "wolf-delete"));
        StatementTriggerDispatcher dispatcher =
                new StatementTriggerDispatcher(config, new ControlledTriggerProcessor(), catalog);
        ObjectInjectionContext rabbit =
                new ObjectInjectionContext("Rabbit", "object-1", Map.of());

        List<String> createStatements = dispatcher
                .stage(StatementTrigger.Type.OBJECT_CREATE, "Rabbit", rabbit)
                .stream()
                .map(StatementTriggerDispatcher.StagedStatement::statement)
                .toList();
        List<String> deleteStatements = dispatcher
                .stage(StatementTrigger.Type.OBJECT_DELETE, "Rabbit", rabbit)
                .stream()
                .map(StatementTriggerDispatcher.StagedStatement::statement)
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
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "Rabbit", "rabbit-update"),
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "Wolf", "wolf-update"),
                trigger(StatementTrigger.Type.OBJECT_UPDATE, "MissingObject", "unknown-update"));
        StatementTriggerDispatcher dispatcher =
                new StatementTriggerDispatcher(config, new ControlledTriggerProcessor(), catalog);
        ObjectInjectionContext rabbit =
                new ObjectInjectionContext("Rabbit", "object-1", Map.of());

        List<String> updateStatements = dispatcher
                .stage(StatementTrigger.Type.OBJECT_UPDATE, "Rabbit", rabbit)
                .stream()
                .map(StatementTriggerDispatcher.StagedStatement::statement)
                .toList();

        assertEquals(
                List.of("sim-entity-update", "rabbit-update"),
                updateStatements);
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
