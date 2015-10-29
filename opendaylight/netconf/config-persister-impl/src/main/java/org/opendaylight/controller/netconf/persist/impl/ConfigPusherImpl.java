/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.netconf.persist.impl;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Collections2;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.management.MBeanServerConnection;
import org.opendaylight.controller.config.api.ConflictingVersionException;
import org.opendaylight.controller.config.persist.api.ConfigPusher;
import org.opendaylight.controller.config.persist.api.ConfigSnapshotHolder;
import org.opendaylight.controller.config.persist.api.Persister;
import org.opendaylight.controller.netconf.api.Capability;
import org.opendaylight.controller.netconf.api.NetconfDocumentedException;
import org.opendaylight.controller.netconf.api.NetconfMessage;
import org.opendaylight.controller.netconf.api.xml.XmlNetconfConstants;
import org.opendaylight.controller.netconf.mapping.api.HandlingPriority;
import org.opendaylight.controller.netconf.mapping.api.NetconfOperation;
import org.opendaylight.controller.netconf.mapping.api.NetconfOperationChainedExecution;
import org.opendaylight.controller.netconf.mapping.api.NetconfOperationService;
import org.opendaylight.controller.netconf.mapping.api.NetconfOperationServiceFactory;
import org.opendaylight.controller.netconf.util.NetconfUtil;
import org.opendaylight.controller.netconf.util.xml.XmlElement;
import org.opendaylight.controller.netconf.util.xml.XmlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

