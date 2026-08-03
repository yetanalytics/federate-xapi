package com.yetanalytics.hlaxapi;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.yetanalytics.hlaxapi.TriggerProcessor.TriggerProcessingResult;
import com.yetanalytics.hlaxapi.cache.FomCatalog;
import com.yetanalytics.hlaxapi.cache.ObjectCache;
import com.yetanalytics.hlaxapi.cache.ObjectSnapshot;
import com.yetanalytics.hlaxapi.config.XapiConfig;
import com.yetanalytics.hlaxapi.config.model.StatementTrigger;
import com.yetanalytics.hlaxapi.exception.XapiConfigurationException;
import com.yetanalytics.hlaxapi.injection.InteractionInjectionContext;
import com.yetanalytics.hlaxapi.injection.ObjectInjectionContext;
import com.yetanalytics.hlaxapi.injection.TestInjectionContext;
import com.yetanalytics.xapi.util.StatementValidator;
import com.yetanalytics.xapi.util.StatementValidator.StatementValidationResult;

import hla.rti1516e.AttributeHandle;
import hla.rti1516e.AttributeHandleSet;
import hla.rti1516e.AttributeHandleValueMap;
import hla.rti1516e.CallbackModel;
import hla.rti1516e.InteractionClassHandle;
import hla.rti1516e.LogicalTime;
import hla.rti1516e.MessageRetractionHandle;
import hla.rti1516e.NullFederateAmbassador;
import hla.rti1516e.ObjectClassHandle;
import hla.rti1516e.ObjectInstanceHandle;
import hla.rti1516e.OrderType;
import hla.rti1516e.ParameterHandleValueMap;
import hla.rti1516e.RTIambassador;
import hla.rti1516e.ResignAction;
import hla.rti1516e.RtiFactory;
import hla.rti1516e.RtiFactoryFactory;
import hla.rti1516e.TransportationTypeHandle;
import hla.rti1516e.exceptions.AlreadyConnected;
import hla.rti1516e.exceptions.AttributeNotDefined;
import hla.rti1516e.exceptions.CallNotAllowedFromWithinCallback;
import hla.rti1516e.exceptions.ConnectionFailed;
import hla.rti1516e.exceptions.CouldNotCreateLogicalTimeFactory;
import hla.rti1516e.exceptions.CouldNotOpenFDD;
import hla.rti1516e.exceptions.ErrorReadingFDD;
import hla.rti1516e.exceptions.FederateAlreadyExecutionMember;
import hla.rti1516e.exceptions.FederateInternalError;
import hla.rti1516e.exceptions.FederateIsExecutionMember;
import hla.rti1516e.exceptions.FederateNameAlreadyInUse;
import hla.rti1516e.exceptions.FederateNotExecutionMember;
import hla.rti1516e.exceptions.FederateOwnsAttributes;
import hla.rti1516e.exceptions.FederateServiceInvocationsAreBeingReportedViaMOM;
import hla.rti1516e.exceptions.FederationExecutionAlreadyExists;
import hla.rti1516e.exceptions.FederationExecutionDoesNotExist;
import hla.rti1516e.exceptions.InconsistentFDD;
import hla.rti1516e.exceptions.InteractionClassNotDefined;
import hla.rti1516e.exceptions.InvalidAttributeHandle;
import hla.rti1516e.exceptions.InteractionParameterNotDefined;
import hla.rti1516e.exceptions.InvalidInteractionClassHandle;
import hla.rti1516e.exceptions.InvalidLocalSettingsDesignator;
import hla.rti1516e.exceptions.InvalidObjectClassHandle;
import hla.rti1516e.exceptions.InvalidParameterHandle;
import hla.rti1516e.exceptions.InvalidResignAction;
import hla.rti1516e.exceptions.NameNotFound;
import hla.rti1516e.exceptions.NotConnected;
import hla.rti1516e.exceptions.ObjectClassNotDefined;
import hla.rti1516e.exceptions.ObjectInstanceNotKnown;
import hla.rti1516e.exceptions.OwnershipAcquisitionPending;
import hla.rti1516e.exceptions.RTIinternalError;
import hla.rti1516e.exceptions.RestoreInProgress;
import hla.rti1516e.exceptions.SaveInProgress;
import hla.rti1516e.exceptions.UnsupportedCallbackModel;

