package com.yetanalytics.hlaxapi.injection;

import com.yetanalytics.hlaxapi.cache.CachedObject;
import com.yetanalytics.hlaxapi.cache.ValueResolution;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.Target;
import java.util.Optional;

/** Value sources used while evaluating and rendering a statement trigger. */
public interface InjectionResolver {

    ValueResolution handleTrigger(Target target, InjectionContext context);

    ValueResolution handlePrevious(Target target, InjectionContext context);

    ValueResolution handleQuery(
            String clazz,
            Target target,
            Expression criteria,
            InjectionContext context);

    Optional<CachedObject> resolveLookup(ObjectLookup lookup, InjectionContext context);

    ValueResolution handleLookup(CachedObject object, Target target, InjectionContext context);

    ValueResolution handleLookup(
            String alias,
            ObjectLookup lookup,
            Target target,
            TestInjectionContext context);
}
