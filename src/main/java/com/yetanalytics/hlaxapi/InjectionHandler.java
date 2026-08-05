package com.yetanalytics.hlaxapi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.xpath.XPathExpressionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.yetanalytics.hlaxapi.FOMXML.PathCheckResult;
import com.yetanalytics.hlaxapi.cache.CachedObject;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.cache.ObjectCache;
import com.yetanalytics.hlaxapi.cache.ValueResolution;
import com.yetanalytics.hlaxapi.config.model.Expression;
import com.yetanalytics.hlaxapi.config.model.ExpressionWalker;
import com.yetanalytics.hlaxapi.config.model.LookupExpression;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.PreviousExpression;
import com.yetanalytics.hlaxapi.config.model.QueryExpression;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.config.model.TriggerExpression;
import com.yetanalytics.hlaxapi.config.model.ValueExpression;
import com.yetanalytics.hlaxapi.injection.InjectionContext;
import com.yetanalytics.hlaxapi.injection.InteractionInjectionContext;
import com.yetanalytics.hlaxapi.injection.ObjectInjectionContext;
import com.yetanalytics.hlaxapi.injection.ObjectUpdateInjectionContext;
import com.yetanalytics.hlaxapi.injection.TestInjectionContext;
import com.yetanalytics.hlaxapi.injection.XapiValueGenerator;

import hla.rti1516e.encoding.ByteWrapper;
import hla.rti1516e.encoding.DataElement;
import hla.rti1516e.encoding.DecoderException;

/**
 * Stubs for injection handlers. In the real app these will implement logic to
 * resolve injection syntaxes like ["trigger", [target]] or ["query", ...].
 */
@Component
public class InjectionHandler {

    private static final Logger logger = LogManager.getLogger(InjectionHandler.class);

    @Autowired
    private ObjectCache objectCache;

    @Autowired
    private FOMXML fomXml;

    @Autowired
    private HLADecoderRegistry hlaDecoderRegistry;

    @Autowired
    private FomCatalog fomCatalog;

    public InjectionHandler() {
    }

    public ValueResolution handleTrigger(Target t, InjectionContext context) {
        if (context instanceof InteractionInjectionContext) {
            return handleTrigger(t, (InteractionInjectionContext) context);
        } else if (context instanceof ObjectInjectionContext) {
            return handleTrigger(t, (ObjectInjectionContext) context);
        } else if (context instanceof TestInjectionContext) {
            return handleTrigger(t, (TestInjectionContext) context);
        } else {
            throw new IllegalArgumentException("Unsupported InjectionContext type: " + context.getClass().getName());
        }
    }

    public ValueResolution handleTrigger(Target t, TestInjectionContext context) {
        EventTargetDefinition target = requireEventTargetDefinition(
                context.getHlaClass(),
                t,
                context.eventType().isObjectEvent(),
                "trigger");
        return testValue(target, t, context);
    }

    public void validateCriteriaSources(
            StatementTrigger trigger,
            TestInjectionContext context) {
        Map<String, ObjectLookup> lookups =
                trigger.lookups == null ? Map.of() : trigger.lookups;
        validateExpressionSources(
                trigger.criteria,
                new ValidationSource(context, lookups, null));
        lookups.forEach((alias, lookup) -> {
            ObjectLookup definition = requireLookupClass(alias, lookup);
            validateExpressionSources(
                    definition.criteria,
                    new ValidationSource(context, lookups, definition.clazz));
        });
    }

    private ValueResolution testValue(
            EventTargetDefinition target,
            Target injectionTarget,
            TestInjectionContext context) {
        Class<?> hlaJavaType =
                target.exists() ? hlaDecoderRegistry.getClassForType(target.primitiveType()) : null;
        Object result = XapiValueGenerator.getTestValue(context, injectionTarget, hlaJavaType);
        return ValueResolution.present(result);
    }

