package com.yetanalytics.hlaxapi.injection;

import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import java.util.Map;

public class ObjectUpdateInjectionContext extends ObjectInjectionContext {

    public ObjectUpdateInjectionContext() {
    }

    public ObjectUpdateInjectionContext(
            String hlaClass,
            String objectHandle,
            Map<String, byte[]> attributeMap) {
        super(hlaClass, objectHandle, attributeMap);
    }

    @Override
    public final StatementTrigger.Type eventType() {
        return StatementTrigger.Type.OBJECT_UPDATE;
    }
}
