package com.yetanalytics.hlaxapi.cache;

import java.util.List;
import java.util.Objects;

record ReflectedAttributeValues(String attributeName, List<DecodedAttributeValue> values) {

    ReflectedAttributeValues {
        Objects.requireNonNull(attributeName, "attributeName");
        values = List.copyOf(Objects.requireNonNull(values, "values"));
    }
}