    public ValueResolution handleTrigger(Target t, InteractionInjectionContext context) {
        return decodeEventTarget(
                t,
                context.getHlaClass(),
                context.getParameterMap(),
                false);
    }

    private ValueResolution decodeEventTarget(
            Target target,
            String hlaClass,
            Map<String, byte[]> values,
            boolean objectEvent) {
        EventTargetDefinition definition = targetDefinition(hlaClass, target, objectEvent);
        Object result = null;

        byte[] value = interrogateParameters(target.parts, values, definition.topLevelType());
        if (value == null) {
            return ValueResolution.missingValue();
        }

        if (definition.exists()) {
            try {
                result = hlaDecoderRegistry.decode(definition.primitiveType(), value);
            } catch (DecoderException e) {
                logger.warn("Problem decoding value:", e);
            }
        } else {
            logger.warn("Target does not exist in FOM: {}", target);
        }

        if (result == null) {
            return ValueResolution.missingValue();
        }
        return ValueResolution.present(result);
    }

    private EventTargetDefinition targetDefinition(
            String hlaClass,
            Target target,
            boolean objectEvent) {
        return objectEvent
                ? objectTargetDefinition(hlaClass, target)
                : interactionTargetDefinition(hlaClass, target);
    }

    private EventTargetDefinition requireEventTargetDefinition(
            String hlaClass,
            Target target,
            boolean objectEvent,
            String source) {
        EventTargetDefinition definition = targetDefinition(hlaClass, target, objectEvent);
        if (!definition.exists()) {
            throw missingTarget(source, hlaClass, target);
        }
        return definition;
    }

    private EventTargetDefinition requireObjectTargetDefinition(
            String hlaClass,
            Target target,
            String source) {
        EventTargetDefinition definition = objectTargetDefinition(hlaClass, target);
        if (!definition.exists()) {
            throw missingTarget(source, hlaClass, target);
        }
        return definition;
    }

    private IllegalArgumentException missingTarget(
            String source,
            String hlaClass,
            Target target) {
        return new IllegalArgumentException(
                source + " target "
                        + (target == null ? "<null>" : target.parts)
                        + " does not exist on FOM class "
                        + hlaClass);
    }

    private EventTargetDefinition interactionTargetDefinition(String hlaClass, Target target) {
        if (target == null) {
            return EventTargetDefinition.missing();
        }
        PathCheckResult path = fomXml.checkInteractionParameterPath(hlaClass, target.parts);
        String topLevelType = fomXml.getInteractionParameterType(
                hlaClass,
                FomCatalog.topLevelTargetPart(target.parts));
        return new EventTargetDefinition(
                path.exists,
                path.primitiveType,
                topLevelType);
    }

    private EventTargetDefinition objectTargetDefinition(String hlaClass, Target target) {
        if (fomCatalog == null) {
            throw new IllegalStateException("FOM object catalog is not configured");
        }
        Optional<FomCatalog.ObjectClassDef> objectClass = fomCatalog.objectClass(hlaClass);
        if (objectClass.isEmpty()) {
            return EventTargetDefinition.missing();
        }
        String pathKey = FomCatalog.targetPath(target.parts);
        String topLevelName = FomCatalog.topLevelTargetPart(target.parts);
        Optional<FomCatalog.FomAttribute> targetAttribute =
                objectClass.orElseThrow().attribute(pathKey);
        Optional<FomCatalog.FomAttribute> topLevelAttribute =
                objectClass.orElseThrow().attribute(topLevelName);
        if (targetAttribute.isEmpty() || topLevelAttribute.isEmpty()) {
            return EventTargetDefinition.missing();
        }
        return new EventTargetDefinition(
                true,
                targetAttribute.orElseThrow().primitiveType(),
                topLevelAttribute.orElseThrow().dataType());
    }

