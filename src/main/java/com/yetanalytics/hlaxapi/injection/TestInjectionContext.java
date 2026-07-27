package com.yetanalytics.hlaxapi.injection;

import com.yetanalytics.hlaxapi.config.model.StatementTrigger;

public class TestInjectionContext extends InjectionContext {

    private StatementTrigger.Type triggerType = StatementTrigger.Type.INTERACTION;

    public TestInjectionContext() {
    }

    public TestInjectionContext(String hlaClass) {
        setHlaClass(hlaClass);
    }

    public TestInjectionContext(StatementTrigger.Type triggerType, String hlaClass) {
        this.triggerType = triggerType;
        setHlaClass(hlaClass);
    }

    public StatementTrigger.Type getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(StatementTrigger.Type triggerType) {
        this.triggerType = triggerType;
    }
}
