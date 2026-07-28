package com.yetanalytics.hlaxapi.injection;

import com.yetanalytics.hlaxapi.config.model.StatementTrigger;

public class TestInjectionContext extends InjectionContext {

    public TestInjectionContext() {
        setTriggerType(StatementTrigger.Type.INTERACTION);
    }

    public TestInjectionContext(String hlaClass) {
        this();
        setHlaClass(hlaClass);
    }

    public TestInjectionContext(StatementTrigger.Type triggerType, String hlaClass) {
        setTriggerType(triggerType);
        setHlaClass(hlaClass);
    }
}
