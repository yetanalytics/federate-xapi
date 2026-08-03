package com.yetanalytics.hlaxapi.injection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.HLADecoderRegistry;
import com.yetanalytics.hlaxapi.InjectionHandler;
import com.yetanalytics.hlaxapi.SimulationConfig;
import com.yetanalytics.hlaxapi.TriggerProcessor;
import com.yetanalytics.hlaxapi.TriggerProcessor.TriggerProcessingResult;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger.Type;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.xapi.util.StatementValidator;
import com.yetanalytics.xapi.util.StatementValidator.StatementValidationResult;


class XapiValueGeneratorTest {

    @Test
    void usesPresetUriForObjectIdPaths() {
        InjectionContext ctx = new InjectionContext() {};
        ctx.setStatementPath(List.of("object", "id"));
        ctx.setObjectType("Activity");
        Object value = XapiValueGenerator.getTestValue(ctx, new Target(List.of("object", "id")), String.class);

        assertInstanceOf(String.class, value);
        assertEquals("https://example.com/object", value);
    }

    @Test
    void usesPresetUuidForObjectIdPaths() {

        InjectionContext ctx = new InjectionContext() {};
        ctx.setStatementPath(List.of("object", "id"));
        ctx.setObjectType("StatementRef");
        Object value = XapiValueGenerator.getTestValue(ctx, new Target(List.of("object", "id")), String.class);

        assertInstanceOf(String.class, value);
        assertEquals("00000000-0000-4000-8000-000000000000", value);
    }

    @Test
    void returnsRandomStringForActorNamePaths() {
        InjectionContext ctx = new InjectionContext() {};
        ctx.setStatementPath(List.of("actor", "name"));
        ctx.setObjectType("StatementRef");
        Object value = XapiValueGenerator.getTestValue(ctx, new Target(List.of("actor", "name")), String.class);

        assertNotNull(value);
        assertInstanceOf(String.class, value);
        assertFalse(((String) value).isBlank());
    }

    /**Possibly break out into validation injection tests */

    private record XapiValidationTestEntry (String statement, InjectionContext ic, boolean passInjection, String errorSubString, boolean passValidation) {}

    List<XapiValidationTestEntry> testList = List.of(
        new XapiValidationTestEntry(
            """
            {
                "actor": {
                    "objectType": "Agent",
                    "name": ["trigger", ["PredatorType"]],
                    "account": {
                        "homePage": "https://hla-federepl.example/entities",
                        "name": ["trigger", ["PredatorId"]]
                    }
                },
                "verb": {
                    "id": "http://example.com/verbs/ate",
                    "display": {"en-US": "Ate"}
                },
                "object": {
                    "id": ["trigger", ["PreyId"]]
                }
            }
            """,
            new TestInjectionContext("EntityAte"),
            true, null, true
        ),
        new XapiValidationTestEntry(
            """
                {
                    "actor": {
                        "name": ["trigger", ["PredatorType"]],
                        "mbox": ["trigger", ["PreyId"]]
                    },
                    "verb": {
                        "id": "http://example.com/verbs/ate"
                    },
                    "object": {
                        "id": ["trigger", ["PreyId"]]
                    },
                    "result": {"score": {"min": ["trigger", ["PreyId"]]}}
                }
            """,
            new TestInjectionContext("EntityAte"),
            false,
            "Mismatch between statement path [result, score, min] and FOM type class java.lang.String",
            true
        ),
        // Numeric in result.duration
        new XapiValidationTestEntry(
            """
            {
                "result": {"duration": ["trigger", ["StepNumber"]]}
            }
            """,
            new TestInjectionContext("StepCompleted"),
            false,
            "Mismatch",
            false

        ),
        // Numeric in embedded result.duration, ignore HLA type and feed generative value
        new XapiValidationTestEntry(
            """
            {
                "result": {"duration": "PT<<[\\"trigger\\", [\\"StepNumber\\"]]>>S"}
            }
            """,
            new TestInjectionContext("StepCompleted"),
            
            true,
            null,
            false
        ),
        
        // InitializeWorld with string in InitialCarrots path (type mismatch)
        new XapiValidationTestEntry(
            """
            {
                "context": {"language": ["trigger", ["InitialCarrots"]]}
            }
            """,
            new TestInjectionContext("InitializeWorld"),
            false,
            "Mismatch between statement path [context, language] and FOM type class java.lang.Integer",
            false
        ),
        // Invalid Injection (non-primitive injection)
        new XapiValidationTestEntry(
            """
            {
                "context": {
                    "contextActivities": {
                        "grouping": ["trigger", ["Position"]]
                    }
                }
            }
            """,
            new TestInjectionContext("EntityCreated"),
            false,
            "hlaType",
            false
        ),
        // Injection into bad place (pass injection as no rules for location, but fail validation)
        new XapiValidationTestEntry(
            """
            {
                "context": {
                    "contextActivities": {
                        "grouping": ["trigger", ["Position", "X"]]
                    }
                }
            }
            """,
            new TestInjectionContext("EntityCreated"),
            true,
            "hlaType",
            false
        ),
        //Totally invalid JSON
        new XapiValidationTestEntry(
            """
            >>stuff ["trigger", ["PredatorType"]]
            """,
            new TestInjectionContext("EntityAte"),
            false, "Unexpected character", false
        ),
        // Langmap Key Injection - keys don't get injected so it's just a wonky value in key, fails xapi val
        new XapiValidationTestEntry(
            """
                {
                    "actor": {
                        "name": "name",
                        "mbox": "mailto:name@yetanalytics.com"
                    },
                    "verb": {
                        "id": "http://example.com/verbs/ate",
                        "display": {
                            "<<[\\"trigger\\", [\\"PreyId\\"]]>>": "Thing"
                        }
                    },
                    "object": {
                        "id": "http://www.yetanalytics.com/objects/1"
                    }
                }
            """,
            new TestInjectionContext("EntityAte"),
            true,
            null,
            false
        )
    );

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.TriggerProcessor"})
    void validationInjectionTests() {
        SimulationConfig simConfig = new SimulationConfig(null, null, null, null,
                "config/HlaFedereplFOM.xml");
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        InjectionHandler ih = new InjectionHandler();
        ih.setFomXml(new FOMXML(simConfig, decoderRegistry));
        ih.setHLADecoderRegistry(decoderRegistry);
        StatementValidator validator = new StatementValidator();

        TriggerProcessor tp = new TriggerProcessor(ih);
        for (XapiValidationTestEntry testEntry : testList) {
            StatementTrigger st = new StatementTrigger();
            st.type = Type.INTERACTION;
            st.clazz = testEntry.ic.getHlaClass();
            st.statement = testEntry.statement;
            TriggerProcessingResult result = tp.processTrigger(st, testEntry.ic);
            if (testEntry.passInjection) {
                assertTrue(result.success());
                assertNotNull(result.statement());
                StatementValidationResult valRes = validator.validateStatement(result.statement());
                assertEquals(testEntry.passValidation, valRes.isValid());
            } else {
                assertFalse(result.success());
                assertNull(result.statement());
                assertNotNull(result.error());
                assertTrue(result.error().getMessage().contains(testEntry.errorSubString()));
            }
        }
    }
}
