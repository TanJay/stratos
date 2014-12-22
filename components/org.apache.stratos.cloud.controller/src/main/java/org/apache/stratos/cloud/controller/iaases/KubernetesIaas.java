/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.stratos.cloud.controller.iaases;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.cloud.controller.concurrent.ScheduledThreadExecutor;
import org.apache.stratos.cloud.controller.context.CloudControllerContext;
import org.apache.stratos.cloud.controller.domain.*;
import org.apache.stratos.cloud.controller.domain.Cartridge;
import org.apache.stratos.cloud.controller.domain.kubernetes.KubernetesClusterContext;
import org.apache.stratos.cloud.controller.exception.*;
import org.apache.stratos.cloud.controller.functions.ContainerClusterContextToReplicationController;
import org.apache.stratos.cloud.controller.iaases.validators.KubernetesPartitionValidator;
import org.apache.stratos.cloud.controller.iaases.validators.PartitionValidator;
import org.apache.stratos.cloud.controller.services.impl.CloudControllerServiceUtil;
import org.apache.stratos.cloud.controller.util.CloudControllerUtil;
import org.apache.stratos.cloud.controller.util.PodActivationWatcher;
import org.apache.stratos.common.beans.NameValuePair;
import org.apache.stratos.common.constants.StratosConstants;
import org.apache.stratos.cloud.controller.domain.kubernetes.KubernetesCluster;
import org.apache.stratos.cloud.controller.domain.kubernetes.PortRange;
import org.apache.stratos.kubernetes.client.KubernetesApiClient;
import org.apache.stratos.kubernetes.client.exceptions.KubernetesClientException;
import org.apache.stratos.kubernetes.client.model.*;
import org.apache.stratos.kubernetes.client.model.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;

/**
 * Kubernetes iaas implementation.
 */
public class KubernetesIaas extends Iaas {

    private static final Log log = LogFactory.getLog(KubernetesIaas.class);
    private static final long POD_CREATION_TIMEOUT = 120000; // 1 min
    public static final String PAYLOAD_PARAMETER_SEPARATOR = ",";
    public static final String PAYLOAD_PARAMETER_NAME_VALUE_SEPARATOR = "=";

    private PartitionValidator partitionValidator;
    private List<NameValuePair> payload;

    public KubernetesIaas(IaasProvider iaasProvider) {
        super(iaasProvider);
        partitionValidator = new KubernetesPartitionValidator();
        payload = new ArrayList<NameValuePair>();
    }

    @Override
    public void initialize() {
    }

    @Override
    public void setDynamicPayload(byte[] payloadByteArray) {
        // Clear existing payload parameters
        payload.clear();

        if(payloadByteArray != null) {
            String payloadString = new String(payloadByteArray);
            String[] parameterArray = payloadString.split(PAYLOAD_PARAMETER_SEPARATOR);
            if(parameterArray != null) {
                for(String parameter : parameterArray) {
                    if(parameter != null) {
                        String[] nameValueArray = parameter.split(PAYLOAD_PARAMETER_NAME_VALUE_SEPARATOR);
                        if ((nameValueArray != null) && (nameValueArray.length == 2)) {
                            NameValuePair nameValuePair = new NameValuePair(nameValueArray[0], nameValueArray[1]);
                            payload.add(nameValuePair);
                        }
                    }
                }
                if(log.isDebugEnabled()) {
                    log.debug("Dynamic payload is set: " + payload.toString());
                }
            }
        }
    }

    @Override
    public MemberContext startInstance(MemberContext memberContext) throws CartridgeNotFoundException {
        return startContainer(memberContext);
    }

    @Override
    public PartitionValidator getPartitionValidator() {
        return partitionValidator;
    }

    @Override
    public void terminateInstance(MemberContext memberContext) throws InvalidCartridgeTypeException,
            InvalidMemberException, MemberTerminationFailedException {
        terminateContainer(memberContext.getMemberId());
    }

