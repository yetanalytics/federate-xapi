package com.yetanalytics.hlaxapi;

import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.asciiString;
import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.fixedArray;
import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.float32;
import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.float64;
import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.int16;
import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.int32;
import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.int64;
import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.testEncoderFactory;
import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.unicodeString;
import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.variableArray;
import static com.yetanalytics.hlaxapi.HLAEncodingTestSupport.variableBytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import hla.rti1516e.encoding.DecoderException;

class HLADecoderRegistryTest {

    private final HLADecoderRegistry registry = new HLADecoderRegistry(testEncoderFactory());

    @Test
    void validatorMetadataMatchesRuntimeDecoderTypes() {
        StandardHlaTypeRegistry metadata = new StandardHlaTypeRegistry();

        for (String hlaType : registry.supportedTypes()) {
            assertTrue(metadata.supports(hlaType), hlaType);
            assertEquals(registry.getClassForType(hlaType), metadata.getClassForType(hlaType), hlaType);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("builtInDecodings")
    void decodesBuiltInHlaTypes(String hlaType, byte[] encodedValue, Object expectedValue) throws DecoderException {
        Object actualValue = registry.decode(hlaType, encodedValue);

        if (expectedValue instanceof byte[] expectedBytes) {
            assertArrayEquals(expectedBytes, (byte[]) actualValue);
        } else {
            assertEquals(expectedValue, actualValue);
        }
    }

    @Test
    void returnsTypedDecoderForRegisteredType() throws DecoderException {
        HLAValueDecoder<Float> decoder = registry.decoderFor("HLAfloat32LE", Float.class);

        assertEquals(3.5f, decoder.decode(float32(3.5f, ByteOrder.LITTLE_ENDIAN)));
    }

    @Test
    void decodesPackageQualifiedTypeNames() throws DecoderException {
        assertEquals(42, registry.decode(
                "hla.rti1516e.encoding.HLAinteger32BE",
                int32(42, ByteOrder.BIG_ENDIAN),
                Integer.class));
    }

    @Test
    void supportsFomSpecificAliases() throws DecoderException {
        registry.registerAlias("ScaleFactorFloat32", "HLAfloat32BE");

        assertEquals(2.25f, registry.decode(
                "ScaleFactorFloat32",
                float32(2.25f, ByteOrder.BIG_ENDIAN),
                Float.class));
    }

    @Test
    void aliasesTrackRetargetedDecoders() throws DecoderException {
        registry.registerAlias("ScaleFactorFloat32", "HLAfloat32BE");
        registry.register("HLAfloat32BE", Float.class, bytes -> 9.5f);

        assertEquals(9.5f, registry.decode(
                "ScaleFactorFloat32",
                new byte[0],
                Float.class));
    }

    @Test
    void decodesRegisteredVariableArrays() throws DecoderException {
        registry.registerVariableArray("IntegerHistory", "HLAinteger32BE", Integer.class);

        assertEquals(List.of(7, -3, 42), registry.decode(
                "IntegerHistory",
                variableArray(
                        int32(7, ByteOrder.BIG_ENDIAN),
                        int32(-3, ByteOrder.BIG_ENDIAN),
                        int32(42, ByteOrder.BIG_ENDIAN)),
                List.class));
    }

    @Test
    void decodesArraysOfAliases() throws DecoderException {
        registry.registerAlias("FuelInt32", "HLAinteger32BE");
        registry.registerVariableArray("FuelHistory", "FuelInt32", Integer.class);

        assertEquals(List.of(100, 75, 50), registry.decode(
                "FuelHistory",
                variableArray(
                        int32(100, ByteOrder.BIG_ENDIAN),
                        int32(75, ByteOrder.BIG_ENDIAN),
                        int32(50, ByteOrder.BIG_ENDIAN)),
                List.class));
    }

    @Test
    void decodesRegisteredFixedArrays() throws DecoderException {
        registry.registerFixedArray("EulerAngles", "HLAfloat64LE", 3, Double.class);

        assertEquals(List.of(10.5d, -20.25d, 30.125d), registry.decode(
                "EulerAngles",
                fixedArray(
                        float64(10.5d, ByteOrder.LITTLE_ENDIAN),
                        float64(-20.25d, ByteOrder.LITTLE_ENDIAN),
                        float64(30.125d, ByteOrder.LITTLE_ENDIAN)),
                List.class));
    }

    @Test
    void decodesNestedRegisteredArrays() throws DecoderException {
        registry.registerFixedArray("CoordinatePair", "HLAinteger16BE", 2, Short.class);
        registry.registerVariableArray("CoordinateHistory", "CoordinatePair", List.class);

        assertEquals(
                List.of(
                        List.of((short) 1, (short) 2),
                        List.of((short) -3, (short) 4)),
                registry.decode(
                        "CoordinateHistory",
                        variableArray(
                                fixedArray(
                                        int16((short) 1, ByteOrder.BIG_ENDIAN),
                                        int16((short) 2, ByteOrder.BIG_ENDIAN)),
                                fixedArray(
                                        int16((short) -3, ByteOrder.BIG_ENDIAN),
                                        int16((short) 4, ByteOrder.BIG_ENDIAN))),
                        List.class));
    }

    @Test
    void rejectsUnknownTypes() {
        assertThrows(IllegalArgumentException.class, () -> registry.decoderFor("MissingHlaType"));
    }

    @Test
    void rejectsMismatchedJavaTypes() {
        assertThrows(IllegalArgumentException.class, () -> registry.decoderFor("HLAfloat32BE", Integer.class));
    }

    @Test
    void rejectsNegativeFixedArraySizes() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerFixedArray("BadFixedArray", "HLAinteger32BE", -1, Integer.class));
    }

