## Federate xAPI - Queue (ActiveMQ) & Broker Configuration

Federate xAPI utilizes a message broker (ActiveMQ) to facilitate decoupling and retries of various parts of the xAPI instrumentation process.

### Default Embedded Artemis

By default, Federate xAPI will run an embedded ActiveMQ Artemis server within the application and will not need any configuration. This Artemis server will host the queues and DLQs utilized by the application automatically.

### External ActiveMQ

If you wish to host the queues in a separate server, it is possible to tell the application to instead point to a running AMQ and in this case it will *not* start the embedded Artemis server. This will allow you visibility into RTI processing and outbound statements from your own infrastructure.

This repository's `docker-compose` actually has such an external ActiveMQ container for development. To run it, execute:

```shell
docker compose up -d amq
```

then to change the application's broker configuration to connect to it use:

```shell
XAPI_BROKER_URL=tcp://localhost:61616 \
XAPI_BROKER_USERNAME=admin \
XAPI_BROKER_PASSWORD=admin \
make run-dev
```

### xAPI Statement Queue

#### xAPI Statement Queue Clear Rate

The xAPI statement queue clear interval defaults to 10s (`10000`). This means every 10s it will attempt to write all queued statements to the LRS. This can be overriden with the the env `XAPI_BUFFER_CLEAR_RATE` which expects a value in milliseconds.

*Note:* This is a delay time between executions not a fixed rate. In order to ensure there is no overlap of statement processing the delay starts after the completion of the previous queue clear iteration.

```shell
# set to 1 second
XAPI_BUFFER_CLEAR_RATE=1000 \
make run-dev
```

#### xAPI Statement Queue Clear Behavior

The xAPI Statement Queue will attempt to clear the entire backlog of statements as long as there are statements, in chunks of the batch size in the LRS config (`batch`, default `50`).

- If it encounters an unretryable error (4xx indicating a bad statement) it will reduce the batch size until the offending statement is identified and sent to a dead letter queue.
- If it encounters a retryable error such as a connection issue or a server error it will continue to attempt the batch for the specified number of retries in the LRS config (`maxRetries`, default `3`).
