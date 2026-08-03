package com.yetanalytics.hlaxapi.cache;

import com.yetanalytics.hlaxapi.FOMXML;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.xpath.XPathExpressionException;

import org.springframework.stereotype.Component;

/**
 * Canonical object and interaction metadata derived from the FOM.
 */
@Component
public final class FomCatalog {

    private final Map<String, ObjectClassDef> classesByName;
    private final Map<String, InteractionClassDef> interactionsByName;
    private final Map<Integer, ObjectClassDef> classesById;
    private final Map<Integer, FomAttribute> attributesById;

    public FomCatalog(FOMXML fomXml) {
        CatalogBuilder builder = new CatalogBuilder(fomXml);
        for (FOMXML.ObjectClassDefinition definition : fomXml.objectClassDefinitions()) {
            builder.addObjectClass(definition);
        }
        for (FOMXML.InteractionClassDefinition definition : fomXml.interactionClassDefinitions()) {
            builder.addInteractionClass(definition);
        }
        this.classesByName = Collections.unmodifiableMap(new LinkedHashMap<>(builder.classesByName));
        this.interactionsByName =
                Collections.unmodifiableMap(new LinkedHashMap<>(builder.interactionsByName));

        Map<Integer, ObjectClassDef> byId = new LinkedHashMap<>();
        Map<Integer, FomAttribute> attrsById = new LinkedHashMap<>();
        for (ObjectClassDef clazz : classesByName.values()) {
            byId.put(clazz.id(), clazz);
            for (FomAttribute attribute : clazz.attributes()) {
                attrsById.put(attribute.id(), attribute);
            }
        }
        this.classesById = Collections.unmodifiableMap(byId);
        this.attributesById = Collections.unmodifiableMap(attrsById);
    }

    public Collection<ObjectClassDef> objectClasses() {
        return classesByName.values();
    }

    /**
     * Resolves a canonical object class name exactly.
     */
    public Optional<ObjectClassDef> canonicalObjectClass(String name) {
        return Optional.ofNullable(classesByName.get(name));
    }

    /**
     * Resolves an object class by its exact canonical name.
     */
    public Optional<ObjectClassDef> objectClass(String name) {
        return canonicalObjectClass(name);
    }

    public Optional<ObjectClassDef> objectClass(int id) {
        return Optional.ofNullable(classesById.get(id));
    }

    public Collection<InteractionClassDef> interactionClasses() {
        return interactionsByName.values();
    }

    /**
     * Resolves a canonical interaction class name exactly.
     */
    public Optional<InteractionClassDef> canonicalInteractionClass(String name) {
        return Optional.ofNullable(interactionsByName.get(name));
    }

    /**
     * Resolves an interaction class by its exact canonical name.
     */
    public Optional<InteractionClassDef> interactionClass(String name) {
        return canonicalInteractionClass(name);
    }

    public List<ObjectClassDef> objectClassAndDescendants(String name) {
        ObjectClassDef requestedClass = objectClass(name).orElse(null);
        if (requestedClass == null) {
            return List.of();
        }
        return classesByName.values().stream()
                .filter(candidate -> isSameOrDescendant(candidate, requestedClass))
                .toList();
    }

    /**
     * Returns whether the actual object class is the configured class or one of
     * its FOM descendants.
     */
    public boolean isSameOrDescendant(String actualClassName, String configuredClassName) {
        ObjectClassDef actualClass = objectClass(actualClassName).orElse(null);
        ObjectClassDef configuredClass = objectClass(configuredClassName).orElse(null);
        return actualClass != null
                && configuredClass != null
                && isSameOrDescendant(actualClass, configuredClass);
    }

    /**
     * Returns the number of known FOM ancestors for an object class, or -1 when
     * the class is unknown.
     */
    public int objectClassDepth(String className) {
        ObjectClassDef current = objectClass(className).orElse(null);
        if (current == null) {
            return -1;
        }
        int depth = 0;
        while ((current = classesByName.get(current.parentName())) != null) {
            depth++;
        }
        return depth;
    }

