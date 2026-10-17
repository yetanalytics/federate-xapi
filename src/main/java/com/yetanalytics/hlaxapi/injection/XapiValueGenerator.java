package com.yetanalytics.hlaxapi.injection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.yetanalytics.hlaxapi.config.model.Target;
import com.yetanalytics.hlaxapi.exception.InjectionDatatypeMismatchException;

public class XapiValueGenerator {

    private static final Logger logger = LogManager.getLogger(XapiValueGenerator.class);

    private static final String DEFAULT_URI = "https://example.com/object";
    private static final String DEFAULT_MBOX = "mailto:test@example.com";
    private static final String DEFAULT_UUID = "00000000-0000-4000-8000-000000000000";
    private static final String DEFAULT_TIMESTAMP = "2024-01-01T00:00:00Z";
    private static final String DEFAULT_SHA1 = "7f5fba166bdface761c25e91cab0744eb5f56d90";
    private static final Double DEFAULT_NUMERIC = 0.5;
    private static final String DEFAULT_DURATION = "PT1S";
    private static final String DEFAULT_LANGUAGE = "en-us";
    private static final Boolean DEFAULT_BOOLEAN = true;

    private static final Set<Class<?>> STRING_CLASSES = Set.of(String.class, byte[].class, Byte.class, Character.class);
    private static final Set<Class<?>> NUMERIC_CLASSES = Set.of(Integer.class, Long.class, Double.class, Float.class,
            Short.class);
    private static final Set<Class<?>> BOOLEAN_CLASSES = Set.of(Boolean.class);

    public static Object getTestValue(InjectionContext context, Target target, Class<?> hlaJavaType)
            throws InjectionDatatypeMismatchException {
        List<Object> statementPath = context.getStatementPath();
        String objectType = context.getObjectType();
        boolean embedded = context.isEmbedded();
        if (statementPath == null || statementPath.isEmpty()) {
            return null;
        }

        List<List<Object>> uriCandidates = new ArrayList<>(uriPaths);
        List<List<Object>> uuidCandidates = new ArrayList<>(uuidPaths);

        // handle object.id changing types for different statement types
        if (objectType != null && ("StatementRef".equals(objectType) || "SubStatement".equals(objectType))) {
            uuidCandidates.add(List.of("object", "id"));
        } else if (objectType == null || "Activity".equals(objectType)) {
            uriCandidates.add(List.of("object", "id"));
        }

        if (containsPath(uriCandidates, statementPath)) {
            detectFOMMismatch(statementPath, STRING_CLASSES, hlaJavaType, embedded);
            return DEFAULT_URI;
        }
        if (containsPath(uuidCandidates, statementPath)) {
            detectFOMMismatch(statementPath, STRING_CLASSES, hlaJavaType, embedded);
            return DEFAULT_UUID;
        }
        if (containsPath(timestampPaths, statementPath)) {
            detectFOMMismatch(statementPath, STRING_CLASSES, hlaJavaType, embedded);
            return DEFAULT_TIMESTAMP;
        }
        if (containsPath(numericPaths, statementPath)) {
            detectFOMMismatch(statementPath, NUMERIC_CLASSES, hlaJavaType, embedded);
            return DEFAULT_NUMERIC;
        }
        if (containsPath(booleanPaths, statementPath)) {
            detectFOMMismatch(statementPath, BOOLEAN_CLASSES, hlaJavaType, embedded);
            return DEFAULT_BOOLEAN;
        }
        if (containsPath(durationPaths, statementPath)) {
            detectFOMMismatch(statementPath, STRING_CLASSES, hlaJavaType, embedded);
            return DEFAULT_DURATION;
        }
        if (containsPath(mboxPaths, statementPath)) {
            detectFOMMismatch(statementPath, STRING_CLASSES, hlaJavaType, embedded);
            return DEFAULT_MBOX;
        }
        if (containsPath(languagePaths, statementPath)) {
            detectFOMMismatch(statementPath, STRING_CLASSES, hlaJavaType, embedded);
            return DEFAULT_LANGUAGE;
        }
        if (containsPath(sha1Paths, statementPath)) {
            detectFOMMismatch(statementPath, STRING_CLASSES, hlaJavaType, embedded);
            return DEFAULT_SHA1;
        }

        if (hlaJavaType != null && NUMERIC_CLASSES.contains(hlaJavaType)) {
            return DEFAULT_NUMERIC;
        }
        if (hlaJavaType != null && BOOLEAN_CLASSES.contains(hlaJavaType)) {
            return DEFAULT_BOOLEAN;
        }
        return randomString();
    }

