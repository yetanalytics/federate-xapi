package com.yetanalytics.hlaxapi.cache;

import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.TrackedObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable cache and event subscription requirements derived from configuration. */
final class ObjectSubscriptionPlan {

    private final FomCatalog catalog;
    private final Map<String, Set<String>> cacheSubscriptions;
    private final Map<String, Set<String>> eventSubscriptions;
    private final Map<String, Set<String>> subscriptions;

    private ObjectSubscriptionPlan(
            FomCatalog catalog,
            Map<String, Set<String>> cacheSubscriptions,
            Map<String, Set<String>> eventSubscriptions) {
        this.catalog = catalog;
        this.cacheSubscriptions = copySubscriptions(cacheSubscriptions);
        this.eventSubscriptions = copySubscriptions(eventSubscriptions);
        this.subscriptions = mergeSubscriptions(
                this.cacheSubscriptions,
                this.eventSubscriptions);
    }

    static ObjectSubscriptionPlan from(XapiConfig xapiConfig, FomCatalog catalog) {
        Objects.requireNonNull(xapiConfig, "xapiConfig");
        Objects.requireNonNull(catalog, "catalog");
        Map<String, Set<String>> cacheSubscriptions =
                collectCacheSubscriptions(xapiConfig, catalog);
        Map<String, Set<String>> eventSubscriptions =
                collectEventSubscriptions(xapiConfig, catalog);
        return new ObjectSubscriptionPlan(catalog, cacheSubscriptions, eventSubscriptions);
    }

    Map<String, Set<String>> cacheSubscriptions() {
        return cacheSubscriptions;
    }

    Map<String, Set<String>> eventSubscriptions() {
        return eventSubscriptions;
    }

    Map<String, Set<String>> subscriptions() {
        return subscriptions;
    }

    boolean hasSubscriptions() {
        return !subscriptions.isEmpty();
    }

    /**
     * Returns the union of subscriptions configured for an object class and each
     * of its FOM ancestors.
     */
    Set<String> effectiveAttributes(String className) {
        LinkedHashSet<String> attributes = new LinkedHashSet<>();
        FomCatalog.ObjectClassDef current = catalog.objectClass(className).orElse(null);
        if (current == null) {
            addAttributes(attributes, subscriptions.get(className));
            return Set.copyOf(attributes);
        }
        while (current != null) {
            addAttributes(attributes, subscriptions.get(current.hlaName()));
            current = catalog.objectClass(current.parentName()).orElse(null);
        }
        return Set.copyOf(attributes);
    }

