package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import com.yetanalytics.hlaxapi.HLAEncodingTestSupport;
import com.yetanalytics.hlaxapi.HlaInterfaceImpl;
import com.yetanalytics.hlaxapi.SimulationConfig;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import hla.rti1516e.AttributeHandle;
import hla.rti1516e.AttributeHandleSet;
import hla.rti1516e.AttributeHandleValueMap;
import hla.rti1516e.ObjectClassHandle;
import hla.rti1516e.ObjectInstanceHandle;
import hla.rti1516e.RTIambassador;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.portico.impl.hla1516e.types.HLA1516eAttributeHandleSetFactory;
import org.portico.impl.hla1516e.types.HLA1516eAttributeHandleValueMap;
import org.portico.impl.hla1516e.types.HLA1516eHandle;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class HlaObjectSubscriptionTest {

    private final HLADecoderRegistry decoderRegistry =
            new HLADecoderRegistry(new HLA1516eEncoderFactory());
    private final FOMXML fomXml = new FOMXML(
            new SimulationConfig(null, null, null, null, "config/HlaFedereplFOM.xml"),
            decoderRegistry);
    private final FomCatalog catalog = new FomCatalog(fomXml);

    @Test
    void eventOnlyConfigurationSubscribesRequestsAndProcessesReflections() throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(objectUpdateTrigger("Rabbit"));
        Set<String> expectedAttributes =
                Set.copyOf(catalog.objectClass("Rabbit").orElseThrow().topLevelAttributeNames());

        try (ObjectCache cache = new ObjectCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();
            HlaInterfaceImpl hlaInterface = hlaInterface(cache, rti.proxy());

            subscribeObjectClasses(hlaInterface);

            assertFalse(cache.isEnabled());
            assertEquals(List.of(new ObjectSubscription("Rabbit", expectedAttributes)), rti.subscriptions);

            ObjectClassHandle rabbitClass = rti.classHandle("Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(91);
            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit One");

            assertEquals(1, rti.requests.size());
            assertEquals(rabbit, rti.requests.get(0).objectHandle());
            assertEquals(expectedAttributes, rti.requests.get(0).attributes());

            AttributeHandle hunger = rti.attributeHandle(rabbitClass, "Hunger");
            AttributeHandleValueMap reflection = new HLA1516eAttributeHandleValueMap();
            reflection.put(hunger, HLAEncodingTestSupport.int32(12, ByteOrder.BIG_ENDIAN));
            hlaInterface.reflectAttributeValues(rabbit, reflection, null, null, null, null);

            assertEquals(1, rti.knownClassResolutions);
            assertEquals(1, rti.attributeNameResolutions);
            assertTrue(cache.currentObjects("Rabbit").isEmpty());
        }
    }

    @Test
    void discoveryCachesMetadataAndRequestsMergedAttributes(@TempDir Path tempDir) throws Exception {
        XapiConfig config = configWithQueryAndObjectUpdate();
        try (ObjectCache cache = new ObjectCache(
                config,
                catalog,
                fomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve("discovery.sqlite"))) {
            RecordingRti rti = new RecordingRti();
            HlaInterfaceImpl hlaInterface = hlaInterface(cache, rti.proxy());
            ObjectClassHandle rabbitClass = rti.classHandle("Rabbit");
            ObjectInstanceHandle rabbit = rti.objectHandle(92);

            hlaInterface.discoverObjectInstance(rabbit, rabbitClass, "Rabbit Two");

            assertTrue(cache.isEnabled());
            assertEquals(1, cache.currentObjects("Rabbit").size());
            assertEquals("Rabbit Two", cache.currentObjects("Rabbit").get(0).objectName());
            assertEquals(cache.subscriptions().get("Rabbit"), rti.requests.get(0).attributes());
        }
    }

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.HlaInterfaceImpl"})
    void unknownObjectUpdateClassIsSkippedDuringSubscription() throws Exception {
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(objectUpdateTrigger("MissingObject"));

        try (ObjectCache cache = new ObjectCache(config, catalog, fomXml, decoderRegistry)) {
            RecordingRti rti = new RecordingRti();

            subscribeObjectClasses(hlaInterface(cache, rti.proxy()));

            assertTrue(rti.subscriptions.isEmpty());
        }
    }

    private XapiConfig configWithQueryAndObjectUpdate() {
        StatementTrigger query = new StatementTrigger();
        query.statement = """
                {"actor":{"name":["query","Rabbit",["EntityId"],[["Hunger"],">",50]]}}
                """;
        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(query, objectUpdateTrigger("Rabbit"));
        return config;
    }

    private StatementTrigger objectUpdateTrigger(String className) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.OBJECT_UPDATE;
        trigger.clazz = className;
        trigger.statement = "{}";
        return trigger;
    }

    private HlaInterfaceImpl hlaInterface(ObjectCache cache, RTIambassador ambassador) throws Exception {
        HlaInterfaceImpl hlaInterface = new HlaInterfaceImpl();
        setField(hlaInterface, "objectCache", cache);
        setField(hlaInterface, "ambassador", ambassador);
        return hlaInterface;
    }

    private void subscribeObjectClasses(HlaInterfaceImpl hlaInterface) throws Exception {
        Method method = HlaInterfaceImpl.class.getDeclaredMethod("subscribeObjectClasses");
        method.setAccessible(true);
        method.invoke(hlaInterface);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record ObjectSubscription(String className, Set<String> attributes) {
    }

    private record AttributeRequest(ObjectInstanceHandle objectHandle, Set<String> attributes) {
    }

    private static final class RecordingRti implements InvocationHandler {

        private final Map<String, ObjectClassHandle> classes = new LinkedHashMap<>();
        private final Map<ObjectClassHandle, String> classNames = new LinkedHashMap<>();
        private final Map<String, AttributeHandle> attributes = new LinkedHashMap<>();
        private final Map<AttributeHandle, String> attributeNames = new LinkedHashMap<>();
        private final List<ObjectSubscription> subscriptions = new ArrayList<>();
        private final List<AttributeRequest> requests = new ArrayList<>();
        private int nextClassHandle = 1;
        private int nextAttributeHandle = 1_000;
        private int knownClassResolutions;
        private int attributeNameResolutions;
        private ObjectClassHandle knownClass;

        private RTIambassador proxy() {
            return (RTIambassador) Proxy.newProxyInstance(
                    RTIambassador.class.getClassLoader(),
                    new Class<?>[] {RTIambassador.class},
                    this);
        }

        private ObjectClassHandle classHandle(String className) {
            ObjectClassHandle handle = classes.computeIfAbsent(
                    className,
                    ignored -> (ObjectClassHandle) new HLA1516eHandle(nextClassHandle++));
            classNames.put(handle, className);
            knownClass = handle;
            return handle;
        }

        private ObjectInstanceHandle objectHandle(int value) {
            return (ObjectInstanceHandle) new HLA1516eHandle(value);
        }

        private AttributeHandle attributeHandle(ObjectClassHandle classHandle, String attributeName) {
            String key = classNames.get(classHandle) + "." + attributeName;
            AttributeHandle handle = attributes.computeIfAbsent(
                    key,
                    ignored -> (AttributeHandle) new HLA1516eHandle(nextAttributeHandle++));
            attributeNames.put(handle, attributeName);
            return handle;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getObjectClassHandle" -> classHandle((String) args[0]);
                case "getObjectClassName" -> qualifiedClassName(classNames.get(args[0]));
                case "getAttributeHandleSetFactory" -> new HLA1516eAttributeHandleSetFactory();
                case "getAttributeHandle" -> attributeHandle((ObjectClassHandle) args[0], (String) args[1]);
                case "subscribeObjectClassAttributes" -> {
                    subscriptions.add(new ObjectSubscription(
                            classNames.get(args[0]),
                            names((AttributeHandleSet) args[1])));
                    yield null;
                }
                case "requestAttributeValueUpdate" -> {
                    requests.add(new AttributeRequest(
                            (ObjectInstanceHandle) args[0],
                            names((AttributeHandleSet) args[1])));
                    yield null;
                }
                case "getKnownObjectClassHandle" -> {
                    knownClassResolutions++;
                    yield knownClass;
                }
                case "getAttributeName" -> {
                    attributeNameResolutions++;
                    yield attributeNames.get(args[1]);
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        private String qualifiedClassName(String className) {
            return switch (className) {
                case "Carrot", "Rabbit", "Wolf" -> "HLAobjectRoot.SimEntity." + className;
                case "SimEntity", "World" -> "HLAobjectRoot." + className;
                default -> className;
            };
        }

        private Set<String> names(AttributeHandleSet handles) {
            Set<String> names = new LinkedHashSet<>();
            for (AttributeHandle handle : handles) {
                names.add(attributeNames.get(handle));
            }
            return Set.copyOf(names);
        }

        private Object defaultValue(Class<?> returnType) {
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
    }
}