    public Optional<FomAttribute> attribute(int id) {
        return Optional.ofNullable(attributesById.get(id));
    }

    public Optional<FomAttribute> attribute(String className, String attributeName, String pathKey) {
        return objectClass(className).flatMap(clazz -> clazz.attribute(attributeName, pathKey));
    }

    public Optional<FomAttribute> attribute(String className, String pathKey) {
        return objectClass(className).flatMap(clazz -> clazz.attribute(pathKey));
    }

    public static String targetPath(List<Object> targetParts) {
        if (targetParts == null || targetParts.isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (Object part : targetParts) {
            if (part instanceof Number number) {
                path.append('[').append(number.intValue()).append(']');
            } else if (part != null) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(part);
            }
        }
        return path.toString();
    }

    public static String topLevelTargetPart(List<Object> targetParts) {
        if (targetParts == null || targetParts.isEmpty() || !(targetParts.get(0) instanceof String part)) {
            return null;
        }
        return part;
    }

    public static String wildcardArrayIndexes(String pathKey) {
        if (pathKey == null) {
            return null;
        }
        return pathKey.replaceAll("\\[[0-9]+\\]", "[]");
    }

    private boolean isSameOrDescendant(ObjectClassDef candidate, ObjectClassDef requestedClass) {
        ObjectClassDef current = candidate;
        while (current != null) {
            if (current.hlaName().equals(requestedClass.hlaName())) {
                return true;
            }
            current = classesByName.get(current.parentName());
        }
        return false;
    }

    public record ObjectClassDef(
            int id,
            String hlaName,
            String parentName,
            List<FomAttribute> attributes) {

        public ObjectClassDef {
            attributes = List.copyOf(attributes);
        }

        public List<FomAttribute> leafAttributes() {
            return attributes.stream().filter(FomAttribute::leaf).toList();
        }

        public List<String> topLevelAttributeNames() {
            Set<String> names = new LinkedHashSet<>();
            for (FomAttribute attribute : attributes) {
                names.add(attribute.attributeName());
            }
            return List.copyOf(names);
        }

        public Optional<FomAttribute> attribute(String pathKey) {
            String localPath = pathKey == null ? null : pathKey.trim();
            String wildcardPath = wildcardArrayIndexes(localPath);
            for (FomAttribute attribute : attributes) {
                if (attribute.pathKey().equals(localPath) || attribute.pathKey().equals(wildcardPath)) {
                    return Optional.of(attribute);
                }
            }
            return Optional.empty();
        }

        public Optional<FomAttribute> attribute(String attributeName, String pathKey) {
            return attribute(pathKey).filter(attribute -> attribute.attributeName().equals(attributeName));
        }
    }

    public record FomAttribute(
            int id,
            int classId,
            String attributeName,
            String pathKey,
            String dataType,
            String primitiveType,
            boolean leaf) {
    }

    public record InteractionClassDef(
            String hlaName,
            String parentName,
            List<FomParameter> parameters) {

        public InteractionClassDef {
            parameters = List.copyOf(parameters);
        }

        public Optional<FomParameter> parameter(String pathKey) {
            String localPath = pathKey == null ? null : pathKey.trim();
            String wildcardPath = wildcardArrayIndexes(localPath);
            for (FomParameter parameter : parameters) {
                if (parameter.pathKey().equals(localPath) || parameter.pathKey().equals(wildcardPath)) {
                    return Optional.of(parameter);
                }
            }
            return Optional.empty();
        }
    }

    public record FomParameter(
            String parameterName,
            String pathKey,
            String dataType,
            String primitiveType,
            boolean leaf) {
    }

    private static final class CatalogBuilder {

        private final FOMXML fomXml;
        private final Map<String, ObjectClassDef> classesByName = new LinkedHashMap<>();
        private final Map<String, List<AttributeSource>> attributesByClassName = new LinkedHashMap<>();
        private final Map<String, InteractionClassDef> interactionsByName = new LinkedHashMap<>();
        private final Map<String, List<ParameterSource>> parametersByClassName = new LinkedHashMap<>();
        private int nextClassId = 1;
        private int nextAttributeId = 1;

