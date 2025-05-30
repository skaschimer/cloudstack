/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.datastore.provider;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CleanupPersistentNetworkResourceCommand;
import com.cloud.agent.api.DeleteStoragePoolCommand;
import com.cloud.agent.api.ModifyStoragePoolAnswer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.api.SetupPersistentNetworkCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.alert.AlertManager;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.StorageConflictException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.StorageService;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.utils.exception.CloudRuntimeException;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.HypervisorHostListener;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DefaultHostListener implements HypervisorHostListener {
    protected Logger logger = LogManager.getLogger(getClass());

    /**
     * Wait time for modify storage pool command to complete. We should wait for 5 minutes for the command to complete.
     * This should ideally be externalised as a global configuration parameter in the future (See #8506).
     **/
    private final int modifyStoragePoolCommandWait = 300; // 5 minutes
    @Inject
    AgentManager agentMgr;
    @Inject
    DataStoreManager dataStoreMgr;
    @Inject
    AlertManager alertMgr;
    @Inject
    StoragePoolHostDao storagePoolHostDao;
    @Inject
    PrimaryDataStoreDao primaryStoreDao;
    @Inject
    StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject
    StorageManager storageManager;
    @Inject
    StorageService storageService;
    @Inject
    DataCenterDao zoneDao;
    @Inject
    NetworkOfferingDao networkOfferingDao;
    @Inject
    HostDao hostDao;
    @Inject
    NetworkModel networkModel;
    @Inject
    ConfigurationManager configManager;
    @Inject
    NetworkDao networkDao;

    @Override
    public boolean hostAdded(long hostId) {
        return true;
    }

    private boolean createPersistentNetworkResourcesOnHost(long hostId) {
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            logger.warn("Host with id {} can't be found", hostId);
            return false;
        }
        setupPersistentNetwork(host);
        return true;
    }

    /**
     * Creates a dummy NicTO object which is used by the respective hypervisors to setup network elements / resources
     * - bridges(KVM), VLANs(Xen) and portgroups(VMWare) for L2 network
     */
    private NicTO createNicTOFromNetworkAndOffering(NetworkVO networkVO, NetworkOfferingVO networkOfferingVO, HostVO hostVO) {
        NicTO to = new NicTO();
        to.setName(networkModel.getNetworkTag(hostVO.getHypervisorType(), networkVO));
        to.setBroadcastType(networkVO.getBroadcastDomainType());
        to.setType(networkVO.getTrafficType());
        to.setBroadcastUri(networkVO.getBroadcastUri());
        to.setIsolationuri(networkVO.getBroadcastUri());
        to.setNetworkRateMbps(configManager.getNetworkOfferingNetworkRate(networkOfferingVO.getId(), networkVO.getDataCenterId()));
        to.setSecurityGroupEnabled(networkModel.isSecurityGroupSupportedInNetwork(networkVO));
        return to;
    }


    @Override
    public boolean hostConnect(long hostId, long poolId) throws StorageConflictException {
        StoragePool pool = (StoragePool) this.dataStoreMgr.getDataStore(poolId, DataStoreRole.Primary);
        Map<String, String> detailsMap = storagePoolDetailsDao.listDetailsKeyPairs(poolId);
        Map<String, String> nfsMountOpts = storageManager.getStoragePoolNFSMountOpts(pool, null).first();

        Optional.ofNullable(nfsMountOpts).ifPresent(detailsMap::putAll);
        ModifyStoragePoolCommand cmd = new ModifyStoragePoolCommand(true, pool, detailsMap);
        cmd.setWait(modifyStoragePoolCommandWait);
        HostVO host = hostDao.findById(hostId);
        logger.debug("Sending modify storage pool command to agent: {} for storage pool: {} with timeout {} seconds", host, pool, cmd.getWait());
        final Answer answer = agentMgr.easySend(hostId, cmd);

        if (answer == null) {
            throw new CloudRuntimeException(String.format("Unable to get an answer to the modify storage pool command %s", pool));
        }

        if (!answer.getResult()) {
            String msg = String.format("Unable to attach storage pool %s to the host %d", pool, hostId);
            alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, pool.getDataCenterId(), pool.getPodId(), msg, msg);
            throw new CloudRuntimeException(String.format("Unable to establish connection from storage head to storage pool %s due to %s %s",
                    pool, answer.getDetails(), pool.getUuid()));
        }

        assert (answer instanceof ModifyStoragePoolAnswer) : String.format(
                "Well, now why won't you actually return the ModifyStoragePoolAnswer when it's ModifyStoragePoolCommand? Pool=%s Host=%d", pool, hostId);
        ModifyStoragePoolAnswer mspAnswer = (ModifyStoragePoolAnswer) answer;
        if (mspAnswer.getLocalDatastoreName() != null && pool.isShared()) {
            String datastoreName = mspAnswer.getLocalDatastoreName();
            List<StoragePoolVO> localStoragePools = this.primaryStoreDao.listLocalStoragePoolByPath(pool.getDataCenterId(), datastoreName);
            for (StoragePoolVO localStoragePool : localStoragePools) {
                if (datastoreName.equals(localStoragePool.getPath())) {
                    logger.warn("Storage pool: {} has already been added as local storage: {}", pool, localStoragePool);
                    throw new StorageConflictException(String.format(
                            "Cannot add shared storage pool: %s because it has already been added as local storage: %s", pool, localStoragePool));
                }
            }
        }
        StoragePoolVO poolVO = this.primaryStoreDao.findById(poolId);
        updateStoragePoolHostVOAndDetails(poolVO, hostId, mspAnswer);

        if (pool.getPoolType() == Storage.StoragePoolType.DatastoreCluster) {
            storageManager.validateChildDatastoresToBeAddedInUpState(poolVO, mspAnswer.getDatastoreClusterChildren());
            storageManager.syncDatastoreClusterStoragePool(poolId, ((ModifyStoragePoolAnswer) answer).getDatastoreClusterChildren(), hostId);
        }

        storageService.updateStorageCapabilities(poolId, false);

        logger.info("Connection established between storage pool {} and host {}", pool, host);

        return createPersistentNetworkResourcesOnHost(hostId);
    }

    private void updateStoragePoolHostVOAndDetails(StoragePool pool, long hostId, ModifyStoragePoolAnswer mspAnswer) {
        StoragePoolHostVO poolHost = storagePoolHostDao.findByPoolHost(pool.getId(), hostId);
        if (poolHost == null) {
            poolHost = new StoragePoolHostVO(pool.getId(), hostId, mspAnswer.getPoolInfo().getLocalPath().replaceAll("//", "/"));
            storagePoolHostDao.persist(poolHost);
        } else {
            poolHost.setLocalPath(mspAnswer.getPoolInfo().getLocalPath().replaceAll("//", "/"));
        }

        StoragePoolVO poolVO = this.primaryStoreDao.findById(pool.getId());
        poolVO.setUsedBytes(mspAnswer.getPoolInfo().getCapacityBytes() - mspAnswer.getPoolInfo().getAvailableBytes());
        poolVO.setCapacityBytes(mspAnswer.getPoolInfo().getCapacityBytes());
        if (StringUtils.isNotEmpty(mspAnswer.getPoolType())) {
            StoragePoolDetailVO poolType = storagePoolDetailsDao.findDetail(pool.getId(), "pool_type");
            if (poolType == null) {
                StoragePoolDetailVO storagePoolDetailVO = new StoragePoolDetailVO(pool.getId(), "pool_type", mspAnswer.getPoolType(), false);
                storagePoolDetailsDao.persist(storagePoolDetailVO);
            }
        }
        primaryStoreDao.update(pool.getId(), poolVO);
    }

    @Override
    public boolean hostDisconnected(long hostId, long poolId) {
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            logger.error("Failed to disconnect host by HostListener as host was not found with id : " + hostId);
            return false;
        }

        DataStore dataStore = dataStoreMgr.getDataStore(poolId, DataStoreRole.Primary);
        StoragePool storagePool = (StoragePool) dataStore;
        DeleteStoragePoolCommand cmd = new DeleteStoragePoolCommand(storagePool);
        Answer answer  = sendDeleteStoragePoolCommand(cmd, storagePool, host);
        if (!answer.getResult()) {
            logger.error("Failed to disconnect storage pool: " + storagePool + " and host: " + host);
            return false;
        }

        StoragePoolHostVO storagePoolHost = storagePoolHostDao.findByPoolHost(poolId, hostId);
        if (storagePoolHost != null) {
            storagePoolHostDao.deleteStoragePoolHostDetails(hostId, poolId);
        }
        logger.info("Connection removed between storage pool: " + storagePool + " and host: " + host);
        return true;
    }

    private Answer sendDeleteStoragePoolCommand(DeleteStoragePoolCommand cmd, StoragePool storagePool, HostVO host) {
        Answer answer = agentMgr.easySend(host.getId(), cmd);
        if (answer == null) {
            throw new CloudRuntimeException(String.format("Unable to get an answer to the delete storage pool command for storage pool %s, sent to host %s", storagePool, host));
        }

        if (!answer.getResult()) {
            String msg = "Unable to detach storage pool " + storagePool + " from the host " + host;
            alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, storagePool.getDataCenterId(), storagePool.getPodId(), msg, msg);
        }

        return answer;
    }

    @Override
    public boolean hostAboutToBeRemoved(long hostId) {
        // send host the cleanup persistent network resources
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            logger.warn("Host with id " + hostId + " can't be found");
            return false;
        }

        List<NetworkVO> allPersistentNetworks = networkDao.getAllPersistentNetworksFromZone(host.getDataCenterId()); // find zoneId of host
        for (NetworkVO persistentNetworkVO : allPersistentNetworks) {
            NetworkOfferingVO networkOfferingVO = networkOfferingDao.findById(persistentNetworkVO.getNetworkOfferingId());
            CleanupPersistentNetworkResourceCommand cleanupCmd =
                    new CleanupPersistentNetworkResourceCommand(createNicTOFromNetworkAndOffering(persistentNetworkVO, networkOfferingVO, host));
            Answer answer = agentMgr.easySend(hostId, cleanupCmd);
            if (answer == null) {
                logger.error("Unable to get answer to the cleanup persistent network command {}", persistentNetworkVO);
                continue;
            }
            if (!answer.getResult()) {
                logger.error("Unable to cleanup persistent network resources from network {} on the host {}", persistentNetworkVO, hostId);
            }
        }
        return true;
    }

    @Override
    public boolean hostRemoved(long hostId, long clusterId) {
        return true;
    }

    @Override
    public boolean hostEnabled(long hostId) {
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            logger.warn(String.format("Host with id %d can't be found", hostId));
            return false;
        }
        setupPersistentNetwork(host);
        return true;
    }

    private void setupPersistentNetwork(HostVO host) {
        List<NetworkVO> allPersistentNetworks = networkDao.getAllPersistentNetworksFromZone(host.getDataCenterId());
        for (NetworkVO networkVO : allPersistentNetworks) {
            NetworkOfferingVO networkOfferingVO = networkOfferingDao.findById(networkVO.getNetworkOfferingId());

            SetupPersistentNetworkCommand persistentNetworkCommand =
                    new SetupPersistentNetworkCommand(createNicTOFromNetworkAndOffering(networkVO, networkOfferingVO, host));
            Answer answer = agentMgr.easySend(host.getId(), persistentNetworkCommand);
            if (answer == null) {
                throw new CloudRuntimeException(String.format("Unable to get answer to the setup persistent network command %s", networkVO));
            }
            if (!answer.getResult()) {
                logger.error("Unable to create persistent network resources for network {} on the host {} in zone {}",
                        networkVO::toString, host::toString, () -> zoneDao.findById(networkVO.getDataCenterId()));
            }
        }
    }
}
