package com.yetanalytics.hlaxapi.cache;

import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.TrackedObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable cache and event subscription requirements derived from configuration. */
final class ObjectSubscriptionPlan {

    private final FomCatalog catalog;
    private final Map<String, Set<String>> cacheSubscriptions;
    private final Map<String, Set<String>> eventSubscriptions;
    private final Map<String, Set<String>> identificationSubscriptions;
    private final Map<String, Set<String>> subscriptions;

    private ObjectSubscriptionPlan(
            FomCatalog catalog,
            Map<String, Set<String>> cacheSubscriptions,
            Map<String, Set<String>> eventSubscriptions,
            Map<String, Set<String>> identificationSubscriptions) {
        this.catalog = catalog;
        this.cacheSubscriptions = copySubscriptions(cacheSubscriptions);
        this.eventSubscriptions = copySubscriptions(eventSubscriptions);
        this.identificationSubscriptions = copySubscriptions(identificationSubscriptions);
        this.subscriptions = mergeSubscriptions(
                this.cacheSubscriptions,
                this.eventSubscriptions,
                this.identificationSubscriptions);
    }

    static ObjectSubscriptionPlan from(XapiConfig xapiConfig, FomCatalog catalog) {
        Objects.requireNonNull(xapiConfig, "xapiConfig");
        Objects.requireNonNull(catalog, "catalog");
        Map<String, Set<String>> cacheSubscriptions =
                collectCacheSubscriptions(xapiConfig, catalog);
        Map<String, Set<String>> eventSubscriptions =
                collectEventSubscriptions(xapiConfig, catalog);
        Map<String, Set<String>> identificationSubscriptions =
                collectIdentificationSubscriptions(xapiConfig, catalog);
        return new ObjectSubscriptionPlan(
                catalog,
                cacheSubscriptions,
                eventSubscriptions,
                identificationSubscriptions);
    }

    Map<String, Set<String>> cacheSubscriptions() {
        return cacheSubscriptions;
    }

    Map<String, Set<String>> eventSubscriptions() {
        return eventSubscriptions;
    }

    Map<String, Set<String>> identificationSubscriptions() {
        return identificationSubscriptions;
    }

    Map<String, Set<String>> subscriptions() {
        return subscriptions;
    }

    boolean requiresCache() {
        return !cacheSubscriptions.isEmpty();
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
            addAttributes(attributes, subscriptions.get(FomCatalog.localName(className)));
            return Set.copyOf(attributes);
        }
        while (current != null) {
            addAttributes(attributes, subscriptions.get(current.localName()));
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
                        addAttributes(merged, className, attributes));
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
            Optional<FomCatalog.ObjectClassDef> clazz = catalog.objectClass(trigger.clazz);
            if (clazz.isPresent()) {
                FomCatalog.ObjectClassDef objectClass = clazz.orElseThrow();
                addAttributes(events, objectClass.localName(), objectClass.topLevelAttributeNames());
            } else {
                addAttributes(events, trigger.clazz, Set.of("*"));
            }
        }
        return events;
    }

    /**
     * Subscribe to every descendant of a configured lifecycle class so the RTI
     * reports an object's most-specific subscribed class. Without these
     * subscriptions, a derived object can be discovered as its subscribed
     * ancestor and incorrectly match an exact-class trigger.
     */
    private static Map<String, Set<String>> collectIdentificationSubscriptions(
            XapiConfig xapiConfig,
            FomCatalog catalog) {
        Map<String, Set<String>> identification = new LinkedHashMap<>();
        if (xapiConfig.statementTriggers == null) {
            return identification;
        }
        for (StatementTrigger trigger : xapiConfig.statementTriggers) {
            if (trigger == null
                    || trigger.type == null
                    || !trigger.type.isObjectEvent()
                    || trigger.clazz == null
                    || trigger.clazz.isBlank()) {
                continue;
            }
            catalog.objectClass(trigger.clazz).ifPresent(configuredClass -> {
                List<String> attributes = configuredClass.topLevelAttributeNames();
                if (attributes.isEmpty()) {
                    return;
                }
                catalog.objectClassAndDescendants(configuredClass.localName()).stream()
                        .filter(candidate -> !candidate.localName().equals(configuredClass.localName()))
                        .forEach(descendant ->
                                addAttributes(identification, descendant.localName(), attributes));
            });
        }
        return identification;
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
            catalog.objectClass(trigger.clazz).ifPresent(clazz ->
                    addAttributes(merged, clazz.localName(), clazz.topLevelAttributeNames()));
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
            if ("*".equals(trackedObject.clazz.trim())) {
                if (trackedObject.allAttributes) {
                    catalog.objectClasses().forEach(clazz ->
                            addAttributes(
                                    merged,
                                    clazz.localName(),
                                    clazz.topLevelAttributeNames()));
                }
                continue;
            }
            if (trackedObject.allAttributes) {
                Optional<FomCatalog.ObjectClassDef> clazz =
                        catalog.objectClass(trackedObject.clazz);
                if (clazz.isPresent()) {
                    FomCatalog.ObjectClassDef objectClass = clazz.orElseThrow();
                    addAttributes(
                            merged,
                            objectClass.localName(),
                            objectClass.topLevelAttributeNames());
                } else {
                    addAttributes(merged, trackedObject.clazz, Set.of("*"));
                }
            } else {
                String className = catalog.objectClass(trackedObject.clazz)
                        .map(FomCatalog.ObjectClassDef::localName)
                        .orElse(trackedObject.clazz);
                addAttributes(merged, className, trackedObject.attributes);
            }
        }
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
