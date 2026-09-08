package com.yetanalytics.hlaxapi.config;

import java.util.Set;

/** Supported versions of the xAPI configuration document contract. */
public final class ConfigVersions {

    public static final String CURRENT = "1.0";
    public static final Set<String> SUPPORTED = Set.of(CURRENT);

    private ConfigVersions() {
    }

    public static String requireSupported(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("configVersion must be a non-empty string");
        }
        if (!SUPPORTED.contains(version)) {
            throw new IllegalArgumentException(
                    "Unsupported configVersion " + version + "; supported versions: " + SUPPORTED);
        }
        return version;
    }
}
