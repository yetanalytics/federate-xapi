package com.yetanalytics.hlaxapi.validation;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.JmsAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.JndiConnectionFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.activemq.ActiveMQAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.jta.JtaAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Isolated Spring Boot entry point for validator-only HTTP mode. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {
    ActiveMQAutoConfiguration.class,
    ArtemisAutoConfiguration.class,
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JmsAutoConfiguration.class,
    JndiConnectionFactoryAutoConfiguration.class,
    JtaAutoConfiguration.class,
    TransactionAutoConfiguration.class
})
public class ValidationServerApplication {

    public static ConfigurableApplicationContext run(String[] args) {
        return run(ValidationServerSettings.load(), args);
    }

    static ConfigurableApplicationContext run(ValidationServerSettings settings, String[] args) {
        SpringApplication application = new SpringApplication(ValidationServerApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setAddCommandLineProperties(false);
        application.setDefaultProperties(springProperties(settings));
        application.addInitializers(context ->
                context.getBeanFactory().registerSingleton("validationServerSettings", settings));
        return application.run(args);
    }

    private static Map<String, Object> springProperties(ValidationServerSettings settings) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.address", settings.bindAddress());
        properties.put("server.port", settings.port());
        properties.put("spring.application.name", "federate-xapi-validator");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.jackson.default-property-inclusion", "non_null");
        properties.put("springdoc.api-docs.path", "/openapi.json");
        properties.put("springdoc.swagger-ui.path", "/docs");
        return properties;
    }

    @Bean
    ValidationCore validationCore() {
        return new ValidationCore();
    }

    @Bean
    ValidationController validationController(ValidationCore validationCore) {
        return new ValidationController(validationCore);
    }

    @Bean
    ValidationExceptionHandler validationExceptionHandler() {
        return new ValidationExceptionHandler();
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeFilter(
            ValidationServerSettings settings) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestSizeLimitFilter(settings.maxRequestBytes()));
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }

    @Bean
    WebMvcConfigurer corsConfiguration(ValidationServerSettings settings) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (!settings.corsAllowedOrigins().isEmpty()) {
                    registry.addMapping("/api/**")
                            .allowedOrigins(settings.corsAllowedOrigins().toArray(String[]::new))
                            .allowedMethods("GET", "POST", "OPTIONS")
                            .allowedHeaders("Content-Type", "Accept")
                            .maxAge(3600);
                }
            }
        };
    }

    @Bean
    OpenAPI validatorOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Federate xAPI Validation API")
                .version(ValidationCore.API_VERSION)
                .description("Stateless FOM and xAPI configuration validation."));
    }

    @EventListener(ApplicationReadyEvent.class)
    void logReady(ApplicationReadyEvent event) {
        ValidationServerSettings settings = event.getApplicationContext().getBean(ValidationServerSettings.class);
        int actualPort = ((WebServerApplicationContext) event.getApplicationContext()).getWebServer().getPort();
        org.apache.logging.log4j.LogManager.getLogger(ValidationServerApplication.class).info(
                "Validator ready at http://{}:{} (API docs: /docs)",
                settings.bindAddress(),
                actualPort);
    }
}
