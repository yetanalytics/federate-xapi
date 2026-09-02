package com.yetanalytics.hlaxapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import hla.rti1516e.encoding.DataElement;
import hla.rti1516e.encoding.DataElementFactory;
import hla.rti1516e.encoding.DecoderException;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.encoding.HLAASCIIstring;
import hla.rti1516e.encoding.HLAboolean;
import hla.rti1516e.encoding.HLAbyte;
import hla.rti1516e.encoding.HLAfixedArray;
import hla.rti1516e.encoding.HLAfloat32BE;
import hla.rti1516e.encoding.HLAfloat32LE;
import hla.rti1516e.encoding.HLAfloat64BE;
import hla.rti1516e.encoding.HLAfloat64LE;
import hla.rti1516e.encoding.HLAinteger16BE;
import hla.rti1516e.encoding.HLAinteger16LE;
import hla.rti1516e.encoding.HLAinteger32BE;
import hla.rti1516e.encoding.HLAinteger32LE;
import hla.rti1516e.encoding.HLAinteger64BE;
import hla.rti1516e.encoding.HLAinteger64LE;
import hla.rti1516e.encoding.HLAoctet;
import hla.rti1516e.encoding.HLAoctetPairBE;
import hla.rti1516e.encoding.HLAoctetPairLE;
import hla.rti1516e.encoding.HLAopaqueData;
import hla.rti1516e.encoding.HLAunicodeString;
import hla.rti1516e.encoding.HLAvariableArray;

/**
 * Central registry for converting HLA-encoded byte arrays into Java values.
 *
 * Unsuffixed multi-byte aliases, such as HLAinteger32 and HLAfloat64, resolve to
 * the big-endian HLA encoder by default. Register a FOM-specific alias when the
 * FOM type name differs from its HLA representation.
 *
 * <p>This class is not thread-safe. If registrations may occur concurrently with decoding,
 * synchronize access externally.</p>
 */
public class HLADecoderRegistry implements HlaTypeRegistry {

    private EncoderFactory encoderFactory;
    private Map<String, RegisteredDecoder> decoders = new LinkedHashMap<String, RegisteredDecoder>();

    // No-arg constructor for Spring. When Spring constructs this bean the encoderFactory
    // will be null; code paths that require it should still use the explicit constructor.
    public HLADecoderRegistry() {
    }

    public HLADecoderRegistry(EncoderFactory encoderFactory) {
        this.encoderFactory = Objects.requireNonNull(encoderFactory, "encoderFactory");
        registerStandardDecoders(this.encoderFactory);
    }

    public <T> void register(String hlaType, Class<T> javaType, HLAValueDecoder<T> decoder) {
        String normalizedType = normalize(hlaType);
        putDecoder(normalizedType, new RegisteredDecoder(
            hlaType.trim(),
            Objects.requireNonNull(javaType, "javaType"),
            Objects.requireNonNull(decoder, "decoder"),
            null,
            null));
    }

    public void registerAlias(String alias, String targetType) {
        String normalizedAlias = normalize(alias);
        String normalizedTargetType = normalize(targetType);
        lookupRegisteredDecoder(normalizedTargetType);
        putDecoder(normalizedAlias, new RegisteredDecoder(
            alias.trim(),
            null,
            null,
            null,
            normalizedTargetType));
    }

    /**
     * Registers an HLAvariableArray decoder. Decoded arrays are returned as
     * immutable List instances. The element type must be backed by an HLA
     * DataElement, which includes built-in primitive decoders, aliases of those
     * decoders, and arrays previously registered through this registry.
     */
    public <T> void registerVariableArray(String hlaType, String elementHlaType, Class<T> elementJavaType) {
        RegisteredDecoder element = lookupRegisteredDecoder(normalize(elementHlaType)).resolved(this);
        requireJavaType(elementHlaType, elementJavaType, element);
        ElementAdapter elementAdapter = requireElementAdapter(elementHlaType, element);
        registerElementBacked(hlaType, List.class, variableArrayAdapter(elementAdapter));
    }

