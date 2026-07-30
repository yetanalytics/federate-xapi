package com.yetanalytics.hlaxapi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

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

    private StatementValidator validator;

    private JmsTemplate jmsTemplate;

    private TransactionTemplate transactionTemplate;

    private int maxBatchSize;

    private Integer retryCount = 0;

    private Integer maxRetries;

    private static final List<Integer> NON_RETRYABLE_STATUSES = List.of(400, 401, 403, 404, 405, 406, 407, 413, 414,
            415);

    private static final String DEAD_LETTER_QUEUE = "dlq.xapi.statements";
    private static final String STATEMENT_QUEUE = "xapi.statements";

    public XapiClient(XapiConfig xapiConfig, StatementValidator validator, JmsTemplate jmsTemplate,
            TransactionTemplate transactionTemplate) {
        this.validator = validator;
        this.jmsTemplate = jmsTemplate;
        this.transactionTemplate = transactionTemplate;
        LRS lrs = new LRS(
                xapiConfig.lrsConfig.host,
                xapiConfig.lrsConfig.key,
                xapiConfig.lrsConfig.secret,
                xapiConfig.lrsConfig.batch);
        client = new StatementClient(lrs);
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
            StatementValidationException cause = new StatementValidationException(res.getErrors());
            jmsTemplate.convertAndSend(DEAD_LETTER_QUEUE, new FailedStatement(res.getStatement(), cause));
            throw cause;
        }
        addToBuffer(res.getStatement());
    }

    /** Synchronized buffer methods */

    private void addToBuffer(Statement stmt) {
        if (stmt == null) {
            logger.warn("Attempted to add null statement to buffer");
            return;
        }
        jmsTemplate.convertAndSend(STATEMENT_QUEUE, stmt);
    }

    // Check and post buffer to LRS every 10 seconds (or ENV) if contains statements
    @Scheduled(fixedDelayString = "${xapi.buffer.clear-rate:10000}")
    private synchronized void clearBuffer() {
        logger.info("Attempting Buffer Clear");

        int size = maxBatchSize;
        int processedCount = 0;
        int failureCount = 0;

        while (size > 0) {
            try {
                List<UUID> results = processBatch(size);
                retryCount = 0;
                if (results.isEmpty()) {
                    logger.info("Cleared buffer of {} statements with {} failures sent to DLQ",
                        processedCount, failureCount);
                    return;
                } else {
                    processedCount += results.size();
                    logger.info("Stored statements: {}", results);
                    size = Math.min(maxBatchSize, size * 2); // double the batch size for next attempt
                }
            } catch (StatementClientException e) {
                if (NON_RETRYABLE_STATUSES.contains(e.getStatusCode())) {
                    // Non-retryable error (e.g. 4xx probably statement related),
                    // reduce batch size or move stmt to dead-letter queue if size 1
                    if (size > 1) {
                        size = Math.max(1, size / 2);
                        logger.info("Non-retryable error, reducing batch size to {}", size);
                    } else {
                        deadLetterFromQueue(size, e);
                        retryCount = 0;
                        logger.info("Moved {} statement to dead-letter queue after non-retryable failure: {}", size,
                            e.getMessage());
                    }
                } else {
                    // Retryable error (e.g. 5xx or network error or similar),
                    // increment retry count and check if it exceeds max retries
                    retryCount++;
                    if (retryCount > maxRetries) {
                        deadLetterFromQueue(size, e);
                        retryCount = 0;
                        logger.info("Moved {} statements to dead-letter queue after exceeding max retries with error",
                            size, e.getMessage());
                    } else {
                        logger.info("Error processing statements. Attempt {}/{} with error {}", retryCount, maxRetries,
                            e.getMessage());
                        return;
                    }
                }
            }
        }
    }

    private List<UUID> processBatch(Integer size) throws StatementClientException {

        //Wrap in a transaction to ensure that if the batch fails, we can roll back and not lose statements
        List<UUID> results = transactionTemplate.execute(status -> {
            List<Statement> batch = new ArrayList<>();

            // Short timeout so we don't lock up if the queue runs out of messages mid-batch
            jmsTemplate.setReceiveTimeout(100);

            // 1. Pull the messages from the queue
            for (int i = 0; i < size; i++) {
                Statement pendingStmt = (Statement) jmsTemplate.receiveAndConvert(STATEMENT_QUEUE);
                if (pendingStmt == null) {
                    break;
                }
                batch.add(pendingStmt);
            }

            if (batch.isEmpty()) {
                logger.info("Buffer Clear");
                return new ArrayList<>();
            }

            // 2. Post the batch to the LRS
            try {
                return client.postStatements(batch);
            } catch (StatementClientException e) {
                logger.error("Error posting statements to LRS: {}", e.getMessage());
                throw e;
            }
        });
        return results;
    }

    private void deadLetterFromQueue(Integer amount, Throwable failureCause) {
        transactionTemplate.execute(status -> {
            for (int i = 0; i < amount; i++) {
                Statement pendingStmt = (Statement) jmsTemplate.receiveAndConvert(STATEMENT_QUEUE);
                if (pendingStmt == null) {
                    break;
                }
                FailedStatement dlqStmt = new FailedStatement(pendingStmt, failureCause);
                jmsTemplate.convertAndSend(DEAD_LETTER_QUEUE, dlqStmt);
            }
            return null;
        });
    }

    private static class FailedStatement {
        private final Statement statement;

        private final String originalStatementString;

        private Throwable failureCause;

        private FailedStatement(String statement, Throwable cause) {
            this.statement = null;
            this.originalStatementString = statement;
            this.failureCause = cause;
        }

        private FailedStatement(Statement statement, Throwable cause) {
            this.statement = statement;
            this.originalStatementString = null;
            this.failureCause = cause;
        }
    }

}