    private byte[] interrogateParameters(
            List<Object> targetParts,
            Map<String, byte[]> paramMap,
            String topLevelType) {
        if (targetParts == null || targetParts.isEmpty()) {
            return null;
        }

        Object firstPart = targetParts.get(0);
        if (!(firstPart instanceof String)) {
            throw new IllegalArgumentException("First target part must be a parameter name");
        }

        String parameterName = (String) firstPart;
        byte[] bytes = paramMap.get(parameterName);
        if (bytes == null || targetParts.size() == 1) {
            return bytes;
        }

        if (topLevelType == null || topLevelType.isEmpty()) {
            return null;
        }

        return extractBytesForPath(topLevelType, targetParts.subList(1, targetParts.size()), bytes);
    }

    private byte[] extractBytesForPath(String currentType, List<Object> remainingPath, byte[] bytes) {
        if (remainingPath == null || remainingPath.isEmpty()) {
            return bytes;
        }

        Object part = remainingPath.get(0);
        if (part instanceof Integer) {
            int index = (Integer) part;
            if (index < 0) {
                throw new IllegalArgumentException("Array index must be 0 or greater");
            }
            String elementType;
            try {
                elementType = fomXml.getArrayElementType(currentType);
            } catch (XPathExpressionException e) {
                throw new IllegalStateException("Unable to resolve array element type for " + currentType, e);
            }
            if (elementType == null || elementType.isEmpty()) {
                return null;
            }
            byte[] elementBytes = extractArrayElementBytes(elementType, index, bytes);
            if (elementBytes == null) {
                return null;
            }
            return extractBytesForPath(elementType, remainingPath.subList(1, remainingPath.size()), elementBytes);
        } else if (part instanceof String) {
            String fieldName = (String) part;
            String fieldType;
            try {
                fieldType = fomXml.getFixedRecordFieldType(currentType, fieldName);
            } catch (XPathExpressionException e) {
                throw new IllegalStateException(
                        "Unable to resolve fixed record field type for " + currentType + "." + fieldName, e);
            }
            if (fieldType == null || fieldType.isEmpty()) {
                return null;
            }
            byte[] fieldBytes = extractFixedRecordFieldBytes(currentType, fieldName, bytes);
            if (fieldBytes == null) {
                return null;
            }
            return extractBytesForPath(fieldType, remainingPath.subList(1, remainingPath.size()), fieldBytes);
        } else {
            throw new IllegalArgumentException("Path parts must be String (field name) or Integer (array index)");
        }
    }

    private byte[] extractArrayElementBytes(String elementType, int index, byte[] bytes) {
        if (bytes.length < Integer.BYTES) {
            logger.warn("Array value is too short to contain an element count");
            return null;
        }
        ByteWrapper wrapper = new ByteWrapper(bytes);
        int count = wrapper.getInt();
        if (count < 0) {
            logger.warn("Array value contains a negative element count: {}", count);
            return null;
        }
        if (index >= count) {
            return null;
        }

        for (int i = 0; i <= index; i++) {
            DataElement element = fomXml.createDataElementForType(elementType);
            try {
                element.decode(wrapper);
            } catch (DecoderException e) {
                logger.warn("Problem decoding array element of type {}", elementType, e);
                return null;
            }
            if (i == index) {
                try {
                    return element.toByteArray();
                } catch (hla.rti1516e.encoding.EncoderException e) {
                    throw new IllegalStateException("Failed to extract bytes for array element of type " + elementType,
                            e);
                }
            }
        }

        return null;
    }

    private byte[] extractFixedRecordFieldBytes(String recordType, String fieldName, byte[] bytes) {
        List<FOMXML.FixedRecordField> fields;
        try {
            fields = fomXml.getFixedRecordFields(recordType);
        } catch (XPathExpressionException e) {
            throw new IllegalStateException("Unable to read fixed record fields for " + recordType, e);
        }
        ByteWrapper wrapper = new ByteWrapper(bytes);
        for (FOMXML.FixedRecordField field : fields) {
            DataElement element = fomXml.createDataElementForType(field.dataType);
            try {
                element.decode(wrapper);
            } catch (DecoderException e) {
                logger.warn(
                        "Problem decoding fixed record field {} for record {}",
                        field.name,
                        recordType,
                        e);
                return null;
            }
            if (field.name.equals(fieldName)) {
                try {
                    return element.toByteArray();
                } catch (hla.rti1516e.encoding.EncoderException e) {
                    throw new IllegalStateException("Failed to extract bytes for fixed record field " + fieldName, e);
                }
            }
        }
        return null;
    }