@Component
public class HlaInterfaceImpl extends NullFederateAmbassador implements HlaInterface {

    private static final Logger logger = LogManager.getLogger(HlaInterfaceImpl.class);
    private static final String OBJECT_ROOT_PREFIX = "HLAobjectRoot.";
    private static final String INTERACTION_ROOT_PREFIX = "HLAinteractionRoot.";

    private RTIambassador ambassador;

    private final Map<String, String> pendingObjectCreates = new HashMap<>();

    @Autowired
    private XapiConfig xapiConfig;

    @Autowired
    private SimulationConfig simulationConfig;

    @Autowired
    private TriggerProcessor triggerProcessor;

    @Autowired
    private StatementValidator validator;

    @Autowired
    private ObjectCache objectCache;

    @Autowired
    private XapiClient xapiClient;

    public void start()
            throws ConnectionFailed, InvalidLocalSettingsDesignator, RTIinternalError, NotConnected, ErrorReadingFDD,
            CouldNotOpenFDD, InconsistentFDD, RestoreInProgress, SaveInProgress,
            FederateServiceInvocationsAreBeingReportedViaMOM, XapiConfigurationException {

        validateConfig();

        RtiFactory rtiFactory = RtiFactoryFactory.getRtiFactory();
        ambassador = rtiFactory.getRtiAmbassador();

        if (!objectCache.isEnabled()) {
            logger.info("No query injections or tracked objects configured; object cache is disabled");
        }

        try {
            if (simulationConfig.getLocalSettingsDesignator() == null
                    || simulationConfig.getLocalSettingsDesignator().isBlank()) {
                ambassador.connect(this, CallbackModel.HLA_IMMEDIATE);
            } else {
                ambassador.connect(this, CallbackModel.HLA_IMMEDIATE, simulationConfig.getLocalSettingsDesignator());
            }
        } catch (UnsupportedCallbackModel | CallNotAllowedFromWithinCallback e) {
            throw new RTIinternalError("HlaInterfaceFailure", e);
        } catch (AlreadyConnected ignored) {
        }

        File fddFile = new File(simulationConfig.getFom());
        URL url = null;
        try {
            url = fddFile.toURI().toURL();
        } catch (MalformedURLException ignored) {
        }

        try {
            ambassador.createFederationExecution(simulationConfig.getFederationName(), url);
        } catch (FederationExecutionAlreadyExists ignored) {
        }

        try {
            boolean joined = false;
            String federateNameSuffix = "";
            int federateNameIndex = 1;
            while (!joined) {
                try {
                    ambassador.joinFederationExecution(simulationConfig.getFederateName() + federateNameSuffix,
                            "xAPI Interaction Processor",
                            simulationConfig.getFederationName(),
                            new URL[] { url });
                    joined = true;
                } catch (FederateNameAlreadyInUse e) {
                    federateNameSuffix = "-" + federateNameIndex++;
                }
            }
        } catch (FederateAlreadyExecutionMember ignored) {
        } catch (CouldNotCreateLogicalTimeFactory | FederationExecutionDoesNotExist
                | CallNotAllowedFromWithinCallback e) {
            throw new RTIinternalError("HlaInterfaceFailure", e);
        }

        // Get relevant interactions to subscribe to from the xapiConfig

        try {
            subscribeObjectClasses();
            subscribeInteractions();
            logger.info("Started Subscription");

        } catch (FederateNotExecutionMember e) {
            throw new RTIinternalError("HlaInterfaceFailure", e);
        }
    }

