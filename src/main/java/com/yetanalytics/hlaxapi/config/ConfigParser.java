package com.yetanalytics.hlaxapi.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yetanalytics.hlaxapi.config.model.LrsConfig;
import com.yetanalytics.hlaxapi.config.model.ObjectLookup;
import com.yetanalytics.hlaxapi.config.model.ObjectCacheConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.config.model.TrackedObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight parser for the config format described in config-ideas.md.
 * It loads the JSON file and performs higher-level parsing into Java objects.
 */
public class ConfigParser {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode root;

    public ConfigParser(String path) throws IOException {
        File f = Paths.get(path).toFile();
        this.root = mapper.readTree(f);
    }

    private ConfigParser(JsonNode root) {
        this.root = root;
    }

    public static ConfigParser fromEnvOrDefault() throws IOException {
        String path = System.getenv().getOrDefault("XAPI_CONFIG", "config/xapi-config.json");
        return ConfigParser.fromFile(path);
    }

    public static ConfigParser fromFile(String path) throws IOException {
        return new ConfigParser(path);
    }

    public static ConfigParser fromJson(String json) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        return new ConfigParser(mapper.readTree(json));
    }

    public XapiConfig parse() {
        XapiConfig cfg = new XapiConfig();

        JsonNode version = root.get("configVersion");
        if (version != null) {
            if (!version.isTextual()) {
                throw new IllegalArgumentException("configVersion must be a string");
            }
            cfg.configVersion = ConfigVersions.requireSupported(version.asText());
        }

        // statementTriggers
        JsonNode st = root.get("statementTriggers");
        if (st != null && st.isArray()) {
            List<StatementTrigger> triggers = new ArrayList<>();
            for (int triggerIndex = 0; triggerIndex < st.size(); triggerIndex++) {
                JsonNode tnode = st.get(triggerIndex);
                StatementTrigger stt = new StatementTrigger();
                stt.type = StatementTrigger.Type.fromString(tnode.path("type").asText(null));
                stt.skipValidation = tnode.path("skipValidation").asBoolean(false);
                // map "class" json prop to clazz
                stt.clazz = tnode.path("class").asText(null);
                stt.lookups = parseLookups(tnode.get("lookups"));
                try {
                    stt.criteria = CriteriaExpressionParser.parseNullable(tnode.get("criteria"));
                    CriteriaExpressionValidator.validateTrigger(stt.criteria, stt.lookups, stt.type);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "statementTriggers[" + triggerIndex + "].criteria: " + e.getMessage(),
                            e);
                }
                if (tnode.has("statement")) {
                    try {
                        stt.statement = mapper.writeValueAsString(tnode.get("statement"));
                    } catch (JsonProcessingException e) {
                        stt.statement = null;
                    }
                }
                triggers.add(stt);
            }
            cfg.statementTriggers = triggers;
        }

        // lrs
        if (root.has("lrs")) {
            cfg.lrsConfig = mapper.convertValue(root.get("lrs"), LrsConfig.class);
        }

        // object cache
        JsonNode oc = root.get("objectCache");
        if (oc != null && oc.isObject()) {
            ObjectCacheConfig objectCacheConfig = new ObjectCacheConfig();
            JsonNode trackedObjects = oc.get("trackedObjects");
            if (trackedObjects != null && trackedObjects.isArray()) {
                List<TrackedObject> tracked = new ArrayList<>();
                for (JsonNode trackedNode : trackedObjects) {
                    TrackedObject trackedObject = new TrackedObject();
                    trackedObject.clazz = trackedNode.path("class").asText(null);
                    trackedObject.allAttributes = trackedNode.path("allAttributes").asBoolean(false);
                    JsonNode attributes = trackedNode.get("attributes");
                    if (attributes != null && attributes.isArray()) {
                        trackedObject.attributes = new ArrayList<>();
                        for (JsonNode attribute : attributes) {
                            if (attribute.isTextual()) {
                                trackedObject.attributes.add(attribute.asText());
                            }
                        }
                    }
                    tracked.add(trackedObject);
                }
                objectCacheConfig.trackedObjects = tracked;
            }
            cfg.objectCacheConfig = objectCacheConfig;
        }

        return cfg;
    }

    private Map<String, ObjectLookup> parseLookups(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, ObjectLookup> lookups = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode lookupNode = field.getValue();
            if (lookupNode == null || !lookupNode.isObject()) {
                continue;
            }
            ObjectLookup lookup = new ObjectLookup();
            lookup.clazz = lookupNode.path("class").asText(null);
            lookup.criteria = CriteriaExpressionParser.parseNullable(lookupNode.get("criteria"));
            CriteriaExpressionValidator.validateCacheFilter(lookup.criteria);
            lookups.put(field.getKey(), lookup);
        }
        return lookups.isEmpty() ? null : lookups;
    }

}