@Immutable
public class ConfigPusherImpl implements ConfigPusher {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigPusherImpl.class);

    private final long maxWaitForCapabilitiesMillis;
    private final long conflictingVersionTimeoutMillis;
    private final NetconfOperationServiceFactory configNetconfConnector;
    private static final int QUEUE_SIZE = 100;
    private final BlockingQueue<List<? extends ConfigSnapshotHolder>> queue = new LinkedBlockingQueue<>(QUEUE_SIZE);

    public ConfigPusherImpl(NetconfOperationServiceFactory configNetconfConnector, long maxWaitForCapabilitiesMillis,
                        long conflictingVersionTimeoutMillis) {
        this.configNetconfConnector = configNetconfConnector;
        this.maxWaitForCapabilitiesMillis = maxWaitForCapabilitiesMillis;
        this.conflictingVersionTimeoutMillis = conflictingVersionTimeoutMillis;
    }

    public void process(List<AutoCloseable> autoCloseables, MBeanServerConnection platformMBeanServer, Persister persisterAggregator) throws InterruptedException {
        List<? extends ConfigSnapshotHolder> configs;
        while(true) {
            configs = queue.take();

            try {
                internalPushConfigs(configs);
                ConfigPersisterNotificationHandler jmxNotificationHandler = new ConfigPersisterNotificationHandler(platformMBeanServer, persisterAggregator);
                synchronized (autoCloseables) {
                    autoCloseables.add(jmxNotificationHandler);
                }

                LOG.debug("ConfigPusher has pushed configs {}", configs);
            } catch (Exception e) {
                LOG.debug("Failed to push some of configs: {}", configs, e);
                break;
            }
        }
    }

    @Override
    public void pushConfigs(List<? extends ConfigSnapshotHolder> configs) throws InterruptedException {
        LOG.debug("Requested to push configs {}", configs);
        this.queue.put(configs);
    }

    private LinkedHashMap<? extends ConfigSnapshotHolder, EditAndCommitResponse> internalPushConfigs(List<? extends ConfigSnapshotHolder> configs) throws NetconfDocumentedException {
        LOG.debug("Last config snapshots to be pushed to netconf: {}", configs);
        LinkedHashMap<ConfigSnapshotHolder, EditAndCommitResponse> result = new LinkedHashMap<>();
        // start pushing snapshots:
        for (ConfigSnapshotHolder configSnapshotHolder : configs) {
            if(configSnapshotHolder != null) {
                LOG.info("Pushing configuration snapshot {}", configSnapshotHolder);
                EditAndCommitResponse editAndCommitResponseWithRetries = null;
                try {
                    editAndCommitResponseWithRetries = pushConfigWithConflictingVersionRetries(configSnapshotHolder);
                } catch (ConfigSnapshotFailureException e) {
                    LOG.error("Failed to apply configuration snapshot: {}. Config snapshot is not semantically correct and will be IGNORED. " +
                            "for detailed information see enclosed exception.", e.getConfigIdForReporting(), e);
                    onFailedConfigPush("Failed to apply configuration snapshot " + e.getConfigIdForReporting(), e);
                } catch (Exception e) {
                    LOG.error("Failed to apply configuration snapshot: {}", configSnapshotHolder, e);
                    onFailedConfigPush("Failed to apply configuration snapshot " + configSnapshotHolder, e);
                }

                LOG.info("Successfully pushed configuration snapshot {}", configSnapshotHolder);
                result.put(configSnapshotHolder, editAndCommitResponseWithRetries);
            }
        }
        LOG.debug("All configuration snapshots have been pushed successfully.");
        return result;
    }

    @VisibleForTesting
    protected void onFailedConfigPush(String message, Exception cause) {
        throw new IllegalStateException(message, cause);
    }

    /**
     * First calls {@link #getOperationServiceWithRetries(java.util.Set, String)} in order to wait until
     * expected capabilities are present, then tries to push configuration. If {@link ConflictingVersionException}
     * is caught, whole process is retried - new service instance need to be obtained from the factory. Closes
     * {@link NetconfOperationService} after each use.
     */
    private synchronized EditAndCommitResponse pushConfigWithConflictingVersionRetries(ConfigSnapshotHolder configSnapshotHolder) throws ConfigSnapshotFailureException {
        ConflictingVersionException lastException;
        Stopwatch stopwatch = Stopwatch.createUnstarted();
        do {
            String idForReporting = configSnapshotHolder.toString();
            SortedSet<String> expectedCapabilities = checkNotNull(configSnapshotHolder.getCapabilities(),
                    "Expected capabilities must not be null - %s, check %s", idForReporting,
                    configSnapshotHolder.getClass().getName());
            try (NetconfOperationService operationService = getOperationServiceWithRetries(expectedCapabilities, idForReporting)) {
                if(!stopwatch.isRunning()) {
                    stopwatch.start();
                }
                return pushConfig(configSnapshotHolder, operationService);
            } catch (ConflictingVersionException e) {
                lastException = e;
                LOG.info("Conflicting version detected, will retry after timeout");
                sleep();
            }
        } while (stopwatch.elapsed(TimeUnit.MILLISECONDS) < conflictingVersionTimeoutMillis);
        throw new IllegalStateException("Max wait for conflicting version stabilization timeout after " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms",
                lastException);
    }

    private NetconfOperationService getOperationServiceWithRetries(Set<String> expectedCapabilities, String idForReporting) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        ConfigPusherException lastException;
        do {
            try {
                return getOperationService(expectedCapabilities, idForReporting);
            } catch (ConfigPusherException e) {
                LOG.debug("Not enough capabilities: {}", e.toString());
                lastException = e;
                sleep();
            }
        } while (stopwatch.elapsed(TimeUnit.MILLISECONDS) < maxWaitForCapabilitiesMillis);

        if(lastException instanceof NotEnoughCapabilitiesException) {
            LOG.error("Unable to push configuration due to missing yang models." +
                            " Yang models that are missing, but required by the configuration: {}." +
                            " For each mentioned model check: " +
                            " 1. that the mentioned yang model namespace/name/revision is identical to those in the yang model itself" +
                            " 2. the yang file is present in the system" +
                            " 3. the bundle with that yang file is present in the system and active" +
                            " 4. the yang parser did not fail while attempting to parse that model",
                    ((NotEnoughCapabilitiesException) lastException).getMissingCaps());
            throw new IllegalStateException("Unable to push configuration due to missing yang models." +
                    " Required yang models that are missing: "
                    + ((NotEnoughCapabilitiesException) lastException).getMissingCaps(), lastException);
        } else {
            final String msg = "Unable to push configuration due to missing netconf service";
            LOG.error(msg, lastException);
            throw new IllegalStateException(msg, lastException);
        }
    }

    private static class ConfigPusherException extends Exception {

        public ConfigPusherException(final String message) {
            super(message);
        }

        public ConfigPusherException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    private static class NotEnoughCapabilitiesException extends ConfigPusherException {
        private static final long serialVersionUID = 1L;
        private final Set<String> missingCaps;

        private NotEnoughCapabilitiesException(String message, Set<String> missingCaps) {
            super(message);
            this.missingCaps = missingCaps;
        }

        public Set<String> getMissingCaps() {
            return missingCaps;
        }
    }

    private static final class NetconfServiceNotAvailableException extends ConfigPusherException {

        public NetconfServiceNotAvailableException(final String s, final RuntimeException e) {
            super(s, e);
        }
    }

    private static final class ConfigSnapshotFailureException extends ConfigPusherException {

        private final String configIdForReporting;

        public ConfigSnapshotFailureException(final String configIdForReporting, final String operationNameForReporting, final Exception e) {
            super(String.format("Failed to apply config snapshot: %s during phase: %s", configIdForReporting, operationNameForReporting), e);
            this.configIdForReporting = configIdForReporting;
        }

        public String getConfigIdForReporting() {
            return configIdForReporting;
        }
    }

    /**
     * Get NetconfOperationService iif all required capabilities are present.
     *
     * @param expectedCapabilities that must be provided by configNetconfConnector
     * @param idForReporting
     * @return service if capabilities are present, otherwise absent value
     */
    private NetconfOperationService getOperationService(Set<String> expectedCapabilities, String idForReporting) throws ConfigPusherException {
        NetconfOperationService serviceCandidate;
        try {
            serviceCandidate = configNetconfConnector.createService(idForReporting);
        } catch(RuntimeException e) {
            throw new NetconfServiceNotAvailableException("Netconf service not stable for config pusher." +
                    " Cannot push any configuration", e);
        }
        Set<String> notFoundDiff = computeNotFoundCapabilities(expectedCapabilities, configNetconfConnector);
        if (notFoundDiff.isEmpty()) {
            return serviceCandidate;
        } else {
            serviceCandidate.close();
            LOG.debug("Netconf server did not provide required capabilities for {} ", idForReporting,
                    "Expected but not found: {}, all expected {}, current {}",
                     notFoundDiff, expectedCapabilities, configNetconfConnector.getCapabilities()
            );
            throw new NotEnoughCapabilitiesException("Not enough capabilities for " + idForReporting + ". Expected but not found: " + notFoundDiff, notFoundDiff);
        }
    }

    private static Set<String> computeNotFoundCapabilities(Set<String> expectedCapabilities, NetconfOperationServiceFactory serviceCandidate) {
        Collection<String> actual = Collections2.transform(serviceCandidate.getCapabilities(), new Function<Capability, String>() {
            @Override
            public String apply(@Nonnull final Capability input) {
                return input.getCapabilityUri();
            }
        });
        Set<String> allNotFound = new HashSet<>(expectedCapabilities);
        allNotFound.removeAll(actual);
        return allNotFound;
    }

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Sends two RPCs to the netconf server: edit-config and commit.
     *
     * @param configSnapshotHolder
     * @throws ConflictingVersionException if commit fails on optimistic lock failure inside of config-manager
     * @throws java.lang.RuntimeException  if edit-config or commit fails otherwise
     */
    private synchronized EditAndCommitResponse pushConfig(ConfigSnapshotHolder configSnapshotHolder, NetconfOperationService operationService)
            throws ConflictingVersionException, ConfigSnapshotFailureException {

        Element xmlToBePersisted;
        try {
            xmlToBePersisted = XmlUtil.readXmlToElement(configSnapshotHolder.getConfigSnapshot());
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Cannot parse " + configSnapshotHolder);
        }
        LOG.trace("Pushing last configuration to netconf: {}", configSnapshotHolder);
        Stopwatch stopwatch = Stopwatch.createStarted();
        NetconfMessage editConfigMessage = createEditConfigMessage(xmlToBePersisted);

        Document editResponseMessage = sendRequestGetResponseCheckIsOK(editConfigMessage, operationService,
                "edit-config", configSnapshotHolder.toString());

        Document commitResponseMessage = sendRequestGetResponseCheckIsOK(getCommitMessage(), operationService,
                "commit", configSnapshotHolder.toString());

        if (LOG.isTraceEnabled()) {
            StringBuilder response = new StringBuilder("editConfig response = {");
            response.append(XmlUtil.toString(editResponseMessage));
            response.append("}");
            response.append("commit response = {");
            response.append(XmlUtil.toString(commitResponseMessage));
            response.append("}");
            LOG.trace("Last configuration loaded successfully");
            LOG.trace("Detailed message {}", response);
            LOG.trace("Total time spent {} ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
        return new EditAndCommitResponse(editResponseMessage, commitResponseMessage);
    }

    private NetconfOperation findOperation(NetconfMessage request, NetconfOperationService operationService) {
        TreeMap<HandlingPriority, NetconfOperation> allOperations = new TreeMap<>();
        Set<NetconfOperation> netconfOperations = operationService.getNetconfOperations();
        if (netconfOperations.isEmpty()) {
            throw new IllegalStateException("Possible code error: no config operations");
        }
        for (NetconfOperation netconfOperation : netconfOperations) {
            HandlingPriority handlingPriority = null;
            try {
                handlingPriority = netconfOperation.canHandle(request.getDocument());
            } catch (NetconfDocumentedException e) {
                throw new IllegalStateException("Possible code error: canHandle threw exception", e);
            }
            allOperations.put(handlingPriority, netconfOperation);
        }
        Entry<HandlingPriority, NetconfOperation> highestEntry = allOperations.lastEntry();
        if (highestEntry.getKey().isCannotHandle()) {
            throw new IllegalStateException("Possible code error: operation with highest priority is CANNOT_HANDLE");
        }
        return highestEntry.getValue();
    }

    private Document sendRequestGetResponseCheckIsOK(NetconfMessage request, NetconfOperationService operationService,
                                                     String operationNameForReporting, String configIdForReporting)
            throws ConflictingVersionException, ConfigSnapshotFailureException {

        NetconfOperation operation = findOperation(request, operationService);
        Document response;
        try {
            response = operation.handle(request.getDocument(), NetconfOperationChainedExecution.EXECUTION_TERMINATION_POINT);
            return NetconfUtil.checkIsMessageOk(response);
        } catch (NetconfDocumentedException e) {
            if (e.getCause() instanceof ConflictingVersionException) {
                throw (ConflictingVersionException) e.getCause();
            }
            throw new ConfigSnapshotFailureException(configIdForReporting, operationNameForReporting, e);
        }
    }

    // load editConfig.xml template, populate /rpc/edit-config/config with parameter
    private static NetconfMessage createEditConfigMessage(Element dataElement) {
        String editConfigResourcePath = "/netconfOp/editConfig.xml";
        try (InputStream stream = ConfigPersisterNotificationHandler.class.getResourceAsStream(editConfigResourcePath)) {
            checkNotNull(stream, "Unable to load resource " + editConfigResourcePath);

            Document doc = XmlUtil.readXmlToDocument(stream);

            XmlElement editConfigElement = XmlElement.fromDomDocument(doc).getOnlyChildElement();
            XmlElement configWrapper = editConfigElement.getOnlyChildElement(XmlNetconfConstants.CONFIG_KEY);
            editConfigElement.getDomElement().removeChild(configWrapper.getDomElement());
            for (XmlElement el : XmlElement.fromDomElement(dataElement).getChildElements()) {
                boolean deep = true;
                configWrapper.appendChild((Element) doc.importNode(el.getDomElement(), deep));
            }
            editConfigElement.appendChild(configWrapper.getDomElement());
            return new NetconfMessage(doc);
        } catch (IOException | SAXException | NetconfDocumentedException e) {
            // error reading the xml file bundled into the jar
            throw new IllegalStateException("Error while opening local resource " + editConfigResourcePath, e);
        }
    }

    private static NetconfMessage getCommitMessage() {
        String resource = "/netconfOp/commit.xml";
        try (InputStream stream = ConfigPusherImpl.class.getResourceAsStream(resource)) {
            checkNotNull(stream, "Unable to load resource " + resource);
            return new NetconfMessage(XmlUtil.readXmlToDocument(stream));
        } catch (SAXException | IOException e) {
            // error reading the xml file bundled into the jar
            throw new IllegalStateException("Error while opening local resource " + resource, e);
        }
    }

    static class EditAndCommitResponse {
        private final Document editResponse, commitResponse;

        EditAndCommitResponse(Document editResponse, Document commitResponse) {
            this.editResponse = editResponse;
            this.commitResponse = commitResponse;
        }

        public Document getEditResponse() {
            return editResponse;
        }

        public Document getCommitResponse() {
            return commitResponse;
        }

        @Override
        public String toString() {
            return "EditAndCommitResponse{" +
                    "editResponse=" + editResponse +
                    ", commitResponse=" + commitResponse +
                    '}';
        }
    }
}
