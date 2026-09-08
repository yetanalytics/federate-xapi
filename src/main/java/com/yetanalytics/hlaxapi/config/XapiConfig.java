package com.yetanalytics.hlaxapi.config;

import java.util.List;

import com.yetanalytics.hlaxapi.config.model.LrsConfig;
import com.yetanalytics.hlaxapi.config.model.ObjectCacheConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;

public class XapiConfig {
    public String configVersion = ConfigVersions.CURRENT;
    public List<StatementTrigger> statementTriggers;
    public LrsConfig lrsConfig;
    public ObjectCacheConfig objectCacheConfig;
}
