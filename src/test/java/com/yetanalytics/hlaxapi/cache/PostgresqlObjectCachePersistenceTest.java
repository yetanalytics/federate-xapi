package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
final class PostgresqlObjectCachePersistenceTest extends ObjectCachePersistenceTest {

    @Container
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("hla_xapi_test")
            .withUsername("hla_xapi")
            .withPassword("hla_xapi_test")
            .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"));

    @Override
    protected ObjectCache newCache(
            String name,
            XapiConfig config,
            FomCatalog cacheCatalog,
            FOMXML cacheFomXml) {
        String schema = "hla_object_cache_test_" + name.replace('-', '_');
        ObjectCacheConnectionSettings settings = ObjectCacheConnectionSettings.postgresql(
                POSTGRESQL.getJdbcUrl(),
                POSTGRESQL.getUsername(),
                POSTGRESQL.getPassword(),
                schema);
        return new ObjectCache(config, cacheCatalog, cacheFomXml, decoderRegistry, settings);
    }

    @Test
    void isolatesTablesInConfiguredSchemaAndUsesPostgresqlBinaryType() throws SQLException {
        try (ObjectCache cache = newCache("schema-types")) {
            assertEquals("hla_object_cache_test_schema_types", scalarString(cache, "SELECT current_schema()"));
            assertEquals(
                    "bytea",
                    scalarString(cache, """
                            SELECT data_type
                            FROM information_schema.columns
                            WHERE table_schema = current_schema()
                                AND table_name = 'object_attribute_current'
                                AND column_name = 'value_blob'
                            """));
        }
    }

    @Test
    void synchronizesAttributeIdentityAfterExplicitFomIds() throws SQLException {
        try (ObjectCache cache = newCache("identity")) {
            long seededMaximum = scalarLong(cache, "SELECT MAX(id) FROM fom_attribute");
            int rabbitClassId = catalog.objectClass("SimEntity.Rabbit").orElseThrow().id();
            try (PreparedStatement statement = cache.connection().prepareStatement("""
                    INSERT INTO fom_attribute
                        (class_id, attribute_name, path_key, data_type, primitive_type, is_leaf)
                    VALUES (?, 'Synthetic', 'Synthetic[0]', 'HLAinteger32BE', 'HLAinteger32BE', 1)
                    """)) {
                statement.setInt(1, rabbitClassId);
                statement.executeUpdate();
            }

            assertTrue(scalarLong(
                            cache,
                            "SELECT id FROM fom_attribute WHERE path_key = 'Synthetic[0]'")
                    > seededMaximum);
        }
    }

    private String scalarString(ObjectCache cache, String sql) throws SQLException {
        try (PreparedStatement statement = cache.connection().prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
