package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.ComparisonOperator;
import com.yetanalytics.hlaxapi.config.model.Criterion;
import com.yetanalytics.hlaxapi.config.model.LogicalExpression;
import com.yetanalytics.hlaxapi.config.model.LogicalOperator;
import com.yetanalytics.hlaxapi.config.model.LookupExpression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueryReferenceCollectorTest {

    @Test
    void findsWholeNodeAndInlineQueryInjections() {
        StatementTrigger wholeNode = trigger("""
                {"actor":{"name":["query","SimEntity.Rabbit",["EntityId"],[["Hunger"],">",50]]}}
                """);
        StatementTrigger inline = trigger("""
                {"result":{"response":"at=<<[\\"query\\",\\"SimEntity.Rabbit\\",[\\"Position\\",\\"Y\\"],[[\\"Position\\",\\"X\\"],\\"<\\",15]]>>"}}
                """);

        Map<String, Set<String>> references = QueryReferenceCollector.collect(List.of(wholeNode, inline));

        assertEquals(Set.of("EntityId", "Hunger", "Position"), references.get("SimEntity.Rabbit"));
    }

    @Test
    void ignoresTriggerExpressionTargetsInsideQueryCriteria() {
        StatementTrigger trigger = trigger("""
                {"actor":{"name":["query","SimEntity.Rabbit",["EntityId"],[["Hunger"],">",["trigger",["DesiredHunger"]]]]}}
                """);

        Map<String, Set<String>> references = QueryReferenceCollector.collect(List.of(trigger));

        assertEquals(Set.of("EntityId", "Hunger"), references.get("SimEntity.Rabbit"));
        assertFalse(references.get("SimEntity.Rabbit").contains("DesiredHunger"));
    }

    @Test
    void findsLookupDefinitionCriteriaAndLookupInjections() {
        StatementTrigger trigger = trigger("""
                {
                    "actor": {
                        "account": {"name": ["lookup", "predator", ["EntityId"], {"nullable": true}]},
                        "name": "<<[\\"lookup\\",\\"predator\\",[\\"EntityType\\"],{\\"nullable\\":true}]>>"
                    }
                }
                """);
        ObjectLookup lookup = new ObjectLookup();
        lookup.clazz = "SimEntity";
        lookup.criteria = new Criterion(
                new Target(List.of("EntityId")),
                ComparisonOperator.EQ,
                new TriggerExpression(new Target(List.of("PredatorId"))));
        trigger.lookups = Map.of("predator", lookup);

        Map<String, Set<String>> references = QueryReferenceCollector.collect(List.of(trigger));

        assertEquals(Set.of("EntityId", "EntityType"), references.get("SimEntity"));
        assertFalse(references.get("SimEntity").contains("PredatorId"));
    }

    @Test
    void findsCacheReferencesUsedOnlyByTriggerCriteria() {
        StatementTrigger trigger = trigger("{}");
        ObjectLookup subject = new ObjectLookup();
        subject.clazz = "SimEntity";
        subject.criteria = new Criterion(
                new Target(List.of("EntityId")),
                ComparisonOperator.EQ,
                new TriggerExpression(new Target(List.of("SubjectId"))));
        trigger.lookups = Map.of("subject", subject);
        trigger.criteria = new LogicalExpression(
                LogicalOperator.AND,
                List.of(
                        new Criterion(
                                new LookupExpression("subject", new Target(List.of("Hunger"))),
                                ComparisonOperator.GT,
                                new ValueExpression(50)),
                        new Criterion(
                                new QueryExpression(
                                        "World",
                                        new Target(List.of("Size")),
                                        new Criterion(
                                                new Target(List.of("WorldId")),
                                                ComparisonOperator.EQ,
                                                new TriggerExpression(new Target(List.of("DesiredWorldId"))))),
                                ComparisonOperator.GT,
                                new ValueExpression(0))));

        Map<String, Set<String>> references = QueryReferenceCollector.collect(List.of(trigger));

        assertEquals(Set.of("EntityId", "Hunger"), references.get("SimEntity"));
        assertEquals(Set.of("WorldId", "Size"), references.get("World"));
        assertFalse(references.get("World").contains("DesiredWorldId"));
    }

    @Test
    void findsObjectUpdatePreviousReferencesOnly() {
        StatementTrigger update = trigger("""
                {
                  "oldX":["previous",["Position","X"]],
                  "description":"old hunger <<[\\"previous\\",[\\"Hunger\\"]]>>"
                }
                """);
        update.type = StatementTrigger.Type.OBJECT_UPDATE;
        update.clazz = "SimEntity.Rabbit";
        update.criteria = new Criterion(
                new PreviousExpression(new Target(List.of("EntityId"))),
                ComparisonOperator.NEQ,
                new TriggerExpression(new Target(List.of("EntityId"))));
        StatementTrigger create = trigger("""
                {"invalid":["previous",["Hunger"]]}
                """);
        create.type = StatementTrigger.Type.OBJECT_CREATE;
        create.clazz = "SimEntity.Wolf";

        Map<String, Set<String>> references =
                QueryReferenceCollector.collect(List.of(update, create));

        assertEquals(Set.of("EntityId", "Position", "Hunger"), references.get("SimEntity.Rabbit"));
        assertFalse(references.containsKey("SimEntity.Wolf"));
    }

    private StatementTrigger trigger(String statement) {
        StatementTrigger trigger = new StatementTrigger();
        trigger.statement = statement;
        return trigger;
    }
}
