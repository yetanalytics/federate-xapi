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

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class FOMXML {

    private static final Logger logger = LogManager.getLogger(App.class);

    private Document doc;
    private XPath xPath;
    private HlaTypeRegistry typeRegistry;

    //automatically injected by spring
    public FOMXML(SimulationConfig simConfig, HlaTypeRegistry typeRegistry) {
        this(parse(new File(simConfig.getFom())), typeRegistry);
    }

    private FOMXML(Document doc, HlaTypeRegistry typeRegistry) {
        this.doc = doc;
        this.xPath = XPathFactory.newInstance().newXPath();
        this.typeRegistry = typeRegistry;
    }

    /** Parses an in-memory FOM for validator-only and API use. */
    public static FOMXML fromXml(String xml, HlaTypeRegistry typeRegistry) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("FOM XML must not be blank");
        }
        return new FOMXML(parse(new InputSource(new StringReader(xml))), typeRegistry);
    }

    private static DocumentBuilderFactory documentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser does not support secure FOM parsing", e);
        }
        return factory;
    }

    private static Document parse(File xmlFile) {
        try {
            DocumentBuilder builder = documentBuilder();
            return builder.parse(xmlFile);
        } catch (SAXException | IOException | ParserConfigurationException e) {
            throw new IllegalArgumentException("Could not parse FOM XML", e);
        }
    }

    private static Document parse(InputSource source) {
        try {
            DocumentBuilder builder = documentBuilder();
            return builder.parse(source);
        } catch (SAXException | IOException | ParserConfigurationException e) {
            throw new IllegalArgumentException("Could not parse FOM XML", e);
        }
    }

    private static DocumentBuilder documentBuilder() throws ParserConfigurationException {
        DocumentBuilder builder = documentBuilderFactory().newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException e) throws SAXException {
                throw e;
            }

            @Override
            public void fatalError(SAXParseException e) throws SAXException {
                throw e;
            }
        });
        return builder;
    }

    private boolean isPrim(String type) {
        return typeRegistry.supports(type);
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
            return checkInteractionParameterPathInternal(interactionName, pathParts);
        } catch (XPathExpressionException e) {
            logger.error("Error checking interaction parameter path", e);
            return new PathCheckResult(false, null, null);
        }
    }

    public PathCheckResult checkInteractionParameterPath(String interactionName, String param){
        return checkInteractionParameterPath(interactionName, List.of(param));
    }

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
    private PathCheckResult checkInteractionParameterPathInternal(
            String interactionName,
            List<Object> pathParts)
            throws XPathExpressionException {
        if (interactionName == null || interactionName.isEmpty())
            throw new IllegalArgumentException("interaction name is required");

        if (pathParts == null || pathParts.isEmpty())
            throw new IllegalArgumentException("First element of pathParts must be the parameter name (String)");

        Object first = pathParts.get(0);
        if (!(first instanceof String)) {
            throw new IllegalArgumentException("First element of pathParts must be the parameter name (String)");
        }

        String currentTypeName = getInteractionParameterType(interactionName, (String) first);

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
                foundType = getArrayElementType(currentTypeName);
            } else if (part instanceof String) {
                String fieldName = (String) part;
                foundType = getFixedRecordFieldType(currentTypeName, fieldName);
            } else {
                throw new IllegalArgumentException("Path parts must be String (field name) or Integer (array index)");
            }

            if (foundType == null || foundType.isEmpty()) {
                return new PathCheckResult(false, null, null);
            }

            currentTypeName = foundType;
        }

        return new PathCheckResult(true, resolvePrimitiveType(currentTypeName), currentTypeName);
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

    /** Returns the FOM model name and version when declared. */
    public ModelIdentification modelIdentification() {
        if (doc == null || doc.getDocumentElement() == null) {
            return null;
        }
        Element identification = firstChildElement(doc.getDocumentElement(), "modelIdentification");
        if (identification == null) {
            return null;
        }
        String name = childText(identification, "name");
        String version = childText(identification, "version");
        return name == null && version == null ? null : new ModelIdentification(name, version);
    }

    /** Return the interaction-class hierarchy as immutable, XML-free definitions. */
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

    private void collectObjectClassDefinitions(
            Element objectClass,
            String parentName,
            List<ObjectClassDefinition> definitions) {
        String className = childText(objectClass, "name");
        if (className == null) {
            return;
        }
        String canonicalName = parentName == null || parentName.equals("HLAobjectRoot")
                ? className
                : parentName + "." + className;

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

    private void collectInteractionClassDefinitions(
            Element interactionClass,
            String parentName,
            List<InteractionClassDefinition> definitions) {
        String className = childText(interactionClass, "name");
        if (className == null) {
            return;
        }
        String canonicalName = parentName == null || parentName.equals("HLAinteractionRoot")
                ? className
                : parentName + "." + className;
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

    String getInteractionParameterType(String interactionName, String parameterName) {
        Element interactionClass = findInteractionClass(interactionName);
        while (interactionClass != null) {
            Element parameter = findNamedChild(interactionClass, "parameter", parameterName);
            String dataType = childText(parameter, "dataType");
            if (dataType != null) {
                return dataType;
            }
            Node parent = interactionClass.getParentNode();
            interactionClass = parent instanceof Element element
                    && element.getTagName().equals("interactionClass")
                            ? element
                            : null;
        }
        return null;
    }

    private Element findInteractionClass(String canonicalName) {
        if (canonicalName == null || canonicalName.isEmpty()
                || doc == null || doc.getDocumentElement() == null) {
            return null;
        }
        Element interactions = firstChildElement(doc.getDocumentElement(), "interactions");
        Element interactionClass = findNamedChild(
                interactions,
                "interactionClass",
                "HLAinteractionRoot");
        if (canonicalName.equals("HLAinteractionRoot")) {
            return interactionClass;
        }
        for (String className : canonicalName.split("\\.", -1)) {
            if (className.isEmpty()) {
                return null;
            }
            interactionClass = findNamedChild(
                    interactionClass,
                    "interactionClass",
                    className);
            if (interactionClass == null) {
                return null;
            }
        }
        return interactionClass;
    }

    private static Element findNamedChild(Element parent, String tagName, String name) {
        if (name == null) {
            return null;
        }
        for (Element child : childElements(parent, tagName)) {
            if (name.equals(childText(child, "name"))) {
                return child;
            }
        }
        return null;
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

    public record ModelIdentification(String name, String version) {
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
