package com.yetanalytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.FOMXML.PathCheckResult;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import com.yetanalytics.hlaxapi.SimulationConfig;
import com.yetanalytics.hlaxapi.StandardHlaTypeRegistry;

/**
 * Tests for FOM Parsing.
 */
public class FOMTest {

    private static final Logger logger = LogManager.getLogger(FOMTest.class);
    FOMXML fomXml;

    @BeforeEach
    public void setUp() {
        fomXml = fomXml("config/HlaFedereplFOM.xml");
    }

    @Test
    void parsesFomFromMemoryAndRejectsDoctypes() throws Exception {
        StandardHlaTypeRegistry typeRegistry = new StandardHlaTypeRegistry();
        String xml = Files.readString(Path.of("config/HlaFedereplFOM.xml"));

        FOMXML inMemoryFom = FOMXML.fromXml(xml, typeRegistry);

        assertEquals(
                "StepCount",
                inMemoryFom.checkInteractionParameterPath("StepCompleted", "StepNumber").resolvedType);
        assertThrows(
                IllegalArgumentException.class,
                () -> FOMXML.fromXml("""
                        <!DOCTYPE objectModel [<!ENTITY external SYSTEM \"file:///etc/passwd\">]>
                        <objectModel><notes>&external;</notes></objectModel>
                        """, typeRegistry));
    }

    @Test
    public void InteractionsXML() {

        // simple data type - Probability is HLAfloat32BE
        PathCheckResult carrotGrowthResult = fomXml.checkInteractionParameterPath("SimulationParametersChanged", "CarrotGrowthRate");
        logger.info("SimulationParametersChanged, CarrotGrowthRate Type: {}", carrotGrowthResult);
        assertTrue(carrotGrowthResult.primitiveType.equals("HLAfloat32BE"));

        // enumerated data type
        PathCheckResult entityTypeResult = fomXml.checkInteractionParameterPath("EntityAte", "PredatorType");
        logger.info("EntityAte, PredatorType Type: {}", entityTypeResult);
        assertTrue(entityTypeResult.primitiveType.equals("HLAinteger32BE"));

        // fixedRecord with nested simple data types
        PathCheckResult entityMovedFromXResult = fomXml.checkInteractionParameterPath("EntityMoved",
                List.of("FromPosition", "X"));
        logger.info("EntityMoved, FromPosition, X Type: {}", entityMovedFromXResult);
        assertTrue(entityMovedFromXResult.primitiveType.equals("HLAinteger32BE"));

        // fixedRecord nested field access - Y component
        PathCheckResult entityMovedToYResult = fomXml.checkInteractionParameterPath("EntityMoved",
                List.of("ToPosition", "Y"));
        logger.info("EntityMoved, ToPosition, Y Type: {}", entityMovedToYResult);
        assertTrue(entityMovedToYResult.primitiveType.equals("HLAinteger32BE"));

        // Failure cases
        // Nonexistent parameter
        PathCheckResult missingParam = fomXml.checkInteractionParameterPath("EntityMoved", "NonExistantParam");
        logger.info("Missing Param: {}", missingParam);
        assertTrue(!missingParam.exists);

        // Bad input: null param name
        try {
            fomXml.checkInteractionParameterPath("EntityMoved", (List<Object>) null);
            assertTrue(false); // should not reach
        } catch (IllegalArgumentException e) {
            // expected
        }

        // Index access on a non-array field (e.g., trying to index into a simple field)
        PathCheckResult wrongIndex = fomXml.checkInteractionParameterPath("EntityMoved", List.of("EntityId", 0));
        logger.info("Wrong Index on non-array: {}", wrongIndex);
        assertTrue(!wrongIndex.exists);

        // Nonexistent interaction
        PathCheckResult missingInteraction = fomXml.checkInteractionParameterPath("NonExistentInteraction", "Anything");
        logger.info("Missing Interaction: {}", missingInteraction);
        assertTrue(!missingInteraction.exists);

    }

    @Test
    public void CanonicalInteractionHierarchy() {
        FOMXML duplicateNames = fomXml(
                "src/test/resources/config/AmbiguousClassNamesFOM.xml");

        assertTrue(duplicateNames
                .checkInteractionParameterPath("EntityEvents.Updated", "EntityId")
                .exists);
        assertTrue(duplicateNames
                .checkInteractionParameterPath("EntityEvents.Updated", "Hunger")
                .exists);
        assertTrue(!duplicateNames
                .checkInteractionParameterPath("EntityEvents.Updated", "OtherId")
                .exists);
        assertTrue(duplicateNames
                .checkInteractionParameterPath("OtherEvents.Updated", "OtherId")
                .exists);
        assertTrue(duplicateNames
                .checkInteractionParameterPath("OtherEvents.Updated", "Speed")
                .exists);
        assertTrue(!duplicateNames
                .checkInteractionParameterPath("OtherEvents.Updated", "EntityId")
                .exists);
        assertTrue(!duplicateNames
                .checkInteractionParameterPath("Updated", "Hunger")
                .exists);
        assertTrue(!duplicateNames
                .checkInteractionParameterPath("Created", "EntityId")
                .exists);
        assertTrue(duplicateNames
                .checkInteractionParameterPath("EntityEvents.Created", "EntityId")
                .exists);
        assertTrue(!duplicateNames
                .checkInteractionParameterPath(
                        "HLAinteractionRoot.EntityEvents.Updated",
                        "Hunger")
                .exists);
    }

    private static FOMXML fomXml(String path) {
        SimulationConfig simConfig = new SimulationConfig(null, null, null, null, path);
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        return new FOMXML(simConfig, decoderRegistry);
    }
}
