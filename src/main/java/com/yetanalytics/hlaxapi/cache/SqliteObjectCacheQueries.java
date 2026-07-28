package com.yetanalytics.hlaxapi.cache;

import java.util.List;
import java.util.Optional;

final class SqliteObjectCacheQueries implements ObjectCacheQueries {

    @Override
    public int binaryJdbcType() {
        return java.sql.Types.BLOB;
    }

    @Override
    public List<String> connectionSetupStatements() {
        return List.of("PRAGMA foreign_keys = ON");
    }

    @Override
    public List<String> resetSchemaStatements() {
        return List.of(
                "DROP TABLE IF EXISTS object_attribute_current",
                "DROP TABLE IF EXISTS object_instance",
                "DROP TABLE IF EXISTS fom_attribute",
                "DROP TABLE IF EXISTS fom_object_class",
                "DROP TABLE IF EXISTS object_cache_metadata");
    }

    @Override
    public List<String> createSchemaStatements() {
        return List.of(
                """
                CREATE TABLE object_cache_metadata (
                    schema_version INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE fom_object_class (
                    id INTEGER PRIMARY KEY,
                    hla_name TEXT NOT NULL,
                    local_name TEXT NOT NULL UNIQUE,
                    parent_name TEXT
                )
                """,
                """
                CREATE TABLE fom_attribute (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    class_id INTEGER NOT NULL REFERENCES fom_object_class(id),
                    attribute_name TEXT NOT NULL,
                    path_key TEXT NOT NULL,
                    data_type TEXT NOT NULL,
                    primitive_type TEXT,
                    is_leaf INTEGER NOT NULL,
                    UNIQUE(class_id, path_key)
                )
                """,
                """
                CREATE TABLE object_instance (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    object_handle TEXT NOT NULL UNIQUE,
                    object_name TEXT,
                    class_id INTEGER NOT NULL REFERENCES fom_object_class(id),
                    discovered_at TEXT NOT NULL,
                    removed_at TEXT
                )
                """,
                """
                CREATE TABLE object_attribute_current (
                    instance_id INTEGER NOT NULL REFERENCES object_instance(id),
                    attribute_id INTEGER NOT NULL REFERENCES fom_attribute(id),
                    value_type TEXT NOT NULL,
                    value_blob BLOB,
                    value_json TEXT,
                    raw_bytes BLOB,
                    observed_at TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    PRIMARY KEY(instance_id, attribute_id)
                )
                """,
                "PRAGMA user_version = 1");
    }

    @Override
    public String insertSchemaVersion() {
        return "INSERT INTO object_cache_metadata (schema_version) VALUES (?)";
    }

    @Override
    public String insertClass() {
        return """
                INSERT OR IGNORE INTO fom_object_class (id, hla_name, local_name, parent_name)
                VALUES (?, ?, ?, ?)
                """;
    }

    @Override
    public String insertAttribute() {
        return """
                INSERT OR IGNORE INTO fom_attribute
                    (id, class_id, attribute_name, path_key, data_type, primitive_type, is_leaf)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
    }

    @Override
    public Optional<String> afterSeedFomMetadata() {
        return Optional.empty();
    }

    @Override
    public String upsertObject() {
        return """
                INSERT INTO object_instance (object_handle, object_name, class_id, discovered_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(object_handle) DO UPDATE SET
                    object_name = COALESCE(excluded.object_name, object_instance.object_name),
                    class_id = excluded.class_id,
                    removed_at = NULL
                """;
    }

    @Override
    public String loadObject() {
        return "SELECT id, object_name FROM object_instance WHERE object_handle = ?";
    }

    @Override
    public String loadCurrentObjectSnapshot() {
        return """
                SELECT i.object_handle, i.object_name, c.local_name, a.attribute_name, v.raw_bytes
                FROM object_instance i
                JOIN fom_object_class c ON c.id = i.class_id
                LEFT JOIN object_attribute_current v
                    ON v.instance_id = i.id
                    AND v.attribute_id IN (
                        SELECT top_level.id
                        FROM fom_attribute top_level
                        WHERE top_level.class_id = i.class_id
                            AND top_level.path_key = top_level.attribute_name
                    )
                LEFT JOIN fom_attribute a ON a.id = v.attribute_id
                WHERE i.object_handle = ? AND i.removed_at IS NULL
                ORDER BY a.id
                """;
    }

    @Override
    public String removeObject() {
        return "UPDATE object_instance SET removed_at = ? WHERE object_handle = ?";
    }

    @Override
    public String findCurrentValue() {
        return """
                SELECT c.value_type, c.value_json, c.value_blob, c.raw_bytes
                FROM object_attribute_current c
                JOIN fom_attribute a ON a.id = c.attribute_id
                WHERE c.instance_id = ? AND (a.path_key = ? OR a.path_key = ?)
                ORDER BY CASE WHEN a.path_key = ? THEN 0 ELSE 1 END
                LIMIT 1
                """;
    }

    @Override
    public String findObjectId() {
        return "SELECT id FROM object_instance WHERE object_handle = ?";
    }

    @Override
    public String listCurrentObjects(int classCount) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(classCount, "?"));
        return """
                SELECT i.id, i.object_handle, i.object_name, c.local_name
                FROM object_instance i
                JOIN fom_object_class c ON c.id = i.class_id
                WHERE i.class_id IN (%s) AND i.removed_at IS NULL
                ORDER BY i.id
                """.formatted(placeholders);
    }

    @Override
    public String deleteCurrentValues() {
        return """
                DELETE FROM object_attribute_current
                WHERE instance_id = ?
                    AND attribute_id IN (
                        SELECT id
                        FROM fom_attribute
                        WHERE class_id = ? AND attribute_name = ?
                    )
                """;
    }

    @Override
    public String upsertCurrentValue() {
        return """
                INSERT INTO object_attribute_current
                    (instance_id, attribute_id, value_type, value_blob, value_json, raw_bytes, observed_at, sequence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(instance_id, attribute_id) DO UPDATE SET
                    value_type = excluded.value_type,
                    value_blob = excluded.value_blob,
                    value_json = excluded.value_json,
                    raw_bytes = excluded.raw_bytes,
                    observed_at = excluded.observed_at,
                    sequence = excluded.sequence
                """;
    }

    @Override
    public String insertDynamicAttribute() {
        return """
                INSERT OR IGNORE INTO fom_attribute
                    (class_id, attribute_name, path_key, data_type, primitive_type, is_leaf)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
    }

    @Override
    public String findAttributeId() {
        return "SELECT id FROM fom_attribute WHERE class_id = ? AND path_key = ?";
    }
}
