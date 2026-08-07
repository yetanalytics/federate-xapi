package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.LrsConfig;
import com.yetanalytics.hlaxapi.exception.StatementValidationException;
import com.yetanalytics.xapi.client.LRS;
import com.yetanalytics.xapi.client.StatementClient;
import com.yetanalytics.xapi.exception.StatementClientException;
import com.yetanalytics.xapi.model.Statement;
import com.yetanalytics.xapi.util.StatementValidator;

class XapiClientTest {

    private static final String STATEMENT_QUEUE = "xapi.statements";
    private static final String DEAD_LETTER_QUEUE = "dlq.xapi.statements";

    private static final String STATEMENT_JSON = """
            {
              "actor": {
                "objectType": "Agent",
                "name": "Test Pilot",
                "mbox": "mailto:test@example.com"
              },
              "verb": {
                "id": "http://adlnet.gov/expapi/verbs/experienced"
              },
              "object": {
                "objectType": "Activity",
                "id": "https://example.com/simulation/activity"
              }
            }
            """;

    private static final String BAD_STATEMENT_JSON = """
            {
              "actor": {
                "objectType": "Agent",
                "name": "Test Pilot",
                "mbox": "mailto:test@example.com"
              },
              "verbz": {
                "id": "http://adlnet.gov/expapi/verbs/experienced"
              },
              "object": {
                "objectType": "Activity",
                "id": "https://example.com/simulation/activity"
              }
            }
            """;

