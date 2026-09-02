package com.yetanalytics.hlaxapi.cache;

import com.yetanalytics.hlaxapi.config.XapiConfig;
import java.util.Map;
import java.util.Set;

/** Read-only subscription analysis that does not initialize an object-cache store. */
public record ObjectSubscriptionAnalysis(
        Map<String, Set<String>> cacheRequirements,
        Map<String, Set<String>> eventRequirements,
        Map<String, Set<String>> effectiveRequirements) {

    public static ObjectSubscriptionAnalysis analyze(XapiConfig config, FomCatalog catalog) {
        ObjectSubscriptionPlan plan = ObjectSubscriptionPlan.from(config, catalog);
        return new ObjectSubscriptionAnalysis(
                plan.cacheSubscriptions(),
                plan.eventSubscriptions(),
                plan.subscriptions());
    }
}
