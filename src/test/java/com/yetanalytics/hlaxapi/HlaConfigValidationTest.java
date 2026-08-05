package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yetanalytics.extension.SuppressTestLogging;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.exception.XapiConfigurationException;
import com.yetanalytics.xapi.util.StatementValidator;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class HlaConfigValidationTest {

    private static final String OBJECT_FOM = "src/test/resources/object-update-fom.xml";

    @Test
    @SuppressTestLogging({"com.yetanalytics.hlaxapi.HlaInterfaceImpl"})
    void skipValidationSkipsOnlyFinalXapiValidation() throws Exception {
        StatementValidator statementValidator = new StatementValidator();
        assertFalse(statementValidator.validateStatement("{}").isValid());

        StatementTrigger trigger = trigger("{}");
        trigger.skipValidation = true;

        assertDoesNotThrow(() -> hlaInterface(trigger, statementValidator).validateConfig());

        trigger.skipValidation = false;
        assertThrows(
                XapiConfigurationException.class,
                () -> hlaInterface(trigger, statementValidator).validateConfig());
    }

    @Test
    @SuppressTestLogging({
        "com.yetanalytics.hlaxapi.HlaInterfaceImpl",
        "com.yetanalytics.hlaxapi.TriggerProcessor"
    })
    void skippedXapiValidationStillRunsFomAndRenderingChecks() throws Exception {
        StatementTrigger missingTarget = trigger("""
                {"value":["trigger",["NotAnAttribute"],{"required":false}]}
                """);
        missingTarget.skipValidation = true;
        StatementTrigger datatypeMismatch = trigger("""
                {"object":{"id":["trigger",["Count"]]}}
                """);
        datatypeMismatch.skipValidation = true;

        assertThrows(
                XapiConfigurationException.class,
                () -> hlaInterface(missingTarget, new StatementValidator()).validateConfig());
        assertThrows(
                XapiConfigurationException.class,
                () -> hlaInterface(datatypeMismatch, new StatementValidator()).validateConfig());
    }

    private HlaInterfaceImpl hlaInterface(
            StatementTrigger trigger,
            StatementValidator statementValidator) throws Exception {
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        FOMXML fomXml = new FOMXML(
                new SimulationConfig(null, null, null, null, OBJECT_FOM),
                decoderRegistry);
        FomCatalog catalog = new FomCatalog(fomXml);
        InjectionHandler handler = new InjectionHandler();
        handler.setFomXml(fomXml);
        handler.setHLADecoderRegistry(decoderRegistry);
        handler.setFomCatalog(catalog);

        XapiConfig config = new XapiConfig();
        config.statementTriggers = List.of(trigger);

        HlaInterfaceImpl hlaInterface = new HlaInterfaceImpl();
        setField(hlaInterface, "xapiConfig", config);
        setField(hlaInterface, "triggerProcessor", new TriggerProcessor(handler));
        setField(hlaInterface, "validator", statementValidator);
        setField(hlaInterface, "fomConfigValidator", new FomConfigValidator(fomXml, catalog));
        return hlaInterface;
    }

    private StatementTrigger trigger(String statement) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.OBJECT_UPDATE;
        trigger.clazz = "BaseEntity.TrackedEntity";
        trigger.statement = statement;
        return trigger;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
