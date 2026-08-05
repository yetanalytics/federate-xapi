package com.yetanalytics.hlaxapi.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.LogicalExpression;
import com.yetanalytics.hlaxapi.config.model.LookupExpression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CriteriaExpressionParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesStrictTargetPaths() throws Exception {
        Target target = CriteriaExpressionParser.parseTarget(
                MAPPER.readTree("[\"PositionHistory\", 0, \"X\"]"));

        assertEquals(List.of("PositionHistory", 0, "X"), target.parts);

        for (String invalidTarget : List.of(
                "null",
                "\"EntityId\"",
                "[]",
                "[\"PositionHistory\", -1]",
                "[\"PositionHistory\", 1.5]",
                "[\"PositionHistory\", {}]",
                "[\"PositionHistory\", []]",
                "[\"PositionHistory\", 2147483648]")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CriteriaExpressionParser.parseTarget(MAPPER.readTree(invalidTarget)),
                    invalidTarget);
        }
    }

    @Test
    void parsesEveryTriggerValueSourceInNestedExpressions() throws Exception {
        Expression expression = CriteriaExpressionParser.parse(MAPPER.readTree("""
                [
                  [["trigger", ["Score"]], ">", 10],
                  "and",
                  [
                    ["query", "World", ["Size"], [["WorldId"], "=", ["trigger", ["WorldId"]]]],
                    ">=",
                    ["lookup", "subject", ["MinimumSize"]]
                  ]
                ]
                """));

        LogicalExpression logical = assertInstanceOf(LogicalExpression.class, expression);
        Criterion eventCriterion = assertInstanceOf(Criterion.class, logical.operands.get(0));
        Criterion cacheCriterion = assertInstanceOf(Criterion.class, logical.operands.get(1));
        assertInstanceOf(TriggerExpression.class, eventCriterion.left);
        assertInstanceOf(QueryExpression.class, cacheCriterion.left);
        assertInstanceOf(LookupExpression.class, cacheCriterion.right);

        ObjectLookup definition = new ObjectLookup();
        definition.clazz = "World";
        assertDoesNotThrow(() -> CriteriaExpressionValidator.validateTrigger(expression, Map.of("subject", definition)));
    }

    @Test
    void preservesNullComparisonOperands() throws Exception {
        Criterion criterion = assertInstanceOf(
                Criterion.class,
                CriteriaExpressionParser.parse(MAPPER.readTree("""
                        [["query", "Rabbit", ["Nickname"], null], "=", null]
                        """)));

        ValueExpression right = assertInstanceOf(ValueExpression.class, criterion.right);
        assertTrue(right.value == null);
    }

    @Test
    void previousCriteriaAreObjectUpdateOnly() throws Exception {
        Criterion criterion = assertInstanceOf(
                Criterion.class,
                CriteriaExpressionParser.parse(MAPPER.readTree("""
                        [["previous", ["Hunger"]], "<", ["trigger", ["Hunger"]]]
                        """)));

        assertInstanceOf(PreviousExpression.class, criterion.left);
        assertInstanceOf(TriggerExpression.class, criterion.right);
        assertDoesNotThrow(() -> CriteriaExpressionValidator.validateTrigger(
                criterion,
                Map.of(),
                StatementTrigger.Type.OBJECT_UPDATE));
        for (StatementTrigger.Type type : List.of(
                StatementTrigger.Type.INTERACTION,
                StatementTrigger.Type.OBJECT_CREATE,
                StatementTrigger.Type.OBJECT_DELETE)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CriteriaExpressionValidator.validateTrigger(criterion, Map.of(), type));
        }
    }

    @Test
    void triggerValidationAppliesCacheFilterRulesInsideQueries() throws Exception {
        Expression expression = CriteriaExpressionParser.parse(MAPPER.readTree("""
                [
                  [
                    "query",
                    "World",
                    ["Size"],
                    [["WorldId"], "=", ["lookup", "subject", ["MinimumSize"]]]
                  ],
                  ">",
                  0
                ]
                """));
        ObjectLookup subject = new ObjectLookup();
        subject.clazz = "World";

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CriteriaExpressionValidator.validateTrigger(expression, Map.of("subject", subject)));

        assertEquals(
                "criteria.left.queryFilter.right contains unsupported LookupExpression",
                error.getMessage());
    }

    @Test
    void rejectsExpressionRenderingOptionsAndMixedLogicalOperators() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> CriteriaExpressionParser.parse(MAPPER.readTree("""
                        ["query", "Rabbit", ["Hunger"], null, {"required": false}]
                        """)));
        assertThrows(
                IllegalArgumentException.class,
                () -> CriteriaExpressionParser.parse(MAPPER.readTree("""
                        [[true, "=", true], "and", [true, "=", true], "or", [true, "=", true]]
                        """)));
    }

    @Test
    void triggerValidationRejectsBareTargetsAndInvalidLookupDefinitions() throws Exception {
        Expression bareTarget = CriteriaExpressionParser.parse(MAPPER.readTree("[[\"Score\"], \">\", 10]"));
        IllegalArgumentException bareTargetError = assertThrows(
                IllegalArgumentException.class,
                () -> CriteriaExpressionValidator.validateTrigger(bareTarget, Map.of()));
        assertTrue(bareTargetError.getMessage().contains("use [\"trigger\""));

        Expression unknownLookup = CriteriaExpressionParser.parse(MAPPER.readTree(
                "[[\"lookup\", \"missing\", [\"Score\"]], \">\", 10]"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CriteriaExpressionValidator.validateTrigger(unknownLookup, Map.of()));

        ObjectLookup missingClass = new ObjectLookup();
        assertThrows(
                IllegalArgumentException.class,
                () -> CriteriaExpressionValidator.validateTrigger(unknownLookup, Map.of("missing", missingClass)));
    }
}
