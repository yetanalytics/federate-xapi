package com.yetanalytics.hlaxapi.cache;

import java.util.List;
import java.util.Optional;

interface ObjectCacheQueries {
    int binaryJdbcType();

    List<String> connectionSetupStatements();

    List<String> resetSchemaStatements();

    List<String> createSchemaStatements();

    String insertSchemaVersion();

    String insertClass();

    String insertAttribute();

    Optional<String> afterSeedFomMetadata();

    String upsertObject();

    String loadObject();

    String loadCurrentObjectSnapshot();

    String removeObject();

    String findCurrentValue();

    String findObjectId();

    String listCurrentObjects();

    String deleteCurrentValues();

    String upsertCurrentValue();

    String insertDynamicAttribute();

    String findAttributeId();
}
