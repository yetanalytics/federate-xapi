package com.yetanalytics.hlaxapi.config;

import java.util.Map;

public class BrokerConfiguration {

    public String brokerUrl;
    public String username;
    public String password;
    public boolean embedded = false;

    private static final String BROKER_URL_ENV = "XAPI_BROKER_URL";
    private static final String BROKER_USERNAME_ENV = "XAPI_BROKER_USERNAME";
    private static final String BROKER_PASSWORD_ENV = "XAPI_BROKER_PASSWORD";

    public static final String EMBEDDED_BROKER_URL = "vm://0";

    public BrokerConfiguration(String brokerUrl, String username, String password, boolean embedded) {
        this.brokerUrl = brokerUrl;
        this.username = username;
        this.password = password;
        this.embedded = embedded;
    }

    public static BrokerConfiguration from(Map<String, String> environment) {
        String brokerUrl = environment.get(BROKER_URL_ENV);
        String username = environment.get(BROKER_USERNAME_ENV);
        String password = environment.get(BROKER_PASSWORD_ENV);
        boolean embedded = false;

        if (brokerUrl == null) {
            brokerUrl = EMBEDDED_BROKER_URL;
            embedded = true;
        }

        return new BrokerConfiguration(brokerUrl, username, password, embedded);
    }

}
