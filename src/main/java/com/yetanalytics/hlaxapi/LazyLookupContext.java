package com.yetanalytics.hlaxapi;

import com.yetanalytics.hlaxapi.cache.CachedObject;
import com.yetanalytics.hlaxapi.cache.ValueResolution;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.injection.InjectionContext;
import com.yetanalytics.hlaxapi.injection.TestInjectionContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Holds lookup results that are scoped to one statement-trigger attempt. */
final class LazyLookupContext {

    private final InjectionHandler handler;
    private final InjectionContext injectionContext;
    private final Map<String, ObjectLookup> definitions;
    private final Map<String, Optional<CachedObject>> objects = new HashMap<>();

    LazyLookupContext(
            InjectionHandler handler,
            InjectionContext injectionContext,
            Map<String, ObjectLookup> definitions) {
        this.handler = handler;
        this.injectionContext = injectionContext;
        this.definitions = definitions == null ? Map.of() : definitions;
    }

    ValueResolution value(String alias, Target target) {
        if (injectionContext instanceof TestInjectionContext testContext) {
            return handler.handleLookup(alias, definitions.get(alias), target, testContext);
        }
        return handler.handleLookup(object(alias), target, injectionContext);
    }

    private CachedObject object(String alias) {
        return objects.computeIfAbsent(alias, this::load).orElse(null);
    }

    private Optional<CachedObject> load(String alias) {
        Optional<CachedObject> result = handler.resolveLookup(definitions.get(alias), injectionContext);
        return result == null ? Optional.empty() : result;
    }
}
