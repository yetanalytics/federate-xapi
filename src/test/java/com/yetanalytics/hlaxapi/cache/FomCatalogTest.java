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
        FomCatalog.ObjectClassDef rabbit = catalog.objectClass("SimEntity.Rabbit").orElseThrow();

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

        FomCatalog.ObjectClassDef rabbit = catalog.objectClass("SimEntity.Rabbit").orElseThrow();
        assertEquals("SimEntity", rabbit.parentName());
        assertEquals("HLAASCIIstring", rabbit.attribute("EntityId").orElseThrow().primitiveType());
        assertEquals("HLAinteger32BE", rabbit.attribute("Hunger").orElseThrow().primitiveType());
    }

    @Test
    void resolvesObjectClassesWithTheirDescendants() {
        FomCatalog catalog = catalog("config/HlaFedereplFOM.xml");

        assertEquals(
                List.of("SimEntity", "SimEntity.Carrot", "SimEntity.Rabbit", "SimEntity.Wolf"),
                catalog.objectClassAndDescendants("SimEntity").stream()
                        .map(FomCatalog.ObjectClassDef::hlaName)
                        .toList());
        assertEquals(
                List.of("SimEntity.Rabbit"),
                catalog.objectClassAndDescendants("SimEntity.Rabbit").stream()
                        .map(FomCatalog.ObjectClassDef::hlaName)
                        .toList());
        assertEquals(List.of(), catalog.objectClassAndDescendants("MissingObject"));
    }

    @Test
    void matchesObjectClassesThroughTheirFomHierarchy() {
        FomCatalog catalog = catalog("config/HlaFedereplFOM.xml");

        assertTrue(catalog.isSameOrDescendant("SimEntity.Rabbit", "SimEntity.Rabbit"));
        assertTrue(catalog.isSameOrDescendant("SimEntity.Rabbit", "SimEntity"));
        assertFalse(catalog.isSameOrDescendant("SimEntity", "SimEntity.Rabbit"));
        assertFalse(catalog.isSameOrDescendant("SimEntity.Wolf", "SimEntity.Rabbit"));
        assertFalse(catalog.isSameOrDescendant("MissingObject", "SimEntity"));
        assertFalse(catalog.isSameOrDescendant("SimEntity.Rabbit", "MissingObject"));
    }

    @Test
    void calculatesObjectClassDepthFromKnownFomAncestors() {
        FomCatalog catalog = catalog("config/HlaFedereplFOM.xml");

        assertEquals(1, catalog.objectClassDepth("SimEntity"));
        assertEquals(2, catalog.objectClassDepth("SimEntity.Carrot"));
        assertEquals(2, catalog.objectClassDepth("SimEntity.Rabbit"));
        assertEquals(2, catalog.objectClassDepth("SimEntity.Wolf"));
        assertEquals(-1, catalog.objectClassDepth("MissingObject"));
    }

    @Test
    void buildsRtiObjectClassNamesRelativeToHlaObjectRoot() {
        FomCatalog catalog = catalog("config/HlaFedereplFOM.xml");

        assertEquals("HLAobjectRoot", catalog.objectClass("HLAobjectRoot").orElseThrow().hlaName());
        assertEquals("SimEntity", catalog.objectClass("SimEntity").orElseThrow().hlaName());
        assertEquals("SimEntity.Carrot", catalog.objectClass("SimEntity.Carrot").orElseThrow().hlaName());
        assertEquals("SimEntity.Rabbit", catalog.objectClass("SimEntity.Rabbit").orElseThrow().hlaName());
        assertEquals("SimEntity.Wolf", catalog.objectClass("SimEntity.Wolf").orElseThrow().hlaName());
        assertTrue(catalog.objectClass("Rabbit").isEmpty());
        assertTrue(catalog.objectClass(" SimEntity.Rabbit ").isEmpty());
    }

    @Test
    void indexesObjectClassesByCanonicalNameWithoutLocalCollisions() {
        FomCatalog catalog = catalog("src/test/resources/config/AmbiguousClassNamesFOM.xml");

        FomCatalog.ObjectClassDef entityRabbit =
                catalog.objectClass("SimEntity.Rabbit").orElseThrow();
        FomCatalog.ObjectClassDef otherRabbit =
                catalog.objectClass("SomeOtherSuperclass.Rabbit").orElseThrow();

        assertEquals("SimEntity", entityRabbit.parentName());
        assertTrue(entityRabbit.attribute("EntityId").isPresent());
        assertTrue(entityRabbit.attribute("Hunger").isPresent());
        assertFalse(entityRabbit.attribute("OtherId").isPresent());
        assertEquals("SomeOtherSuperclass", otherRabbit.parentName());
        assertTrue(otherRabbit.attribute("OtherId").isPresent());
        assertTrue(otherRabbit.attribute("Speed").isPresent());
        assertFalse(otherRabbit.attribute("EntityId").isPresent());
        assertTrue(catalog.objectClass("Rabbit").isEmpty());
    }

    @Test
    void fomXmlReturnsHierarchyWithDeclaredAttributes() {
        FOMXML fomXml = fomXml("config/HlaFedereplFOM.xml");

        FOMXML.ObjectClassDefinition simEntity = fomXml.objectClassDefinitions().stream()
                .filter(definition -> definition.name().equals("SimEntity"))
                .findFirst()
                .orElseThrow();
        FOMXML.ObjectClassDefinition rabbit = fomXml.objectClassDefinitions().stream()
                .filter(definition -> definition.name().equals("SimEntity.Rabbit"))
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
