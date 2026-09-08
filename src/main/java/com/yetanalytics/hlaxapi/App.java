package com.yetanalytics.hlaxapi;

import com.yetanalytics.hlaxapi.validation.ValidationServerApplication;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

public class App {

    private static final Logger logger = LogManager.getLogger(App.class);

    private static final String VALIDATION_SERVER_ARG = "validate-server";

    public static void main(String[] args) {
        if (args.length > 0 && VALIDATION_SERVER_ARG.equals(args[0])) {
            ValidationServerApplication.run(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        logger.info("Initializing Application Context");

        @SuppressWarnings("resource")
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();

        try {
            ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(ctx);
            scanner.addExcludeFilter(new RegexPatternTypeFilter(
                    Pattern.compile("com\\.yetanalytics\\.hlaxapi\\.validation\\..*")));
            scanner.scan("com.yetanalytics.hlaxapi");
            ctx.refresh();
            ctx.registerShutdownHook();

            Federate federate = ctx.getBean(Federate.class);
            federate.run(args);
        } finally {
            if (ctx.isActive()) {
                ctx.close();
            }
        }
    }
}
