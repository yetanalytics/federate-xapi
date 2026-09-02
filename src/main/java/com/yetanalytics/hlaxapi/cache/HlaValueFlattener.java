package com.yetanalytics.hlaxapi.cache;

import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.FomDataElementFactory;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import hla.rti1516e.encoding.DataElement;
import hla.rti1516e.encoding.DecoderException;
import hla.rti1516e.encoding.EncoderException;
import hla.rti1516e.encoding.HLAfixedRecord;
import hla.rti1516e.encoding.HLAvariableArray;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.xpath.XPathExpressionException;

public class HlaValueFlattener {

    private final FOMXML fomXml;
    private final HLADecoderRegistry decoderRegistry;
    private final FomDataElementFactory dataElementFactory;

    public HlaValueFlattener(FOMXML fomXml, HLADecoderRegistry decoderRegistry) {
        this.fomXml = Objects.requireNonNull(fomXml, "fomXml");
        this.decoderRegistry = Objects.requireNonNull(decoderRegistry, "decoderRegistry");
        this.dataElementFactory = new FomDataElementFactory(fomXml, decoderRegistry);
    }

    public List<DecodedAttributeValue> flatten(String attributeName, String dataType, byte[] bytes) {
        Objects.requireNonNull(attributeName, "attributeName");
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(bytes, "bytes");
        try {
            DataElement element = dataElementFactory.create(dataType);
            element.decode(bytes);
            List<DecodedAttributeValue> values = new ArrayList<>();
            Object value = extractValue(attributeName, dataType, element, values);
            String primitiveType = primitiveType(dataType);
            if (primitiveType != null) {
                return values;
            }
            values.add(0, new DecodedAttributeValue(
                    attributeName,
                    dataType,
                    null,
                    value,
                    bytes,
                    false));
            return values;
        } catch (DecoderException | EncoderException | IllegalArgumentException e) {
            return List.of(new DecodedAttributeValue(attributeName, dataType, null, null, bytes, false));
        }
    }

    private Object extractValue(
            String pathKey,
            String dataType,
            DataElement element,
            List<DecodedAttributeValue> values) throws DecoderException, EncoderException {
        String primitiveType = primitiveType(dataType);
        if (primitiveType != null) {
            byte[] elementBytes = element.toByteArray();
            Object value = decoderRegistry.decode(primitiveType, elementBytes);
            values.add(new DecodedAttributeValue(
                    pathKey,
                    dataType,
                    primitiveType,
                    value,
                    elementBytes,
                    true));
            return value;
        }

        List<FOMXML.FixedRecordField> fields = fixedRecordFields(dataType);
        if (!fields.isEmpty() && element instanceof HLAfixedRecord record) {
            Map<String, Object> fieldValues = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                FOMXML.FixedRecordField field = fields.get(i);
                String fieldPath = pathKey + "." + field.name;
                fieldValues.put(field.name, extractValue(fieldPath, field.dataType, record.get(i), values));
            }
            return fieldValues;
        }

        String elementType = arrayElementType(dataType);
        if (elementType != null && element instanceof HLAvariableArray<?> variableArray) {
            List<Object> arrayValues = new ArrayList<>();
            int index = 0;
            for (DataElement child : variableArray) {
                String childPath = pathKey + "[" + index + "]";
                arrayValues.add(extractValue(childPath, elementType, child, values));
                index++;
            }
            return arrayValues;
        }

        return null;
    }

    private String primitiveType(String dataType) {
        try {
            return fomXml.resolvePrimitiveType(dataType);
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException("Could not resolve primitive type for " + dataType, e);
        }
    }

    private List<FOMXML.FixedRecordField> fixedRecordFields(String dataType) {
        try {
            if (!fomXml.isFixedRecordType(dataType)) {
                return List.of();
            }
            return fomXml.getFixedRecordFields(dataType);
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException("Could not resolve fixed record fields for " + dataType, e);
        }
    }

    private String arrayElementType(String dataType) {
        try {
            if (!fomXml.isArrayType(dataType)) {
                return null;
            }
            String elementType = fomXml.getArrayElementType(dataType);
            return elementType == null || elementType.isBlank() ? null : elementType;
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException("Could not resolve array element type for " + dataType, e);
        }
    }
}
