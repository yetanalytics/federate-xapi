package com.yetanalytics.hlaxapi;

import java.io.IOException;

import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.connection.JmsTransactionManager;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.cache.ObjectCache;
import com.yetanalytics.hlaxapi.config.ConfigParser;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.xapi.util.StatementValidator;

import hla.rti1516e.RtiFactory;
import hla.rti1516e.RtiFactoryFactory;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.exceptions.RTIinternalError;
import jakarta.jms.ConnectionFactory;


/**
 * Spring configuration class for beans related to HLA and simulation configuration.
 */
@Configuration
@EnableScheduling
public class AppConfig {

    private static final Logger logger = LogManager.getLogger(AppConfig.class);

    @Bean
    public EncoderFactory encoderFactory() {
        try {
            RtiFactory rtiFactory = RtiFactoryFactory.getRtiFactory();
            return rtiFactory.getEncoderFactory();
        } catch (RTIinternalError e) {
            throw new RuntimeException("Could not obtain EncoderFactory from RTI", e);
        }
    }

    @Bean
    public HLADecoderRegistry hlaDecoderRegistry(EncoderFactory encoderFactory) {
        return new HLADecoderRegistry(encoderFactory);
    }

    @Bean(destroyMethod = "close")
    public ObjectCache objectCache(
            XapiConfig xapiConfig,
            FomCatalog fomCatalog,
            FOMXML fomXml,
            HLADecoderRegistry decoderRegistry) {
        return new ObjectCache(xapiConfig, fomCatalog, fomXml, decoderRegistry);
    }

    @Bean
    public StatementValidator statementValidator() {
        return new StatementValidator();
    }

    @Bean
    public XapiConfig xapiConfig() {
        XapiConfig xapiConfig;
        try {
            xapiConfig = ConfigParser.fromEnvOrDefault().parse();
            logger.info(
                    "Loaded xapi config: {} triggers",
                    xapiConfig.statementTriggers == null ? 0 : xapiConfig.statementTriggers.size());
            return xapiConfig;
        } catch (Exception e) {
            logger.warn("Could not load xapi config", e);
            throw new RuntimeException(e);
        }
    }

    @Bean
    public SimulationConfig simulationConfig() {
        String path = System.getenv().getOrDefault("SIM_CONFIG", "config/Simulation.config");

        final SimulationConfig config;
        try {
            config = new SimulationConfig(path);
            return config;
        } catch (IOException e) {
            logger.error("Could not read Simulation config: " + path, e);
            throw new RuntimeException(e);
        }
    }

    /** Broker Stuff */

    //TODO: Make optional, only if no broker is provided in the config.
    @Bean(initMethod = "start", destroyMethod = "stop")
    public EmbeddedActiveMQ embeddedServer() throws Exception {
        System.setProperty("org.jboss.logging.provider", "slf4j");
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
        EmbeddedActiveMQ server = new EmbeddedActiveMQ();
        org.apache.activemq.artemis.core.config.Configuration config = new ConfigurationImpl()
            .setPersistenceEnabled(false)
            .setSecurityEnabled(false)
            .setJournalType(JournalType.NIO)
            .addAcceptorConfiguration("in-vm", "vm://0");

        server.setConfiguration(config);
        return server;
    }

    @Bean
    public ConnectionFactory jmsConnectionFactory() {
        // Artemis specific Jakarta factory
        ActiveMQConnectionFactory rawFactory = new ActiveMQConnectionFactory("vm://0");
        rawFactory.setConsumerWindowSize(0); // strict FIFO

        // Wrap the raw factory to cache connections and sessions
        CachingConnectionFactory cachingFactory = new CachingConnectionFactory(rawFactory);
        // Crucial: must be false for variable batch pooling logic to work correctly
        cachingFactory.setCacheConsumers(false);
        cachingFactory.setSessionCacheSize(10);

        return cachingFactory;
    }

    @Bean
    @SuppressWarnings("null")
    public JmsTemplate jmsTemplate(ConnectionFactory jmsConnectionFactory) {
        return new JmsTemplate(jmsConnectionFactory);
    }

    @Bean
    @SuppressWarnings("null")
    public PlatformTransactionManager transactionManager(ConnectionFactory connectionFactory) {
        // Spring automatically pairs this manager with @Transactional when processing JMS
        return new JmsTransactionManager(connectionFactory);
    }

    @Bean
    @SuppressWarnings("null")
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
