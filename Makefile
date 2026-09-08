.PHONY: clean build format lint run-dev run-dev-pitch run-rti run-validator test verify clean-vendor refresh run-debug-portico

APP_JAR := target/hla-xapi-1.0-SNAPSHOT.jar
PORTICO_REPO_URL ?= https://github.com/yetanalytics/portico.git
PORTICO_REF ?= yet_patch_object_subs
PORTICO_JAR ?= lib/maven-repository/org/porticoproject/portico/3.0.0-local/portico-3.0.0-local.jar
RTI_RID ?= RTI.rid
SIM_CONFIG ?= config/Simulation.config
PITCH_SIM_CONFIG ?= config/Simulation.pitch.config
PITCH_RTI_LIB ?= $(HOME)/prti1516e/lib/*

lib:
	PORTICO_REPO_URL=$(PORTICO_REPO_URL) PORTICO_REF=$(PORTICO_REF) ./scripts/vendor-portico.sh

build: lib
	mvn package

clean:
	mvn clean

clean-vendor:
	rm -rf lib

format:
	mvn spotless:apply

lint:
	mvn spotless:check checkstyle:check

test:
	mvn test

refresh: clean clean-vendor lib lint test build

verify: lib
	mvn --batch-mode verify

run-dev:
	java -cp "$(APP_JAR):$(PORTICO_JAR)" com.yetanalytics.hlaxapi.App $(SIM_CONFIG)

run-debug-portico:
	java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 -cp "$(APP_JAR):$(PORTICO_JAR)" com.yetanalytics.hlaxapi.App $(SIM_CONFIG)

run-dev-pitch:
	java -cp "$(APP_JAR):$(PITCH_RTI_LIB)" com.yetanalytics.hlaxapi.App $(PITCH_SIM_CONFIG)

run-rti:
	java -cp "$(APP_JAR):$(PORTICO_JAR)" org.portico2.rti.Main --rid $(RTI_RID) $(RTI_ARGS)

run-validator:
	java -jar $(APP_JAR) validate-server