    public void stop() throws RTIinternalError {
        try {
            try {
                ambassador.resignFederationExecution(ResignAction.CANCEL_THEN_DELETE_THEN_DIVEST);
            } catch (FederateOwnsAttributes | OwnershipAcquisitionPending
                    | CallNotAllowedFromWithinCallback | InvalidResignAction e) {
                throw new RTIinternalError("HlaInterfaceFailure", e);
            } catch (FederateNotExecutionMember ignored) {
            }

            try {
                ambassador.disconnect();
            } catch (FederateIsExecutionMember | CallNotAllowedFromWithinCallback e) {
                throw new RTIinternalError("HlaInterfaceFailure", e);
            }
        } catch (NotConnected ignored) {
        }
    }

    public void validateConfig() throws XapiConfigurationException {
        for(StatementTrigger st : xapiConfig.statementTriggers){
            if (st.skipValidation) continue;
            TriggerProcessingResult tpr = triggerProcessor.renderTemplateForValidation(
                    st,
                    new TestInjectionContext(st.type, st.clazz));
            if (tpr.success()) {
                StatementValidationResult svr = validator.validateStatement(tpr.statement());
                if (!svr.isValid()){
                    logger.error("Invalid Statement Trigger (Invalid xAPI): {}. {}", st, svr.getErrors());
                    throw new XapiConfigurationException("Could not validate xAPI Configuration");
                }
            } else {
                logger.error("Invalid Statement Trigger (Could not Process): {}. {}", st, tpr.error());
                throw new XapiConfigurationException("Could not validate xAPI Configuration", tpr.error());
            }
        }
    }

    @Override
    public void connectionLost(String faultDescription) throws FederateInternalError {
        System.out.println("Lost Connection because: " + faultDescription);
    }

    /*
     * Objects
     */

    private void subscribeObjectClasses()
            throws FederateNotExecutionMember, RestoreInProgress, SaveInProgress, NotConnected, RTIinternalError {
        if (!objectCache.hasSubscriptions()) {
            return;
        }
        List<Map.Entry<String, Set<String>>> subscriptions =
                new ArrayList<>(objectCache.subscriptions().entrySet());
        subscriptions.sort(Comparator
                .<Map.Entry<String, Set<String>>>comparingInt(subscription ->
                        objectCache.catalog().objectClassDepth(subscription.getKey()))
                .reversed()
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, Set<String>> subscription : subscriptions) {
            try {
                FomCatalog.ObjectClassDef clazz = objectCache.catalog().objectClass(subscription.getKey()).orElseThrow(
                        () -> new IllegalArgumentException("No FOM object class " + subscription.getKey()));
                ObjectClassHandle classHandle = ambassador.getObjectClassHandle(clazz.hlaName());
                AttributeHandleSet attributeHandles =
                        attributeHandles(classHandle, subscription.getValue());
                if (attributeHandles.isEmpty()) {
                    continue;
                }
                ambassador.subscribeObjectClassAttributes(classHandle, attributeHandles);
            } catch (AttributeNotDefined | InvalidObjectClassHandle | NameNotFound | ObjectClassNotDefined
                    | IllegalArgumentException e) {
                logger.error("Could not subscribe object class {}!", subscription.getKey(), e);
            }
        }
    }

    @Override
    public void discoverObjectInstance(
            ObjectInstanceHandle theObject,
            ObjectClassHandle theObjectClass,
            String objectName) throws FederateInternalError {
        discoverObjectInstance(theObject, theObjectClass, objectName, null);
    }

