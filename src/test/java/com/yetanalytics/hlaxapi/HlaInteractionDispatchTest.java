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
import java.lang.reflect.Proxy;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.HLA1516eHandle;
import org.portico.impl.hla1516e.types.HLA1516eParameterHandleValueMap;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class HlaInteractionDispatchTest {

    @Test
    void interactionCallbackStillRendersAndEnqueuesThroughTheSharedDispatcher() throws Exception {
        HLADecoderRegistry decoderRegistry =
                new HLADecoderRegistry(new HLA1516eEncoderFactory());
        FOMXML fomXml = new FOMXML(
                new SimulationConfig(null, null, null, null, "config/HlaFedereplFOM.xml"),
                decoderRegistry);
        InjectionHandler injectionHandler = new InjectionHandler();
        injectionHandler.setFomXml(fomXml);
        injectionHandler.setHLADecoderRegistry(decoderRegistry);
        FomCatalog catalog = new FomCatalog(fomXml);
        injectionHandler.setFomCatalog(catalog);
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.INTERACTION;
        trigger.clazz = "StepCompleted";
        trigger.statement = """
                {"step":["trigger",["StepNumber"]]}
                """;
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(trigger);
        RecordingXapiClient xapiClient = new RecordingXapiClient();
        InteractionClassHandle interactionClass =
                (InteractionClassHandle) new HLA1516eHandle(101);
        ParameterHandle stepNumber = (ParameterHandle) new HLA1516eHandle(102);
        RTIambassador ambassador = (RTIambassador) Proxy.newProxyInstance(
                RTIambassador.class.getClassLoader(),
                new Class<?>[] {RTIambassador.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInteractionClassName" -> "HLAinteractionRoot.StepCompleted";
                    case "getParameterName" -> "StepNumber";
                    default -> defaultValue(method.getReturnType());
                });
        HlaInterfaceImpl hlaInterface = new HlaInterfaceImpl();
        setField(hlaInterface, "ambassador", ambassador);
        setField(
                hlaInterface,
                "triggerDispatcher",
                new StatementTriggerDispatcher(
                        config,
                        new TriggerProcessor(injectionHandler),
                        catalog));
        setField(hlaInterface, "xapiClient", xapiClient);
        ParameterHandleValueMap parameters = new HLA1516eParameterHandleValueMap();
        parameters.put(stepNumber, HLAEncodingTestSupport.int32(42, ByteOrder.BIG_ENDIAN));

        hlaInterface.receiveInteraction(interactionClass, parameters, null, null, null, null);

        assertEquals(List.of("{\"step\":42}"), xapiClient.statements);
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
            super(clientConfig(), new StatementValidator());
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
