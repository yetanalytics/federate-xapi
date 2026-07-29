package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.core.JmsTemplate;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.LrsConfig;
import com.yetanalytics.hlaxapi.exception.StatementValidationException;
import com.yetanalytics.xapi.client.LRS;
import com.yetanalytics.xapi.client.StatementClient;
import com.yetanalytics.xapi.exception.StatementClientException;
import com.yetanalytics.xapi.model.Statement;
import com.yetanalytics.xapi.util.StatementValidator;

@ExtendWith(MockitoExtension.class)
class XapiClientTest {

    @Mock
    JmsTemplate jmsTemplate;

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
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), jmsTemplate);

        xapiClient.sendStatement(STATEMENT_JSON);

        assertEquals(1, buffer(xapiClient).size());
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.XapiClient"})
    void rejectsInvalidStatementJson() {
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), jmsTemplate);
        assertThrows(StatementValidationException.class, () -> xapiClient.sendStatement("{"));
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.XapiClient"})
    void rejectsInvalidStatementXApi() {
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), jmsTemplate);

        try {
            xapiClient.sendStatement(BAD_STATEMENT_JSON);
        } catch (StatementValidationException e) {
            assertEquals(1, e.getErrors().size());
            assertTrue(e.getErrors().iterator().next().contains("verbz"));
        }
    }

    @Test
    void clearBufferPostsBufferedStatementsAndClearsBuffer() throws Exception {
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), jmsTemplate);
        FakeStatementClient fakeClient = new FakeStatementClient();
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);

        assertEquals(1, fakeClient.postedStatements.size());
        assertEquals(0, buffer(xapiClient).size());
        assertEquals(0, retryCount(xapiClient));
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.XapiClient"})
    void clearBufferKeepsStatementsWhenRetryableClientErrorsBeforeMaxRetries() throws Exception {
        XapiClient xapiClient = new XapiClient(config(4, 2), new StatementValidator(), jmsTemplate);
        FakeStatementClient fakeClient = new FakeStatementClient();
        fakeClient.failuresRemaining = 1;
        fakeClient.statusCodeToThrow = 503;
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);

        assertEquals(1, fakeClient.postAttempts);
        assertEquals(1, buffer(xapiClient).size());
        assertEquals(1, retryCount(xapiClient));
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.XapiClient"})
    void clearBufferMovesBatchToDeadLetterQueueAfterMaxRetries() throws Exception {
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), jmsTemplate);
        FakeStatementClient fakeClient = new FakeStatementClient();
        fakeClient.failuresRemaining = 2;
        fakeClient.statusCodeToThrow = 503;
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);
        clearBuffer(xapiClient);

        assertEquals(2, fakeClient.postAttempts);
        assertEquals(0, buffer(xapiClient).size());
        assertEquals(1, deadLetterQueue(xapiClient).size());
        assertEquals(0, retryCount(xapiClient));
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.XapiClient"})
    void clearBufferReducesBatchSizeOnNonRetryableError() throws Exception {
        XapiClient xapiClient = new XapiClient(config(4, 1), new StatementValidator(), jmsTemplate);
        FakeStatementClient fakeClient = new FakeStatementClient();
        fakeClient.statusCodeToThrow = 400;
        fakeClient.failuresRemaining = 3;
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        xapiClient.sendStatement(STATEMENT_JSON);
        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);

        assertEquals(1, fakeClient.postAttempts);
        assertEquals(3, buffer(xapiClient).size());
        assertEquals(2, currentBatchSize(xapiClient));

        clearBuffer(xapiClient);
        assertEquals(2, fakeClient.postAttempts);
        assertEquals(3, buffer(xapiClient).size());
        assertEquals(1, currentBatchSize(xapiClient));

        clearBuffer(xapiClient);
        assertEquals(3, fakeClient.postAttempts);
        assertEquals(2, buffer(xapiClient).size());
        assertEquals(1, deadLetterQueue(xapiClient).size());
        assertEquals(1, currentBatchSize(xapiClient));

        clearBuffer(xapiClient);
        assertEquals(4, fakeClient.postAttempts);
        assertEquals(1, buffer(xapiClient).size());
        assertEquals(1, deadLetterQueue(xapiClient).size());
        assertEquals(2, currentBatchSize(xapiClient));

    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.XapiClient"})
    void clearBufferDeadLettersSingleStatementAfterNonRetryableFailure() throws Exception {
        XapiClient xapiClient = new XapiClient(config(2, 1), new StatementValidator(), jmsTemplate);
        FakeStatementClient fakeClient = new FakeStatementClient();
        fakeClient.failuresRemaining = 2;
        fakeClient.statusCodeToThrow = 400;
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);
        clearBuffer(xapiClient);
        clearBuffer(xapiClient);

        assertEquals(0, buffer(xapiClient).size());
        assertEquals(1, deadLetterQueue(xapiClient).size());
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.XapiClient"})
    void clearBufferUsesConfiguredBatchSize() throws Exception {
        XapiClient xapiClient = new XapiClient(config(2, 1), new StatementValidator(), jmsTemplate);
        FakeStatementClient fakeClient = new FakeStatementClient();
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        xapiClient.sendStatement(STATEMENT_JSON);
        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);

        assertEquals(2, fakeClient.lastBatchSize);
        assertEquals(1, buffer(xapiClient).size());
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.XapiClient"})
    void clearBufferMovesFailedStatementsToDeadLetterQueueAfterMaxRetries() throws Exception {
        XapiClient xapiClient = new XapiClient(config(2, 1), new StatementValidator(), jmsTemplate);
        FakeStatementClient fakeClient = new FakeStatementClient();
        fakeClient.failuresRemaining = 2;
        setClient(xapiClient, fakeClient);

        xapiClient.sendStatement(STATEMENT_JSON);
        clearBuffer(xapiClient);
        clearBuffer(xapiClient);

        assertEquals(0, buffer(xapiClient).size());
        assertEquals(1, deadLetterQueue(xapiClient).size());
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

    private static List<?> buffer(XapiClient xapiClient) throws Exception {
        Field bufferField = XapiClient.class.getDeclaredField("buffer");
        bufferField.setAccessible(true);
        return (List<?>) bufferField.get(xapiClient);
    }

    private static List<?> deadLetterQueue(XapiClient xapiClient) throws Exception {
        Field deadLetterQueueField = XapiClient.class.getDeclaredField("deadLetterQueue");
        deadLetterQueueField.setAccessible(true);
        return (List<?>) deadLetterQueueField.get(xapiClient);
    }

    private static int retryCount(XapiClient xapiClient) throws Exception {
        Field retryCountField = XapiClient.class.getDeclaredField("retryCount");
        retryCountField.setAccessible(true);
        return (Integer) retryCountField.get(xapiClient);
    }

    private static int currentBatchSize(XapiClient xapiClient) throws Exception {
        Field currentBatchSizeField = XapiClient.class.getDeclaredField("currentBatchSize");
        currentBatchSizeField.setAccessible(true);
        return currentBatchSizeField.getInt(xapiClient);
    }

    private static class FakeStatementClient extends StatementClient {
        private int failuresRemaining;
        private int postAttempts;
        private int lastBatchSize;
        private Integer statusCodeToThrow;
        private final List<Statement> postedStatements = new ArrayList<>();

        FakeStatementClient() {
            super(new LRS("https://example.com/xapi/", "key", "secret", 4));
        }

        @Override
        public List<UUID> postStatements(List<Statement> statements) {
            postAttempts++;
            lastBatchSize = statements.size();

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
}