    @Override
    public void discoverObjectInstance(
            ObjectInstanceHandle theObject,
            ObjectClassHandle theObjectClass,
            String objectName,
            hla.rti1516e.FederateHandle producingFederate) throws FederateInternalError {
        if (!objectCache.hasSubscriptions()) {
            return;
        }
        String className;
        try {
            className = rootRelativeObjectClassName(
                    ambassador.getObjectClassName(theObjectClass));
        } catch (InvalidObjectClassHandle | FederateNotExecutionMember | NotConnected | RTIinternalError e) {
            logger.error("Error resolving discovered object {}", objectName, e);
            return;
        }
        Set<String> subscribedAttributes =
                objectCache.effectiveSubscriptionAttributes(className);
        if (subscribedAttributes.isEmpty()) {
            return;
        }
        if (triggerProcessor.hasMatchingTrigger(
                StatementTrigger.Type.OBJECT_CREATE,
                className)) {
            pendingObjectCreates.put(theObject.toString(), className);
        }
        if (objectCache.isEnabled()) {
            try {
                objectCache.discoverObject(theObject.toString(), objectName, className);
            } catch (RuntimeException e) {
                logger.error("Error caching discovered object {}", objectName, e);
            }
        }
        try {
            AttributeHandleSet attributeHandles = attributeHandles(theObjectClass, subscribedAttributes);
            if (!attributeHandles.isEmpty()) {
                ambassador.requestAttributeValueUpdate(theObject, attributeHandles, new byte[0]);
            }
            logger.info("Discovered object {} as {}", objectName, className);
        } catch (ObjectInstanceNotKnown e) {
            logger.debug("Discovered object {} was removed before its attributes could be requested", objectName);
        } catch (AttributeNotDefined | InvalidObjectClassHandle | NameNotFound | FederateNotExecutionMember
                | SaveInProgress | RestoreInProgress | NotConnected | RTIinternalError | RuntimeException e) {
            logger.error("Error requesting values for discovered object {}", objectName, e);
        }
    }

    private AttributeHandleSet attributeHandles(
            ObjectClassHandle classHandle,
            Iterable<String> attributeNames)
            throws InvalidObjectClassHandle, NameNotFound, FederateNotExecutionMember, NotConnected, RTIinternalError {
        AttributeHandleSet attributeHandles = ambassador.getAttributeHandleSetFactory().create();
        for (String attributeName : attributeNames) {
            attributeHandles.add(ambassador.getAttributeHandle(classHandle, attributeName));
        }
        return attributeHandles;
    }

    @Override
    public void reflectAttributeValues(
            ObjectInstanceHandle theObject,
            AttributeHandleValueMap theAttributes,
            byte[] userSuppliedTag,
            OrderType sentOrdering,
            TransportationTypeHandle theTransport,
            SupplementalReflectInfo reflectInfo) throws FederateInternalError {
        reflectAttributeValues(theObject, theAttributes);
    }

    @Override
    public void reflectAttributeValues(
            ObjectInstanceHandle theObject,
            AttributeHandleValueMap theAttributes,
            byte[] userSuppliedTag,
            OrderType sentOrdering,
            TransportationTypeHandle theTransport,
            LogicalTime theTime,
            OrderType receivedOrdering,
            SupplementalReflectInfo reflectInfo) throws FederateInternalError {
        reflectAttributeValues(theObject, theAttributes);
    }

    @Override
    public void reflectAttributeValues(
            ObjectInstanceHandle theObject,
            AttributeHandleValueMap theAttributes,
            byte[] userSuppliedTag,
            OrderType sentOrdering,
            TransportationTypeHandle theTransport,
            LogicalTime theTime,
            OrderType receivedOrdering,
            MessageRetractionHandle retractionHandle,
            SupplementalReflectInfo reflectInfo) throws FederateInternalError {
        reflectAttributeValues(theObject, theAttributes);
    }