    private static Map<String, Set<String>> collectCacheSubscriptions(
            XapiConfig xapiConfig,
            FomCatalog catalog) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        QueryReferenceCollector.collect(xapiConfig.statementTriggers)
                .forEach((className, attributes) ->
                        addReferencedAttributesForClassAndDescendants(
                                merged,
                                catalog,
                                className,
                                attributes));
        addObjectDeleteTriggers(merged, xapiConfig, catalog);
        addTrackedObjects(merged, xapiConfig, catalog);
        return merged;
    }

    private static Map<String, Set<String>> collectEventSubscriptions(
            XapiConfig xapiConfig,
            FomCatalog catalog) {
        Map<String, Set<String>> events = new LinkedHashMap<>();
        if (xapiConfig.statementTriggers == null) {
            return events;
        }
        for (StatementTrigger trigger : xapiConfig.statementTriggers) {
            if (trigger == null
                    || trigger.type == null
                    || !trigger.type.isObjectEvent()
                    || trigger.clazz == null
                    || trigger.clazz.isBlank()) {
                continue;
            }
            addAllAttributesForClassAndDescendants(
                    events,
                    catalog,
                    trigger.clazz,
                    true);
        }
        return events;
    }

    private static void addObjectDeleteTriggers(
            Map<String, Set<String>> merged,
            XapiConfig xapiConfig,
            FomCatalog catalog) {
        if (xapiConfig.statementTriggers == null) {
            return;
        }
        for (StatementTrigger trigger : xapiConfig.statementTriggers) {
            if (trigger == null
                    || trigger.type != StatementTrigger.Type.OBJECT_DELETE
                    || trigger.clazz == null
                    || trigger.clazz.isBlank()) {
                continue;
            }
            addAllAttributesForClassAndDescendants(
                    merged,
                    catalog,
                    trigger.clazz,
                    false);
        }
    }

    private static void addTrackedObjects(
            Map<String, Set<String>> merged,
            XapiConfig xapiConfig,
            FomCatalog catalog) {
        if (xapiConfig.objectCacheConfig == null
                || xapiConfig.objectCacheConfig.trackedObjects == null) {
            return;
        }
        for (TrackedObject trackedObject : xapiConfig.objectCacheConfig.trackedObjects) {
            if (trackedObject == null
                    || trackedObject.clazz == null
                    || trackedObject.clazz.isBlank()) {
                continue;
            }
            if ("*".equals(trackedObject.clazz)) {
                if (trackedObject.allAttributes) {
                    catalog.objectClasses().forEach(clazz ->
                            addAttributes(
                                    merged,
                                    clazz.hlaName(),
                                    clazz.topLevelAttributeNames()));
                }
                continue;
            }
            if (trackedObject.allAttributes) {
                addAllAttributesForClassAndDescendants(
                        merged,
                        catalog,
                        trackedObject.clazz,
                        true);
            } else {
                addReferencedAttributesForClassAndDescendants(
                        merged,
                        catalog,
                        trackedObject.clazz,
                        trackedObject.attributes);
            }
        }
    }

    private static void addReferencedAttributesForClassAndDescendants(
            Map<String, Set<String>> subscriptions,
            FomCatalog catalog,
            String className,
            Iterable<String> attributes) {
        var classes = catalog.objectClassAndDescendants(className);
        if (classes.isEmpty()) {
            addAttributes(subscriptions, className, attributes);
            return;
        }
        classes.forEach(clazz ->
                addAttributes(subscriptions, clazz.hlaName(), attributes));
    }

    private static void addAllAttributesForClassAndDescendants(
            Map<String, Set<String>> subscriptions,
            FomCatalog catalog,
            String className,
            boolean retainUnknownClass) {
        var classes = catalog.objectClassAndDescendants(className);
        if (classes.isEmpty()) {
            if (retainUnknownClass) {
                addAttributes(subscriptions, className, Set.of("*"));
            }
            return;
        }
        classes.forEach(clazz ->
                addAttributes(
                        subscriptions,
                        clazz.hlaName(),
                        clazz.topLevelAttributeNames()));
    }

    @SafeVarargs
    private static Map<String, Set<String>> mergeSubscriptions(
            Map<String, Set<String>>... plans) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        for (Map<String, Set<String>> plan : plans) {
            plan.forEach((className, attributes) ->
                    addAttributes(merged, className, attributes));
        }
        return copySubscriptions(merged);
    }

    private static void addAttributes(
            Map<String, Set<String>> subscriptions,
            String className,
            Iterable<String> attributes) {
        if (className == null || className.isBlank() || attributes == null) {
            return;
        }
        Set<String> targetAttributes =
                subscriptions.computeIfAbsent(className, ignored -> new LinkedHashSet<>());
        addAttributes(targetAttributes, attributes);
        if (targetAttributes.isEmpty()) {
            subscriptions.remove(className);
        }
    }

    private static void addAttributes(Set<String> target, Iterable<String> attributes) {
        if (attributes == null) {
            return;
        }
        for (String attribute : attributes) {
            if (attribute != null && !attribute.isBlank()) {
                target.add(attribute);
            }
        }
    }

    private static Map<String, Set<String>> copySubscriptions(
            Map<String, Set<String>> subscriptions) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        subscriptions.forEach((className, attributes) ->
                copy.put(className, Set.copyOf(attributes)));
        return Map.copyOf(copy);
    }
}
