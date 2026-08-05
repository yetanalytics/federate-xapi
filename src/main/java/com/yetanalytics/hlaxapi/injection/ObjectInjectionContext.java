package com.yetanalytics.hlaxapi.injection;

import java.util.Map;

public abstract class ObjectInjectionContext extends InjectionContext {

    private String objectHandle;
    private Map<String, byte[]> attributeMap = Map.of();

    protected ObjectInjectionContext() {
    }

    protected ObjectInjectionContext(
            String hlaClass,
            String objectHandle,
            Map<String, byte[]> attributeMap) {
        setHlaClass(hlaClass);
        this.objectHandle = objectHandle;
        setAttributeMap(attributeMap);
    }

    public String getObjectHandle() {
        return objectHandle;
    }

    public void setObjectHandle(String objectHandle) {
        this.objectHandle = objectHandle;
    }

    public Map<String, byte[]> getAttributeMap() {
        return attributeMap;
    }

    public void setAttributeMap(Map<String, byte[]> attributeMap) {
        this.attributeMap = attributeMap == null ? Map.of() : Map.copyOf(attributeMap);
    }
}
