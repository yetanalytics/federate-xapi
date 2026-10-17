package com.yetanalytics.hlaxapi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Standard HLA primitive metadata that does not require an RTI implementation. */
public final class StandardHlaTypeRegistry implements HlaTypeRegistry {

    private final Map<String, Class<?>> javaTypes;

    public StandardHlaTypeRegistry() {
        Map<String, Class<?>> types = new LinkedHashMap<>();
        register(types, Boolean.class, "HLAboolean");
        register(types, Short.class, "HLAinteger16BE", "HLAinteger16LE", "HLAinteger16");
        register(types, Integer.class, "HLAinteger32BE", "HLAinteger32LE", "HLAinteger32");
        register(types, Long.class, "HLAinteger64BE", "HLAinteger64LE", "HLAinteger64");
        register(types, Float.class, "HLAfloat32BE", "HLAfloat32LE", "HLAfloat32");
        register(types, Double.class, "HLAfloat64BE", "HLAfloat64LE", "HLAfloat64");
        register(types, Byte.class, "HLAoctet", "HLAbyte");
        register(types, Character.class, "HLAASCIIchar", "HLAunicodeChar", "HLAcharacter");
        register(types, Short.class, "HLAoctetPairBE", "HLAoctetPairLE", "HLAoctetPair");
        register(types, byte[].class, "HLAopaqueData");
        register(types, String.class, "HLAASCIIstring", "HLAunicodeString");
        this.javaTypes = Map.copyOf(types);
    }

    @Override
    public boolean supports(String hlaType) {
        return javaTypes.containsKey(normalize(hlaType));
    }

    @Override
    public Class<?> getClassForType(String hlaType) {
        Class<?> javaType = javaTypes.get(normalize(hlaType));
        if (javaType == null) {
            throw new IllegalArgumentException("Unsupported HLA primitive type " + hlaType);
        }
        return javaType;
    }

    private static void register(Map<String, Class<?>> types, Class<?> javaType, String... hlaTypes) {
        for (String hlaType : hlaTypes) {
            types.put(normalize(hlaType), javaType);
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
}