    /**
     * Registers an HLAfixedArray decoder. Decoded arrays are returned as immutable
     * List instances. The element type must be backed by an HLA DataElement, which
     * includes built-in primitive decoders, aliases of those decoders, and arrays
     * previously registered through this registry.
     */
    public <T> void registerFixedArray(String hlaType, String elementHlaType, int size, Class<T> elementJavaType) {
        if (size < 0) {
            throw new IllegalArgumentException("Fixed array size must not be negative: " + size);
        }
        RegisteredDecoder element = lookupRegisteredDecoder(normalize(elementHlaType)).resolved(this);
        requireJavaType(elementHlaType, elementJavaType, element);
        ElementAdapter elementAdapter = requireElementAdapter(elementHlaType, element);
        registerElementBacked(hlaType, List.class, fixedArrayAdapter(elementAdapter, size));
    }

    public Set<String> supportedTypes() {
        return Collections.unmodifiableSet(decoders.keySet());
    }

    @Override
    public boolean supports(String hlaType) {
        return decoders.containsKey(normalize(hlaType));
    }

    public HLAValueDecoder<?> decoderFor(String hlaType) {
        return registeredDecoderFor(hlaType).decoder();
    }

    @Override
    public Class<?> getClassForType(String hlaType) {
        return registeredDecoderFor(hlaType).javaType();
    }

    public <T> HLAValueDecoder<T> decoderFor(String hlaType, Class<T> javaType) {
        RegisteredDecoder registered = registeredDecoderFor(hlaType);
        requireJavaType(hlaType, javaType, registered);
        @SuppressWarnings("unchecked")
        HLAValueDecoder<T> decoder = (HLAValueDecoder<T>) registered.decoder();
        return decoder;
    }

    public Object decode(String hlaType, byte[] bytes) throws DecoderException {
        return decoderFor(hlaType).decode(bytes);
    }

    public <T> T decode(String hlaType, byte[] bytes, Class<T> javaType) throws DecoderException {
        Object value = decode(hlaType, bytes);
        if (value == null) {
            return null;
        }
        Class<?> expectedType = wrapperType(Objects.requireNonNull(javaType, "javaType"));
        if (!expectedType.isInstance(value)) {
            throw new IllegalArgumentException("HLA type " + normalize(hlaType)
                    + " decodes to " + value.getClass().getName()
                    + ", not " + expectedType.getName());
        }
        @SuppressWarnings("unchecked")
        T typedValue = (T) value;
        return typedValue;
    }

    public EncoderFactory getEncoderFactory() {
        return encoderFactory;
    }

    public DataElement createElement(String hlaType) {
        String normalizedType = normalize(hlaType);
        RegisteredDecoder registered = lookupRegisteredDecoder(normalizedType).resolved(this);
        if (registered.elementAdapter() == null) {
            throw new IllegalArgumentException("HLA type " + hlaType + " is not backed by a DataElement");
        }
        return registered.elementAdapter().createElement();
    }

    private void registerStandardDecoders(EncoderFactory encoderFactory) {
        registerElement("HLAboolean", Boolean.class, encoderFactory::createHLAboolean, HLAboolean::getValue);

        registerElement("HLAinteger16BE", Short.class, encoderFactory::createHLAinteger16BE,
                HLAinteger16BE::getValue);
        registerElement("HLAinteger16LE", Short.class, encoderFactory::createHLAinteger16LE,
                HLAinteger16LE::getValue);
        registerAlias("HLAinteger16", "HLAinteger16BE");

        registerElement("HLAinteger32BE", Integer.class, encoderFactory::createHLAinteger32BE,
                HLAinteger32BE::getValue);
        registerElement("HLAinteger32LE", Integer.class, encoderFactory::createHLAinteger32LE,
                HLAinteger32LE::getValue);
        registerAlias("HLAinteger32", "HLAinteger32BE");

        registerElement("HLAinteger64BE", Long.class, encoderFactory::createHLAinteger64BE, HLAinteger64BE::getValue);
        registerElement("HLAinteger64LE", Long.class, encoderFactory::createHLAinteger64LE, HLAinteger64LE::getValue);
        registerAlias("HLAinteger64", "HLAinteger64BE");

        registerElement("HLAfloat32BE", Float.class, encoderFactory::createHLAfloat32BE, HLAfloat32BE::getValue);
        registerElement("HLAfloat32LE", Float.class, encoderFactory::createHLAfloat32LE, HLAfloat32LE::getValue);
        registerAlias("HLAfloat32", "HLAfloat32BE");

        registerElement("HLAfloat64BE", Double.class, encoderFactory::createHLAfloat64BE, HLAfloat64BE::getValue);
        registerElement("HLAfloat64LE", Double.class, encoderFactory::createHLAfloat64LE, HLAfloat64LE::getValue);
        registerAlias("HLAfloat64", "HLAfloat64BE");

        registerElement("HLAoctet", Byte.class, encoderFactory::createHLAoctet, HLAoctet::getValue);
        registerElement("HLAbyte", Byte.class, encoderFactory::createHLAbyte, HLAbyte::getValue);
        registerElement("HLAASCIIchar", Character.class, encoderFactory::createHLAASCIIchar,
                element -> Character.valueOf((char) (element.getValue() & 0xFF)));
        registerElement("HLAunicodeChar", Character.class, encoderFactory::createHLAunicodeChar,
                element -> Character.valueOf((char) (element.getValue() & 0xFFFF)));
        registerAlias("HLAcharacter", "HLAASCIIchar");

        registerElement("HLAoctetPairBE", Short.class, encoderFactory::createHLAoctetPairBE,
                HLAoctetPairBE::getValue);
        registerElement("HLAoctetPairLE", Short.class, encoderFactory::createHLAoctetPairLE,
                HLAoctetPairLE::getValue);
        registerAlias("HLAoctetPair", "HLAoctetPairBE");

        registerElement("HLAopaqueData", byte[].class, encoderFactory::createHLAopaqueData, HLAopaqueData::getValue);
        registerElement("HLAASCIIstring", String.class, encoderFactory::createHLAASCIIstring, HLAASCIIstring::getValue);
        registerElement("HLAunicodeString", String.class, encoderFactory::createHLAunicodeString,
                HLAunicodeString::getValue);
    }

