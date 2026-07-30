package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import com.yetanalytics.hlaxapi.SimulationConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class FomCatalogTest {

    @Test
    void flattensPrimitiveAliasesEnumsAndFixedRecords() {
        FomCatalog catalog = catalog("config/HlaFedereplFOM.xml");
        FomCatalog.ObjectClassDef rabbit = catalog.objectClass("Rabbit").orElseThrow();

        assertTrue(rabbit.attribute("Hunger").orElseThrow().leaf());
        assertEquals("HLAinteger32BE", rabbit.attribute("Hunger").orElseThrow().primitiveType());
        assertEquals("HLAinteger32BE", rabbit.attribute("EntityType").orElseThrow().primitiveType());

        FomCatalog.FomAttribute position = rabbit.attribute("Position").orElseThrow();
        assertFalse(position.leaf());
        assertEquals("GridPosition", position.dataType());
        assertEquals("HLAinteger32BE", rabbit.attribute("Position.X").orElseThrow().primitiveType());
        assertEquals("HLAinteger32BE", rabbit.attribute("Position.Y").orElseThrow().primitiveType());
    }

    @Test
    void includesInheritedObjectAttributes() {
        FomCatalog catalog = catalog("config/HlaFedereplFOM.xml");

        FomCatalog.ObjectClassDef simEntity = catalog.objectClass("SimEntity").orElseThrow();
        assertEquals("HLAASCIIstring", simEntity.attribute("EntityId").orElseThrow().primitiveType());
        assertEquals("HLAinteger32BE", simEntity.attribute("Position.X").orElseThrow().primitiveType());

        FomCatalog.ObjectClassDef rabbit = catalog.objectClass("Rabbit").orElseThrow();
        assertEquals("SimEntity", rabbit.parentName());
        assertEquals("HLAASCIIstring", rabbit.attribute("EntityId").orElseThrow().primitiveType());
        assertEquals("HLAinteger32BE", rabbit.attribute("Hunger").orElseThrow().primitiveType());
    }

    @Test
    void resolvesObjectClassesWithTheirDescendants() {
        FomCatalog catalog = catalog("config/HlaFedereplFOM.xml");

        assertEquals(
                List.of("SimEntity", "Carrot", "Rabbit", "Wolf"),
                catalog.objectClassAndDescendants("SimEntity").stream()
                        .map(FomCatalog.ObjectClassDef::localName)
                        .toList());
        assertEquals(
                List.of("Rabbit"),
                catalog.objectClassAndDescendants("Rabbit").stream()
                        .map(FomCatalog.ObjectClassDef::localName)
                        .toList());
        assertEquals(List.of(), catalog.objectClassAndDescendants("MissingObject"));
    }

    @Test
    void matchesObjectClassesThroughTheirFomHierarchy() {
        FomCatalog catalog = catalog("config/HlaFedereplFOM.xml");

        assertTrue(catalog.isSameOrDescendant("Rabbit", "Rabbit"));
        assertTrue(catalog.isSameOrDescendant("Rabbit", "SimEntity"));
        assertFalse(catalog.isSameOrDescendant("SimEntity", "Rabbit"));
        assertFalse(catalog.isSameOrDescendant("Wolf", "Rabbit"));
        assertFalse(catalog.isSameOrDescendant("MissingObject", "SimEntity"));
        assertFalse(catalog.isSameOrDescendant("Rabbit", "MissingObject"));
    }

    @Test
    void fomXmlReturnsHierarchyWithDeclaredAttributes() {
        FOMXML fomXml = fomXml("config/HlaFedereplFOM.xml");

        FOMXML.ObjectClassDefinition simEntity = fomXml.objectClassDefinitions().stream()
                .filter(definition -> definition.name().equals("SimEntity"))
                .findFirst()
                .orElseThrow();
        FOMXML.ObjectClassDefinition rabbit = fomXml.objectClassDefinitions().stream()
                .filter(definition -> definition.name().equals("Rabbit"))
                .findFirst()
                .orElseThrow();

        assertEquals("HLAobjectRoot", simEntity.parentName());
        assertTrue(simEntity.attributes().stream()
                .anyMatch(attribute -> attribute.name().equals("EntityId")));
        assertEquals("SimEntity", rabbit.parentName());
        assertEquals(List.of("Hunger"), rabbit.attributes().stream()
                .map(FOMXML.ObjectAttributeDefinition::name)
                .toList());
    }

    @Test
    void convertsTargetsToPathKeys() {
        assertEquals("Position.X", FomCatalog.targetPath(List.of("Position", "X")));
        assertEquals("TargetModifiers[0].Key", FomCatalog.targetPath(List.of("TargetModifiers", 0, "Key")));
        assertEquals("TargetModifiers[].Key", FomCatalog.wildcardArrayIndexes("TargetModifiers[12].Key"));
    }

    private FomCatalog catalog(String fomPath) {
        return new FomCatalog(fomXml(fomPath));
    }

    private FOMXML fomXml(String fomPath) {
        SimulationConfig simConfig = new SimulationConfig(null, null, null, null, fomPath);
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        return new FOMXML(simConfig, decoderRegistry);
    }
}
