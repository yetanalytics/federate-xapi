package com.yetanalytics.hlaxapi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.exception.StatementValidationException;
import com.yetanalytics.xapi.client.LRS;
import com.yetanalytics.xapi.model.Statement;
import com.yetanalytics.xapi.client.StatementClient;
import com.yetanalytics.xapi.exception.StatementClientException;
import com.yetanalytics.xapi.util.StatementValidator;
import com.yetanalytics.xapi.util.StatementValidator.StatementValidationResult;

@Component
public class XapiClient {

    private static final Logger logger = LogManager.getLogger(XapiClient.class);

    private StatementClient client;

    private List<PendingStatement> buffer;

    private List<PendingStatement> deadLetterQueue;

    private StatementValidator validator;

    private int maxBatchSize;

    private int currentBatchSize;

    private Integer retryCount = 0;

    private Integer maxRetries;

    private static final List<Integer> NON_RETRYABLE_STATUSES = 
            List.of(400, 401, 403, 404, 405, 406, 407, 413, 414, 415);

    public XapiClient(XapiConfig xapiConfig, StatementValidator validator) {
        this.validator = validator;
        LRS lrs = new LRS(
            xapiConfig.lrsConfig.host,
            xapiConfig.lrsConfig.key,
            xapiConfig.lrsConfig.secret,
            xapiConfig.lrsConfig.batch
        );
        client = new StatementClient(lrs);
        buffer = new ArrayList<PendingStatement>();
        deadLetterQueue = new ArrayList<PendingStatement>();
        maxBatchSize = xapiConfig.lrsConfig.batch;
        currentBatchSize = maxBatchSize;
        maxRetries = xapiConfig.lrsConfig.maxRetries;
    }

    public void sendStatement(String s) throws StatementValidationException {
        addToBuffer(validator.validateStatement(s));
    }

    public void sendStatement(Statement stmt) throws StatementValidationException {
        addToBuffer(validator.validateStatement(stmt));
    }

    private void addToBuffer(StatementValidationResult res) throws StatementValidationException {
        if (!res.isValid()) {
            logger.error("Invalid statement: {}", res.getErrors());
            throw new StatementValidationException("Invalid statement", res.getErrors());
        }
        addToBuffer(res.getStatement());
    }

    /** Synchronized buffer methods */

    private synchronized void addToBuffer(Statement stmt){
        buffer.add(new PendingStatement(stmt));
    }

    // Check and post buffer to LRS every 10 seconds (or ENV) if contains statements
    @Scheduled(fixedRateString = "${xapi.buffer.clear-rate:10000}")
    private synchronized void clearBuffer() {
        logger.info("Buffer Size: {}", buffer.size());
        if (buffer.isEmpty()) {
            return;
        }

        List<PendingStatement> batch = 
                new ArrayList<>(buffer.subList(0, Math.min(currentBatchSize, buffer.size())));
        if (batch.isEmpty()) {
            return;
        }

        List<Statement> statementsToSend = batch.stream()
            .map(pending -> pending.statement)
            .toList();

        try {
            List<UUID> results = client.postStatements(statementsToSend);
            logger.info("Stored statements: {}", results);
            buffer.removeAll(batch);
            
            if (buffer.size() == 0) {
                logger.info("Buffer cleared");
            } else {
                logger.info("Buffer partially cleared: {} statements remain", buffer.size());
            }
            
            retryCount = 0;
            // double the current batch size for the next attempt, but do not exceed the configured batch size
            currentBatchSize = Math.min(maxBatchSize, Math.max(1, currentBatchSize * 2));
        } catch (StatementClientException e) {
            logger.error("Error sending statements to LRS:", e);

            if (!NON_RETRYABLE_STATUSES.contains(e.getStatusCode())) {
                // Retryable error (e.g. 5xx or network error), increment retry count and check if it exceeds max retries
                retryCount++;
                if (retryCount > maxRetries) {
                    deadLetterQueue.addAll(batch);
                    buffer.removeAll(batch);
                    retryCount = 0;
                    logger.info("Moved {} statements to dead-letter queue after exceeding max retries", batch.size());
                } else {
                    logger.info("Retrying batch of {} statements, attempt {}/{}", batch.size(), retryCount, maxRetries);
                }
            } else {
                // Non-retryable error (e.g. 4xx probably statement related), move to dead-letter queue if size 1, or reduce batch size
                if (currentBatchSize > 1) {
                    currentBatchSize = Math.max(1, currentBatchSize / 2);
                    logger.info("Non-retryable error, reducing batch size to {}", currentBatchSize);
                } else {
                    deadLetterQueue.addAll(batch);
                    buffer.removeAll(batch);
                    retryCount = 0;
                    logger.info("Moved {} statements to dead-letter queue after non-retryable failure", batch.size());
                }
            }
        }
    }

    private static class PendingStatement {
        private final Statement statement;
        
        private PendingStatement(Statement statement) {
            this.statement = statement;
        }
    }

}
