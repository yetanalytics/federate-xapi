package com.yetanalytics.hlaxapi;

import com.yetanalytics.hlaxapi.cache.ValueResolution;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.LookupExpression;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.criteria.CriteriaEvaluator;
import com.yetanalytics.hlaxapi.injection.InjectionContext;
import com.yetanalytics.hlaxapi.injection.InjectionResolver;

/** Evaluates a statement trigger's expression against its runtime value sources. */
final class TriggerCriteriaMatcher {

    private final InjectionResolver handler;
    private final CriteriaEvaluator evaluator = new CriteriaEvaluator();

    TriggerCriteriaMatcher(InjectionResolver handler) {
        this.handler = handler;
    }

    boolean matches(Expression criteria, InjectionContext context, LazyLookupContext lookups) {
        return evaluator.matches(criteria, expression -> resolve(expression, context, lookups));
    }

    private Object resolve(Expression expression, InjectionContext context, LazyLookupContext lookups) {
        ValueResolution resolution;
        if (expression instanceof TriggerExpression trigger) {
            resolution = handler.handleTrigger(trigger.target, context);
        } else if (expression instanceof PreviousExpression previous) {
            resolution = handler.handlePrevious(previous.target, context);
        } else if (expression instanceof QueryExpression query) {
            resolution = handler.handleQuery(query.clazz, query.target, query.criteria, context);
        } else if (expression instanceof LookupExpression lookup) {
            resolution = lookups.value(lookup.alias, lookup.target);
        } else if (expression instanceof Target target) {
            throw new IllegalStateException(
                    "Bare target " + target.parts + " reached statement-trigger evaluation");
        } else {
            throw new IllegalStateException(
                    "Unsupported statement-trigger expression " + expression.getClass().getSimpleName());
        }
        return resolution != null && resolution.present() ? resolution.value() : null;
    }
}
