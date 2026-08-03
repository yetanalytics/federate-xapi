package com.yetanalytics.hlaxapi;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.xml.sax.SAXException;

import hla.rti1516e.encoding.DataElement;
import hla.rti1516e.encoding.DataElementFactory;
import hla.rti1516e.encoding.HLAfixedRecord;
import hla.rti1516e.encoding.HLAvariableArray;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class FOMXML {

    private static final Logger logger = LogManager.getLogger(App.class);

    private Document doc;
    private XPath xPath;
    private HLADecoderRegistry decoderRegistry;

    //automatically injected by spring
    public FOMXML(SimulationConfig simConfig, HLADecoderRegistry decoderRegistry) {
        File xmlFile = new File(simConfig.getFom());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);

        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(xmlFile);
        } catch (SAXException | IOException | ParserConfigurationException e) {
            logger.error("Could not parse FOM XML.", e);
        }

        xPath = XPathFactory.newInstance().newXPath();
        this.decoderRegistry = decoderRegistry;
    }

    private boolean isPrim(String type) {
        try {
            decoderRegistry.decoderFor(type);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Result holder for path checks.
     */
    public static class PathCheckResult {
        public final boolean exists;
        /**
         * The resolved primitive type (e.g. HLAinteger32LE) if available, otherwise null.
         */
        public final String primitiveType;
        /**
         * The raw type name found at the end of the provided path (may be a custom type).
         */
        public final String resolvedType;

        public PathCheckResult(boolean exists, String primitiveType, String resolvedType) {
            this.exists = exists;
            this.primitiveType = primitiveType;
            this.resolvedType = resolvedType;
        }

        public String toString() {
            return String.format(
                    "PathCheckResult{exists=%s, primitiveType=%s, resolvedType=%s}",
                    exists,
                    primitiveType,
                    resolvedType);
        }
    }

    public PathCheckResult checkInteractionParameterPath(String interactionName, List<Object> pathParts){
        try {
            return checkParameterPath(interactionName, true, pathParts);
        } catch (XPathExpressionException e) {
            logger.error("Error checking interaction parameter path", e);
            return new PathCheckResult(false, null, null);
        }
    }

    public PathCheckResult checkInteractionParameterPath(String interactionName, String param){
        return checkInteractionParameterPath(interactionName, List.of(param));
    }

    public PathCheckResult checkObjectParameterPath(String objectName, List<Object> pathParts) {
        try {
            return checkParameterPath(objectName, false, pathParts);
        } catch (XPathExpressionException e) {
            logger.error("Error checking object parameter path", e);
            return new PathCheckResult(false, null, null);
        }
    }

    public PathCheckResult checkObjectParameterPath(String objectName, String param){
        return checkObjectParameterPath(objectName, List.of(param));
    }



    private final String findInteractionByNameExp =
            "//interactionClass[name[text()='%s']]/parameter[name[text()='%s']]/dataType";
    private final String findObjectByNameExp =
            "//objectClass[name[text()='%s']]/attribute[name[text()='%s']]/dataType";
    private final String fixedRecordDataTypeExp =
            "//fixedRecordData[name[text()='%s']]/field[name[text()='%s']]/dataType";
    private final String arrayDataTypeExp = "//arrayData[name[text()='%s']]/dataType";

    /**
     * Given an interaction name and a path (where the first element is the parameter
     * name and subsequent elements are field names or integer array indices), resolve
     * whether that path exists and what primitive representation (if any) is at the
     * root of the resolved path.
     *
     */
    private PathCheckResult checkParameterPath(String entityName, boolean isInteraction, List<Object> pathParts)
            throws XPathExpressionException {
        if (entityName == null || entityName.isEmpty())
            throw new IllegalArgumentException("entity name is required");

        if (pathParts == null || pathParts.isEmpty())
            throw new IllegalArgumentException("First element of pathParts must be the parameter name (String)");

        Object first = pathParts.get(0);
        if (!(first instanceof String)) {
            throw new IllegalArgumentException("First element of pathParts must be the parameter name (String)");
        }

        String currentTypeName = getParameterType(entityName, (String) first, isInteraction);

        if (currentTypeName == null || currentTypeName.isEmpty()) {
            return new PathCheckResult(false, null, null);
        }

        // Walk the remaining parts
        for (int i = 1; i < pathParts.size(); i++) {
            Object part = pathParts.get(i);
            String foundType = null;

            if (part instanceof Integer) {
                int idx = (Integer) part;
                if (idx < 0) {
                    throw new IllegalArgumentException("Array index must be 0 or greater");
                }
                // resolve array element dataType for currentTypeName
                String exp = String.format(arrayDataTypeExp, currentTypeName);
                foundType = (String) xPath.compile(exp).evaluate(doc, XPathConstants.STRING);
            } else if (part instanceof String) {
                String fieldName = (String) part;
                // try fixedRecord field
                String fixedRecordExp = String.format(fixedRecordDataTypeExp, currentTypeName, fieldName);
                foundType = (String) xPath.compile(fixedRecordExp).evaluate(doc, XPathConstants.STRING);
            } else {
                throw new IllegalArgumentException("Path parts must be String (field name) or Integer (array index)");
            }

            if (foundType == null || foundType.isEmpty()) {
                return new PathCheckResult(false, null, null);
            }

            currentTypeName = foundType;
        }

        // currentTypeName is now the type at the end of the path. It may be
        // primitive, simpleData, enumeratedData, or another custom type.
        // If it's a primitive, return it. Otherwise try to resolve to a primitive via getRawType.
        if (isPrim(currentTypeName)) {
            return new PathCheckResult(true, currentTypeName, currentTypeName);
        }

        String raw = getRawType(currentTypeName);
        if (raw != null && !raw.isEmpty() && isPrim(raw)) {
            return new PathCheckResult(true, raw, currentTypeName);
        }

        // Not resolved to a primitive
        return new PathCheckResult(true, null, currentTypeName);
    }

    private final String checkSimpleDataTypeExp = "//simpleData[name[text()='%s']]/representation";
    private final String checkEnumDataTypeExp = "//enumeratedData[name[text()='%s']]/representation";

    /**
     * Given what is presumed to be a custom data type name, check if it's a simpleData or enumeratedData and return
     * the primitive representation if so.
     *
     */
    public String getRawType(String dataTypeName) throws XPathExpressionException{
        return resolvePrimitiveType(dataTypeName);
    }

    public String resolvePrimitiveType(String dataTypeName) throws XPathExpressionException {
        return resolvePrimitiveType(dataTypeName, new HashSet<>());
    }

    private String resolvePrimitiveType(String dataTypeName, Set<String> seenTypes) throws XPathExpressionException{
        if (dataTypeName == null || dataTypeName.isEmpty()) {
            return null;
        }
        if (isPrim(dataTypeName)) {
            return dataTypeName;
        }
        if (!seenTypes.add(dataTypeName)) {
            return null;
        }

        String simpleExp = String.format(checkSimpleDataTypeExp, dataTypeName);
        String simpleType = (String) xPath.compile(simpleExp).evaluate(doc, XPathConstants.STRING);
        if (!simpleType.isEmpty())
            return resolvePrimitiveType(simpleType, seenTypes);

        String enumExp = String.format(checkEnumDataTypeExp, dataTypeName);
        String enumType = (String) xPath.compile(enumExp).evaluate(doc, XPathConstants.STRING);
        if (!enumType.isEmpty())
            return resolvePrimitiveType(enumType, seenTypes);

        return null;
    }

    public HLADecoderRegistry getDecoderRegistry() {
        return decoderRegistry;
    }

    public void setDecoderRegistry(HLADecoderRegistry decoderRegistry) {
        this.decoderRegistry = decoderRegistry;
    }

    /**
     * Return the object-class hierarchy as immutable, XML-free definitions.
     *
     * <p>Class and parent names are canonical, root-relative HLA names. The
     * standard {@code HLAobjectRoot} prefix is omitted for its descendants.
     *
     * <p>Only attributes declared directly on a class are included. Consumers that
     * need inherited attributes can apply inheritance using {@link
     * ObjectClassDefinition#parentName()} without accessing the raw FOM document.
     */
    public List<ObjectClassDefinition> objectClassDefinitions() {
        if (doc == null || doc.getDocumentElement() == null) {
            return List.of();
        }
        Element objects = firstChildElement(doc.getDocumentElement(), "objects");
        if (objects == null) {
            return List.of();
        }

        List<ObjectClassDefinition> definitions = new ArrayList<>();
        for (Element objectClass : childElements(objects, "objectClass")) {
            collectObjectClassDefinitions(objectClass, null, definitions);
        }
        return List.copyOf(definitions);
    }

    private void collectObjectClassDefinitions(
            Element objectClass,
            String parentName,
            List<ObjectClassDefinition> definitions) {
        String className = childText(objectClass, "name");
        if (className == null) {
            return;
        }
        String canonicalName = canonicalClassName(className, parentName, "HLAobjectRoot");

        List<ObjectAttributeDefinition> attributes = new ArrayList<>();
        for (Element attribute : childElements(objectClass, "attribute")) {
            String attributeName = childText(attribute, "name");
            String dataType = childText(attribute, "dataType");
            if (attributeName != null && dataType != null) {
                attributes.add(new ObjectAttributeDefinition(attributeName, dataType));
            }
        }
        definitions.add(new ObjectClassDefinition(canonicalName, parentName, attributes));

        for (Element childClass : childElements(objectClass, "objectClass")) {
            collectObjectClassDefinitions(childClass, canonicalName, definitions);
        }
    }

    /**
     * Return the interaction-class hierarchy as immutable, XML-free definitions.
     *
     * <p>Class and parent names are canonical, root-relative HLA names. The
     * standard {@code HLAinteractionRoot} prefix is omitted for its descendants.
     * Only parameters declared directly on a class are included.
     */
    public List<InteractionClassDefinition> interactionClassDefinitions() {
        if (doc == null || doc.getDocumentElement() == null) {
            return List.of();
        }
        Element interactions = firstChildElement(doc.getDocumentElement(), "interactions");
        if (interactions == null) {
            return List.of();
        }

        List<InteractionClassDefinition> definitions = new ArrayList<>();
        for (Element interactionClass : childElements(interactions, "interactionClass")) {
            collectInteractionClassDefinitions(interactionClass, null, definitions);
        }
        return List.copyOf(definitions);
    }

    private void collectInteractionClassDefinitions(
            Element interactionClass,
            String parentName,
            List<InteractionClassDefinition> definitions) {
        String className = childText(interactionClass, "name");
        if (className == null) {
            return;
        }
        String canonicalName = canonicalClassName(className, parentName, "HLAinteractionRoot");

        List<InteractionParameterDefinition> parameters = new ArrayList<>();
        for (Element parameter : childElements(interactionClass, "parameter")) {
            String parameterName = childText(parameter, "name");
            String dataType = childText(parameter, "dataType");
            if (parameterName != null && dataType != null) {
                parameters.add(new InteractionParameterDefinition(parameterName, dataType));
            }
        }
        definitions.add(new InteractionClassDefinition(canonicalName, parentName, parameters));

        for (Element childClass : childElements(interactionClass, "interactionClass")) {
            collectInteractionClassDefinitions(childClass, canonicalName, definitions);
        }
    }

    private static String canonicalClassName(
            String localClassName,
            String parentName,
            String rootName) {
        if (parentName == null || parentName.equals(rootName)) {
            return localClassName;
        }
        return parentName + "." + localClassName;
    }

    private static String childText(Element parent, String tagName) {
        Element element = firstChildElement(parent, tagName);
        if (element == null) {
            return null;
        }
        String text = element.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static Element firstChildElement(Element parent, String tagName) {
        for (Element element : childElements(parent, tagName)) {
            return element;
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String tagName) {
        if (parent == null) {
            return List.of();
        }
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && element.getTagName().equals(tagName)) {
                elements.add(element);
            }
        }
        return elements;
    }

    public String getParameterType(String entityName, String parameterName, boolean isInteraction)
            throws XPathExpressionException {
        String exp = String.format(isInteraction ? findInteractionByNameExp : findObjectByNameExp,
                entityName, parameterName);
        return (String) xPath.compile(exp).evaluate(doc, XPathConstants.STRING);
    }

    public String getArrayElementType(String arrayType) throws XPathExpressionException {
        String exp = String.format(arrayDataTypeExp, arrayType);
        return (String) xPath.compile(exp).evaluate(doc, XPathConstants.STRING);
    }

    public String getFixedRecordFieldType(String recordType, String fieldName)
            throws XPathExpressionException {
        String exp = String.format(fixedRecordDataTypeExp, recordType, fieldName);
        return (String) xPath.compile(exp).evaluate(doc, XPathConstants.STRING);
    }

    public boolean isFixedRecordType(String typeName) throws XPathExpressionException {
        String exp = String.format("//fixedRecordData[name[text()='%s']]/name", typeName);
        String found = (String) xPath.compile(exp).evaluate(doc, XPathConstants.STRING);
        return found != null && !found.isEmpty();
    }

    public boolean isArrayType(String typeName) throws XPathExpressionException {
        String exp = String.format("//arrayData[name[text()='%s']]/name", typeName);
        String found = (String) xPath.compile(exp).evaluate(doc, XPathConstants.STRING);
        return found != null && !found.isEmpty();
    }

    public List<FixedRecordField> getFixedRecordFields(String fixedRecordType)
            throws XPathExpressionException {
        String exp = String.format("//fixedRecordData[name[text()='%s']]/field", fixedRecordType);
        NodeList fieldNodes = (NodeList) xPath.compile(exp).evaluate(doc, XPathConstants.NODESET);
        List<FixedRecordField> fields = new ArrayList<>();
        for (int i = 0; i < fieldNodes.getLength(); i++) {
            Node fieldNode = fieldNodes.item(i);
            String fieldName = (String) xPath.compile("name/text()").evaluate(fieldNode, XPathConstants.STRING);
            String dataType = (String) xPath.compile("dataType/text()").evaluate(fieldNode, XPathConstants.STRING);
            fields.add(new FixedRecordField(fieldName, dataType));
        }
        return fields;
    }

    public DataElement createDataElementForType(String typeName) {
        try {
            String hlaType = getRawType(typeName);
            if (hlaType != null) {
                return decoderRegistry.createElement(hlaType);
            }
            if (isFixedRecordType(typeName)) {
                return createFixedRecordElement(typeName);
            }
            if (isArrayType(typeName)) {
                return createArrayElement(typeName);
            }
            throw new IllegalArgumentException("Unsupported data type: " + typeName);
        } catch (XPathExpressionException e) {
            throw new IllegalStateException("Failed to resolve HLA type for " + typeName, e);
        }
    }

    private HLAfixedRecord createFixedRecordElement(String fixedRecordType) throws XPathExpressionException {
        HLAfixedRecord record = decoderRegistry.getEncoderFactory().createHLAfixedRecord();
        for (FOMXML.FixedRecordField field : getFixedRecordFields(fixedRecordType)) {
            record.add(createDataElementForType(field.dataType));
        }
        return record;
    }

    private HLAvariableArray<DataElement> createArrayElement(String arrayType) throws XPathExpressionException {
        String elementType = getArrayElementType(arrayType);
        if (elementType == null || elementType.isEmpty()) {
            throw new IllegalArgumentException("Unknown array element type for " + arrayType);
        }
        DataElementFactory<DataElement> factory = index -> createDataElementForType(elementType);
        return decoderRegistry.getEncoderFactory().createHLAvariableArray(factory);
    }

    public static final class FixedRecordField {
        public final String name;
        public final String dataType;

        public FixedRecordField(String name, String dataType) {
            this.name = name;
            this.dataType = dataType;
        }
    }

    public record ObjectClassDefinition(
            String name,
            String parentName,
            List<ObjectAttributeDefinition> attributes) {

        public ObjectClassDefinition {
            attributes = List.copyOf(attributes);
        }
    }

    public record ObjectAttributeDefinition(String name, String dataType) {
    }

    public record InteractionClassDefinition(
            String name,
            String parentName,
            List<InteractionParameterDefinition> parameters) {

        public InteractionClassDefinition {
            parameters = List.copyOf(parameters);
        }
    }

    public record InteractionParameterDefinition(String name, String dataType) {
    }
}
