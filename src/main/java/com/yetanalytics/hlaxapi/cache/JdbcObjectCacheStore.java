package com.yetanalytics.hlaxapi.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class JdbcObjectCacheStore implements ObjectCacheStore {

    private static final int SCHEMA_VERSION = 1;

    private final ObjectCacheQueries queries;
    private final ObjectMapper mapper = new ObjectMapper();
    private Connection connection;

    JdbcObjectCacheStore(Connection connection, ObjectCacheQueries queries, FomCatalog catalog) throws SQLException {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.queries = Objects.requireNonNull(queries, "queries");
        initialize(Objects.requireNonNull(catalog, "catalog"));
    }

    @Override
    public boolean isOpen() {
        return connection != null;
    }

    @Override
    public CachedObject ensureObject(
            String objectHandle,
            String objectName,
            FomCatalog.ObjectClassDef clazz) {
        try (PreparedStatement statement = connection.prepareStatement(queries.upsertObject())) {
            statement.setString(1, Objects.requireNonNull(objectHandle, "objectHandle"));
            statement.setString(2, objectName);
            statement.setInt(3, clazz.id());
            statement.setString(4, java.time.Instant.now().toString());
            statement.executeUpdate();
            return loadObject(objectHandle, clazz.localName());
        } catch (SQLException e) {
            throw new IllegalStateException("Could not upsert object instance " + objectHandle, e);
        }
    }

    @Override
    public Optional<ObjectSnapshot> findCurrentObjectSnapshot(String objectHandle) {
        try (PreparedStatement statement = connection.prepareStatement(queries.loadCurrentObjectSnapshot())) {
            statement.setString(1, objectHandle);
            try (ResultSet resultSet = statement.executeQuery()) {
                String objectName = null;
                String className = null;
                Map<String, byte[]> attributes = new LinkedHashMap<>();
                boolean found = false;
                while (resultSet.next()) {
                    found = true;
                    objectName = resultSet.getString("object_name");
                    className = resultSet.getString("local_name");
                    String attributeName = resultSet.getString("attribute_name");
                    byte[] rawBytes = resultSet.getBytes("raw_bytes");
                    if (attributeName != null && rawBytes != null) {
                        attributes.put(attributeName, rawBytes);
                    }
                }
                return found
                        ? Optional.of(new ObjectSnapshot(objectHandle, objectName, className, attributes))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load current object snapshot: " + objectHandle, e);
        }
    }

    @Override
    public void removeObject(String objectHandle, String removedAt) {
        try (PreparedStatement statement = connection.prepareStatement(queries.removeObject())) {
            statement.setString(1, removedAt);
            statement.setString(2, objectHandle);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not mark object removed: " + objectHandle, e);
        }
    }

    @Override
    public Optional<CachedValue> findCurrentValue(long instanceId, String pathKey) {
        String normalizedPath = FomCatalog.wildcardArrayIndexes(pathKey);
        try (PreparedStatement statement = connection.prepareStatement(queries.findCurrentValue())) {
            statement.setLong(1, instanceId);
            statement.setString(2, pathKey);
            statement.setString(3, normalizedPath);
            statement.setString(4, pathKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readCachedValue(resultSet));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read current cached value", e);
        }
    }

    @Override
    public Optional<CachedValue> findCurrentValue(String objectHandle, String pathKey) {
        try (PreparedStatement statement = connection.prepareStatement(queries.findObjectId())) {
            statement.setString(1, objectHandle);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return findCurrentValue(resultSet.getLong("id"), pathKey);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read object handle: " + objectHandle, e);
        }
    }

    @Override
    public List<CachedObject> currentObjects(List<FomCatalog.ObjectClassDef> classes) {
        if (classes == null || classes.isEmpty()) {
            return List.of();
        }
        List<CachedObject> objects = new ArrayList<>();
        try (PreparedStatement statement =
                connection.prepareStatement(queries.listCurrentObjects(classes.size()))) {
            for (int i = 0; i < classes.size(); i++) {
                statement.setInt(i + 1, classes.get(i).id());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    objects.add(new CachedObject(
                            resultSet.getLong("id"),
                            resultSet.getString("object_handle"),
                            resultSet.getString("object_name"),
                            resultSet.getString("local_name")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not list current cached objects", e);
        }
        return objects;
    }

    @Override
    public void replaceCurrentValues(
            String objectHandle,
            FomCatalog.ObjectClassDef clazz,
            List<ReflectedAttributeValues> attributes,
            String observedAt,
            long observedSequence) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        boolean autoCommit = currentAutoCommit();
        try {
            connection.setAutoCommit(false);
            CachedObject object = ensureObject(objectHandle, null, clazz);
            for (ReflectedAttributeValues attribute : attributes) {
                deleteCurrentValues(object.id(), clazz.id(), attribute.attributeName());
                for (DecodedAttributeValue value : attribute.values()) {
                    Optional<Integer> attributeId = attributeIdForPath(clazz, value.pathKey());
                    if (attributeId.isPresent()) {
                        upsertCurrentValue(
                                object.id(),
                                attributeId.orElseThrow(),
                                value,
                                observedAt,
                                observedSequence);
                    }
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackAfterReplacementFailure(e);
            throw new IllegalStateException(
                    "Could not replace reflected object attributes", e);
        } finally {
            restoreAutoCommit(autoCommit);
        }
    }

    @Override
    public Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
            connection = null;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not close HLA object cache", e);
        }
    }

    private void initialize(FomCatalog catalog) throws SQLException {
        executeStatements(queries.connectionSetupStatements());
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            executeStatements(queries.resetSchemaStatements());
            executeStatements(queries.createSchemaStatements());
            insertSchemaVersion();
            insertClasses(catalog);
            insertAttributes(catalog);
            if (queries.afterSeedFomMetadata().isPresent()) {
                executeStatements(List.of(queries.afterSeedFomMetadata().orElseThrow()));
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void executeStatements(List<String> statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private void insertSchemaVersion() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(queries.insertSchemaVersion())) {
            statement.setInt(1, SCHEMA_VERSION);
            statement.executeUpdate();
        }
    }

    private void insertClasses(FomCatalog catalog) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(queries.insertClass())) {
            for (FomCatalog.ObjectClassDef clazz : catalog.objectClasses()) {
                statement.setInt(1, clazz.id());
                statement.setString(2, clazz.hlaName());
                statement.setString(3, clazz.localName());
                statement.setString(4, clazz.parentName());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertAttributes(FomCatalog catalog) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(queries.insertAttribute())) {
            for (FomCatalog.ObjectClassDef clazz : catalog.objectClasses()) {
                for (FomCatalog.FomAttribute attribute : clazz.attributes()) {
                    statement.setInt(1, attribute.id());
                    statement.setInt(2, attribute.classId());
                    statement.setString(3, attribute.attributeName());
                    statement.setString(4, attribute.pathKey());
                    statement.setString(5, attribute.dataType());
                    statement.setString(6, attribute.primitiveType());
                    statement.setInt(7, attribute.leaf() ? 1 : 0);
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private CachedObject loadObject(String objectHandle, String className) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(queries.loadObject())) {
            statement.setString(1, objectHandle);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Object instance was not inserted: " + objectHandle);
                }
                return new CachedObject(
                        resultSet.getLong("id"),
                        objectHandle,
                        resultSet.getString("object_name"),
                        className);
            }
        }
    }

    private void deleteCurrentValues(long instanceId, int classId, String attributeName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(queries.deleteCurrentValues())) {
            statement.setLong(1, instanceId);
            statement.setInt(2, classId);
            statement.setString(3, attributeName);
            statement.executeUpdate();
        }
    }

    private void upsertCurrentValue(
            long instanceId,
            int attributeId,
            DecodedAttributeValue value,
            String observedAt,
            long observedSequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(queries.upsertCurrentValue())) {
            Object objectValue = value.value();
            String valueType = CachedValue.valueType(objectValue);
            statement.setLong(1, instanceId);
            statement.setInt(2, attributeId);
            statement.setString(3, valueType);
            if (objectValue instanceof byte[] bytes) {
                statement.setBytes(4, bytes);
                statement.setNull(5, Types.VARCHAR);
            } else {
                statement.setNull(4, queries.binaryJdbcType());
                statement.setString(5, serializeJson(objectValue));
            }
            statement.setBytes(6, value.rawBytes());
            statement.setString(7, observedAt);
            statement.setLong(8, observedSequence);
            statement.executeUpdate();
        }
    }

    private Optional<Integer> attributeIdForPath(FomCatalog.ObjectClassDef clazz, String pathKey) throws SQLException {
        Optional<Integer> existingId = attributeIdFromDatabase(clazz.id(), pathKey);
        if (existingId.isPresent()) {
            return existingId;
        }

        String wildcardPath = FomCatalog.wildcardArrayIndexes(pathKey);
        FomCatalog.FomAttribute template = clazz.attribute(wildcardPath).orElse(null);
        if (template == null || wildcardPath.equals(pathKey)) {
            return Optional.empty();
        }

        try (PreparedStatement statement = connection.prepareStatement(queries.insertDynamicAttribute())) {
            statement.setInt(1, clazz.id());
            statement.setString(2, template.attributeName());
            statement.setString(3, pathKey);
            statement.setString(4, template.dataType());
            statement.setString(5, template.primitiveType());
            statement.setInt(6, template.leaf() ? 1 : 0);
            statement.executeUpdate();
        }
        return attributeIdFromDatabase(clazz.id(), pathKey);
    }

    private Optional<Integer> attributeIdFromDatabase(int classId, String pathKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(queries.findAttributeId())) {
            statement.setInt(1, classId);
            statement.setString(2, pathKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(resultSet.getInt("id"));
                }
            }
        }
        return Optional.empty();
    }

    private void rollbackAfterReplacementFailure(Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            failure.addSuppressed(rollbackError);
        }
    }

    private boolean currentAutoCommit() {
        try {
            return connection.getAutoCommit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read object cache auto-commit", e);
        }
    }

    private void restoreAutoCommit(boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not restore object cache auto-commit", e);
        }
    }

    private String serializeJson(Object value) throws SQLException {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new SQLException("Could not serialize cached value as JSON", e);
        }
    }

    private CachedValue readCachedValue(ResultSet resultSet) throws SQLException {
        String valueType = resultSet.getString("value_type");
        byte[] rawBytes = resultSet.getBytes("raw_bytes");
        if ("blob".equals(valueType)) {
            return new CachedValue(valueType, resultSet.getBytes("value_blob"), rawBytes);
        }
        String valueJson = resultSet.getString("value_json");
        if (valueJson == null) {
            return new CachedValue(valueType, null, rawBytes);
        }
        try {
            return new CachedValue(valueType, mapper.readValue(valueJson, Object.class), rawBytes);
        } catch (JsonProcessingException e) {
            throw new SQLException("Could not deserialize cached value JSON", e);
        }
    }
}