    private void reflectAttributeValues(ObjectInstanceHandle theObject, AttributeHandleValueMap theAttributes) {
        if (!objectCache.hasSubscriptions()) {
            return;
        }
        try {
            ObjectClassHandle classHandle = ambassador.getKnownObjectClassHandle(theObject);
            String className = rootRelativeObjectClassName(
                    ambassador.getObjectClassName(classHandle));
            Map<String, byte[]> attributes = new HashMap<>();
            for (AttributeHandle attributeHandle : theAttributes.keySet()) {
                String attributeName = ambassador.getAttributeName(classHandle, attributeHandle);
                attributes.put(attributeName, theAttributes.get(attributeHandle));
            }
            if (attributes.isEmpty()) {
                logger.debug("Ignoring empty reflection for object {}", theObject);
                return;
            }
            ObjectInjectionContext context =
                    new ObjectInjectionContext(className, theObject.toString(), attributes);
            boolean createPending = className.equals(pendingObjectCreates.get(theObject.toString()));
            List<TriggerProcessor.StagedStatement> statements = new ArrayList<>();
            if (createPending) {
                statements.addAll(
                        triggerProcessor.stage(StatementTrigger.Type.OBJECT_CREATE, className, context));
            }
            statements.addAll(
                    triggerProcessor.stage(StatementTrigger.Type.OBJECT_UPDATE, className, context));
            objectCache.reflectAttributeValues(theObject.toString(), className, attributes);
            if (createPending) {
                pendingObjectCreates.remove(theObject.toString(), className);
            }
            triggerProcessor.enqueue(statements, xapiClient::sendStatement);
        } catch (AttributeNotDefined | InvalidAttributeHandle | InvalidObjectClassHandle | ObjectInstanceNotKnown
                | FederateNotExecutionMember | NotConnected | RTIinternalError | RuntimeException e) {
            logger.error("Error processing reflected object attributes", e);
        }
    }

    @Override
    public void removeObjectInstance(
            ObjectInstanceHandle theObject,
            byte[] userSuppliedTag,
            OrderType sentOrdering,
            SupplementalRemoveInfo removeInfo) throws FederateInternalError {
        removeCachedObject(theObject);
    }

    @Override
    public void removeObjectInstance(
            ObjectInstanceHandle theObject,
            byte[] userSuppliedTag,
            OrderType sentOrdering,
            LogicalTime theTime,
            OrderType receivedOrdering,
            SupplementalRemoveInfo removeInfo) throws FederateInternalError {
        removeCachedObject(theObject);
    }

    @Override
    public void removeObjectInstance(
            ObjectInstanceHandle theObject,
            byte[] userSuppliedTag,
            OrderType sentOrdering,
            LogicalTime theTime,
            OrderType receivedOrdering,
            MessageRetractionHandle retractionHandle,
            SupplementalRemoveInfo removeInfo) throws FederateInternalError {
        removeCachedObject(theObject);
    }

    private void removeCachedObject(ObjectInstanceHandle theObject) {
        String objectHandle = theObject.toString();
        pendingObjectCreates.remove(objectHandle);
        if (!objectCache.isEnabled()) {
            return;
        }
        try {
            ObjectSnapshot snapshot = objectCache.findCurrentObjectSnapshot(objectHandle).orElse(null);
            List<TriggerProcessor.StagedStatement> statements = List.of();
            if (snapshot != null) {
                ObjectInjectionContext context = new ObjectInjectionContext(
                        snapshot.className(),
                        snapshot.objectHandle(),
                        snapshot.attributes());
                statements = triggerProcessor.stage(
                        StatementTrigger.Type.OBJECT_DELETE,
                        snapshot.className(),
                        context);
            } else {
                logger.debug("Skipping ObjectDelete triggers for unknown or removed object {}", theObject);
                return;
            }
            objectCache.removeObject(objectHandle);
            triggerProcessor.enqueue(statements, xapiClient::sendStatement);
        } catch (RuntimeException e) {
            logger.error("Error removing cached object {}", theObject, e);
        }
    }

    private String rootRelativeObjectClassName(String className) {
        return className != null && className.startsWith(OBJECT_ROOT_PREFIX)
                ? className.substring(OBJECT_ROOT_PREFIX.length())
                : className;
    }

    /*
     * Interactions
     */

