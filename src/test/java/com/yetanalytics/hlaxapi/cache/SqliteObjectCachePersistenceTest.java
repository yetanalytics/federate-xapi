package com.yetanalytics.hlaxapi.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yetanalytics.hlaxapi.FOMXML;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteObjectCachePersistenceTest extends ObjectCachePersistenceTest {

    @TempDir
    private Path tempDir;

    @Override
    protected ObjectCache newCache(
            String name,
            XapiConfig config,
            FomCatalog cacheCatalog,
            FOMXML cacheFomXml) {
        return new ObjectCache(
                config,
                cacheCatalog,
                cacheFomXml,
                decoderRegistry,
                "jdbc:sqlite:" + tempDir.resolve(name + ".sqlite"));
    }

    @Test
    void configuresSqliteSchemaVersionAndForeignKeys() throws SQLException {
        try (ObjectCache cache = newCache()) {
            assertEquals(2, scalarLong(cache, "PRAGMA user_version"));
            assertEquals(1, scalarLong(cache, "PRAGMA foreign_keys"));
        }
    }
}