    public ValueResolution handleTrigger(Target t, ObjectInjectionContext context) {
        return decodeEventTarget(
                t,
                context.getHlaClass(),
                context.getAttributeMap(),
                true);
    }

    public ValueResolution handlePrevious(Target target, InjectionContext context) {
        if (context instanceof TestInjectionContext testContext) {
            return handlePrevious(target, testContext);
        }
        if (context instanceof ObjectUpdateInjectionContext objectContext) {
            return handlePrevious(target, objectContext);
        }
        throw new IllegalArgumentException(
                "previous values are only available to ObjectUpdate triggers");
    }

    public ValueResolution handlePrevious(Target target, TestInjectionContext context) {
        if (context.eventType() != StatementTrigger.Type.OBJECT_UPDATE) {
            throw new IllegalArgumentException(
                    "previous values are only available to ObjectUpdate triggers");
        }
        EventTargetDefinition definition = requireObjectTargetDefinition(
                context.getHlaClass(),
                target,
                "previous");
        return testValue(definition, target, context);
    }

    public ValueResolution handlePrevious(Target target, ObjectUpdateInjectionContext context) {
        if (objectCache == null) {
            return ValueResolution.missingObject();
        }
        return objectCache.findCurrentValueResolution(
                context.getObjectHandle(),
                target);
    }

    public ValueResolution handleQuery(
            String clazz,
            Target attrTarget,
            Expression criteria,
            InjectionContext context) {

        // Validation Test-Injection
        if (context instanceof TestInjectionContext testContext) {
            EventTargetDefinition target =
                    requireObjectTargetDefinition(clazz, attrTarget, "query");
            validateExpressionSources(
                    criteria,
                    new ValidationSource(testContext, Map.of(), clazz));
            return testValue(target, attrTarget, testContext);
        }

        if (objectCache == null) {
            return ValueResolution.missingObject();
        }

        Expression resolvedCriteria = resolveTriggerExpressions(criteria, context);
        return objectCache.findFirstResolution(clazz, attrTarget, resolvedCriteria);
    }

    public Optional<CachedObject> resolveLookup(ObjectLookup lookup, InjectionContext context) {
        if (objectCache == null || lookup == null || lookup.clazz == null || lookup.clazz.isBlank()) {
            return Optional.empty();
        }
        Expression resolvedCriteria = resolveTriggerExpressions(lookup.criteria, context);
        return objectCache.findFirstObject(lookup.clazz, resolvedCriteria);
    }

    public ValueResolution handleLookup(CachedObject object, Target attrTarget, InjectionContext context) {

        // Validation Test-Injection
        if (context instanceof TestInjectionContext) {
            throw new IllegalArgumentException(
                    "lookup validation requires its lookup definition");
        }

        if (objectCache == null || object == null) {
            return ValueResolution.missingObject();
        }
        return objectCache.findValueResolution(object, attrTarget);
    }

    public ValueResolution handleLookup(
            String alias,
            ObjectLookup lookup,
            Target attrTarget,
            TestInjectionContext context) {
        requireLookupClass(alias, lookup);
        EventTargetDefinition target =
                requireObjectTargetDefinition(lookup.clazz, attrTarget, "lookup(" + alias + ")");
        return testValue(target, attrTarget, context);
    }

