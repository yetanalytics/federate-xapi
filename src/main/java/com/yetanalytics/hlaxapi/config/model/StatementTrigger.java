package com.yetanalytics.hlaxapi.config.model;

import java.util.Map;

public class StatementTrigger {

    public Type type;
    public String clazz; // "class" is a Java keyword, map json "class" to this field via parser
    public Expression criteria;
    public Map<String, ObjectLookup> lookups;
    // keep the original statement as a JSON string (we'll process injections at
    // runtime)
    public String statement;
    public boolean skipValidation;

    @Override
    public String toString() {
        return String.format("StatementTrigger{type=%s,clazz=%s,criteria=%s,statement=%s,skipValidation=%b}",
                type, clazz, criteria, statement, skipValidation);
    }

    public enum Type {

        INTERACTION, OBJECT_CREATE, OBJECT_UPDATE, OBJECT_DELETE;

        public boolean isObjectEvent() {
            return this == OBJECT_CREATE || this == OBJECT_UPDATE || this == OBJECT_DELETE;
        }

        public static Type fromString(String s) {
            if (s == null) return null;
            switch (s.trim().toLowerCase()) {
                case "interaction": return INTERACTION;
                case "objectcreate": return OBJECT_CREATE;
                case "objectupdate": return OBJECT_UPDATE;
                case "objectdelete": return OBJECT_DELETE;
                default: return null;
            }
        }
    }
}
