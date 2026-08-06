package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.LrsConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.xapi.util.StatementValidator;
import hla.rti1516e.InteractionClassHandle;
import hla.rti1516e.ParameterHandle;
import hla.rti1516e.ParameterHandleValueMap;
import hla.rti1516e.RTIambassador;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.HLA1516eHandle;
import org.portico.impl.hla1516e.types.HLA1516eParameterHandleValueMap;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class HlaInteractionDispatchTest {

    @Test
    void canonicalNestedInteractionsSubscribeAndDispatchIndependently() throws Exception {
        HLADecoderRegistry decoderRegistry =
                new HLADecoderRegistry(new HLA1516eEncoderFactory());
        FOMXML fomXml = new FOMXML(
                new SimulationConfig(
                        null,
                        null,
                        null,
                        null,
                        "src/test/resources/config/AmbiguousClassNamesFOM.xml"),
                decoderRegistry);
        InjectionHandler injectionHandler = new InjectionHandler();
        injectionHandler.setFomXml(fomXml);
        injectionHandler.setHLADecoderRegistry(decoderRegistry);
        FomCatalog catalog = new FomCatalog(fomXml);
        injectionHandler.setFomCatalog(catalog);
        StatementTrigger entityTrigger = interactionTrigger(
                "EntityEvents.Updated",
                """
                {"branch":"entity","id":["trigger",["EntityId"]],"value":["trigger",["Hunger"]]}
                """);
        StatementTrigger otherTrigger = interactionTrigger(
                "OtherEvents.Updated",
                """
                {"branch":"other","id":["trigger",["OtherId"]],"value":["trigger",["Speed"]]}
                """);
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(entityTrigger, otherTrigger);
        RecordingXapiClient xapiClient = new RecordingXapiClient();
        InteractionClassHandle entityClass =
                (InteractionClassHandle) new HLA1516eHandle(101);
        InteractionClassHandle otherClass =
                (InteractionClassHandle) new HLA1516eHandle(102);
        ParameterHandle entityId = (ParameterHandle) new HLA1516eHandle(201);
        ParameterHandle hunger = (ParameterHandle) new HLA1516eHandle(202);
        ParameterHandle otherId = (ParameterHandle) new HLA1516eHandle(203);
        ParameterHandle speed = (ParameterHandle) new HLA1516eHandle(204);
        Map<String, InteractionClassHandle> classHandles = Map.of(
                "EntityEvents.Updated", entityClass,
                "OtherEvents.Updated", otherClass);
        Map<InteractionClassHandle, String> classNames = Map.of(
                entityClass, "EntityEvents.Updated",
                otherClass, "OtherEvents.Updated");
        Map<ParameterHandle, String> parameterNames = Map.of(
                entityId, "EntityId",
                hunger, "Hunger",
                otherId, "OtherId",
                speed, "Speed");
        List<String> requestedClasses = new ArrayList<>();
        List<InteractionClassHandle> subscribedClasses = new ArrayList<>();
        RTIambassador ambassador = (RTIambassador) Proxy.newProxyInstance(
                RTIambassador.class.getClassLoader(),
                new Class<?>[] {RTIambassador.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInteractionClassHandle" -> {
                        String className = (String) args[0];
                        requestedClasses.add(className);
                        yield classHandles.get(className);
                    }
                    case "subscribeInteractionClass" -> {
                        subscribedClasses.add((InteractionClassHandle) args[0]);
                        yield null;
                    }
                    case "getInteractionClassName" ->
                            "HLAinteractionRoot." + classNames.get(args[0]);
                    case "getParameterName" -> parameterNames.get(args[1]);
                    default -> defaultValue(method.getReturnType());
                });
        HlaInterfaceImpl hlaInterface = new HlaInterfaceImpl();
        setField(hlaInterface, "ambassador", ambassador);
        setField(hlaInterface, "xapiConfig", config);
        setField(
                hlaInterface,
                "triggerProcessor",
                new TriggerProcessor(config, injectionHandler, catalog));
        setField(hlaInterface, "xapiClient", xapiClient);
        invokeSubscribeInteractions(hlaInterface);

        ParameterHandleValueMap entityParameters = new HLA1516eParameterHandleValueMap();
        entityParameters.put(entityId, HLAEncodingTestSupport.int32(10, ByteOrder.BIG_ENDIAN));
        entityParameters.put(hunger, HLAEncodingTestSupport.int32(11, ByteOrder.BIG_ENDIAN));
        hlaInterface.receiveInteraction(entityClass, entityParameters, null, null, null, null);

        ParameterHandleValueMap otherParameters = new HLA1516eParameterHandleValueMap();
        otherParameters.put(otherId, HLAEncodingTestSupport.int32(20, ByteOrder.BIG_ENDIAN));
        otherParameters.put(speed, HLAEncodingTestSupport.int32(21, ByteOrder.BIG_ENDIAN));
        hlaInterface.receiveInteraction(otherClass, otherParameters, null, null, null, null);

        assertEquals(
                List.of("EntityEvents.Updated", "OtherEvents.Updated"),
                requestedClasses);
        assertEquals(List.of(entityClass, otherClass), subscribedClasses);
        assertEquals(
                List.of(
                        "{\"branch\":\"entity\",\"id\":10,\"value\":11}",
                        "{\"branch\":\"other\",\"id\":20,\"value\":21}"),
                xapiClient.statements);
    }

    private static StatementTrigger interactionTrigger(String className, String statement) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.INTERACTION;
        trigger.clazz = className;
        trigger.statement = statement;
        return trigger;
    }

    private static void invokeSubscribeInteractions(HlaInterfaceImpl hlaInterface) throws Exception {
        Method method = HlaInterfaceImpl.class.getDeclaredMethod("subscribeInteractions");
        method.setAccessible(true);
        method.invoke(hlaInterface);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class RecordingXapiClient extends XapiClient {

        private final List<String> statements = new ArrayList<>();

        private RecordingXapiClient() {
            super(clientConfig(), new StatementValidator(), new JmsTemplate(), new TransactionTemplate());
        }

        @Override
        public void sendStatement(String statement) {
            statements.add(statement);
        }

        private static XapiConfig clientConfig() {
            LrsConfig lrs = new LrsConfig();
            lrs.host = "https://example.com/xapi/";
            lrs.key = "key";
            lrs.secret = "secret";
            lrs.batch = 10;
            lrs.maxRetries = 1;
            XapiConfig config = new XapiConfig();
            config.lrsConfig = lrs;
            return config;
        }
    }
}