    private <E extends DataElement, T> void registerElement(
            String hlaType,
            Class<T> javaType,
            Supplier<E> dataElementSupplier,
            Function<E, T> valueExtractor) {
        ElementAdapter adapter = new ElementAdapter() {
            @Override
            public DataElement createElement() {
                return dataElementSupplier.get();
            }

            @Override
            public Object extractValue(DataElement element) {
                return valueExtractor.apply(elementType(element));
            }

            @SuppressWarnings("unchecked")
            private E elementType(DataElement element) {
                return (E) element;
            }
        };
        registerElementBacked(hlaType, javaType, adapter);
    }

    private void registerElementBacked(String hlaType, Class<?> javaType, ElementAdapter elementAdapter) {
        ElementAdapter adapter = Objects.requireNonNull(elementAdapter, "elementAdapter");
        ThreadLocal<DataElement> elementCache = ThreadLocal.withInitial(adapter::createElement);
        HLAValueDecoder<Object> decoder = bytes -> {
            DataElement element = elementCache.get();
            element.decode(bytes);
            return adapter.extractValue(element);
        };
        String normalizedType = normalize(hlaType);
        putDecoder(normalizedType, new RegisteredDecoder(
                hlaType.trim(),
                Objects.requireNonNull(javaType, "javaType"),
                decoder,
                adapter,
                null));
    }

    private ElementAdapter variableArrayAdapter(ElementAdapter elementAdapter) {
        return new ElementAdapter() {
            @Override
            public DataElement createElement() {
                DataElementFactory<DataElement> factory = index -> elementAdapter.createElement();
                return encoderFactory.createHLAvariableArray(factory);
            }

            @Override
            public Object extractValue(DataElement element) {
                HLAvariableArray<?> array = (HLAvariableArray<?>) element;
                return extractArrayValues(array, elementAdapter);
            }
        };
    }

    private ElementAdapter fixedArrayAdapter(ElementAdapter elementAdapter, int size) {
        return new ElementAdapter() {
            @Override
            public DataElement createElement() {
                DataElementFactory<DataElement> factory = index -> elementAdapter.createElement();
                return encoderFactory.createHLAfixedArray(factory, size);
            }

            @Override
            public Object extractValue(DataElement element) {
                HLAfixedArray<?> array = (HLAfixedArray<?>) element;
                return extractArrayValues(array, elementAdapter);
            }
        };
    }

    private static List<Object> extractArrayValues(
            Iterable<? extends DataElement> array,
            ElementAdapter elementAdapter) {
        List<Object> values = new ArrayList<Object>();
        for (DataElement element : array) {
            values.add(elementAdapter.extractValue(element));
        }
        return Collections.unmodifiableList(values);
    }

    private static ElementAdapter requireElementAdapter(String hlaType, RegisteredDecoder registered) {
        if (registered.elementAdapter() == null) {
            throw new IllegalArgumentException("HLA type " + normalize(hlaType)
                    + " is registered as a value decoder and cannot be used as an array element");
        }
        return registered.elementAdapter();
    }

