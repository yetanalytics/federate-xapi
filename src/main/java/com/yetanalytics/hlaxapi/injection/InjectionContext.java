package com.yetanalytics.hlaxapi.injection;

import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import java.util.List;

public abstract class InjectionContext {

    private String hlaClass;
    private List<Object> statementPath = List.of();
    private boolean embedded = false;
    private String objectType;
    private StatementTrigger.Type triggerType;

    public String getHlaClass() {
        return hlaClass;
    }

    public void setHlaClass(String hlaClass) {
        this.hlaClass = hlaClass;
    }

    /**
     * The path to the statement node currently being processed for injection.
     * Object-property names are represented as {@link String}s and array
     * positions as {@link Integer}s.
     */
    public List<Object> getStatementPath() {
        return statementPath;
    }

    public void setStatementPath(List<Object> statementPath) {
        this.statementPath = statementPath == null ? List.of() : List.copyOf(statementPath);
    }

    public void setEmbedded(Boolean embedded) {
        this.embedded = embedded;
    }

    public boolean isEmbedded() {
        return embedded;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public StatementTrigger.Type getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(StatementTrigger.Type triggerType) {
        this.triggerType = triggerType;
    }
}
