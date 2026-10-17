package com.yetanalytics.hlaxapi.injection;

import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.FOMXML.PathCheckResult;
import com.yetanalytics.hlaxapi.HlaTypeRegistry;
import com.yetanalytics.hlaxapi.cache.CachedObject;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.cache.ValueResolution;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.Target;
import java.util.Optional;

/** Resolves representative values for validation without RTI or cache services. */
public final class TestInjectionResolver implements InjectionResolver {

    private final FOMXML fomXml;
    private final FomCatalog fomCatalog;
    private final HlaTypeRegistry typeRegistry;

    public TestInjectionResolver(
            FOMXML fomXml,
            FomCatalog fomCatalog,
            HlaTypeRegistry typeRegistry) {
        this.fomXml = fomXml;
        this.fomCatalog = fomCatalog;
        this.typeRegistry = typeRegistry;
    }

    @Override
    public ValueResolution handleTrigger(Target target, InjectionContext context) {
        TestInjectionContext testContext = requireTestContext(context);
        TargetDefinition definition = testContext.eventType().isObjectEvent()
                ? objectTargetDefinition(testContext.getHlaClass(), target)
                : interactionTargetDefinition(testContext.getHlaClass(), target);
        return testValue(definition, target, testContext);
    }

    @Override
    public ValueResolution handlePrevious(Target target, InjectionContext context) {
        TestInjectionContext testContext = requireTestContext(context);
        return testValue(
                objectTargetDefinition(testContext.getHlaClass(), target),
                target,
                testContext);
    }

    @Override
    public ValueResolution handleQuery(
            String clazz,
            Target target,
            Expression criteria,
            InjectionContext context) {
        TestInjectionContext testContext = requireTestContext(context);
        return testValue(objectTargetDefinition(clazz, target), target, testContext);
    }

    @Override
    public Optional<CachedObject> resolveLookup(ObjectLookup lookup, InjectionContext context) {
        requireTestContext(context);
        return Optional.empty();
    }

    @Override
    public ValueResolution handleLookup(
            CachedObject object,
            Target target,
            InjectionContext context) {
        throw new IllegalArgumentException("Validation lookup resolution requires its lookup definition");
    }

    @Override
    public ValueResolution handleLookup(
            String alias,
            ObjectLookup lookup,
            Target target,
            TestInjectionContext context) {
        return testValue(
                objectTargetDefinition(lookup == null ? null : lookup.clazz, target),
                target,
                context);
    }

    private ValueResolution testValue(
            TargetDefinition definition,
            Target target,
            TestInjectionContext context) {
        Class<?> javaType = definition.exists()
                ? typeRegistry.getClassForType(definition.primitiveType())
                : null;
        return ValueResolution.present(XapiValueGenerator.getTestValue(context, target, javaType));
    }

    private TargetDefinition interactionTargetDefinition(String hlaClass, Target target) {
        if (target == null) {
            return TargetDefinition.missing();
        }
        PathCheckResult path = fomXml.checkInteractionParameterPath(hlaClass, target.parts);
        return new TargetDefinition(path.exists, path.primitiveType);
    }

    private TargetDefinition objectTargetDefinition(String hlaClass, Target target) {
        if (target == null) {
            return TargetDefinition.missing();
        }
        return fomCatalog.objectClass(hlaClass)
                .flatMap(clazz -> clazz.attribute(FomCatalog.targetPath(target.parts)))
                .filter(attribute -> attribute.primitiveType() != null)
                .map(attribute -> new TargetDefinition(true, attribute.primitiveType()))
                .orElseGet(TargetDefinition::missing);
    }

    private TestInjectionContext requireTestContext(InjectionContext context) {
        if (context instanceof TestInjectionContext testContext) {
            return testContext;
        }
        throw new IllegalArgumentException("Test injection resolver requires a validation context");
    }

    private record TargetDefinition(boolean exists, String primitiveType) {

        private static TargetDefinition missing() {
            return new TargetDefinition(false, null);
        }
    }
}
