package com.yetanalytics.hlaxapi.config.model;

public sealed interface Expression
        permits Criterion,
                LogicalExpression,
                LookupExpression,
                PreviousExpression,
                QueryExpression,
                Target,
                TriggerExpression,
                ValueExpression {
}
