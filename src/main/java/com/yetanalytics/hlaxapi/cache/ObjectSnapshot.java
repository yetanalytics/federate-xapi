package com.yetanalytics.hlaxapi.cache;

import java.util.Map;

public record ObjectSnapshot(
        String objectHandle,
        String objectName,
        String className,
        Map<String, byte[]> attributes) {

    public ObjectSnapshot {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