    private void subscribeInteractions()
            throws FederateNotExecutionMember, RestoreInProgress, SaveInProgress, NotConnected,
            RTIinternalError, FederateServiceInvocationsAreBeingReportedViaMOM {
        if (xapiConfig.statementTriggers == null) {
            return;
        }
        xapiConfig.statementTriggers.stream()
                .filter(trigger -> trigger.type == StatementTrigger.Type.INTERACTION)
                .forEach(trigger -> {
            try {
                InteractionClassHandle handle = ambassador.getInteractionClassHandle(trigger.clazz);
                ambassador.subscribeInteractionClass(handle);
            } catch (NameNotFound | FederateNotExecutionMember | NotConnected | RTIinternalError
                    | FederateServiceInvocationsAreBeingReportedViaMOM | InteractionClassNotDefined
                    | SaveInProgress | RestoreInProgress e) {
                logger.error("Could not register listener for {}!", trigger.clazz, e);
            }
        });
    }

    @Override
    public void receiveInteraction(InteractionClassHandle interactionClass, ParameterHandleValueMap theParameters,
            byte[] userSuppliedTag, OrderType sentOrdering, TransportationTypeHandle theTransport,
            SupplementalReceiveInfo receiveInfo) throws FederateInternalError {
        receiveInteraction(interactionClass, theParameters);
    }

    @Override
    public void receiveInteraction(InteractionClassHandle interactionClass, ParameterHandleValueMap theParameters,
            byte[] userSuppliedTag, OrderType sentOrdering, TransportationTypeHandle theTransport, LogicalTime theTime,
            OrderType receivedOrdering, SupplementalReceiveInfo receiveInfo) throws FederateInternalError {
        receiveInteraction(interactionClass, theParameters);
    }

    @Override
    public void receiveInteraction(InteractionClassHandle interactionClass, ParameterHandleValueMap theParameters,
            byte[] userSuppliedTag, OrderType sentOrdering, TransportationTypeHandle theTransport, LogicalTime theTime,
            OrderType receivedOrdering, MessageRetractionHandle retractionHandle, SupplementalReceiveInfo receiveInfo)
            throws FederateInternalError {
        receiveInteraction(interactionClass, theParameters);
    }

    private void receiveInteraction(InteractionClassHandle interactionClass, ParameterHandleValueMap theParameters) {
        logger.info("Received Interaction");
        try {
            String interactionName = ambassador.getInteractionClassName(interactionClass);
            logger.trace("Interaction Handle: {}", interactionName);
            String interactionKey = rootRelativeInteractionClassName(interactionName);

            // Create Interaction-specific injection context to pass to trigger processor
            InteractionInjectionContext context = new InteractionInjectionContext(interactionKey,
                    getMapWithParameterNames(interactionClass, theParameters));

            triggerProcessor.dispatch(
                    StatementTrigger.Type.INTERACTION,
                    interactionKey,
                    context,
                    xapiClient::sendStatement);
        } catch (InvalidInteractionClassHandle | FederateNotExecutionMember | NotConnected | RTIinternalError e) {
            logger.error("Error ascertaining interaction details!", e);
        }
    }

    private String rootRelativeInteractionClassName(String className) {
        return className != null && className.startsWith(INTERACTION_ROOT_PREFIX)
                ? className.substring(INTERACTION_ROOT_PREFIX.length())
                : className;
    }

    private Map<String, byte[]> getMapWithParameterNames(InteractionClassHandle interactionClass,
            ParameterHandleValueMap theParameters) {
        Map<String, byte[]> parameters = new HashMap<String, byte[]>();
        theParameters.forEach((handle, value) -> {
            String paramName;
            try {
                paramName = ambassador.getParameterName(interactionClass, handle);
                parameters.put(paramName, value);
            } catch (InteractionParameterNotDefined | InvalidParameterHandle | InvalidInteractionClassHandle
                    | FederateNotExecutionMember | NotConnected | RTIinternalError e) {
                throw new RuntimeException("Exception processing parameter " + handle + " for interaction "
                        + interactionClass, e);
            }
        });
        return parameters;

    }
}
