package com.yetanalytics.hlaxapi.config.model;

public class LrsConfig {
    public String host;
    public String key;
    public String secret;
    public int batch = 50;
    public int maxRetries = 3;

    @Override
    public String toString() {
        return String.format("LrsConfig{host=%s,key=%s,batch=%d,maxRetries=%d}", host, key, batch, maxRetries);
    }
}
