## Federate xAPI - Broker (ActiveMQ) Configuration

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
