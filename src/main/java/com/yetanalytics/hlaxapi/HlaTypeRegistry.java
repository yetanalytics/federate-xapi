package com.yetanalytics.hlaxapi;

/** Runtime-independent metadata for HLA primitive representations. */
public interface HlaTypeRegistry {

    boolean supports(String hlaType);

    Class<?> getClassForType(String hlaType);
}
