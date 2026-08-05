package com.yetanalytics.hlaxapi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.config.model.ComparisonOperator;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.portico.impl.hla1516e.types.encoding.HLA1516eEncoderFactory;

class FomConfigValidatorTest {

    private static final String OBJECT_FOM = "src/test/resources/object-update-fom.xml";
    private static final String SIMULATION_FOM = "config/HlaFedereplFOM.xml";

    @Test
    void validatesInheritedObjectTargetsInStatementsAndCriteriaEvenWhenOptional() {
        FomConfigValidator validator = validator(OBJECT_FOM);
        StatementTrigger valid = trigger("""
                {"object":{"id":["trigger",["EntityId"]]}}
                """);
        StatementTrigger missingTrigger = trigger("""
                {"missing":["trigger",["NotAnAttribute"],{"required":false}]}
                """);
        StatementTrigger missingPrevious = trigger("""
                {"missing":["previous",["NotAnAttribute"],{"required":false}]}
                """);
        StatementTrigger missingTriggerCriterion = trigger("{}", new Criterion(
                new TriggerExpression(target("NotAnAttribute")),
                ComparisonOperator.EQ,
                new ValueExpression(1)));
        StatementTrigger missingPreviousCriterion = trigger("{}", new Criterion(
                new PreviousExpression(target("NotAnAttribute")),
                ComparisonOperator.EQ,
                new ValueExpression(1)));

        assertDoesNotThrow(() -> validator.validate(valid));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingTrigger));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingPrevious));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingTriggerCriterion));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingPreviousCriterion));
    }

    @Test
    void validatesQueryAndLookupPathsAgainstTheirReferencedObjectClasses() {
        FomConfigValidator validator = validator(OBJECT_FOM);
        StatementTrigger valid = trigger("""
                {
                  "query":["query","BaseEntity",["Position","X"],[["Position","Y"],">",0]],
                  "lookup":["lookup","base",["EntityId"]]
                }
                """);
        valid.lookups = Map.of("base", lookup("BaseEntity", new Criterion(
                target("Position", "Y"),
                ComparisonOperator.GT,
                new ValueExpression(0))));
        StatementTrigger missingQueryTarget = trigger("""
                {"value":["query","BaseEntity",["NotAnAttribute"],null,{"required":false}]}
                """);
        StatementTrigger missingQueryCriterion = trigger("""
                {"value":["query","BaseEntity",["EntityId"],[["NotAnAttribute"],"=",1]]}
                """);
        StatementTrigger missingLookupTarget = trigger("""
                {"value":["lookup","base",["NotAnAttribute"],{"required":false}]}
                """);
        missingLookupTarget.lookups = Map.of("base", lookup("BaseEntity", null));
        StatementTrigger missingLookupCriterion = trigger("{}");
        missingLookupCriterion.lookups = Map.of("base", lookup("BaseEntity", new Criterion(
                target("NotAnAttribute"),
                ComparisonOperator.EQ,
                new ValueExpression(1))));

        assertDoesNotThrow(() -> validator.validate(valid));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingQueryTarget));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingQueryCriterion));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingLookupTarget));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingLookupCriterion));
    }

    @Test
    void validatesInteractionTargets() {
        FomConfigValidator validator = validator(SIMULATION_FOM);
        StatementTrigger valid = interaction("""
                {"result":{"score":{"raw":["trigger",["StepNumber"]]}}}
                """);
        StatementTrigger missing = interaction("""
                {"missing":["trigger",["NotAParameter"],{"required":false}]}
                """);

        assertDoesNotThrow(() -> validator.validate(valid));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missing));
    }

    @Test
    void validatesPreviousOnlyForObjectUpdateTriggers() {
        FomConfigValidator validator = validator(OBJECT_FOM);
        for (StatementTrigger.Type type : List.of(
                StatementTrigger.Type.OBJECT_UPDATE,
                StatementTrigger.Type.INTERACTION,
                StatementTrigger.Type.OBJECT_CREATE,
                StatementTrigger.Type.OBJECT_DELETE)) {
            StatementTrigger trigger = trigger("""
                    {"oldCount":["previous",["Count"]]}
                    """);
            trigger.type = type;

            if (type == StatementTrigger.Type.OBJECT_UPDATE) {
                assertDoesNotThrow(() -> validator.validate(trigger), type.toString());
            } else {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> validator.validate(trigger),
                        type.toString());
            }
        }
    }

    private FomConfigValidator validator(String fomPath) {
        HLADecoderRegistry decoderRegistry = new HLADecoderRegistry(new HLA1516eEncoderFactory());
        FOMXML fomXml = new FOMXML(
                new SimulationConfig(null, null, null, null, fomPath),
                decoderRegistry);
        return new FomConfigValidator(fomXml, new FomCatalog(fomXml));
    }

    private StatementTrigger interaction(String statement) {
        StatementTrigger trigger = trigger(statement);
        trigger.type = StatementTrigger.Type.INTERACTION;
        trigger.clazz = "StepCompleted";
        return trigger;
    }

    private StatementTrigger trigger(String statement) {
        return trigger(statement, null);
    }

    private StatementTrigger trigger(String statement, Expression criteria) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.type = StatementTrigger.Type.OBJECT_UPDATE;
        trigger.clazz = "BaseEntity.TrackedEntity";
        trigger.criteria = criteria;
        trigger.statement = statement;
        return trigger;
    }

    private ObjectLookup lookup(String className, Expression criteria) {
        ObjectLookup lookup = new ObjectLookup();
        lookup.clazz = className;
        lookup.criteria = criteria;
        return lookup;
    }

    private Target target(Object... parts) {
        return new Target(List.of(parts));
    }
}
