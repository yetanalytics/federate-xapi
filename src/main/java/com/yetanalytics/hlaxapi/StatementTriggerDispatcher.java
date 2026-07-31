package com.yetanalytics.hlaxapi;

import com.yetanalytics.hlaxapi.TriggerProcessor.TriggerProcessingResult;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.injection.InjectionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

@Component
public class StatementTriggerDispatcher {

    private static final Logger logger = LogManager.getLogger(StatementTriggerDispatcher.class);

    private final XapiConfig xapiConfig;
    private final TriggerProcessor triggerProcessor;
    private final FomCatalog fomCatalog;

    public StatementTriggerDispatcher(
            XapiConfig xapiConfig,
            TriggerProcessor triggerProcessor,
            FomCatalog fomCatalog) {
        this.xapiConfig = xapiConfig;
        this.triggerProcessor = triggerProcessor;
        this.fomCatalog = fomCatalog;
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
            if (!matchesTrigger(trigger, eventType, hlaClass)) {
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

    public boolean hasMatchingTrigger(
            StatementTrigger.Type eventType,
            String hlaClass) {
        if (xapiConfig.statementTriggers == null) {
            return false;
        }
        return xapiConfig.statementTriggers.stream()
                .anyMatch(trigger -> matchesTrigger(trigger, eventType, hlaClass));
    }

    private boolean matchesTrigger(
            StatementTrigger trigger,
            StatementTrigger.Type eventType,
            String hlaClass) {
        return trigger != null
                && trigger.type == eventType
                && matchesClass(eventType, trigger.clazz, hlaClass);
    }

    private boolean matchesClass(
            StatementTrigger.Type eventType,
            String configuredClass,
            String actualClass) {
        return eventType.isObjectEvent()
                ? fomCatalog.isSameOrDescendant(actualClass, configuredClass)
                : Objects.equals(configuredClass, actualClass);
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
