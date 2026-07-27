package com.yetanalytics.hlaxapi.cache;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

interface ObjectCacheStore extends AutoCloseable {
    boolean isOpen();

    CachedObject ensureObject(String objectHandle, String objectName, FomCatalog.ObjectClassDef clazz);

    void removeObject(String objectHandle, String removedAt);

    Optional<CachedValue> findCurrentValue(long instanceId, String pathKey);

    Optional<CachedValue> findCurrentValue(String objectHandle, String pathKey);

    List<CachedObject> currentObjects(FomCatalog.ObjectClassDef clazz);

    void replaceCurrentValues(
            String objectHandle,
            FomCatalog.ObjectClassDef clazz,
            List<ReflectedAttributeValues> attributes,
            String observedAt,
            long observedSequence);

    Connection connection();

    @Override
    void close();
}