    private void validateExpressionSources(
            Expression expression,
            ValidationSource initialState) {
        ExpressionWalker.walk(
                expression,
                initialState,
                new ExpressionWalker.Visitor<>() {
                    @Override
                    public void visit(Expression candidate, ValidationSource state) {
                        if (candidate instanceof TriggerExpression trigger) {
                            TestInjectionContext event = state.eventContext();
                            requireEventTargetDefinition(
                                    event.getHlaClass(),
                                    trigger.target,
                                    event.eventType().isObjectEvent(),
                                    "trigger");
                        } else if (candidate instanceof PreviousExpression previous) {
                            TestInjectionContext event = state.eventContext();
                            if (event.eventType() != StatementTrigger.Type.OBJECT_UPDATE) {
                                throw new IllegalArgumentException(
                                        "previous values are only available to ObjectUpdate triggers");
                            }
                            requireObjectTargetDefinition(
                                    event.getHlaClass(),
                                    previous.target,
                                    "previous");
                        } else if (candidate instanceof QueryExpression query) {
                            requireObjectTargetDefinition(
                                    query.clazz,
                                    query.target,
                                    "query");
                        } else if (candidate instanceof LookupExpression lookup) {
                            ObjectLookup definition =
                                    requireLookupClass(lookup.alias, state.lookups().get(lookup.alias));
                            requireObjectTargetDefinition(
                                    definition.clazz,
                                    lookup.target,
                                    "lookup(" + lookup.alias + ")");
                        } else if (candidate instanceof Target target) {
                            if (state.cacheClass() == null) {
                                throw new IllegalArgumentException(
                                        "bare target " + target.parts
                                                + " is not scoped to a cache class");
                            }
                            requireObjectTargetDefinition(
                                    state.cacheClass(),
                                    target,
                                    "cache");
                        }
                    }

                    @Override
                    public ValidationSource stateForChild(
                            Expression parent,
                            ExpressionWalker.Child child,
                            ValidationSource state) {
                        String cacheClass = child.role() == ExpressionWalker.ChildRole.QUERY_FILTER
                                ? ((QueryExpression) parent).clazz
                                : state.cacheClass();
                        return new ValidationSource(
                                state.eventContext(),
                                state.lookups(),
                                cacheClass);
                    }
                });
    }

    private ObjectLookup requireLookupClass(String alias, ObjectLookup lookup) {
        if (lookup == null || lookup.clazz == null || lookup.clazz.isBlank()) {
            throw new IllegalArgumentException(
                    "lookup alias '" + alias + "' does not define an object class");
        }
        if (fomCatalog.objectClass(lookup.clazz).isEmpty()) {
            throw new IllegalArgumentException(
                    "lookup alias '" + alias + "' references unknown FOM class " + lookup.clazz);
        }
        return lookup;
    }

    private Expression resolveTriggerExpressions(Expression expression, InjectionContext context) {
        if (expression == null || context == null) {
            return expression;
        }
        return ExpressionWalker.rewrite(expression, candidate -> {
            if (candidate instanceof TriggerExpression triggerExpression) {
                ValueResolution resolution = handleTrigger(triggerExpression.target, context);
                return new ValueExpression(resolution.value());
            }
            return candidate;
        });
    }

    // for test
    public void setFomXml(FOMXML fomXml) {
        this.fomXml = fomXml;
    }

    public void setHLADecoderRegistry(HLADecoderRegistry hdr) {
        this.hlaDecoderRegistry = hdr;
    }

    public void setFomCatalog(FomCatalog fomCatalog) {
        this.fomCatalog = fomCatalog;
    }

    ObjectCache objectCache() {
        return objectCache;
    }

    private record EventTargetDefinition(
            boolean exists,
            String primitiveType,
            String topLevelType) {

        private static EventTargetDefinition missing() {
            return new EventTargetDefinition(false, null, null);
        }
    }

    private record ValidationSource(
            TestInjectionContext eventContext,
            Map<String, ObjectLookup> lookups,
            String cacheClass) {
    }
}