        private CatalogBuilder(FOMXML fomXml) {
            this.fomXml = fomXml;
        }

        private void addObjectClass(FOMXML.ObjectClassDefinition definition) {
            List<AttributeSource> allAttributes = new ArrayList<>();
            if (definition.parentName() != null) {
                allAttributes.addAll(attributesByClassName.getOrDefault(definition.parentName(), List.of()));
            }
            for (FOMXML.ObjectAttributeDefinition attribute : definition.attributes()) {
                allAttributes.add(new AttributeSource(attribute.name(), attribute.dataType()));
            }
            attributesByClassName.put(definition.name(), List.copyOf(allAttributes));

            int classId = nextClassId++;
            List<FomAttribute> flattened = new ArrayList<>();
            for (AttributeSource attribute : allAttributes) {
                flattenAttribute(classId, attribute.name(), attribute.name(), attribute.dataType(), flattened);
            }

            ObjectClassDef classDef =
                    new ObjectClassDef(
                            classId,
                            definition.name(),
                            definition.parentName(),
                            flattened);
            classesByName.put(classDef.hlaName(), classDef);
        }

        private void addInteractionClass(FOMXML.InteractionClassDefinition definition) {
            List<ParameterSource> allParameters = new ArrayList<>();
            if (definition.parentName() != null) {
                allParameters.addAll(parametersByClassName.getOrDefault(definition.parentName(), List.of()));
            }
            for (FOMXML.InteractionParameterDefinition parameter : definition.parameters()) {
                allParameters.add(new ParameterSource(parameter.name(), parameter.dataType()));
            }
            parametersByClassName.put(definition.name(), List.copyOf(allParameters));

            List<FomParameter> flattened = new ArrayList<>();
            for (ParameterSource parameter : allParameters) {
                flattenParameter(parameter.name(), parameter.name(), parameter.dataType(), flattened);
            }
            InteractionClassDef classDef = new InteractionClassDef(
                    definition.name(),
                    definition.parentName(),
                    flattened);
            interactionsByName.put(classDef.hlaName(), classDef);
        }

        private void flattenAttribute(
                int classId,
                String attributeName,
                String pathKey,
                String dataType,
                List<FomAttribute> attributes) {
            String primitive = primitiveType(dataType);
            List<FOMXML.FixedRecordField> fields = fixedRecordFields(dataType);
            String arrayElementType = arrayElementType(dataType);
            boolean leaf = primitive != null || fields.isEmpty() && arrayElementType == null;

            attributes.add(new FomAttribute(
                    nextAttributeId++,
                    classId,
                    attributeName,
                    pathKey,
                    dataType,
                    primitive,
                    leaf));

            if (!fields.isEmpty()) {
                for (FOMXML.FixedRecordField field : fields) {
                    flattenAttribute(
                            classId,
                            attributeName,
                            pathKey + "." + field.name,
                            field.dataType,
                            attributes);
                }
            } else if (arrayElementType != null) {
                flattenAttribute(classId, attributeName, pathKey + "[]", arrayElementType, attributes);
            }
        }

        private void flattenParameter(
                String parameterName,
                String pathKey,
                String dataType,
                List<FomParameter> parameters) {
            String primitive = primitiveType(dataType);
            List<FOMXML.FixedRecordField> fields = fixedRecordFields(dataType);
            String arrayElementType = arrayElementType(dataType);
            boolean leaf = primitive != null || fields.isEmpty() && arrayElementType == null;

            parameters.add(new FomParameter(
                    parameterName,
                    pathKey,
                    dataType,
                    primitive,
                    leaf));

            if (!fields.isEmpty()) {
                for (FOMXML.FixedRecordField field : fields) {
                    flattenParameter(
                            parameterName,
                            pathKey + "." + field.name,
                            field.dataType,
                            parameters);
                }
            } else if (arrayElementType != null) {
                flattenParameter(parameterName, pathKey + "[]", arrayElementType, parameters);
            }
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

        private record AttributeSource(String name, String dataType) {
        }

        private record ParameterSource(String name, String dataType) {
        }
    }
}
