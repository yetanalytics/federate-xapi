package com.yetanalytics.hlaxapi.injection;

import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import java.util.Objects;

public class TestInjectionContext extends InjectionContext {

    private final StatementTrigger.Type eventType;

    public TestInjectionContext() {
        this(StatementTrigger.Type.INTERACTION, null);
    }

    public TestInjectionContext(String hlaClass) {
        this(StatementTrigger.Type.INTERACTION, hlaClass);
    }

    public TestInjectionContext(StatementTrigger.Type eventType, String hlaClass) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        setHlaClass(hlaClass);
    }

    @Override
    public final StatementTrigger.Type eventType() {
        return eventType;
    }
}