    private static boolean containsPath(List<List<Object>> pathList, List<Object> path) {
        if (pathList == null || path == null) {
            return false;
        }

        for (List<Object> candidatePath : pathList) {
            if (candidatePath == null || candidatePath.size() != path.size()) {
                continue;
            }

            boolean matches = true;
            for (int i = 0; i < candidatePath.size(); i++) {
                Object candidatePart = candidatePath.get(i);
                Object pathPart = path.get(i);
                if (candidatePart == null || pathPart == null) {
                    if (candidatePart != pathPart) {
                        matches = false;
                        break;
                    }
                } else if (!candidatePart.equals("*") && !candidatePart.equals(pathPart)) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static void detectFOMMismatch(List<Object> statementPath, Set<Class<?>> candidates, Class<?> fomType,
            boolean embedded) throws InjectionDatatypeMismatchException {
        if (!embedded && fomType != null && !candidates.contains(fomType)){
            String message = String.format("Mismatch between statement path %s and FOM type %s",
                    statementPath.toString(), fomType.toString());
            throw new InjectionDatatypeMismatchException(message);
        }
    }

    private static final List<List<Object>> numericPaths = List.of(
            List.of("result", "score", "scaled"),
            List.of("result", "score", "raw"),
            List.of("result", "score", "min"),
            List.of("result", "score", "max"));

    private static final List<List<Object>> booleanPaths = List.of(
            List.of("result", "success"),
            List.of("result", "completion"));

    private static final List<List<Object>> durationPaths = List.of(
            List.of("result", "duration"));

    private static final List<List<Object>> languagePaths = List.of(
            List.of("context", "language"));

    private static final List<List<Object>> uuidPaths = List.of(
            List.of("context", "registration"),
            List.of("id"));

    private static final List<List<Object>> timestampPaths = List.of(
            List.of("timestamp"),
            List.of("stored"));

    private static final List<List<Object>> mboxPaths = List.of(
            List.of("actor", "mbox"),
            List.of("actor", "member", "*", "mbox"),
            List.of("object", "mbox"),
            List.of("object", "member", "*", "mbox"),
            List.of("context", "instructor", "mbox"),
            List.of("context", "instructor", "member", "*", "mbox"),
            List.of("context", "team", "member", "*", "mbox"));

    private static final List<List<Object>> sha1Paths = List.of(
            List.of("actor", "mbox_sha1sum"),
            List.of("actor", "member", "*", "mbox_sha1sum"),
            List.of("object", "mbox_sha1sum"),
            List.of("object", "member", "*", "mbox_sha1sum"),
            List.of("context", "instructor", "mbox_sha1sum"),
            List.of("context", "instructor", "member", "*", "mbox_sha1sum"),
            List.of("context", "team", "member", "*", "mbox_sha1sum"));

    private static final List<List<Object>> uriPaths = List.of(
            List.of("actor", "openid"),
            List.of("actor", "account", "homePage"),
            List.of("object", "openid"),
            List.of("object", "account", "homePage"),
            List.of("object", "member", "*", "openid"),
            List.of("object", "member", "*", "account", "homePage"),
            List.of("context", "team", "member", "*", "account", "homePage"),
            List.of("context", "team", "member", "*", "openid"),
            List.of("context", "instructor", "member", "*", "account", "homePage"),
            List.of("context", "instructor", "member", "*", "openid"),
            List.of("context", "instructor", "openid"),
            List.of("context", "instructor", "account", "homePage"),
            List.of("verb", "id"),
            List.of("object", "definition", "type"),
            List.of("object", "definition", "moreInfo"),
            List.of("context", "contextActivities", "*", "*", "id"),
            List.of("context", "contextActivities", "*", "*", "definition", "type"),
            List.of("context", "contextActivities", "*", "*", "definition", "moreInfo"));

    private static String randomString() {
        return "random-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
