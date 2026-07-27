package com.yetanalytics.hlaxapi;

import com.yetanalytics.hlaxapi.TriggerProcessor.TriggerProcessingResult;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.injection.InjectionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class StatementTriggerDispatcher {

    private static final Logger logger = LogManager.getLogger(StatementTriggerDispatcher.class);

    private final XapiConfig xapiConfig;
    private final TriggerProcessor triggerProcessor;

    @Autowired
    public StatementTriggerDispatcher(XapiConfig xapiConfig, TriggerProcessor triggerProcessor) {
        this.xapiConfig = xapiConfig;
        this.triggerProcessor = triggerProcessor;
    }

    public List<StagedStatement> stage(
            StatementTrigger.Type eventType,
            String hlaClass,
            InjectionContext context) {
        if (xapiConfig.statementTriggers == null) {
            return List.of();
        }
        List<StagedStatement> statements = new ArrayList<>();
        for (StatementTrigger trigger : xapiConfig.statementTriggers) {
            if (trigger == null
                    || trigger.type != eventType
                    || !Objects.equals(trigger.clazz, hlaClass)) {
                continue;
            }
            try {
                logger.trace("Processing {} trigger for {}", eventType, hlaClass);
                TriggerProcessingResult result = triggerProcessor.processTrigger(trigger, context);
                if (result == null) {
                    logger.error("Trigger {}.{} did not produce a processing result", eventType, hlaClass);
                } else if (result.success() && result.matched()) {
                    statements.add(new StagedStatement(trigger, result.statement()));
                } else if (!result.success()) {
                    logger.error("Error processing trigger {}.{}", eventType, hlaClass, result.error());
                }
            } catch (RuntimeException e) {
                logger.error("Error processing trigger {}.{}", eventType, hlaClass, e);
            }
        }
        return List.copyOf(statements);
    }

    public void enqueue(List<StagedStatement> statements, Consumer<String> statementSink) {
        for (StagedStatement statement : statements) {
            try {
                statementSink.accept(statement.statement());
            } catch (RuntimeException e) {
                StatementTrigger trigger = statement.trigger();
                logger.error("Error enqueueing statement for trigger {}.{}",
                        trigger.type,
                        trigger.clazz,
                        e);
            }
        }
    }

    public void dispatch(
            StatementTrigger.Type eventType,
            String hlaClass,
            InjectionContext context,
            Consumer<String> statementSink) {
        enqueue(stage(eventType, hlaClass, context), statementSink);
    }

    public record StagedStatement(StatementTrigger trigger, String statement) {
    }
}
