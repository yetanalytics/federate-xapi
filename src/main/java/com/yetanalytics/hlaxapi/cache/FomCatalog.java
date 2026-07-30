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
 * FOM-derived object metadata used by the SQLite cache.
 */
@Component
public final class FomCatalog {

    private final Map<String, ObjectClassDef> classesByName;
    private final Map<Integer, ObjectClassDef> classesById;
    private final Map<Integer, FomAttribute> attributesById;

    public FomCatalog(FOMXML fomXml) {
        CatalogBuilder builder = new CatalogBuilder(fomXml);
        for (FOMXML.ObjectClassDefinition definition : fomXml.objectClassDefinitions()) {
            builder.addObjectClass(definition);
        }
        this.classesByName = builder.classesByName;

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

    public Optional<ObjectClassDef> objectClass(String name) {
        return Optional.ofNullable(classesByName.get(localName(name)));
    }

    public Optional<ObjectClassDef> objectClass(int id) {
        return Optional.ofNullable(classesById.get(id));
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

    static String localName(String hlaName) {
        if (hlaName == null) {
            return null;
        }
        String trimmed = hlaName.trim();
        int index = trimmed.lastIndexOf('.');
        return index >= 0 ? trimmed.substring(index + 1) : trimmed;
    }

    private boolean isSameOrDescendant(ObjectClassDef candidate, ObjectClassDef requestedClass) {
        ObjectClassDef current = candidate;
        while (current != null) {
            if (current.localName().equals(requestedClass.localName())) {
                return true;
            }
            current = classesByName.get(current.parentName());
        }
        return false;
    }

    public record ObjectClassDef(
            int id,
            String hlaName,
            String localName,
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

    private static final class CatalogBuilder {

        private final FOMXML fomXml;
        private final Map<String, ObjectClassDef> classesByName = new LinkedHashMap<>();
        private final Map<String, List<AttributeSource>> attributesByClassName = new LinkedHashMap<>();
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
                            localName(definition.name()),
                            localName(definition.parentName()),
                            flattened);
            classesByName.put(classDef.localName(), classDef);
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
    }
}