    @Test
    void rejectsNormalizedNameCollisions() {
        registry.register("com.example.CustomFloat", Float.class, bytes -> 1.0f);

        assertThrows(IllegalArgumentException.class,
                () -> registry.register("org.example.CustomFloat", Float.class, bytes -> 2.0f));
    }

    @Test
    void rejectsValueOnlyDecodersAsArrayElements() {
        registry.register("CustomValue", Object.class, bytes -> new Object());

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerVariableArray("CustomArray", "CustomValue", Object.class));
    }

    static Stream<Arguments> builtInDecodings() {
        return Stream.of(
                Arguments.of("HLAboolean", int32(1, ByteOrder.BIG_ENDIAN), Boolean.TRUE),
                Arguments.of("HLAinteger16BE", int16((short) -12345, ByteOrder.BIG_ENDIAN), (short) -12345),
                Arguments.of("HLAinteger16LE", int16((short) -12345, ByteOrder.LITTLE_ENDIAN), (short) -12345),
                Arguments.of("HLAinteger16", int16((short) 12345, ByteOrder.BIG_ENDIAN), (short) 12345),
                Arguments.of("HLAinteger32BE", int32(-1234567, ByteOrder.BIG_ENDIAN), -1234567),
                Arguments.of("HLAinteger32LE", int32(-1234567, ByteOrder.LITTLE_ENDIAN), -1234567),
                Arguments.of("HLAinteger32", int32(7654321, ByteOrder.BIG_ENDIAN), 7654321),
                Arguments.of("HLAinteger64BE", int64(-123456789012345L, ByteOrder.BIG_ENDIAN), -123456789012345L),
                Arguments.of("HLAinteger64LE", int64(-123456789012345L, ByteOrder.LITTLE_ENDIAN), -123456789012345L),
                Arguments.of("HLAinteger64", int64(123456789012345L, ByteOrder.BIG_ENDIAN), 123456789012345L),
                Arguments.of("HLAfloat32BE", float32(12.5f, ByteOrder.BIG_ENDIAN), 12.5f),
                Arguments.of("HLAfloat32LE", float32(12.5f, ByteOrder.LITTLE_ENDIAN), 12.5f),
                Arguments.of("HLAfloat32", float32(-6.75f, ByteOrder.BIG_ENDIAN), -6.75f),
                Arguments.of("HLAfloat64BE", float64(-42.25d, ByteOrder.BIG_ENDIAN), -42.25d),
                Arguments.of("HLAfloat64LE", float64(-42.25d, ByteOrder.LITTLE_ENDIAN), -42.25d),
                Arguments.of("HLAfloat64", float64(128.5d, ByteOrder.BIG_ENDIAN), 128.5d),
                Arguments.of("HLAoctet", new byte[] { (byte) 0xA5 }, (byte) 0xA5),
                Arguments.of("HLAbyte", new byte[] { (byte) 0x80 }, (byte) 0x80),
                Arguments.of("HLAASCIIchar", new byte[] { 'Z' }, 'Z'),
                Arguments.of("HLAunicodeChar", int16((short) '\u03a9', ByteOrder.BIG_ENDIAN), '\u03a9'),
                Arguments.of("HLAcharacter", new byte[] { 'A' }, 'A'),
                Arguments.of("HLAoctetPairBE", int16((short) 0x1234, ByteOrder.BIG_ENDIAN), (short) 0x1234),
                Arguments.of("HLAoctetPairLE", int16((short) 0x1234, ByteOrder.LITTLE_ENDIAN), (short) 0x1234),
                Arguments.of("HLAoctetPair", int16((short) 0x4567, ByteOrder.BIG_ENDIAN), (short) 0x4567),
                Arguments.of("HLAopaqueData", variableBytes(new byte[] { 1, 2, (byte) 0xFF }),
                        new byte[] { 1, 2, (byte) 0xFF }),
                Arguments.of("HLAASCIIstring", asciiString("xAPI Ready"), "xAPI Ready"),
                Arguments.of("HLAunicodeString", unicodeString("R\u00e9sum\u00e9"), "R\u00e9sum\u00e9"));
    }
}