    public MemberContext startContainer(MemberContext memberContext)
            throws CartridgeNotFoundException {
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireMemberContextWriteLock();

            handleNullObject(memberContext, "Could not start container, member context is null");
            if (log.isInfoEnabled()) {
                log.info(String.format("Starting container: [cartridge-type] %s", memberContext.getCartridgeType()));
            }

            // Validate cluster id
            String clusterId = memberContext.getClusterId();
            String memberId = memberContext.getMemberId();
            handleNullObject(clusterId, "Could not start containers, cluster id is null in member context");

            // Validate cluster context
            ClusterContext clusterContext = CloudControllerContext.getInstance().getClusterContext(clusterId);
            handleNullObject(clusterContext, "Could not start containers, cluster context not found: [cluster-id] "
                    + clusterId + " [member-id] " + memberId);

            // Validate partition
            Partition partition = memberContext.getPartition();
            handleNullObject(partition, "Could not start containers, partition not found in member context: " +
                    "[cluster-id] " + clusterId + " [member-id] " + memberId);

            // Validate cartridge
            String cartridgeType = clusterContext.getCartridgeType();
            Cartridge cartridge = CloudControllerContext.getInstance().getCartridge(cartridgeType);
            if (cartridge == null) {
                String msg = "Could not start containers, cartridge not found: [cartridge-type] " + cartridgeType + " " +
                        "[cluster-id] " + clusterId + " [member-id] " + memberId;
                log.error(msg);
                throw new CartridgeNotFoundException(msg);
            }

            try {
                String kubernetesClusterId = partition.getKubernetesClusterId();
                KubernetesCluster kubernetesCluster = CloudControllerContext.getInstance().
                        getKubernetesCluster(kubernetesClusterId);
                handleNullObject(kubernetesCluster, "Could not start container, kubernetes cluster not found: " +
                        "[kubernetes-cluster-id] " + kubernetesClusterId + " [cluster-id] " + clusterId +
                        " [member-id] " + memberId);

                // Prepare kubernetes context
                String kubernetesMasterIp = kubernetesCluster.getKubernetesMaster().getHostIpAddress();
                PortRange kubernetesPortRange = kubernetesCluster.getPortRange();
                String kubernetesMasterPort = CloudControllerUtil.getProperty(
                        kubernetesCluster.getKubernetesMaster().getProperties(), StratosConstants.KUBERNETES_MASTER_PORT,
                        StratosConstants.KUBERNETES_MASTER_DEFAULT_PORT);

                KubernetesClusterContext kubClusterContext = getKubernetesClusterContext(kubernetesClusterId,
                        kubernetesMasterIp, kubernetesMasterPort, kubernetesPortRange.getUpper(),
                        kubernetesPortRange.getLower());

                // Get kubernetes API
                KubernetesApiClient kubernetesApi = kubClusterContext.getKubApi();

                // Create proxy services for port mappings
                List<Service> services = createProxyServices(clusterContext, kubClusterContext, kubernetesApi);
                clusterContext.setKubernetesServices(services);
                CloudControllerContext.getInstance().updateClusterContext(clusterContext);

                // Create replication controller
                createReplicationController(memberContext, clusterId, kubernetesApi);

                // Wait for pod to be created
                List<Pod> pods = waitForPodToBeCreated(memberContext, kubernetesApi);
                if (pods.size() != 1) {
                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Pod did not create within %d sec, hence deleting the service: " +
                                "[cluster-id] %s [member-id] %s", ((int)POD_CREATION_TIMEOUT/1000), clusterId, memberId));
                    }
                    try {
                        terminateContainers(clusterId);
                    } catch (Exception e) {
                        String message = "Could not terminate containers which were partially created";
                        log.error(message, e);
                        throw new RuntimeException(message, e);
                    }
                }
                Pod pod = pods.get(0);
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Pod created: [cluster-id] %s [member-id] %s [pod-id] %s",
                            clusterId, memberId, pod.getId()));
                }

                // Create member context
                MemberContext newMemberContext = createNewMemberContext(memberContext, pod);
                CloudControllerContext.getInstance().addMemberContext(newMemberContext);

                // wait till pod status turns to running and send member spawned.
                ScheduledThreadExecutor exec = ScheduledThreadExecutor.getInstance();
                if (log.isDebugEnabled()) {
                    log.debug("Cloud Controller is starting the instance start up thread.");
                }
                CloudControllerContext.getInstance().addScheduledFutureJob(newMemberContext.getMemberId(),
                        exec.schedule(new PodActivationWatcher(pod.getId(), newMemberContext, kubernetesApi), 5000));

                // persist in registry
                CloudControllerContext.getInstance().persist();
                log.info("Container started successfully: [cluster-id] " + clusterId + " [member-id] " +
                        memberContext.getMemberId());

                return newMemberContext;
            } catch (Exception e) {
                String msg = String.format("Could not start container: [cartridge-type] %s [member-id] %s",
                        memberContext.getCartridgeType(), memberContext.getMemberId());
                log.error(msg, e);
                throw new IllegalStateException(msg, e);
            }
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    private MemberContext createNewMemberContext(MemberContext memberContext, Pod pod) {
        MemberContext newMemberContext = new MemberContext();
        newMemberContext.setCartridgeType(memberContext.getCartridgeType());
        newMemberContext.setClusterId(memberContext.getClusterId());
        newMemberContext.setClusterInstanceId(memberContext.getClusterInstanceId());
        newMemberContext.setMemberId(memberContext.getMemberId());
        newMemberContext.setNetworkPartitionId(memberContext.getNetworkPartitionId());
        newMemberContext.setPartition(memberContext.getPartition());
        newMemberContext.setInstanceId(pod.getId());
        newMemberContext.setDefaultPrivateIP(pod.getCurrentState().getHostIP());
        newMemberContext.setPrivateIPs(new String[]{pod.getCurrentState().getHostIP()});
        newMemberContext.setDefaultPublicIP(pod.getCurrentState().getHostIP());
        newMemberContext.setPublicIPs(new String[]{pod.getCurrentState().getHostIP()});
        newMemberContext.setInitTime(memberContext.getInitTime());
        newMemberContext.setProperties(memberContext.getProperties());
        return newMemberContext;
    }

    private List<Pod> waitForPodToBeCreated(MemberContext memberContext, KubernetesApiClient kubernetesApi) throws KubernetesClientException, InterruptedException {
        Labels labels = new Labels();
        labels.setName(memberContext.getClusterId());
        List<Pod> podList = new ArrayList<Pod>();
        long startTime = System.currentTimeMillis();
        while (podList.size() == 0) {
            Pod[] pods = kubernetesApi.queryPods(new Labels[]{labels});
            if((pods != null) && (pods.length > 0)){
                for(Pod pod : pods) {
                    if(pod != null) {
                        podList.add(pod);
                    }
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("Member pod: [member-id] " + memberContext.getMemberId() + " [count] " + podList.size());
            }
            if ((System.currentTimeMillis() - startTime) > POD_CREATION_TIMEOUT) {
                break;
            }
            Thread.sleep(5000);
        }
        return podList;
    }

    /**
     * Create new replication controller for the cluster and generate environment variables using member context.
     * @param memberContext
     * @param clusterId
     * @param kubernetesApi
     * @throws KubernetesClientException
     */
    private ReplicationController createReplicationController(MemberContext memberContext, String clusterId,
                                                              KubernetesApiClient kubernetesApi)
            throws KubernetesClientException {
        if (log.isDebugEnabled()) {
            log.debug("Creating kubernetes replication controller: [cluster-id] " + clusterId);
        }

        // Add dynamic payload to the member context
        memberContext.setDynamicPayload(payload);
        ContainerClusterContextToReplicationController controllerFunction =
                new ContainerClusterContextToReplicationController();

        // Create replication controller
        ReplicationController replicationController = controllerFunction.apply(memberContext);
        kubernetesApi.createReplicationController(replicationController);
        if (log.isInfoEnabled()) {
            log.info("Kubernetes replication controller successfully created: [cluster-id] " + clusterId);
        }
        return replicationController;
    }

    /**
     * Create proxy services for the cluster and add them to the cluster context.
     * @param clusterContext
     * @param kubernetesClusterContext
     * @param kubernetesApi
     * @return
     * @throws KubernetesClientException
     */
    private List<Service> createProxyServices(ClusterContext clusterContext,
                                              KubernetesClusterContext kubernetesClusterContext,
                                              KubernetesApiClient kubernetesApi) throws KubernetesClientException {
        List<Service> services = new ArrayList<Service>();

        String clusterId = clusterContext.getClusterId();
        Cartridge cartridge = CloudControllerContext.getInstance().getCartridge(clusterContext.getCartridgeType());
        if(cartridge == null) {
            String message = "Could not create kubernetes services, cartridge not found: [cartridge-type] " +
                    clusterContext.getCartridgeType();
            log.error(message);
            throw new RuntimeException(message);
        }

        List<PortMapping> portMappings = cartridge.getPortMappings();
        for(PortMapping portMapping : portMappings) {
            String serviceId = prepareKubernetesServiceId(clusterId, portMapping);
            int nextServicePort = kubernetesClusterContext.getNextServicePort();
            if(nextServicePort == -1) {
                throw new RuntimeException(String.format("Could not generate service port: [cluster-id] %s ",
                        clusterContext.getClusterId()));
            }

            if (log.isInfoEnabled()) {
                log.info(String.format("Creating kubernetes service: [cluster-id] %s [service-id] %s " +
                                "[protocol] %s [service-port] %d [container-port] %s [proxy-port] %s", clusterId,
                        serviceId, portMapping.getProtocol(), nextServicePort, portMapping.getPort(),
                        portMapping.getProxyPort()));
            }

            Service service = new Service();
            service.setId(serviceId);
            service.setApiVersion("v1beta1");
            service.setKind("Service");
            service.setPort(nextServicePort);
            service.setContainerPort(portMapping.getPort());

            Selector selector = new Selector();
            selector.setName(clusterId);
            service.setSelector(selector);

            kubernetesApi.createService(service);
            services.add(service);

            if (log.isInfoEnabled()) {
                log.info(String.format("Kubernetes service successfully created: [cluster-id] %s [service-id] %s " +
                                "[protocol] %s [service-port] %d [container-port] %s [proxy-port] %s", clusterId,
                        service.getId(), portMapping.getProtocol(), service.getPort(), portMapping.getPort(),
                        portMapping.getProxyPort()));
            }
        }
        return services;
    }

    /**
     * Prepare kubernetes service id using clusterId, port protocol and port.
     * @param portMapping
     * @return
     */
    private String prepareKubernetesServiceId(String clusterId, PortMapping portMapping) {
        String serviceId = String.format("%s-%s-%s", clusterId, portMapping.getProtocol(), portMapping.getPort());
        if(serviceId.contains(".")) {
            serviceId = serviceId.replace(".", "-");
        }
        return serviceId;
    }

    /**
     * Terminate all the containers belong to a cluster by cluster id.
     * @param clusterId
     * @return
     * @throws InvalidClusterException
     */
    public MemberContext[] terminateContainers(String clusterId)
            throws InvalidClusterException {
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireMemberContextWriteLock();

            ClusterContext clusterContext = CloudControllerContext.getInstance().getClusterContext(clusterId);
            handleNullObject(clusterContext, "Could not terminate containers, cluster not found: [cluster-id] " + clusterId);

            String kubernetesClusterId = CloudControllerUtil.getProperty(clusterContext.getProperties(),
                    StratosConstants.KUBERNETES_CLUSTER_ID);
            handleNullObject(kubernetesClusterId, "Could not terminate containers, kubernetes cluster id not found: " +
                    "[cluster-id] " + clusterId);

            KubernetesClusterContext kubClusterContext = CloudControllerContext.getInstance().getKubernetesClusterContext(kubernetesClusterId);
            handleNullObject(kubClusterContext, "Could not terminate containers, kubernetes cluster not found: " +
                    "[kubernetes-cluster-id] " + kubernetesClusterId);

            KubernetesApiClient kubApi = kubClusterContext.getKubApi();

            // Remove the services
            List<Service> services = clusterContext.getKubernetesServices();
            if (services != null) {
                for (Service service : services) {
                    try {
                        kubApi.deleteService(service.getId());
                        int allocatedPort = service.getPort();
                        kubClusterContext.deallocatePort(allocatedPort);
                    } catch (KubernetesClientException e) {
                        log.error("Could not remove kubernetes service: [cluster-id] " + clusterId, e);
                    }
                }
            }

            List<MemberContext> memberContextsRemoved = new ArrayList<MemberContext>();
            List<MemberContext> memberContexts = CloudControllerContext.getInstance().getMemberContextsOfClusterId(clusterId);
            for(MemberContext memberContext : memberContexts) {
                try {
                    MemberContext memberContextRemoved = terminateContainer(memberContext.getMemberId());
                    memberContextsRemoved.add(memberContextRemoved);
                } catch (MemberTerminationFailedException e) {
                    String message = "Could not terminate container: [member-id] " + memberContext.getMemberId();
                    log.error(message);
                }
            }

            // persist
            CloudControllerContext.getInstance().persist();
            return memberContextsRemoved.toArray(new MemberContext[memberContextsRemoved.size()]);
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    /**
     * Terminate a container by member id
     * @param memberId
     * @return
     * @throws MemberTerminationFailedException
     */
    public MemberContext terminateContainer(String memberId) throws MemberTerminationFailedException {
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireMemberContextWriteLock();
            handleNullObject(memberId, "Could not terminate container, member id is null");

            MemberContext memberContext = CloudControllerContext.getInstance().getMemberContextOfMemberId(memberId);
            handleNullObject(memberContext, "Could not terminate container, member context not found: [member-id] " + memberId);

            String clusterId = memberContext.getClusterId();
            handleNullObject(clusterId, "Could not terminate container, cluster id is null: [member-id] " + memberId);

            ClusterContext clusterContext = CloudControllerContext.getInstance().getClusterContext(clusterId);
            handleNullObject(clusterContext, String.format("Could not terminate container, cluster context not found: " +
                    "[cluster-id] %s [member-id] %s", clusterId, memberId));

            String kubernetesClusterId = CloudControllerUtil.getProperty(clusterContext.getProperties(),
                    StratosConstants.KUBERNETES_CLUSTER_ID);
            handleNullObject(kubernetesClusterId, String.format("Could not terminate container, kubernetes cluster " +
                    "context id is null: [cluster-id] %s [member-id] %s", clusterId, memberId));

            KubernetesClusterContext kubernetesClusterContext = CloudControllerContext.getInstance().getKubernetesClusterContext(kubernetesClusterId);
            handleNullObject(kubernetesClusterContext, String.format("Could not terminate container, kubernetes cluster " +
                    "context not found: [cluster-id] %s [member-id] %s", clusterId, memberId));
            KubernetesApiClient kubApi = kubernetesClusterContext.getKubApi();

            // Remove the pod forcefully
            try {
                Labels l = new Labels();
                l.setName(memberId);
                // execute the label query
                Pod[] pods = kubApi.queryPods(new Labels[]{l});
                for (Pod pod : pods) {
                    try {
                        // delete pods forcefully
                        kubApi.deletePod(pod.getId());
                    } catch (KubernetesClientException ignore) {
                        // we can't do nothing here
                        log.warn(String.format("Could not delete pod: [pod-id] %s", pod.getId()));
                    }
                }
            } catch (KubernetesClientException e) {
                // we're not going to throw this error, but proceed with other deletions
                log.error("Could not delete pods of cluster: [cluster-id] " + clusterId, e);
            }

            // Remove the replication controller
            try {
                kubApi.deleteReplicationController(memberContext.getMemberId());
                MemberContext memberToBeRemoved = CloudControllerContext.getInstance().getMemberContextOfMemberId(memberId);
                CloudControllerServiceUtil.executeMemberTerminationPostProcess(memberToBeRemoved);
                return memberToBeRemoved;
            } catch (KubernetesClientException e) {
                String msg = String.format("Failed to terminate member: [cluster-id] %s [member-id] %s", clusterId, memberId);
                log.error(msg, e);
                throw new MemberTerminationFailedException(msg, e);
            }
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    /**
     * Get kubernetes cluster context
     * @param kubernetesClusterId
     * @param kubernetesMasterIp
     * @param kubernetesMasterPort
     * @param upperPort
     * @param lowerPort
     * @return
     */
    private KubernetesClusterContext getKubernetesClusterContext(String kubernetesClusterId, String kubernetesMasterIp,
                                                                 String kubernetesMasterPort, int upperPort, int lowerPort) {

        KubernetesClusterContext kubernetesClusterContext = CloudControllerContext.getInstance().
                getKubernetesClusterContext(kubernetesClusterId);
        if (kubernetesClusterContext != null) {
            return kubernetesClusterContext;
        }

        kubernetesClusterContext = new KubernetesClusterContext(kubernetesClusterId, kubernetesMasterIp,
                kubernetesMasterPort, lowerPort, upperPort);
        CloudControllerContext.getInstance().addKubernetesClusterContext(kubernetesClusterContext);
        return kubernetesClusterContext;
    }

    private String readProperty(String property, org.apache.stratos.common.Properties properties, String object) {
        String propVal = CloudControllerUtil.getProperty(properties, property);
        handleNullObject(propVal, "Property validation failed. Could not find property: '" + property + " in " + object);
        return propVal;

    }

    private void handleNullObject(Object obj, String errorMsg) {
        if (obj == null) {
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
    }

    @Override
    public void releaseAddress(String ip) {

    }

    @Override
    public boolean isValidRegion(String region) throws InvalidRegionException {
        // No regions in kubernetes cluster
        return true;
    }

    @Override
    public boolean isValidZone(String region, String zone) throws InvalidZoneException, InvalidRegionException {
        // No zones in kubernetes cluster
        return true;
    }

    @Override
    public boolean isValidHost(String zone, String host) throws InvalidHostException {
        // No zones in kubernetes cluster
        return true;
    }

    @Override
    public String createVolume(int sizeGB, String snapshotId) {
        throw new NotImplementedException();
    }

    @Override
    public String attachVolume(String instanceId, String volumeId, String deviceName) {
        throw new NotImplementedException();
    }

    @Override
    public void detachVolume(String instanceId, String volumeId) {
        throw new NotImplementedException();
    }

    @Override
    public void deleteVolume(String volumeId) {
        throw new NotImplementedException();
    }

    @Override
    public String getIaasDevice(String device) {
        throw new NotImplementedException();
    }

    @Override
    public void allocateIpAddress(String clusterId, MemberContext memberContext, Partition partition) {

    }
}