    @Test
    void buffersStatementFromJsonString() throws Exception {
        FakeJmsTemplate fakeJmsTemplate = new FakeJmsTemplate();
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), fakeJmsTemplate,
                new TestTransactionTemplate(fakeJmsTemplate));

        xapiClient.sendStatement(STATEMENT_JSON);

        assertEquals(1, fakeJmsTemplate.queueSize(STATEMENT_QUEUE));
        assertEquals(0, fakeJmsTemplate.queueSize(DEAD_LETTER_QUEUE));
    }

    @Test
    @SuppressTestLogging({ "com.yetanalytics.hlaxapi.XapiClient" })
    void rejectsInvalidStatementJson() {
        FakeJmsTemplate fakeJmsTemplate = new FakeJmsTemplate();
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), fakeJmsTemplate,
                new TestTransactionTemplate(fakeJmsTemplate));

        assertThrows(StatementValidationException.class, () -> xapiClient.sendStatement("{"));
        assertEquals(1, fakeJmsTemplate.queueSize(DEAD_LETTER_QUEUE));
    }

    @Test
    @SuppressTestLogging({ "com.yetanalytics.hlaxapi.XapiClient" })
    void rejectsInvalidStatementXApi() {
        FakeJmsTemplate fakeJmsTemplate = new FakeJmsTemplate();
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), fakeJmsTemplate,
                new TestTransactionTemplate(fakeJmsTemplate));

        try {
            xapiClient.sendStatement(BAD_STATEMENT_JSON);
        } catch (StatementValidationException e) {
            assertEquals(1, e.getErrors().size());
            assertTrue(e.getErrors().iterator().next().contains("verbz"));
        }
        assertEquals(1, fakeJmsTemplate.queueSize(DEAD_LETTER_QUEUE));
    }

    @Test
    void clearBufferPostsBufferedStatementsAndClearsBuffer() throws Exception {
        FakeJmsTemplate fakeJmsTemplate = new FakeJmsTemplate();
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), fakeJmsTemplate,
                new TestTransactionTemplate(fakeJmsTemplate));
        FakeStatementClient fakeClient = new FakeStatementClient();
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);

        assertEquals(1, fakeClient.postedStatements.size());
        assertEquals(0, fakeJmsTemplate.queueSize(STATEMENT_QUEUE));
        assertEquals(0, retryCount(xapiClient));
    }

    @Test
    @SuppressTestLogging({ "com.yetanalytics.hlaxapi.XapiClient" })
    void clearBufferKeepsStatementsWhenRetryableClientErrorsBeforeMaxRetries() throws Exception {
        FakeJmsTemplate fakeJmsTemplate = new FakeJmsTemplate();
        XapiClient xapiClient = new XapiClient(config(4, 2), new StatementValidator(), fakeJmsTemplate,
                new TestTransactionTemplate(fakeJmsTemplate));
        FakeStatementClient fakeClient = new FakeStatementClient();
        fakeClient.failuresRemaining = 1;
        fakeClient.statusCodeToThrow = 503;
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);

        assertEquals(1, fakeClient.postAttempts);
        assertEquals(1, fakeJmsTemplate.queueSize(STATEMENT_QUEUE));
        assertEquals(1, retryCount(xapiClient));
    }

    @Test
    @SuppressTestLogging({ "com.yetanalytics.hlaxapi.XapiClient" })
    void clearBufferMovesBatchToDeadLetterQueueAfterMaxRetries() throws Exception {
        FakeJmsTemplate fakeJmsTemplate = new FakeJmsTemplate();
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), fakeJmsTemplate,
                new TestTransactionTemplate(fakeJmsTemplate));
        FakeStatementClient fakeClient = new FakeStatementClient();
        fakeClient.failuresRemaining = 2;
        fakeClient.statusCodeToThrow = 503;
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);
        clearBuffer(xapiClient);

        assertEquals(2, fakeClient.postAttempts);
        assertEquals(0, fakeJmsTemplate.queueSize(STATEMENT_QUEUE));
        assertEquals(1, fakeJmsTemplate.queueSize(DEAD_LETTER_QUEUE));
        assertEquals(0, retryCount(xapiClient));
    }

    @Test
    @SuppressTestLogging({ "com.yetanalytics.hlaxapi.XapiClient" })
    void clearBufferReducesBatchSizeOnNonRetryableError() throws Exception {
        FakeJmsTemplate fakeJmsTemplate = new FakeJmsTemplate();
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), fakeJmsTemplate,
                new TestTransactionTemplate(fakeJmsTemplate));
        FakeStatementClient fakeClient = new FakeStatementClient();
        fakeClient.statusCodeToThrow = 400;
        fakeClient.failuresRemaining = 3;
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        xapiClient.sendStatement(STATEMENT_JSON);
        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);

        assertTrue(fakeClient.postAttempts >= 1);
        assertTrue(fakeClient.batchSizes.stream().anyMatch(size -> size == 3));
        assertTrue(fakeClient.batchSizes.stream().anyMatch(size -> size == 2));
        assertTrue(fakeClient.batchSizes.stream().anyMatch(size -> size == 1));
        assertTrue(fakeJmsTemplate.queueSize(DEAD_LETTER_QUEUE) >= 1);
        assertEquals(0, fakeJmsTemplate.queueSize(STATEMENT_QUEUE));
    }

    @Test
    @SuppressTestLogging({ "com.yetanalytics.hlaxapi.XapiClient" })
    void clearBufferDeadLettersSingleStatementAfterNonRetryableFailure() throws Exception {
        FakeJmsTemplate fakeJmsTemplate = new FakeJmsTemplate();
        XapiClient xapiClient = new XapiClient(config(2, 1), new StatementValidator(), fakeJmsTemplate,
                new TestTransactionTemplate(fakeJmsTemplate));
        FakeStatementClient fakeClient = new FakeStatementClient();
        fakeClient.failuresRemaining = 2;
        fakeClient.statusCodeToThrow = 400;
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);
        clearBuffer(xapiClient);
        clearBuffer(xapiClient);

        assertEquals(0, fakeJmsTemplate.queueSize(STATEMENT_QUEUE));
        assertEquals(1, fakeJmsTemplate.queueSize(DEAD_LETTER_QUEUE));
    }

    @Test
    @SuppressTestLogging({ "com.yetanalytics.hlaxapi.XapiClient" })
    void clearBufferUsesConfiguredBatchSize() throws Exception {
        FakeJmsTemplate fakeJmsTemplate = new FakeJmsTemplate();
        XapiClient xapiClient = new XapiClient(config(2, 1), new StatementValidator(), fakeJmsTemplate,
                new TestTransactionTemplate(fakeJmsTemplate));
        FakeStatementClient fakeClient = new FakeStatementClient();
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        xapiClient.sendStatement(STATEMENT_JSON);
        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);

        assertEquals(2, fakeClient.batchSizes.get(0));
        assertTrue(fakeClient.batchSizes.size() >= 2);
        assertEquals(0, fakeJmsTemplate.queueSize(STATEMENT_QUEUE));
    }

    @Test
    @SuppressTestLogging({ "com.yetanalytics.hlaxapi.XapiClient" })
    void clearBufferMovesFailedStatementsToDeadLetterQueueAfterMaxRetries() throws Exception {
        FakeJmsTemplate fakeJmsTemplate = new FakeJmsTemplate();
        XapiClient xapiClient = new XapiClient(config(2, 1), new StatementValidator(), fakeJmsTemplate,
                new TestTransactionTemplate(fakeJmsTemplate));
        FakeStatementClient fakeClient = new FakeStatementClient();
        fakeClient.failuresRemaining = 2;
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);
        clearBuffer(xapiClient);

        assertEquals(0, fakeJmsTemplate.queueSize(STATEMENT_QUEUE));
        assertEquals(1, fakeJmsTemplate.queueSize(DEAD_LETTER_QUEUE));
    }

    private static XapiConfig config(int batch, int maxRetries) {
        LrsConfig lrsConfig = new LrsConfig();
        lrsConfig.host = "https://example.com/xapi/";
        lrsConfig.key = "key";
        lrsConfig.secret = "secret";
        lrsConfig.batch = batch;
        lrsConfig.maxRetries = maxRetries;

        XapiConfig xapiConfig = new XapiConfig();
        xapiConfig.lrsConfig = lrsConfig;
        return xapiConfig;
    }

    private static void clearBuffer(XapiClient xapiClient) throws Exception {
        Method clearBuffer = XapiClient.class.getDeclaredMethod("clearBuffer");
        clearBuffer.setAccessible(true);
        clearBuffer.invoke(xapiClient);
    }

    private static void setClient(XapiClient xapiClient, StatementClient client) throws Exception {
        Field clientField = XapiClient.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(xapiClient, client);
    }

    private static int retryCount(XapiClient xapiClient) throws Exception {
        Field retryCountField = XapiClient.class.getDeclaredField("retryCount");
        retryCountField.setAccessible(true);
        return (Integer) retryCountField.get(xapiClient);
    }

    private static class FakeStatementClient extends StatementClient {
        private int failuresRemaining;
        private int postAttempts;
        private int lastBatchSize;
        private Integer statusCodeToThrow;
        private final List<Statement> postedStatements = new ArrayList<>();
        private final List<Integer> batchSizes = new ArrayList<>();

        FakeStatementClient() {
            super(new LRS("https://example.com/xapi/", "key", "secret", 4));
        }

        @Override
        public List<UUID> postStatements(List<Statement> statements) {
            postAttempts++;
            lastBatchSize = statements.size();
            batchSizes.add(statements.size());

            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new StatementClientException("Client error", statusCodeToThrow != null ? statusCodeToThrow : 503);
            }
            postedStatements.addAll(statements);
            return statements.stream()
                    .map(statement -> UUID.randomUUID())
                    .toList();
        }
    }

    private static class FakeJmsTemplate extends JmsTemplate {
        private final Map<String, Deque<Object>> queues = new LinkedHashMap<>();
        private final ThreadLocal<TransactionContext> currentTransaction = new ThreadLocal<>();

        private FakeJmsTemplate() {
            queues.put(STATEMENT_QUEUE, new ArrayDeque<>());
            queues.put(DEAD_LETTER_QUEUE, new ArrayDeque<>());
        }

        @Override
        public void convertAndSend(String destinationName, Object message) {
            queue(destinationName).addLast(message);
        }

        @Override
        public Object receiveAndConvert(String destinationName) {
            Deque<Object> queue = queue(destinationName);
            if (queue.isEmpty()) {
                return null;
            }
            Object message = queue.removeFirst();
            TransactionContext transactionContext = currentTransaction.get();
            if (transactionContext != null) {
                transactionContext.receivedMessages.add(new ReceivedMessage(destinationName, message));
            }
            return message;
        }

        @Override
        public void setReceiveTimeout(long timeout) {
            // no-op for tests
        }

        private TransactionContext beginTransaction() {
            TransactionContext context = new TransactionContext();
            currentTransaction.set(context);
            return context;
        }

        private void commitTransaction(TransactionContext context) {
            currentTransaction.remove();
        }

        private void rollbackTransaction(TransactionContext context) {
            for (int i = context.receivedMessages.size() - 1; i >= 0; i--) {
                ReceivedMessage receivedMessage = context.receivedMessages.get(i);
                queue(receivedMessage.destinationName).addFirst(receivedMessage.message);
            }
            currentTransaction.remove();
        }

        private int queueSize(String destinationName) {
            return queue(destinationName).size();
        }

        private Deque<Object> queue(String destinationName) {
            return queues.computeIfAbsent(destinationName, ignored -> new ArrayDeque<>());
        }
    }

    private static class TestTransactionTemplate extends TransactionTemplate {
        private final FakeJmsTemplate fakeJmsTemplate;

        private TestTransactionTemplate(FakeJmsTemplate fakeJmsTemplate) {
            this.fakeJmsTemplate = fakeJmsTemplate;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            TransactionContext transactionContext = fakeJmsTemplate.beginTransaction();
            try {
                T result = action.doInTransaction(new DefaultTransactionStatus(null, true, true, false, false, null));
                fakeJmsTemplate.commitTransaction(transactionContext);
                return result;
            } catch (RuntimeException | Error e) {
                fakeJmsTemplate.rollbackTransaction(transactionContext);
                throw e;
            }
        }
    }

    private static class TransactionContext {
        private final List<ReceivedMessage> receivedMessages = new ArrayList<>();
    }

    private static class ReceivedMessage {
        private final String destinationName;
        private final Object message;

        private ReceivedMessage(String destinationName, Object message) {
            this.destinationName = destinationName;
            this.message = message;
        }
    }
}
