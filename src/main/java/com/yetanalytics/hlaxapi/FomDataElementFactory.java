package com.yetanalytics.hlaxapi;

import hla.rti1516e.encoding.DataElement;
import hla.rti1516e.encoding.DataElementFactory;
import hla.rti1516e.encoding.HLAfixedRecord;
import hla.rti1516e.encoding.HLAvariableArray;
import javax.xml.xpath.XPathExpressionException;

/** Creates RTI-backed data elements for live federation value decoding. */
public final class FomDataElementFactory {

    private final FOMXML fomXml;
    private final HLADecoderRegistry decoderRegistry;

    public FomDataElementFactory(FOMXML fomXml, HLADecoderRegistry decoderRegistry) {
        this.fomXml = fomXml;
        this.decoderRegistry = decoderRegistry;
    }

    public DataElement create(String typeName) {
        try {
            String hlaType = fomXml.getRawType(typeName);
            if (hlaType != null) {
                return decoderRegistry.createElement(hlaType);
            }
            if (fomXml.isFixedRecordType(typeName)) {
                return createFixedRecord(typeName);
            }
            if (fomXml.isArrayType(typeName)) {
                return createArray(typeName);
            }
            throw new IllegalArgumentException("Unsupported data type: " + typeName);
        } catch (XPathExpressionException e) {
            throw new IllegalStateException("Failed to resolve HLA type for " + typeName, e);
        }
    }

    private HLAfixedRecord createFixedRecord(String fixedRecordType) throws XPathExpressionException {
        HLAfixedRecord record = decoderRegistry.getEncoderFactory().createHLAfixedRecord();
        for (FOMXML.FixedRecordField field : fomXml.getFixedRecordFields(fixedRecordType)) {
            record.add(create(field.dataType));
        }
        return record;
    }

    private HLAvariableArray<DataElement> createArray(String arrayType) throws XPathExpressionException {
        String elementType = fomXml.getArrayElementType(arrayType);
        if (elementType == null || elementType.isEmpty()) {
            throw new IllegalArgumentException("Unknown array element type for " + arrayType);
        }
        DataElementFactory<DataElement> factory = index -> create(elementType);
        return decoderRegistry.getEncoderFactory().createHLAvariableArray(factory);
    }
}
