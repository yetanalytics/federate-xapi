package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.cache.ObjectCache;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.LrsConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.injection.ObjectInjectionContext;
import com.yetanalytics.xapi.util.StatementValidator;
import hla.rti1516e.encoding.EncoderFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ObjectTriggerSpringWiringTest {

    @Test
    void springConstructsTheObjectTriggerBeanGraph() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.register(
                    TestDependencies.class,
                    FomCatalog.class,
                    InjectionHandler.class,
                    TriggerProcessor.class,
                    StatementTriggerDispatcher.class,
                    XapiClient.class,
                    HlaInterfaceImpl.class);
            context.refresh();

            ObjectCache cache = context.getBean(ObjectCache.class);
            InjectionHandler injectionHandler = context.getBean(InjectionHandler.class);
            StatementTriggerDispatcher dispatcher =
                    context.getBean(StatementTriggerDispatcher.class);

            assertSame(cache, injectionHandler.objectCache());
            assertSame(context.getBean(FomCatalog.class), cache.catalog());
            assertSame(
                    context.getBean(HlaInterfaceImpl.class),
                    context.getBean(HlaInterface.class));
            assertFalse(cache.isEnabled());
            assertEquals(
                    1,
                    dispatcher.stage(
                            StatementTrigger.Type.OBJECT_UPDATE,
                            "Rabbit",
                            new ObjectInjectionContext(
                                    "Rabbit",
                                    "object-1",
                                    Map.of()))
                            .size());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        EncoderFactory encoderFactory() {
            return new HLA1516eEncoderFactory();
        }

        @Bean
        HLADecoderRegistry decoderRegistry(EncoderFactory encoderFactory) {
            return new HLADecoderRegistry(encoderFactory);
        }

        @Bean
        SimulationConfig simulationConfig() {
            return new SimulationConfig(
                    null,
                    null,
                    null,
                    null,
                    "config/HlaFedereplFOM.xml");
        }

        @Bean
        FOMXML fomXml(
                SimulationConfig simulationConfig,
                HLADecoderRegistry decoderRegistry) {
            return new FOMXML(simulationConfig, decoderRegistry);
        }

        @Bean
        XapiConfig xapiConfig() {
            StatementTrigger trigger = new StatementTrigger();
            trigger.type = StatementTrigger.Type.OBJECT_UPDATE;
            trigger.clazz = "Rabbit";
            trigger.statement = "{}";
            XapiConfig config = new XapiConfig();
            config.statementTriggers = List.of(trigger);
            LrsConfig lrsConfig = new LrsConfig();
            lrsConfig.host = "http://localhost:8080/xapi";
            lrsConfig.key = "test";
            lrsConfig.secret = "test";
            lrsConfig.batch = 10;
            lrsConfig.maxRetries = 0;
            config.lrsConfig = lrsConfig;
            return config;
        }

        @Bean
        StatementValidator statementValidator() {
            return new StatementValidator();
        }

        @Bean(destroyMethod = "close")
        ObjectCache objectCache(
                XapiConfig xapiConfig,
                FomCatalog fomCatalog,
                FOMXML fomXml,
                HLADecoderRegistry decoderRegistry) {
            return new ObjectCache(
                    xapiConfig,
                    fomCatalog,
                    fomXml,
                    decoderRegistry);
        }
    }
}
