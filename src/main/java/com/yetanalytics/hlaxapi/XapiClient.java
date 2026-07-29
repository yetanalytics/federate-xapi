package com.yetanalytics.hlaxapi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jms.core.JmsTemplate;
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

    private List<PendingStatement> deadLetterQueue;

    private StatementValidator validator;

    private JmsTemplate jmsTemplate;

    private int maxBatchSize;

    private Integer retryCount = 0;

    private Integer maxRetries;

    private static final List<Integer> NON_RETRYABLE_STATUSES = List.of(400, 401, 403, 404, 405, 406, 407, 413, 414,
            415);

    private static final String DEAD_LETTER_QUEUE = "dlq.xapi.statements";
    private static final String STATEMENT_QUEUE = "xapi.statements";

    public XapiClient(XapiConfig xapiConfig, StatementValidator validator, JmsTemplate jmsTemplate) {
        this.validator = validator;
        this.jmsTemplate = jmsTemplate;
        LRS lrs = new LRS(
                xapiConfig.lrsConfig.host,
                xapiConfig.lrsConfig.key,
                xapiConfig.lrsConfig.secret,
                xapiConfig.lrsConfig.batch);
        client = new StatementClient(lrs);
        deadLetterQueue = new ArrayList<PendingStatement>();
        maxBatchSize = xapiConfig.lrsConfig.batch;
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

    private synchronized void addToBuffer(Statement stmt) {
        jmsTemplate.convertAndSend("xapi.statements", new PendingStatement(stmt));
    }

    // Check and post buffer to LRS every 10 seconds (or ENV) if contains statements
    @Scheduled(fixedDelayString = "${xapi.buffer.clear-rate:10000}")
    private synchronized void clearBuffer() {
        logger.info("Attempting Buffer Clear");
        
        int size = maxBatchSize;
        int processedCount = 0;
        int failureCount = 0; 

        while (size > 0) {
            BatchResult result = processBatch(size);
            if(result.success()) {
                retryCount = 0;
                if (result.getResults().isEmpty()) {
                    logger.info("Cleared buffer of {} statements with {} failures", processedCount, failureCount);
                    return;
                } else {
                    processedCount += result.getResults().size();
                    logger.info("Stored statements: {}", result.getResults());
                    size = Math.min(maxBatchSize, size * 2); // double the batch size for next attempt
                }
            } else {
                if (NON_RETRYABLE_STATUSES.contains(result.getError().getStatusCode())) {
                    // Non-retryable error (e.g. 4xx probably statement related),
                    // reduce batch size or move stmt to dead-letter queue if size 1
                    if (size > 1) {
                        size = Math.max(1, size / 2);
                        logger.info("Non-retryable error, reducing batch size to {}", size);
                    } else {
                        deadLetterFromQueue(size, result.getError());
                        retryCount = 0;
                        logger.info("Moved {} statement to dead-letter queue after non-retryable failure: {}", size, 
                            result.getError().getMessage());
                    }
                } else {
                    // Retryable error (e.g. 5xx or network error or similar),
                    // increment retry count and check if it exceeds max retries
                    retryCount++;
                    if (retryCount > maxRetries) {
                        deadLetterFromQueue(size, result.getError());
                        retryCount = 0;
                        logger.info("Moved {} statements to dead-letter queue after exceeding max retries with error", 
                            size, result.getError().getMessage());
                    } else {
                        logger.info("Error processing statements. Attempt {}/{} with error {}", retryCount, maxRetries,
                            result.getError().getMessage());
                        return;
                    }
                }
            }
        }
    }

    private BatchResult processBatch(Integer size) throws StatementClientException {

        List<PendingStatement> batch = new ArrayList<>();

        // Short timeout so we don't lock up if the queue runs out of messages mid-batch
        jmsTemplate.setReceiveTimeout(100);

        // 1. Pull the messages from the queue
        for (int i = 0; i < size; i++) {
            PendingStatement pendingStmt = (PendingStatement) jmsTemplate.receiveAndConvert(STATEMENT_QUEUE);
            if (pendingStmt == null) {
                break;
            }
            batch.add(pendingStmt);
        }

        if (batch.isEmpty()) {
            logger.info("Buffer Clear");
            return new BatchResult(new ArrayList<>());
        }

        List<Statement> statementsToSend = batch.stream()
                .map(pending -> pending.statement)
                .toList();
        try {
            List<UUID> results = client.postStatements(statementsToSend);
            return new BatchResult(results);
        } catch (StatementClientException e) {
            return new BatchResult(e);
        }
    }

    private void deadLetterFromQueue(Integer amount, Throwable cause) {
        for (int i = 0; i < amount; i++) {
            PendingStatement pendingStmt = (PendingStatement) jmsTemplate.receiveAndConvert(STATEMENT_QUEUE);
            if (pendingStmt == null) {
                break;
            }
            PendingStatement dlqStmt = new PendingStatement(pendingStmt.statement, cause);
            jmsTemplate.convertAndSend("dlq.xapi.statements", dlqStmt);
        }
    }

    private class BatchResult {
        private List<UUID> results;
        private boolean success;
        private StatementClientException error;

        private BatchResult(List<UUID> results) {
            this.results = results;
            this.success = true;
        }

        private BatchResult(StatementClientException error) {
            this.error = error;
            this.success = false;
        }

        public List<UUID> getResults() {
            return results;
        }

        public boolean success() {
            return success;
        }

        public StatementClientException getError() {
            return error;
        }
    }

    private static class PendingStatement {
        private final Statement statement;

        private Throwable cause;

        private PendingStatement(Statement statement) {
            this.statement = statement;
        }

        private PendingStatement(Statement statement, Throwable cause) {
            this.statement = statement;
            this.cause = cause;
        }
    }

}
