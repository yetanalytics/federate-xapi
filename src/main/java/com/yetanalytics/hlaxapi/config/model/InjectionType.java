package com.yetanalytics.hlaxapi.config.model;

public enum InjectionType {
    TRIGGER("trigger"), PREVIOUS("previous"), QUERY("query"), LOOKUP("lookup");

    public final String token;

    InjectionType(String token) { this.token = token; }

    @Override
    public String toString() { return token; }

    public static InjectionType fromString(String s) {
        if (s == null) return null;
        switch (s.trim().toLowerCase()) {
            case "trigger": return TRIGGER;
            case "previous": return PREVIOUS;
            case "query": return QUERY;
            case "lookup": return LOOKUP;
            default: return null;
        }
    }
}