    private RegisteredDecoder registeredDecoderFor(String hlaType) {
        return lookupRegisteredDecoder(normalize(hlaType)).resolved(this);
    }

    public RegisteredDecoder lookupRegisteredDecoder(String normalizedType) {
        RegisteredDecoder registered = decoders.get(normalizedType);
        if (registered == null) {
            throw new IllegalArgumentException("No HLA decoder registered for type " + normalizedType
                    + ". Supported types: " + decoders.keySet());
        }
        return registered;
    }

    private void putDecoder(String normalizedType, RegisteredDecoder registeredDecoder) {
        RegisteredDecoder existing = decoders.get(normalizedType);
        if (existing != null && !existing.registeredType().equals(registeredDecoder.registeredType())) {
            throw new IllegalArgumentException("HLA type " + normalizedType
                    + " is already registered as " + existing.registeredType());
        }
        decoders.put(normalizedType, registeredDecoder);
    }

    private void requireJavaType(String hlaType, Class<?> requestedJavaType, RegisteredDecoder registered) {
        Class<?> requestedType = wrapperType(Objects.requireNonNull(requestedJavaType, "requestedJavaType"));
        if (!requestedType.isAssignableFrom(registered.javaType())) {
            throw new IllegalArgumentException("HLA type " + normalize(hlaType)
                    + " decodes to " + registered.javaType().getName()
                    + ", not " + requestedType.getName());
        }
    }

    private static String normalize(String hlaType) {
        String normalizedType = Objects.requireNonNull(hlaType, "hlaType").trim();
        int packageSeparator = normalizedType.lastIndexOf('.');
        if (packageSeparator >= 0) {
            normalizedType = normalizedType.substring(packageSeparator + 1);
        }
        if (normalizedType.isEmpty()) {
            throw new IllegalArgumentException("HLA type must not be blank");
        }
        return normalizedType;
    }

    private static Class<?> wrapperType(Class<?> javaType) {
        if (!javaType.isPrimitive()) {
            return javaType;
        }
        if (javaType == boolean.class) {
            return Boolean.class;
        }
        if (javaType == byte.class) {
            return Byte.class;
        }
        if (javaType == short.class) {
            return Short.class;
        }
        if (javaType == int.class) {
            return Integer.class;
        }
        if (javaType == long.class) {
            return Long.class;
        }
        if (javaType == float.class) {
            return Float.class;
        }
        if (javaType == double.class) {
            return Double.class;
        }
        if (javaType == char.class) {
            return Character.class;
        }
        return javaType;
    }

    private interface ElementAdapter {
        DataElement createElement();

        Object extractValue(DataElement element);
    }

    private static final class RegisteredDecoder {

        private final String registeredType;
        private final Class<?> javaType;
        private final HLAValueDecoder<?> decoder;
        private final ElementAdapter elementAdapter;
        private final String targetType;

        private RegisteredDecoder(
                String registeredType,
                Class<?> javaType,
                HLAValueDecoder<?> decoder,
                ElementAdapter elementAdapter,
                String targetType) {
            this.registeredType = Objects.requireNonNull(registeredType, "registeredType");
            this.javaType = javaType;
            this.decoder = decoder;
            this.elementAdapter = elementAdapter;
            this.targetType = targetType;
        }

        private String registeredType() {
            return registeredType;
        }

        private Class<?> javaType() {
            return javaType;
        }

        private HLAValueDecoder<?> decoder() {
            return decoder;
        }

        private ElementAdapter elementAdapter() {
            return elementAdapter;
        }

        private RegisteredDecoder resolved(HLADecoderRegistry registry) {
            if (targetType == null) {
                return this;
            }
            if (registry == null) {
                return this;
            }
            return resolveTarget(registry, targetType, new java.util.LinkedHashSet<String>());
        }

        private RegisteredDecoder resolveTarget(
                HLADecoderRegistry registry,
                String normalizedType,
                java.util.Set<String> seenTypes) {
            if (!seenTypes.add(normalizedType)) {
                throw new IllegalArgumentException("Alias cycle detected involving HLA type " + normalizedType);
            }
            RegisteredDecoder next = registry.lookupRegisteredDecoder(normalizedType);
            if (next.targetType == null) {
                return next;
            }
            return next.resolveTarget(registry, next.targetType, seenTypes);
        }
    }
}
