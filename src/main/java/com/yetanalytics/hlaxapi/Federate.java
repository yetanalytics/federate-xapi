package com.yetanalytics.hlaxapi;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.yetanalytics.hlaxapi.exception.XapiConfigurationException;

import hla.rti1516e.exceptions.RTIexception;
import hla.rti1516e.exceptions.RTIinternalError;

@Component
public class Federate {

    private static final Logger logger = LogManager.getLogger(Federate.class);

    @Autowired
    private HlaInterface hlaInterface;

    @Autowired
    private SimulationConfig config;

    public void run(String[] args) {
        logger.info("Starting xAPI HLA Federate");

        final CountDownLatch shutdownLatch = new CountDownLatch(1);
        final AtomicBoolean shuttingDown = new AtomicBoolean(false);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdown(hlaInterface, shutdownLatch, shuttingDown);
        }, "hla-xapi-shutdown-hook"));

        try {
            logger.info("Attempting RTI Connection");
            hlaInterface.start();
        } catch (RTIexception e) {
            logger.error("Could not connect to the RTI using the local settings designator \""
                    + config.getLocalSettingsDesignator() + "\"", e);
            shutdown(hlaInterface, shutdownLatch, shuttingDown);
            return;
        } catch (XapiConfigurationException e) {
            logger.error("Failed to start due to configuration error", e);
            shutdown(hlaInterface, shutdownLatch, shuttingDown);
            return;
        }

        printBanner();
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Main thread interrupted. Shutting down");
            shutdown(hlaInterface, shutdownLatch, shuttingDown);
        }
    }

    private static void shutdown(
            HlaInterface hlaInterface,
            CountDownLatch shutdownLatch,
            AtomicBoolean shuttingDown) {

        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        logger.info("Shutting down Custom Federate");

        try {
            hlaInterface.stop();
        } catch (RTIinternalError e) {
            logger.warn("Error while stopping HLA interface", e);
        } finally {
            shutdownLatch.countDown();

        }
    }

    private static final String BANNER = """
            \n
             ______       _                _                         _____ _____
            |  ____|     | |              | |                  /\\   |  __ \\_   _|
            | |__ ___  __| | ___ _ __ __ _| |_ ___   __  __   /  \\  | |__) || |
            |  __/ _ \\/ _` |/ _ \\ '__/ _` | __/ _ \\  \\ \\/ /  / /\\ \\ |  ___/ | |
            | | |  __/ (_| |  __/ | | (_| | ||  __/   >  <  / ____ \\| |    _| |_
            |_|  \\___|\\__,_|\\___|_|  \\__,_|\\__\\___|  /_/\\_\\/_/    \\_\\_|   |_____|
            (c) 2026 Yet Analytics, Inc
            """;

    public static void printBanner() {
        logger.info(BANNER);
    }
}
