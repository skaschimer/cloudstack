// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.storage.resource;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.Charset;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.cloudstack.agent.directdownload.DirectDownloadCommand;
import org.apache.cloudstack.storage.command.AttachAnswer;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.CheckDataStoreStoragePolicyComplainceCommand;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.command.ForgetObjectCmd;
import org.apache.cloudstack.storage.command.IntroduceObjectCmd;
import org.apache.cloudstack.storage.command.ResignatureAnswer;
import org.apache.cloudstack.storage.command.ResignatureCommand;
import org.apache.cloudstack.storage.command.SnapshotAndCopyAnswer;
import org.apache.cloudstack.storage.command.SnapshotAndCopyCommand;
import org.apache.cloudstack.storage.command.SyncVolumePathAnswer;
import org.apache.cloudstack.storage.command.SyncVolumePathCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.volume.VirtualMachineDiskInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.hypervisor.vmware.manager.VmwareHostService;
import com.cloud.hypervisor.vmware.manager.VmwareManager;
import com.cloud.hypervisor.vmware.manager.VmwareStorageMount;
import com.cloud.hypervisor.vmware.mo.ClusterMO;
import com.cloud.hypervisor.vmware.mo.CustomFieldConstants;
import com.cloud.hypervisor.vmware.mo.DatacenterMO;
import com.cloud.hypervisor.vmware.mo.DatastoreFile;
import com.cloud.hypervisor.vmware.mo.DatastoreMO;
import com.cloud.hypervisor.vmware.mo.DiskControllerType;
import com.cloud.hypervisor.vmware.mo.HostDatastoreSystemMO;
import com.cloud.hypervisor.vmware.mo.HostMO;
import com.cloud.hypervisor.vmware.mo.HostStorageSystemMO;
import com.cloud.hypervisor.vmware.mo.HypervisorHostHelper;
import com.cloud.hypervisor.vmware.mo.NetworkDetails;
import com.cloud.hypervisor.vmware.mo.VirtualMachineDiskInfoBuilder;
import com.cloud.hypervisor.vmware.mo.VirtualMachineMO;
import com.cloud.hypervisor.vmware.mo.VirtualStorageObjectManagerMO;
import com.cloud.hypervisor.vmware.mo.VmwareHypervisorHost;
import com.cloud.hypervisor.vmware.resource.VmwareResource;
import com.cloud.hypervisor.vmware.util.VmwareContext;
import com.cloud.hypervisor.vmware.util.VmwareHelper;
import com.cloud.serializer.GsonHelper;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.JavaStorageLayer;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.storage.StorageLayer;
import com.cloud.storage.Volume;
import com.cloud.storage.template.OVAProcessor;
import com.cloud.template.TemplateManager;
import com.cloud.utils.LogUtils;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.VmDetailConstants;
import com.google.gson.Gson;
import com.vmware.vim25.BaseConfigInfoDiskFileBackingInfo;
import com.vmware.vim25.DatastoreHostMount;
import com.vmware.vim25.HostHostBusAdapter;
import com.vmware.vim25.HostInternetScsiHba;
import com.vmware.vim25.HostInternetScsiHbaAuthenticationProperties;
import com.vmware.vim25.HostInternetScsiHbaSendTarget;
import com.vmware.vim25.HostInternetScsiHbaStaticTarget;
import com.vmware.vim25.HostInternetScsiTargetTransport;
import com.vmware.vim25.HostResignatureRescanResult;
import com.vmware.vim25.HostScsiDisk;
import com.vmware.vim25.HostScsiTopology;
import com.vmware.vim25.HostScsiTopologyInterface;
import com.vmware.vim25.HostScsiTopologyLun;
import com.vmware.vim25.HostScsiTopologyTarget;
import com.vmware.vim25.HostUnresolvedVmfsExtent;
import com.vmware.vim25.HostUnresolvedVmfsResignatureSpec;
import com.vmware.vim25.HostUnresolvedVmfsVolume;
import com.vmware.vim25.InvalidStateFaultMsg;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.VStorageObject;
import com.vmware.vim25.VirtualDeviceBackingInfo;
import com.vmware.vim25.VirtualDeviceConfigSpec;
import com.vmware.vim25.VirtualDeviceConfigSpecOperation;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualDiskFlatVer2BackingInfo;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VmConfigInfo;
import com.vmware.vim25.VmfsDatastoreExpandSpec;
import com.vmware.vim25.VmfsDatastoreOption;

public class VmwareStorageProcessor implements StorageProcessor {

    public enum VmwareStorageProcessorConfigurableFields {
        NFS_VERSION("nfsVersion"), FULL_CLONE_FLAG("fullCloneFlag"), DISK_PROVISIONING_STRICTNESS("diskProvisioningStrictness");

        private String name;

        VmwareStorageProcessorConfigurableFields(String name){
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    protected Logger logger = LogManager.getLogger(getClass());
    private static final int DEFAULT_NFS_PORT = 2049;
    private static final int SECONDS_TO_WAIT_FOR_DATASTORE = 120;

    private final VmwareHostService hostService;
    private boolean _fullCloneFlag;
    private boolean _diskProvisioningStrictness;
    private final VmwareStorageMount mountService;
    private final VmwareResource resource;
    private final Integer _timeout;
    protected Integer _shutdownWaitMs;
    private final Gson _gson;
    private final StorageLayer _storage = new JavaStorageLayer();
    private String _nfsVersion;
    private static final Random RANDOM = new Random(System.nanoTime());

    public VmwareStorageProcessor(VmwareHostService hostService, boolean fullCloneFlag, VmwareStorageMount mountService, Integer timeout, VmwareResource resource,
                                  Integer shutdownWaitMs, PremiumSecondaryStorageResource storageResource, String nfsVersion) {
        this.hostService = hostService;
        _fullCloneFlag = fullCloneFlag;
        this.mountService = mountService;
        _timeout = timeout;
        this.resource = resource;
        _shutdownWaitMs = shutdownWaitMs;
        _gson = GsonHelper.getGsonLogger();
        _nfsVersion = nfsVersion;
    }

    @Override
    public SnapshotAndCopyAnswer snapshotAndCopy(SnapshotAndCopyCommand cmd) {
        logger.info("'SnapshotAndCopyAnswer snapshotAndCopy(SnapshotAndCopyCommand)' not currently used for VmwareStorageProcessor");

        return new SnapshotAndCopyAnswer();
    }

    @Override
    public ResignatureAnswer resignature(ResignatureCommand cmd) {
        final Map<String, String> details = cmd.getDetails();

        String scsiNaaDeviceId = details.get(DiskTO.SCSI_NAA_DEVICE_ID);

        if (scsiNaaDeviceId == null || scsiNaaDeviceId.trim().length() == 0) {
            throw new CloudRuntimeException("The 'scsiNaaDeviceId' needs to be specified when resignaturing a VMware datastore.");
        }

        final String iScsiName = details.get(DiskTO.IQN);
        final String datastoreName = getMaximumDatastoreName(VmwareResource.getDatastoreName(iScsiName));

        String vmdk = null;

        try {
            VmwareContext context = hostService.getServiceContext(null);
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);

            ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
            ClusterMO clusterMO = new ClusterMO(context, morCluster);

            List<Pair<ManagedObjectReference, String>> lstHosts = clusterMO.getClusterHosts();

            // add iSCSI connection to host

            final String storageHost = details.get(DiskTO.STORAGE_HOST);
            final int storagePortNumber = Integer.parseInt(details.get(DiskTO.STORAGE_PORT));
            final String chapInitiatorUsername = details.get(DiskTO.CHAP_INITIATOR_USERNAME);
            final String chapInitiatorSecret = details.get(DiskTO.CHAP_INITIATOR_SECRET);
            final String chapTargetUsername = details.get(DiskTO.CHAP_TARGET_USERNAME);
            final String chapTargetSecret = details.get(DiskTO.CHAP_TARGET_SECRET);

            HostDiscoveryMethod hostDiscoveryMethod = getHostDiscoveryMethod(context, storageHost, lstHosts);
            List<HostMO> hostsUsingStaticDiscovery = hostDiscoveryMethod.getHostsUsingStaticDiscovery();

            if (hostsUsingStaticDiscovery != null && hostsUsingStaticDiscovery.size() > 0) {
                List<HostInternetScsiHbaStaticTarget> lstTargets = getTargets(storageHost, storagePortNumber, trimIqn(iScsiName),
                        chapInitiatorUsername, chapInitiatorSecret, chapTargetUsername, chapTargetSecret);

                addRemoveInternetScsiTargetsToAllHosts(true, lstTargets, hostsUsingStaticDiscovery);
            }

            rescanAllHosts(context, lstHosts, true, true);

            // perform resignature operation

            HostMO hostMO = new HostMO(context, lstHosts.get(0).first());

            HostDatastoreSystemMO hostDatastoreSystem = hostMO.getHostDatastoreSystemMO();

            List<HostUnresolvedVmfsVolume> hostUnresolvedVmfsVolumes = hostDatastoreSystem.queryUnresolvedVmfsVolumes();

            if (hostUnresolvedVmfsVolumes == null || hostUnresolvedVmfsVolumes.size() == 0) {
                throw new CloudRuntimeException("Unable to locate any snapshot datastores");
            }

            boolean foundExtent = false;

            for (HostUnresolvedVmfsVolume hostUnresolvedVmfsVolume : hostUnresolvedVmfsVolumes) {
                List<HostUnresolvedVmfsExtent> extents = hostUnresolvedVmfsVolume.getExtent();
                List<HostUnresolvedVmfsExtent> matchingExtents = getExtentsMatching(extents, scsiNaaDeviceId);

                if (matchingExtents.size() >= 1) {
                    String extentDevicePath = matchingExtents.get(0).getDevicePath();
                    HostResignatureRescanResult hostResignatureRescanResult = resignatureDatastore(hostDatastoreSystem, extentDevicePath);

                    if (hostResignatureRescanResult == null) {
                        throw new CloudRuntimeException("'hostResignatureRescanResult' should not be 'null'.");
                    }

                    ManagedObjectReference morDs = hostResignatureRescanResult.getResult();

                    if (morDs == null) {
                        throw new CloudRuntimeException("'morDs' should not be 'null'.");
                    }

                    DatastoreMO datastoreMO = new DatastoreMO(context, morDs);

                    boolean isOnlyForTemplate = Boolean.parseBoolean(details.get(DiskTO.TEMPLATE_RESIGN));

                    // If this is only for a template, all we really want to do is resignature the datastore (done at this point),
                    // then rename the datastore.
                    if (isOnlyForTemplate) {
                        vmdk = details.get(DiskTO.VMDK);
                    }
                    else {
                        vmdk = cleanUpDatastore(cmd, hostDatastoreSystem, datastoreMO, details);
                    }

                    if (renameDatastore(context, morDs, datastoreName, lstHosts)) {
                        foundExtent = true;

                        break;
                    }
                }
            }

            removeVmfsDatastore(cmd, hyperHost, datastoreName, storageHost, storagePortNumber, trimIqn(iScsiName), lstHosts);

            if (!foundExtent) {
                throw new CloudRuntimeException("Unable to locate the applicable extent");
            }

            final ResignatureAnswer answer = new ResignatureAnswer();

            final long volumeSize = Long.parseLong(details.get(DiskTO.VOLUME_SIZE));

            answer.setSize(volumeSize);

            answer.setPath("[" + datastoreName + "] " + vmdk);

            answer.setFormat(ImageFormat.OVA);

            return answer;
        }
        catch (Exception ex) {
            logger.error(String.format("Command %s failed due to: [%s].", cmd.getClass().getSimpleName(), ex.getMessage()), ex);

            throw new CloudRuntimeException(ex.getMessage());
        }
    }

    private List<HostUnresolvedVmfsExtent> getExtentsMatching(List<HostUnresolvedVmfsExtent> extents, String naa) {
        List<HostUnresolvedVmfsExtent> matchingExtents = new ArrayList<>();

        if (extents != null) {
            for (HostUnresolvedVmfsExtent extent : extents) {
                logger.debug(String.format("HostUnresolvedVmfsExtent details: [devicePath: %s, ordinal: %s, reason: %s, isHeadExtent: %s].", extent.getDevicePath(),
                        extent.getOrdinal(), extent.getReason(), extent.isIsHeadExtent()));

                String extentDevicePath = extent.getDevicePath();

                if (extentDevicePath.contains(naa)) {
                    matchingExtents.add(extent);
                }
            }
        }

        return matchingExtents;
    }

    private class HostUnresolvedVmfsResignatureSpecCustom extends HostUnresolvedVmfsResignatureSpec {
        private HostUnresolvedVmfsResignatureSpecCustom(String extentDevicePath) {
            this.extentDevicePath = new ArrayList<>(1);

            this.extentDevicePath.add(extentDevicePath);
        }
    }

    private HostResignatureRescanResult resignatureDatastore(HostDatastoreSystemMO hostDatastoreSystemMO, String extentDevicePath) throws Exception {
        HostUnresolvedVmfsResignatureSpecCustom resignatureSpec = new HostUnresolvedVmfsResignatureSpecCustom(extentDevicePath);

        return hostDatastoreSystemMO.resignatureUnresolvedVmfsVolume(resignatureSpec);
    }

    private boolean renameDatastore(VmwareContext context, ManagedObjectReference morDs, String newName, List<Pair<ManagedObjectReference, String>> lstHosts) throws Exception {
        if (morDs != null) {
            DatastoreMO datastoreMO = new DatastoreMO(context, morDs);

            datastoreMO.renameDatastore(newName);

            waitForAllHostsToMountDatastore(lstHosts, datastoreMO);

            return true;
        }

        logger.debug("Unable to locate datastore to rename");

        return false;
    }

    private String getMaximumDatastoreName(String datastoreName) {
        final int maxDatastoreNameLength = 80;

        return datastoreName.length() > maxDatastoreNameLength ? datastoreName.substring(0, maxDatastoreNameLength) : datastoreName;
    }

    /**
     * 1) Possibly expand the datastore.
     * 2) Possibly consolidate all relevant VMDK files into one VMDK file.
     * 3) Possibly move the VMDK file to the root folder (may already be there).
     * 4) If the VMDK file wasn't already in the root folder, then delete the folder the VMDK file was in.
     * 5) Possibly rename the VMDK file (this will lead to there being a delta file with the new name and the
     *    original file with the original name).
     *
     * Note: If the underlying VMDK file was for a root disk, the 'vmdk' parameter's value might look, for example,
     *  like "i-2-32-VM/ROOT-32.vmdk".
     *
     * Note: If the underlying VMDK file was for a data disk, the 'vmdk' parameter's value might look, for example,
     *  like "-iqn.2010-01.com.solidfire:4nhe.data-32.79-0.vmdk".
     *
     * Returns the (potentially new) name of the VMDK file.
     */
    private String cleanUpDatastore(Command cmd, HostDatastoreSystemMO hostDatastoreSystem, DatastoreMO dsMo, Map<String, String> details) throws Exception {
        logger.debug(String.format("Executing clean up in DataStore: [%s].", dsMo.getName()));
        boolean expandDatastore = Boolean.parseBoolean(details.get(DiskTO.EXPAND_DATASTORE));

        // A volume on the storage system holding a template uses a minimum hypervisor snapshot reserve value.
        // When this volume is cloned to a new volume, the new volume can be expanded (to take a new hypervisor snapshot reserve value
        // into consideration). If expandDatastore is true, we want to expand the datastore in the new volume to the size of the cloned volume.
        // It's possible that expandDatastore might be true and there isn't any extra space in the cloned volume (if the hypervisor snapshot
        // reserve value in use is set to the minimum for the cloned volume), but that's fine.
        if (expandDatastore) {
            expandDatastore(hostDatastoreSystem, dsMo);
        }

        String vmdk = details.get(DiskTO.VMDK);
        String fullVmdkPath = new DatastoreFile(dsMo.getName(), vmdk).getPath();

        VmwareContext context = hostService.getServiceContext(null);
        VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);

        DatacenterMO dcMo = new DatacenterMO(context, hyperHost.getHyperHostDatacenter());

        String vmName = getVmName(vmdk);

        // If vmName is not null, then move all VMDK files out of this folder to the root folder and then delete the folder named vmName.
        if (vmName != null) {
            String workerVmName = hostService.getWorkerName(context, cmd, 0, dsMo);

            VirtualMachineMO vmMo = HypervisorHostHelper.createWorkerVM(hyperHost, dsMo, workerVmName, null);

            if (vmMo == null) {
                throw new Exception("Unable to create a worker VM for volume creation");
            }

            vmMo.attachDisk(new String[] { fullVmdkPath }, dsMo.getMor());

            List<String> backingFiles = new ArrayList<>(1);

            List<VirtualDisk> virtualDisks = vmMo.getVirtualDisks();

            VirtualDisk virtualDisk = virtualDisks.get(0);

            VirtualDeviceBackingInfo virtualDeviceBackingInfo = virtualDisk.getBacking();

            while (virtualDeviceBackingInfo instanceof VirtualDiskFlatVer2BackingInfo) {
                VirtualDiskFlatVer2BackingInfo backingInfo = (VirtualDiskFlatVer2BackingInfo)virtualDeviceBackingInfo;

                backingFiles.add(backingInfo.getFileName());

                virtualDeviceBackingInfo = backingInfo.getParent();
            }

            vmMo.detachAllDisksAndDestroy();

            VmwareStorageLayoutHelper.moveVolumeToRootFolder(dcMo, backingFiles);

            vmdk = new DatastoreFile(vmdk).getFileName();

            // Delete the folder the VMDK file was in.

            DatastoreFile folderToDelete = new DatastoreFile(dsMo.getName(), vmName);

            dsMo.deleteFolder(folderToDelete.getPath(), dcMo.getMor());
        }

        return vmdk;
    }

    /**
     * Example input for the 'vmdk' parameter:
     *  i-2-32-VM/ROOT-32.vmdk
     *  -iqn.2010-01.com.solidfire:4nhe.data-32.79-0.vmdk
     */
    private String getVmName(String vmdk) {
        int indexOf = vmdk.indexOf("/");

        if (indexOf == -1) {
            return null;
        }

        return vmdk.substring(0, indexOf).trim();
    }

    public void expandDatastore(HostDatastoreSystemMO hostDatastoreSystem, DatastoreMO datastoreMO) throws Exception {
        List<VmfsDatastoreOption> vmfsDatastoreOptions = hostDatastoreSystem.queryVmfsDatastoreExpandOptions(datastoreMO);

        if (vmfsDatastoreOptions != null && vmfsDatastoreOptions.size() > 0) {
            VmfsDatastoreExpandSpec vmfsDatastoreExpandSpec = (VmfsDatastoreExpandSpec)vmfsDatastoreOptions.get(0).getSpec();

            hostDatastoreSystem.expandVmfsDatastore(datastoreMO, vmfsDatastoreExpandSpec);
        }
    }

    private String getOVFFilePath(String srcOVAFileName) {
        File file = new File(srcOVAFileName);
        assert (_storage != null);
        String[] files = _storage.listFiles(file.getParent());
        if (files != null) {
            for (String fileName : files) {
                if (fileName.toLowerCase().endsWith(".ovf")) {
                    File ovfFile = new File(fileName);
                    return file.getParent() + File.separator + ovfFile.getName();
                }
            }
        }
        return null;
    }

    private Pair<VirtualMachineMO, Long> copyTemplateFromSecondaryToPrimary(VmwareHypervisorHost hyperHost, DatastoreMO datastoreMo, String secondaryStorageUrl,
                                                                            String templatePathAtSecondaryStorage, String templateName, String templateUuid,
                                                                            boolean createSnapshot, String nfsVersion, String configuration) throws Exception {
        String secondaryMountPoint = mountService.getMountPoint(secondaryStorageUrl, nfsVersion);

        logger.info(String.format("Init copy of template [uuid: %s, name: %s, path in secondary storage: %s, configuration: %s] in secondary storage [url: %s, mount point: %s] to primary storage.",
                templateUuid, templateName, templatePathAtSecondaryStorage, configuration, secondaryStorageUrl, secondaryMountPoint));

        String srcOVAFileName =
                VmwareStorageLayoutHelper.getTemplateOnSecStorageFilePath(secondaryMountPoint, templatePathAtSecondaryStorage, templateName,
                        ImageFormat.OVA.getFileExtension());

        String srcFileName = getOVFFilePath(srcOVAFileName);
        if (srcFileName == null) {
            Script command = new Script("tar", 0, logger);
            command.add("--no-same-owner");
            command.add("-xf", srcOVAFileName);
            command.setWorkDir(secondaryMountPoint + "/" + templatePathAtSecondaryStorage);
            logger.info("Executing command: " + command.toString());
            String result = command.execute();
            if (result != null) {
                String msg = "Unable to unpack snapshot OVA file at: " + srcOVAFileName;
                logger.error(msg);
                throw new Exception(msg);
            }
        }

        srcFileName = getOVFFilePath(srcOVAFileName);
        if (srcFileName == null) {
            String msg = "Unable to locate OVF file in template package directory: " + srcOVAFileName;
            logger.error(msg);
            throw new Exception(msg);
        }

        if (datastoreMo.getDatastoreType().equalsIgnoreCase("VVOL")) {
            templateUuid = CustomFieldConstants.CLOUD_UUID + "-" + templateUuid;
        }

        VmConfigInfo vAppConfig;
        logger.debug(String.format("Deploying OVF template %s with configuration %s.", templateName, configuration));
        hyperHost.importVmFromOVF(srcFileName, templateUuid, datastoreMo, "thin", configuration);
        VirtualMachineMO vmMo = hyperHost.findVmOnHyperHost(templateUuid);
        if (vmMo == null) {
            String msg =
                    "Failed to import OVA template. secondaryStorage: " + secondaryStorageUrl + ", templatePathAtSecondaryStorage: " + templatePathAtSecondaryStorage +
                            ", templateName: " + templateName + ", templateUuid: " + templateUuid;
            logger.error(msg);
            throw new Exception(msg);
        } else {
            vAppConfig = vmMo.getConfigInfo().getVAppConfig();
            if (vAppConfig != null) {
                logger.info("Found vApp configuration");
            }
        }

        OVAProcessor processor = new OVAProcessor();
        Map<String, Object> params = new HashMap<>();
        params.put(StorageLayer.InstanceConfigKey, _storage);
        processor.configure("OVA Processor", params);
        long virtualSize = processor.getTemplateVirtualSize(secondaryMountPoint + "/" + templatePathAtSecondaryStorage, templateName);

        if (createSnapshot) {
            if (vmMo.createSnapshot("cloud.template.base", "Base snapshot", false, false)) {
                // the same template may be deployed with multiple copies at per-datastore per-host basis,
                // save the original template name from CloudStack DB as the UUID to associate them.
                vmMo.setCustomFieldValue(CustomFieldConstants.CLOUD_UUID, templateName);
                if (vAppConfig == null || (vAppConfig.getProperty().size() == 0)) {
                    vmMo.markAsTemplate();
                }
            } else {
                vmMo.destroy();

                String msg = "Unable to create base snapshot for template, templateName: " + templateName + ", templateUuid: " + templateUuid;

                logger.error(msg);

                throw new Exception(msg);
            }
        }

        return new Pair<>(vmMo, virtualSize);
    }

    @Override
    public Answer copyTemplateToPrimaryStorage(CopyCommand cmd) {
        DataTO srcData = cmd.getSrcTO();
        TemplateObjectTO template = (TemplateObjectTO)srcData;
        DataStoreTO srcStore = srcData.getDataStore();

        if (!(srcStore instanceof NfsTO)) {
            return new CopyCmdAnswer("unsupported protocol");
        }

        NfsTO nfsImageStore = (NfsTO)srcStore;
        DataTO destData = cmd.getDestTO();
        DataStoreTO destStore = destData.getDataStore();
        DataStoreTO primaryStore = destStore;
        String configurationId = ((TemplateObjectTO) destData).getDeployAsIsConfiguration();

        String secondaryStorageUrl = nfsImageStore.getUrl();

        assert secondaryStorageUrl != null;

        boolean managed = false;
        String storageHost = null;
        int storagePort = Integer.MIN_VALUE;
        String managedStoragePoolName = null;
        String managedStoragePoolRootVolumeName = null;
        String chapInitiatorUsername = null;
        String chapInitiatorSecret = null;
        String chapTargetUsername = null;
        String chapTargetSecret = null;

        if (destStore instanceof PrimaryDataStoreTO) {
            PrimaryDataStoreTO destPrimaryDataStoreTo = (PrimaryDataStoreTO)destStore;

            Map<String, String> details = destPrimaryDataStoreTo.getDetails();

            if (details != null) {
                managed = Boolean.parseBoolean(details.get(PrimaryDataStoreTO.MANAGED));

                if (managed) {
                    storageHost = details.get(PrimaryDataStoreTO.STORAGE_HOST);

                    try {
                        storagePort = Integer.parseInt(details.get(PrimaryDataStoreTO.STORAGE_PORT));
                    }
                    catch (Exception ex) {
                        storagePort = 3260;
                    }

                    managedStoragePoolName = details.get(PrimaryDataStoreTO.MANAGED_STORE_TARGET);
                    managedStoragePoolRootVolumeName = details.get(PrimaryDataStoreTO.MANAGED_STORE_TARGET_ROOT_VOLUME);
                    chapInitiatorUsername = details.get(PrimaryDataStoreTO.CHAP_INITIATOR_USERNAME);
                    chapInitiatorSecret = details.get(PrimaryDataStoreTO.CHAP_INITIATOR_SECRET);
                    chapTargetUsername = details.get(PrimaryDataStoreTO.CHAP_TARGET_USERNAME);
                    chapTargetSecret = details.get(PrimaryDataStoreTO.CHAP_TARGET_SECRET);
                }
            }
        }

        String templateUrl = secondaryStorageUrl + "/" + srcData.getPath();
        Pair<String, String> templateInfo = VmwareStorageLayoutHelper.decodeTemplateRelativePathAndNameFromUrl(secondaryStorageUrl, templateUrl, template.getName());

        VmwareContext context = hostService.getServiceContext(cmd);

        if (context == null) {
            return new CopyCmdAnswer("Failed to create a VMware context, check the management server logs or the SSVM log for details");
        }

        VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, cmd);
        DatastoreMO dsMo = null;

        try {
            String storageUuid = managed ? managedStoragePoolName : primaryStore.getUuid();

            // Generate a new template uuid if the template is marked as deploy-as-is,
            // as it supports multiple configurations
            String templateUuidName = template.isDeployAsIs() ?
                    UUID.randomUUID().toString() :
                    deriveTemplateUuidOnHost(hyperHost, storageUuid, templateInfo.second());

            DatacenterMO dcMo = new DatacenterMO(context, hyperHost.getHyperHostDatacenter());
            VirtualMachineMO templateMo = VmwareHelper.pickOneVmOnRunningHost(dcMo.findVmByNameAndLabel(templateUuidName), true);
            Pair<VirtualMachineMO, Long> vmInfo = null;

            final ManagedObjectReference morDs;
            if (managed) {
                morDs = prepareManagedDatastore(context, hyperHost, null, managedStoragePoolName, storageHost, storagePort,
                        chapInitiatorUsername, chapInitiatorSecret, chapTargetUsername, chapTargetSecret);
            }
            else {
                morDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, storageUuid);
            }
            assert (morDs != null);
            dsMo = new DatastoreMO(context, morDs);

            if (templateMo == null) {
                if (logger.isInfoEnabled()) {
                    logger.info("Template " + templateInfo.second() + " is not setup yet. Set up template from secondary storage with uuid name: " + templateUuidName);
                }

                if (managed) {
                    vmInfo = copyTemplateFromSecondaryToPrimary(hyperHost, dsMo, secondaryStorageUrl, templateInfo.first(), templateInfo.second(),
                            managedStoragePoolRootVolumeName, false, _nfsVersion, configurationId);

                    VirtualMachineMO vmMo = vmInfo.first();
                    vmMo.unregisterVm();

                    String[] vmwareLayoutFilePair = VmwareStorageLayoutHelper.getVmdkFilePairManagedDatastorePath(dsMo, managedStoragePoolRootVolumeName,
                            managedStoragePoolRootVolumeName, VmwareStorageLayoutType.VMWARE, false);
                    String[] legacyCloudStackLayoutFilePair = VmwareStorageLayoutHelper.getVmdkFilePairManagedDatastorePath(dsMo, null,
                            managedStoragePoolRootVolumeName, VmwareStorageLayoutType.CLOUDSTACK_LEGACY, false);

                    VmwareStorageLayoutHelper.moveDatastoreFile(dsMo, vmwareLayoutFilePair[0], dcMo.getMor(), dsMo.getMor(), legacyCloudStackLayoutFilePair[0], dcMo.getMor(), true);
                    for (int i=1; i<vmwareLayoutFilePair.length; i++) {
                        VmwareStorageLayoutHelper.moveDatastoreFile(dsMo, vmwareLayoutFilePair[i], dcMo.getMor(), dsMo.getMor(), legacyCloudStackLayoutFilePair[i], dcMo.getMor(), true);
                    }

                    String folderToDelete = dsMo.getDatastorePath(managedStoragePoolRootVolumeName, true);
                    dsMo.deleteFolder(folderToDelete, dcMo.getMor());
                }
                else {
                    vmInfo = copyTemplateFromSecondaryToPrimary(hyperHost, dsMo, secondaryStorageUrl, templateInfo.first(), templateInfo.second(),
                            templateUuidName, true, _nfsVersion, configurationId);
                }
            } else {
                logger.info("Template " + templateInfo.second() + " has already been setup, skip the template setup process in primary storage");
            }

            TemplateObjectTO newTemplate = new TemplateObjectTO();

            if (managed) {
                if (dsMo != null) {
                    String path = dsMo.getDatastorePath(managedStoragePoolRootVolumeName + ".vmdk");

                    newTemplate.setPath(path);
                }
            }
            else {
                if (dsMo.getDatastoreType().equalsIgnoreCase("VVOL")) {
                    newTemplate.setPath(CustomFieldConstants.CLOUD_UUID + "-" + templateUuidName);
                } else {
                    newTemplate.setPath(templateUuidName);
                }
            }

            newTemplate.setDeployAsIsConfiguration(configurationId);
            newTemplate.setSize((vmInfo != null)? vmInfo.second() : new Long(0));

            return new CopyCmdAnswer(newTemplate);
        } catch (Throwable e) {
            return new CopyCmdAnswer(hostService.createLogMessageException(e, cmd));
        }
        finally {
            if (dsMo != null && managedStoragePoolName != null) {
                try {
                    removeVmfsDatastore(cmd, hyperHost, VmwareResource.getDatastoreName(managedStoragePoolName), storageHost, storagePort, trimIqn(managedStoragePoolName));
                }
                catch (Exception ex) {
                    logger.error("Unable to remove the following datastore: " + VmwareResource.getDatastoreName(managedStoragePoolName), ex);
                }
            }
        }
    }

    private boolean createVMLinkedClone(VirtualMachineMO vmTemplate, DatacenterMO dcMo, String vmdkName, ManagedObjectReference morDatastore,
                                        ManagedObjectReference morPool) throws Exception {
        return createVMLinkedClone(vmTemplate, dcMo, vmdkName, morDatastore, morPool, null);
    }

    private boolean createVMLinkedClone(VirtualMachineMO vmTemplate, DatacenterMO dcMo, String vmdkName, ManagedObjectReference morDatastore,
                                        ManagedObjectReference morPool, ManagedObjectReference morBaseSnapshot) throws Exception {
        if (morBaseSnapshot == null) {
            morBaseSnapshot = vmTemplate.getSnapshotMor("cloud.template.base");
        }

        if (morBaseSnapshot == null) {
            String msg = "Unable to find template base snapshot, invalid template";

            logger.error(msg);

            throw new Exception(msg);
        }

        logger.info("creating linked clone from template");

        if (!vmTemplate.createLinkedClone(vmdkName, morBaseSnapshot, dcMo.getVmFolder(), morPool, morDatastore)) {
            String msg = "Unable to clone from the template";

            logger.error(msg);

            throw new Exception(msg);
        }

        return true;
    }

    private boolean createVMFullClone(VirtualMachineMO vmTemplate, DatacenterMO dcMo, DatastoreMO dsMo, String vmdkName, ManagedObjectReference morDatastore,
                                      ManagedObjectReference morPool, ProvisioningType diskProvisioningType) throws Exception {
        logger.info("creating full clone from template");

        if (!vmTemplate.createFullClone(vmdkName, dcMo.getVmFolder(), morPool, morDatastore, diskProvisioningType)) {
            String msg = "Unable to create full clone from the template";

            logger.error(msg);

            throw new Exception(msg);
        }

        return true;
    }

    @Override
    public Answer cloneVolumeFromBaseTemplate(CopyCommand cmd) {
        DataTO srcData = cmd.getSrcTO();
        TemplateObjectTO template = (TemplateObjectTO)srcData;
        DataTO destData = cmd.getDestTO();
        VolumeObjectTO volume = (VolumeObjectTO)destData;
        DataStoreTO primaryStore = volume.getDataStore();
        DataStoreTO srcStore = template.getDataStore();
        String searchExcludedFolders = cmd.getContextParam("searchexludefolders");

        try {
            VmwareContext context = hostService.getServiceContext(null);
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);
            DatacenterMO dcMo = new DatacenterMO(context, hyperHost.getHyperHostDatacenter());
            VirtualMachineMO vmMo = null;
            ManagedObjectReference morDatastore = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, primaryStore.getUuid());
            if (morDatastore == null) {
                throw new Exception("Unable to find datastore in vSphere");
            }

            DatastoreMO dsMo = new DatastoreMO(context, morDatastore);

            String vmdkName = volume.getName();
            String vmName = volume.getVmName();
            String vmdkFileBaseName = null;
            if (template.isDeployAsIs() && volume.getVolumeType() == Volume.Type.ROOT) {
                VirtualMachineMO existingVm = dcMo.findVm(vmName);
                if (volume.getDeviceId().equals(0L)) {
                    if (existingVm != null) {
                        logger.info(String.format("Found existing VM wth name [%s] before cloning from template, destroying it", vmName));
                        existingVm.detachAllDisksAndDestroy();
                    }
                    logger.info("ROOT Volume from deploy-as-is template, cloning template");
                    cloneVMFromTemplate(hyperHost, template, volume, vmName, primaryStore.getUuid());
                } else {
                    logger.info("ROOT Volume from deploy-as-is template, volume already created at this point");
                }
            } else {
                if (srcStore == null) {
                    // create a root volume for blank VM (created from ISO)
                    String dummyVmName = hostService.getWorkerName(context, cmd, 0, dsMo);

                    try {
                        vmMo = HypervisorHostHelper.createWorkerVM(hyperHost, dsMo, dummyVmName, null);
                        if (vmMo == null) {
                            throw new Exception("Unable to create a dummy VM for volume creation");
                        }

                        vmdkFileBaseName = vmMo.getVmdkFileBaseNames().get(0);
                        // we only use the first file in the pair, linked or not will not matter
                        String vmdkFilePair[] = VmwareStorageLayoutHelper.getVmdkFilePairDatastorePath(dsMo, null, vmdkFileBaseName, VmwareStorageLayoutType.CLOUDSTACK_LEGACY, true);
                        String volumeDatastorePath = vmdkFilePair[0];
                        synchronized (this) {
                            logger.info("Delete file if exists in datastore to clear the way for creating the volume. file: " + volumeDatastorePath);
                            VmwareStorageLayoutHelper.deleteVolumeVmdkFiles(dsMo, vmdkName, dcMo, searchExcludedFolders);
                            vmMo.createDisk(volumeDatastorePath, (long)(volume.getSize() / (1024L * 1024L)), morDatastore, -1, null);
                            vmMo.detachDisk(volumeDatastorePath, false);
                        }
                    } finally {
                        logger.info("Destroy dummy VM after volume creation");
                        if (vmMo != null) {
                            logger.warn("Unable to destroy a null VM ManagedObjectReference");
                            vmMo.detachAllDisksAndDestroy();
                        }
                    }
                } else {
                    String templatePath = template.getPath();
                    VirtualMachineMO vmTemplate = VmwareHelper.pickOneVmOnRunningHost(dcMo.findVmByNameAndLabel(templatePath), true);
                    if (vmTemplate == null) {
                        logger.warn("Template host in vSphere is not in connected state, request template reload");
                        return new CopyCmdAnswer("Template host in vSphere is not in connected state, request template reload");
                    }
                    if (dsMo.getDatastoreType().equalsIgnoreCase("VVOL")) {
                        vmdkFileBaseName = cloneVMforVvols(context, hyperHost, template, vmTemplate, volume, dcMo, dsMo);
                    } else {
                        vmdkFileBaseName = createVMAndFolderWithVMName(context, hyperHost, template, vmTemplate, volume, dcMo, dsMo, searchExcludedFolders);
                    }
                }
                // restoreVM - move the new ROOT disk into corresponding VM folder
                VirtualMachineMO restoreVmMo = dcMo.findVm(volume.getVmName());
                if (restoreVmMo != null) {
                    if (!dsMo.getDatastoreType().equalsIgnoreCase("VVOL")) {
                        String vmNameInVcenter = restoreVmMo.getName(); // VM folder name in datastore will be VM's name in vCenter.
                        if (dsMo.folderExists(String.format("[%s]", dsMo.getName()), vmNameInVcenter)) {
                            VmwareStorageLayoutHelper.syncVolumeToVmDefaultFolder(dcMo, vmNameInVcenter, dsMo, vmdkFileBaseName, searchExcludedFolders);
                        }
                    }
                }
            }

            VolumeObjectTO newVol = new VolumeObjectTO();
            newVol.setPath(vmdkFileBaseName);
            if (template.isDeployAsIs()) {
                newVol.setSize(volume.getSize());
            } else if (template.getSize() != null) {
                newVol.setSize(template.getSize());
            } else {
                newVol.setSize(volume.getSize());
            }
            return new CopyCmdAnswer(newVol);
        } catch (Throwable e) {
            return new CopyCmdAnswer(hostService.createLogMessageException(e, cmd));
        }
    }

    private String cloneVMforVvols(VmwareContext context, VmwareHypervisorHost hyperHost, TemplateObjectTO template,
                                   VirtualMachineMO vmTemplate, VolumeObjectTO volume, DatacenterMO dcMo, DatastoreMO dsMo) throws Exception {
        ManagedObjectReference morDatastore = dsMo.getMor();
        ManagedObjectReference morPool = hyperHost.getHyperHostOwnerResourcePool();
        ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
        if (template.getSize() != null) {
            _fullCloneFlag = volume.getSize() > template.getSize() ? true : _fullCloneFlag;
        }
        String vmName = volume.getVmName();
        if (volume.getVolumeType() == Volume.Type.DATADISK)
            vmName = volume.getName();
        if (!_fullCloneFlag) {
            if (_diskProvisioningStrictness && volume.getProvisioningType() != ProvisioningType.THIN) {
                throw new CloudRuntimeException("Unable to create linked clones with strict disk provisioning enabled");
            }
            createVMLinkedClone(vmTemplate, dcMo, vmName, morDatastore, morPool);
        } else {
            createVMFullClone(vmTemplate, dcMo, dsMo, vmName, morDatastore, morPool, volume.getProvisioningType());
        }

        VirtualMachineMO vmMo = new ClusterMO(context, morCluster).findVmOnHyperHost(vmName);
        assert (vmMo != null);
        String vmdkFileBaseName = vmMo.getVmdkFileBaseNames().get(0);
        if (volume.getVolumeType() == Volume.Type.DATADISK) {
            logger.info("detach disks from volume-wrapper VM " + vmName);
            vmMo.detachAllDisksAndDestroy();
        }
        return vmdkFileBaseName;
    }

    private String createVMAndFolderWithVMName(VmwareContext context, VmwareHypervisorHost hyperHost, TemplateObjectTO template,
                                               VirtualMachineMO vmTemplate, VolumeObjectTO volume, DatacenterMO dcMo, DatastoreMO dsMo,
                                               String searchExcludedFolders) throws Exception {
        String vmdkName = volume.getName();
        try {
            ManagedObjectReference morDatastore = dsMo.getMor();
            ManagedObjectReference morPool = hyperHost.getHyperHostOwnerResourcePool();
            ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
            if (template.getSize() != null) {
                _fullCloneFlag = volume.getSize() > template.getSize() ? true : _fullCloneFlag;
            }
            if (!_fullCloneFlag) {
                if (_diskProvisioningStrictness && volume.getProvisioningType() != ProvisioningType.THIN) {
                    throw new CloudRuntimeException("Unable to create linked clones with strict disk provisioning enabled");
                }
                createVMLinkedClone(vmTemplate, dcMo, vmdkName, morDatastore, morPool);
            } else {
                createVMFullClone(vmTemplate, dcMo, dsMo, vmdkName, morDatastore, morPool, volume.getProvisioningType());
            }

            VirtualMachineMO vmMo = new ClusterMO(context, morCluster).findVmOnHyperHost(vmdkName);
            assert (vmMo != null);

            String vmdkFileBaseName = vmMo.getVmdkFileBaseNames().get(0);
            logger.info("Move volume out of volume-wrapper VM " + vmdkFileBaseName);
            String[] vmwareLayoutFilePair = VmwareStorageLayoutHelper.getVmdkFilePairDatastorePath(dsMo, vmdkName, vmdkFileBaseName, VmwareStorageLayoutType.VMWARE, !_fullCloneFlag);
            String[] legacyCloudStackLayoutFilePair = VmwareStorageLayoutHelper.getVmdkFilePairDatastorePath(dsMo, vmdkName, vmdkFileBaseName, VmwareStorageLayoutType.CLOUDSTACK_LEGACY, !_fullCloneFlag);

            for (int i = 0; i < vmwareLayoutFilePair.length; i++) {
                VmwareStorageLayoutHelper.moveDatastoreFile(dsMo, vmwareLayoutFilePair[i], dcMo.getMor(), dsMo.getMor(), legacyCloudStackLayoutFilePair[i], dcMo.getMor(), true);
            }

            logger.info("detach disks from volume-wrapper VM and destroy {}", vmdkName);
            vmMo.detachAllDisksAndDestroy();

            String srcFile = dsMo.getDatastorePath(vmdkName, true);

            dsMo.deleteFile(srcFile, dcMo.getMor(), true, searchExcludedFolders);

            if (dsMo.folderExists(String.format("[%s]", dsMo.getName()), vmdkName)) {
                dsMo.deleteFolder(srcFile, dcMo.getMor());
            }

            // restoreVM - move the new ROOT disk into corresponding VM folder
            VirtualMachineMO restoreVmMo = dcMo.findVm(volume.getVmName());
            if (restoreVmMo != null) {
                String vmNameInVcenter = restoreVmMo.getName(); // VM folder name in datastore will be VM's name in vCenter.
                if (dsMo.folderExists(String.format("[%s]", dsMo.getName()), vmNameInVcenter)) {
                    VmwareStorageLayoutHelper.syncVolumeToVmDefaultFolder(dcMo, vmNameInVcenter, dsMo, vmdkFileBaseName, searchExcludedFolders);
                }
            }

            return vmdkFileBaseName;
        } finally {
            // check if volume wrapper VM is cleaned, if not cleanup
            VirtualMachineMO vmdknamedVM = dcMo.findVm(vmdkName);
            if (vmdknamedVM != null) {
                vmdknamedVM.destroy();
            }
        }
    }

    private void createLinkedOrFullClone(TemplateObjectTO template, VolumeObjectTO volume, DatacenterMO dcMo, VirtualMachineMO vmMo, ManagedObjectReference morDatastore,
                                         DatastoreMO dsMo, String cloneName, ManagedObjectReference morPool) throws Exception {
        if (template.getSize() != null) {
            _fullCloneFlag = volume.getSize() > template.getSize() || _fullCloneFlag;
        }
        if (!_fullCloneFlag) {
            if (_diskProvisioningStrictness && volume.getProvisioningType() != ProvisioningType.THIN) {
                throw new CloudRuntimeException("Unable to create linked clones with strict disk provisioning enabled");
            }
            createVMLinkedClone(vmMo, dcMo, cloneName, morDatastore, morPool);
        } else {
            createVMFullClone(vmMo, dcMo, dsMo, cloneName, morDatastore, morPool, volume.getProvisioningType());
        }
    }

    private Pair<String, String> copyVolumeFromSecStorage(VmwareHypervisorHost hyperHost, String srcVolumePath, DatastoreMO dsMo, String secStorageUrl,
                                                          long wait, String nfsVersion) throws Exception {
        String volumeFolder;
        String volumeName;
        String sufix = ".ova";
        int index = srcVolumePath.lastIndexOf(File.separator);
        if (srcVolumePath.endsWith(sufix)) {
            volumeFolder = srcVolumePath.substring(0, index);
            volumeName = srcVolumePath.substring(index + 1).replace(sufix, "");
        } else {
            volumeFolder = srcVolumePath;
            volumeName = srcVolumePath.substring(index + 1);
        }

        String newVolume = VmwareHelper.getVCenterSafeUuid(dsMo);
        restoreVolumeFromSecStorage(hyperHost, dsMo, newVolume, secStorageUrl, volumeFolder, volumeName, wait, nfsVersion);

        return new Pair<>(volumeFolder, newVolume);
    }

    private String deleteVolumeDirOnSecondaryStorage(String volumeDir, String secStorageUrl, String nfsVersion) throws Exception {
        String secondaryMountPoint = mountService.getMountPoint(secStorageUrl, nfsVersion);
        String volumeMountRoot = secondaryMountPoint + File.separator + volumeDir;

        return deleteDir(volumeMountRoot);
    }

    private String deleteDir(String dir) {
        synchronized (dir.intern()) {
            Script command = new Script(false, "rm", _timeout, logger);
            command.add("-rf");
            command.add(dir);
            return command.execute();
        }
    }

    @Override
    public Answer copyVolumeFromImageCacheToPrimary(CopyCommand cmd) {
        VolumeObjectTO srcVolume = (VolumeObjectTO)cmd.getSrcTO();
        VolumeObjectTO destVolume = (VolumeObjectTO)cmd.getDestTO();
        VmwareContext context = hostService.getServiceContext(cmd);
        try {

            NfsTO srcStore = (NfsTO)srcVolume.getDataStore();
            DataStoreTO destStore = destVolume.getDataStore();

            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, cmd);
            String uuid = destStore.getUuid();

            ManagedObjectReference morDatastore = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, uuid);
            if (morDatastore == null) {
                URI uri = new URI(destStore.getUrl());

                morDatastore = hyperHost.mountDatastore(false, uri.getHost(), 0, uri.getPath(), destStore.getUuid().replace("-", ""), true);

                if (morDatastore == null) {
                    throw new Exception("Unable to mount storage pool on host. storeUrl: " + uri.getHost() + ":/" + uri.getPath());
                }
            }

            Pair<String, String> result = copyVolumeFromSecStorage(hyperHost, srcVolume.getPath(), new DatastoreMO(context, morDatastore), srcStore.getUrl(), (long)cmd.getWait() * 1000, _nfsVersion);
            deleteVolumeDirOnSecondaryStorage(result.first(), srcStore.getUrl(), _nfsVersion);
            VolumeObjectTO newVolume = new VolumeObjectTO();
            newVolume.setPath(result.second());
            return new CopyCmdAnswer(newVolume);
        } catch (Throwable t) {
            return new CopyCmdAnswer(hostService.createLogMessageException(t, cmd));
        }

    }

    private String getVolumePathInDatastore(DatastoreMO dsMo, String volumeFileName, String searchExcludedFolders) throws Exception {
        String datastoreVolumePath = dsMo.searchFileInSubFolders(volumeFileName, true, searchExcludedFolders);
        assert (datastoreVolumePath != null) : "Virtual disk file missing from datastore.";
        return datastoreVolumePath;
    }

    private Pair<String, String> copyVolumeToSecStorage(VmwareHostService hostService, VmwareHypervisorHost hyperHost, CopyCommand cmd, String vmName, String poolId,
                                                        String volumePath, String destVolumePath, String secStorageUrl, String workerVmName) throws Exception {
        VirtualMachineMO workerVm = null;
        VirtualMachineMO vmMo = null;
        String exportName = UUID.randomUUID().toString().replace("-", "");
        String searchExcludedFolders = cmd.getContextParam("searchexludefolders");

        try {
            ManagedObjectReference morDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, poolId);

            if (morDs == null) {
                String msg = "Unable to find volumes's storage pool for copy volume operation";
                logger.error(msg);
                throw new Exception(msg);
            }

            boolean clonedWorkerVMNeeded = true;
            vmMo = hyperHost.findVmOnHyperHost(vmName);
            if (vmMo == null || VmwareResource.getVmState(vmMo) == PowerState.PowerOff) {
                // create a dummy worker vm for attaching the volume
                DatastoreMO dsMo = new DatastoreMO(hyperHost.getContext(), morDs);
                workerVm = HypervisorHostHelper.createWorkerVM(hyperHost, dsMo, workerVmName, null);

                if (workerVm == null) {
                    String msg = "Unable to create worker VM to execute CopyVolumeCommand";
                    logger.error(msg);
                    throw new Exception(msg);
                }

                // attach volume to worker VM
                String datastoreVolumePath = getVolumePathInDatastore(dsMo, volumePath + ".vmdk", searchExcludedFolders);
                workerVm.attachDisk(new String[] {datastoreVolumePath}, morDs);
                vmMo = workerVm;
                clonedWorkerVMNeeded = false;
            }

            exportVolumeToSecondaryStorage(hyperHost.getContext(), vmMo, hyperHost, volumePath, secStorageUrl, destVolumePath, exportName, hostService.getWorkerName(hyperHost.getContext(), cmd, 1, null), _nfsVersion, clonedWorkerVMNeeded);
            return new Pair<>(destVolumePath, exportName);

        } finally {
            if (vmMo != null && vmMo.getSnapshotMor(exportName) != null) {
                vmMo.removeSnapshot(exportName, false);
            }
            if (workerVm != null) {
                workerVm.detachAllDisksAndDestroy();
            }
        }
    }

    @Override
    public Answer copyVolumeFromPrimaryToSecondary(CopyCommand cmd) {
        VolumeObjectTO srcVolume = (VolumeObjectTO)cmd.getSrcTO();
        VolumeObjectTO destVolume = (VolumeObjectTO)cmd.getDestTO();
        String vmName = srcVolume.getVmName();

        VmwareContext context = hostService.getServiceContext(cmd);
        try {
            DataStoreTO primaryStorage = srcVolume.getDataStore();
            NfsTO destStore = (NfsTO)destVolume.getDataStore();
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, cmd);

            Pair<String, String> result;

            result =
                    copyVolumeToSecStorage(hostService, hyperHost, cmd, vmName, primaryStorage.getUuid(), srcVolume.getPath(), destVolume.getPath(), destStore.getUrl(),
                            hostService.getWorkerName(context, cmd, 0, null));
            VolumeObjectTO newVolume = new VolumeObjectTO();
            newVolume.setPath(result.first() + File.separator + result.second());
            return new CopyCmdAnswer(newVolume);
        } catch (Throwable e) {
            return new CopyCmdAnswer(hostService.createLogMessageException(e, cmd));
        }
    }

    private void postCreatePrivateTemplate(String installFullPath, long templateId, String templateName, long size, long virtualSize) throws Exception {

        // TODO a bit ugly here
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(installFullPath + "/template.properties"),"UTF-8"));
            out.write("filename=" + templateName + ".ova");
            out.newLine();
            out.write("description=");
            out.newLine();
            out.write("checksum=");
            out.newLine();
            out.write("hvm=false");
            out.newLine();
            out.write("size=" + size);
            out.newLine();
            out.write("ova=true");
            out.newLine();
            out.write("id=" + templateId);
            out.newLine();
            out.write("public=false");
            out.newLine();
            out.write("ova.filename=" + templateName + ".ova");
            out.newLine();
            out.write("uniquename=" + templateName);
            out.newLine();
            out.write("ova.virtualsize=" + virtualSize);
            out.newLine();
            out.write("virtualsize=" + virtualSize);
            out.newLine();
            out.write("ova.size=" + size);
            out.newLine();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private Ternary<String, Long, Long> createTemplateFromVolume(VmwareContext context, VirtualMachineMO vmMo, VmwareHypervisorHost hyperHost, String installPath, long templateId, String templateUniqueName,
                                                                 String secStorageUrl, String volumePath, String workerVmName, String nfsVersion) throws Exception {

        String secondaryMountPoint = mountService.getMountPoint(secStorageUrl, nfsVersion);
        String installFullPath = secondaryMountPoint + "/" + installPath;
        synchronized (installPath.intern()) {
            Script command = new Script(false, "mkdir", _timeout, logger);
            command.add("-p");
            command.add(installFullPath);

            String result = command.execute();
            if (result != null) {
                String msg = "unable to prepare template directory: " + installPath + ", storage: " + secStorageUrl + ", error msg: " + result;
                logger.error(msg);
                throw new Exception(msg);
            }
        }

        VirtualMachineMO clonedVm = null;
        try {
            Pair<VirtualDisk, String> volumeDeviceInfo = vmMo.getDiskDevice(volumePath);
            if (volumeDeviceInfo == null) {
                String msg = "Unable to find related disk device for volume. volume path: " + volumePath;
                logger.error(msg);
                throw new Exception(msg);
            }

            DatacenterMO dcMo = new DatacenterMO(context, hyperHost.getHyperHostDatacenter());
            ManagedObjectReference morPool = hyperHost.getHyperHostOwnerResourcePool();
            VirtualDisk requiredDisk = volumeDeviceInfo.first();
            clonedVm = vmMo.createFullCloneWithSpecificDisk(templateUniqueName, dcMo.getVmFolder(), morPool, requiredDisk);
            if (clonedVm == null) {
                throw new Exception(String.format("Failed to clone VM with name %s during create template from volume operation", templateUniqueName));
            }
            clonedVm.exportVm(secondaryMountPoint + "/" + installPath, templateUniqueName, false, false);

            // Get VMDK filename
            String templateVMDKName = "";
            File[] files = new File(installFullPath).listFiles();
            if (files != null) {
                for(File file : files) {
                    String fileName = file.getName();
                    if (fileName.toLowerCase().startsWith(templateUniqueName) && fileName.toLowerCase().endsWith(".vmdk")) {
                        templateVMDKName += fileName;
                        break;
                    }
                }
            }

            long physicalSize = new File(installFullPath + "/" + templateVMDKName).length();
            OVAProcessor processor = new OVAProcessor();

            Map<String, Object> params = new HashMap<>();
            params.put(StorageLayer.InstanceConfigKey, _storage);
            processor.configure("OVA Processor", params);
            long virtualSize = processor.getTemplateVirtualSize(installFullPath, templateUniqueName);

            postCreatePrivateTemplate(installFullPath, templateId, templateUniqueName, physicalSize, virtualSize);
            writeMetaOvaForTemplate(installFullPath, templateUniqueName + ".ovf", templateVMDKName, templateUniqueName, physicalSize);
            return new Ternary<String, Long, Long>(installPath + "/" + templateUniqueName + ".ova", physicalSize, virtualSize);

        } finally {
            if (clonedVm != null) {
                logger.debug(String.format("Destroying cloned VM: %s with its disks", clonedVm.getName()));
                clonedVm.destroy();
            }
        }
    }

    @Override
    public Answer createTemplateFromVolume(CopyCommand cmd) {
        VolumeObjectTO volume = (VolumeObjectTO)cmd.getSrcTO();
        TemplateObjectTO template = (TemplateObjectTO)cmd.getDestTO();
        DataStoreTO imageStore = template.getDataStore();

        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("unsupported protocol");
        }
        NfsTO nfsImageStore = (NfsTO)imageStore;
        String secondaryStoragePoolURL = nfsImageStore.getUrl();
        String volumePath = volume.getPath();

        String details = null;
        VirtualMachineMO vmMo = null;
        VirtualMachineMO workerVmMo = null;
        VmwareContext context = hostService.getServiceContext(cmd);
        try {
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, cmd);
            if (volume.getVmName() == null) {
                ManagedObjectReference secMorDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, volume.getDataStore().getUuid());
                DatastoreMO dsMo = new DatastoreMO(hyperHost.getContext(), secMorDs);
                workerVmMo = HypervisorHostHelper.createWorkerVM(hyperHost, dsMo, "workervm"+volume.getUuid(), null);
                if (workerVmMo == null) {
                    throw new Exception("Unable to find created worker VM");
                }
                vmMo = workerVmMo;
                String vmdkDataStorePath = VmwareStorageLayoutHelper.getLegacyDatastorePathFromVmdkFileName(dsMo, volumePath + ".vmdk");
                vmMo.attachDisk(new String[] {vmdkDataStorePath}, secMorDs);
            } else {
                vmMo = hyperHost.findVmOnHyperHost(volume.getVmName());
                if (vmMo == null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Unable to find the owner VM for CreatePrivateTemplateFromVolumeCommand on host " + hyperHost.getHyperHostName() +
                                ", try within datacenter");
                    }
                    vmMo = hyperHost.findVmOnPeerHyperHost(volume.getVmName());

                    if (vmMo == null) {
                        // This means either the volume is on a zone wide storage pool or VM is deleted by external entity.
                        // Look for the VM in the datacenter.
                        ManagedObjectReference dcMor = hyperHost.getHyperHostDatacenter();
                        DatacenterMO dcMo = new DatacenterMO(context, dcMor);
                        vmMo = dcMo.findVm(volume.getVmName());
                    }

                    if (vmMo == null) {
                        String msg = "Unable to find the owner VM for volume operation. vm: " + volume.getVmName();
                        logger.error(msg);
                        throw new Exception(msg);
                    }
                }
            }

            Ternary<String, Long, Long> result =
                    createTemplateFromVolume(context, vmMo, hyperHost, template.getPath(), template.getId(), template.getName(), secondaryStoragePoolURL, volumePath,
                            hostService.getWorkerName(context, cmd, 0, null), _nfsVersion);
            TemplateObjectTO newTemplate = new TemplateObjectTO();
            newTemplate.setPath(result.first());
            newTemplate.setFormat(ImageFormat.OVA);
            newTemplate.setSize(result.third());
            newTemplate.setPhysicalSize(result.second());
            return new CopyCmdAnswer(newTemplate);

        } catch (Throwable e) {
            return new CopyCmdAnswer(hostService.createLogMessageException(e, cmd));
        } finally {
            try {
                if (volume.getVmName() == null && workerVmMo != null) {
                    workerVmMo.detachAllDisksAndDestroy();
                }
            } catch (Throwable e) {
                logger.error("Failed to destroy worker VM created for detached volume");
            }
        }
    }

    private void writeMetaOvaForTemplate(String installFullPath, String ovfFilename, String vmdkFilename, String templateName, long diskSize) throws Exception {

        // TODO a bit ugly here
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(installFullPath + "/" + templateName + ".ova.meta"),"UTF-8"));
            out.write("ova.filename=" + templateName + ".ova");
            out.newLine();
            out.write("version=1.0");
            out.newLine();
            out.write("ovf=" + ovfFilename);
            out.newLine();
            out.write("numDisks=1");
            out.newLine();
            out.write("disk1.name=" + vmdkFilename);
            out.newLine();
            out.write("disk1.size=" + diskSize);
            out.newLine();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private Ternary<String, Long, Long> createTemplateFromSnapshot(String installPath, String templateUniqueName, String secStorageUrl, String snapshotPath,
                                                                   Long templateId, long wait, String nfsVersion) throws Exception {
        //Snapshot path is decoded in this form: /snapshots/account/volumeId/uuid/uuid
        String backupSSUuid;
        String snapshotFolder;
        if (snapshotPath.endsWith(".ova")) {
            int index = snapshotPath.lastIndexOf(File.separator);
            backupSSUuid = snapshotPath.substring(index + 1).replace(".ova", "");
            snapshotFolder = snapshotPath.substring(0, index);
        } else {
            String[] tokens = snapshotPath.split(File.separatorChar == '\\' ? "\\\\" : File.separator);
            backupSSUuid = tokens[tokens.length - 1];
            snapshotFolder = StringUtils.join(tokens, File.separator, 0, tokens.length - 1);
        }

        String secondaryMountPoint = mountService.getMountPoint(secStorageUrl, nfsVersion);
        String installFullPath = secondaryMountPoint + "/" + installPath;
        String installFullOVAName = installFullPath + "/" + templateUniqueName + ".ova";  //Note: volss for tmpl
        String snapshotRoot = secondaryMountPoint + "/" + snapshotFolder;
        String snapshotFullOVAName = snapshotRoot + "/" + backupSSUuid + ".ova";
        String snapshotFullOvfName = snapshotRoot + "/" + backupSSUuid + ".ovf";
        String result;
        Script command;
        String templateVMDKName = "";
        String snapshotFullVMDKName = snapshotRoot + "/" + backupSSUuid + "/";

        synchronized (installPath.intern()) {
            command = new Script(false, "mkdir", _timeout, logger);
            command.add("-p");
            command.add(installFullPath);

            result = command.execute();
            if (result != null) {
                String msg = "unable to prepare template directory: " + installPath + ", storage: " + secStorageUrl + ", error msg: " + result;
                logger.error(msg);
                throw new Exception(msg);
            }
        }

        try {
            if (new File(snapshotFullOVAName).exists()) {
                command = new Script(false, "cp", wait, logger);
                command.add(snapshotFullOVAName);
                command.add(installFullOVAName);
                result = command.execute();
                if (result != null) {
                    String msg = "unable to copy snapshot " + snapshotFullOVAName + " to " + installFullPath;
                    logger.error(msg);
                    throw new Exception(msg);
                }

                // untar OVA file at template directory
                command = new Script("tar", wait, logger);
                command.add("--no-same-owner");
                command.add("-xf", installFullOVAName);
                command.setWorkDir(installFullPath);
                logger.info("Executing command: " + command.toString());
                result = command.execute();
                if (result != null) {
                    String msg = "unable to untar snapshot " + snapshotFullOVAName + " to " + installFullPath;
                    logger.error(msg);
                    throw new Exception(msg);
                }

            } else {  // there is no ova file, only ovf originally;
                if (new File(snapshotFullOvfName).exists()) {
                    command = new Script(false, "cp", wait, logger);
                    command.add(snapshotFullOvfName);
                    //command.add(installFullOvfName);
                    command.add(installFullPath);
                    result = command.execute();
                    if (result != null) {
                        String msg = "unable to copy snapshot " + snapshotFullOvfName + " to " + installFullPath;
                        logger.error(msg);
                        throw new Exception(msg);
                    }

                    logger.info("vmdkfile parent dir: " + snapshotRoot);
                    File snapshotdir = new File(snapshotRoot);
                    File[] ssfiles = snapshotdir.listFiles();
                    if (ssfiles == null) {
                        String msg = "unable to find snapshot vmdk files in " + snapshotRoot;
                        logger.error(msg);
                        throw new Exception(msg);
                    }
                    // List<String> filenames = new ArrayList<String>();
                    for (int i = 0; i < ssfiles.length; i++) {
                        String vmdkfile = ssfiles[i].getName();
                        logger.info("vmdk file name: " + vmdkfile);
                        if (vmdkfile.toLowerCase().startsWith(backupSSUuid) && vmdkfile.toLowerCase().endsWith(".vmdk")) {
                            snapshotFullVMDKName = snapshotRoot + File.separator + vmdkfile;
                            templateVMDKName += vmdkfile;
                            break;
                        }
                    }
                    if (snapshotFullVMDKName != null) {
                        command = new Script(false, "cp", wait, logger);
                        command.add(snapshotFullVMDKName);
                        command.add(installFullPath);
                        result = command.execute();
                        logger.info("Copy VMDK file: " + snapshotFullVMDKName);
                        if (result != null) {
                            String msg = "unable to copy snapshot vmdk file " + snapshotFullVMDKName + " to " + installFullPath;
                            logger.error(msg);
                            throw new Exception(msg);
                        }
                    }
                } else {
                    String msg = "unable to find any snapshot ova/ovf files" + snapshotFullOVAName + " to " + installFullPath;
                    logger.error(msg);
                    throw new Exception(msg);
                }
            }

            Size size = handleMetadataCreateTemplateFromSnapshot(installFullPath, templateVMDKName, templateId, templateUniqueName, backupSSUuid);

            return new Ternary<>(installPath + "/" + templateUniqueName + ".ova", size.getPhysicalSize(), size.getVirtualSize());
        } finally {
            // TODO, clean up left over files
        }
    }

    private class Size {
        private final long _physicalSize;
        private final long _virtualSize;

        Size(long physicalSize, long virtualSize) {
            _physicalSize = physicalSize;
            _virtualSize = virtualSize;
        }

        long getPhysicalSize() {
            return _physicalSize;
        }

        long getVirtualSize() {
            return _virtualSize;
        }
    }

    private Size handleMetadataCreateTemplateFromSnapshot(String installFullPath, String templateVMDKName, long templateId, String templateUniqueName,
                                                          String ovfFilename) throws Exception {
        long physicalSize = new File(installFullPath + "/" + templateVMDKName).length();

        OVAProcessor processor = new OVAProcessor();

        Map<String, Object> params = new HashMap<>();

        params.put(StorageLayer.InstanceConfigKey, _storage);

        processor.configure("OVA Processor", params);

        long virtualSize = processor.getTemplateVirtualSize(installFullPath, templateUniqueName);

        postCreatePrivateTemplate(installFullPath, templateId, templateUniqueName, physicalSize, virtualSize);

        writeMetaOvaForTemplate(installFullPath, ovfFilename + ".ovf", templateVMDKName, templateUniqueName, physicalSize);

        return new Size(physicalSize, virtualSize);
    }

    private void setUpManagedStorageCopyTemplateFromSnapshot(CopyCommand cmd) throws Exception {
        VmwareContext context = hostService.getServiceContext(cmd);
        VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, cmd);

        ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
        ClusterMO clusterMO = new ClusterMO(context, morCluster);

        List<Pair<ManagedObjectReference, String>> lstHosts = clusterMO.getClusterHosts();

        final Map<String, String> options = cmd.getOptions();

        final String storageHost = options.get(DiskTO.STORAGE_HOST);
        final int storagePortNumber = Integer.parseInt(options.get(DiskTO.STORAGE_PORT));
        final String iScsiName = options.get(DiskTO.IQN);
        final String snapshotPath = options.get(DiskTO.VMDK);
        final String chapInitiatorUsername = options.get(DiskTO.CHAP_INITIATOR_USERNAME);
        final String chapInitiatorSecret = options.get(DiskTO.CHAP_INITIATOR_SECRET);
        final String chapTargetUsername = options.get(DiskTO.CHAP_TARGET_USERNAME);
        final String chapTargetSecret = options.get(DiskTO.CHAP_TARGET_SECRET);

        String datastoreName = getManagedDatastoreNameFromPath(snapshotPath);

        HostDiscoveryMethod hostDiscoveryMethod = getHostDiscoveryMethod(context, storageHost, lstHosts);
        List<HostMO> hostsUsingStaticDiscovery = hostDiscoveryMethod.getHostsUsingStaticDiscovery();

        if (hostsUsingStaticDiscovery != null && hostsUsingStaticDiscovery.size() > 0) {
            final List<HostInternetScsiHbaStaticTarget> lstTargets = getTargets(storageHost, storagePortNumber, trimIqn(iScsiName),
                    chapInitiatorUsername, chapInitiatorSecret, chapTargetUsername, chapTargetSecret);

            addRemoveInternetScsiTargetsToAllHosts(true, lstTargets, hostsUsingStaticDiscovery);
        }

        rescanAllHosts(context, lstHosts, true, true);

        Pair<ManagedObjectReference, String> firstHost = lstHosts.get(0);
        HostMO firstHostMO = new HostMO(context, firstHost.first());
        HostDatastoreSystemMO firstHostDatastoreSystemMO = firstHostMO.getHostDatastoreSystemMO();
        ManagedObjectReference morDs = firstHostDatastoreSystemMO.findDatastoreByName(datastoreName);
        DatastoreMO datastoreMO = new DatastoreMO(context, morDs);

        mountVmfsDatastore(datastoreMO, lstHosts);
    }

    private void takeDownManagedStorageCopyTemplateFromSnapshot(CopyCommand cmd) throws Exception {
        VmwareContext context = hostService.getServiceContext(cmd);
        VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, cmd);

        ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
        ClusterMO clusterMO = new ClusterMO(context, morCluster);

        List<Pair<ManagedObjectReference, String>> lstHosts = clusterMO.getClusterHosts();

        final Map<String, String> options = cmd.getOptions();

        final String storageHost = options.get(DiskTO.STORAGE_HOST);
        final int storagePortNumber = Integer.parseInt(options.get(DiskTO.STORAGE_PORT));
        final String iScsiName = options.get(DiskTO.IQN);
        final String snapshotPath = options.get(DiskTO.VMDK);

        String datastoreName = getManagedDatastoreNameFromPath(snapshotPath);

        unmountVmfsDatastore(context, hyperHost, datastoreName, lstHosts);

        HostDiscoveryMethod hostDiscoveryMethod = getHostDiscoveryMethod(context, storageHost, lstHosts);
        List<HostMO> hostsUsingStaticDiscovery = hostDiscoveryMethod.getHostsUsingStaticDiscovery();

        if (hostsUsingStaticDiscovery != null && hostsUsingStaticDiscovery.size() > 0) {
            final List<HostInternetScsiHbaStaticTarget> lstTargets = getTargets(storageHost, storagePortNumber, trimIqn(iScsiName),
                    null, null, null, null);

            addRemoveInternetScsiTargetsToAllHosts(false, lstTargets, hostsUsingStaticDiscovery);

            rescanAllHosts(context, lstHosts, true, false);
        }
    }

    private void createTemplateFolder(String installPath, String installFullPath, NfsTO nfsSvr) {
        synchronized (installPath.intern()) {
            Script command = new Script(false, "mkdir", _timeout, logger);

            command.add("-p");
            command.add(installFullPath);

            String result = command.execute();

            if (result != null) {
                String secStorageUrl = nfsSvr.getUrl();
                String msg = "unable to prepare template directory: " + installPath + "; storage: " + secStorageUrl + "; error msg: " + result;

                logger.error(msg);

                throw new CloudRuntimeException(msg);
            }
        }
    }

    private void exportManagedStorageSnapshotToTemplate(CopyCommand cmd, String installFullPath, String snapshotPath, String exportName) throws Exception {
        DatastoreFile dsFile = new DatastoreFile(snapshotPath);

        VmwareContext context = hostService.getServiceContext(cmd);
        VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);

        ManagedObjectReference dsMor = hyperHost.findDatastoreByName(dsFile.getDatastoreName());
        DatastoreMO dsMo = new DatastoreMO(context, dsMor);
        String workerVMName = hostService.getWorkerName(context, cmd, 0, dsMo);

        VirtualMachineMO workerVM = HypervisorHostHelper.createWorkerVM(hyperHost, dsMo, workerVMName, null);

        if (workerVM == null) {
            throw new CloudRuntimeException("Failed to find the newly created worker VM: " + workerVMName);
        }

        workerVM.attachDisk(new String[]{snapshotPath}, dsMor);

        workerVM.exportVm(installFullPath, exportName, false, false);

        workerVM.detachAllDisksAndDestroy();
    }

    private String getTemplateVmdkName(String installFullPath, String exportName) {
        File templateDir = new File(installFullPath);
        File[] templateFiles = templateDir.listFiles();

        if (templateFiles == null) {
            String msg = "Unable to find template files in " + installFullPath;

            logger.error(msg);

            throw new CloudRuntimeException(msg);
        }

        for (int i = 0; i < templateFiles.length; i++) {
            String templateFile = templateFiles[i].getName();

            if (templateFile.toLowerCase().startsWith(exportName) && templateFile.toLowerCase().endsWith(".vmdk")) {
                return templateFile;
            }
        }

        throw new CloudRuntimeException("Unable to locate the template VMDK file");
    }

    private Answer handleManagedStorageCreateTemplateFromSnapshot(CopyCommand cmd, TemplateObjectTO template, NfsTO nfsSvr) {
        try {
            setUpManagedStorageCopyTemplateFromSnapshot(cmd);

            final Map<String, String> options = cmd.getOptions();

            String snapshotPath = options.get(DiskTO.VMDK);

            String secondaryMountPoint = mountService.getMountPoint(nfsSvr.getUrl(), _nfsVersion);
            String installPath = template.getPath();
            String installFullPath = secondaryMountPoint + "/" + installPath;

            createTemplateFolder(installPath, installFullPath, nfsSvr);

            String exportName = UUID.randomUUID().toString();

            exportManagedStorageSnapshotToTemplate(cmd, installFullPath, snapshotPath, exportName);

            String templateVmdkName = getTemplateVmdkName(installFullPath, exportName);

            String uniqueName = options.get(DiskTO.UUID);

            Size size = handleMetadataCreateTemplateFromSnapshot(installFullPath, templateVmdkName, template.getId(), uniqueName, exportName);

            TemplateObjectTO newTemplate = new TemplateObjectTO();

            newTemplate.setPath(installPath + "/" + uniqueName + ".ova");
            newTemplate.setPhysicalSize(size.getPhysicalSize());
            newTemplate.setSize(size.getVirtualSize());
            newTemplate.setFormat(ImageFormat.OVA);
            newTemplate.setName(uniqueName);

            return new CopyCmdAnswer(newTemplate);
        }
        catch (Exception ex) {
            String errMsg = "Problem creating a template from a snapshot for managed storage: " + ex.getMessage();

            logger.error(errMsg);

            throw new CloudRuntimeException(errMsg, ex);
        }
        finally {
            try {
                takeDownManagedStorageCopyTemplateFromSnapshot(cmd);
            }
            catch (Exception ex) {
                logger.warn("Unable to remove one or more static targets");
            }
        }
    }

    @Override
    public Answer createTemplateFromSnapshot(CopyCommand cmd) {
        String details;

        SnapshotObjectTO snapshot = (SnapshotObjectTO)cmd.getSrcTO();
        TemplateObjectTO template = (TemplateObjectTO)cmd.getDestTO();

        DataStoreTO imageStore = template.getDataStore();

        String uniqueName = UUID.randomUUID().toString();

        VmwareContext context = hostService.getServiceContext(cmd);

        try {
            if (!(imageStore instanceof NfsTO)) {
                return new CopyCmdAnswer("Creating a template from a snapshot is only supported when the destination store is NFS.");
            }

            NfsTO nfsSvr = (NfsTO)imageStore;

            if (snapshot.getDataStore() instanceof PrimaryDataStoreTO && template.getDataStore() instanceof NfsTO) {
                return handleManagedStorageCreateTemplateFromSnapshot(cmd, template, nfsSvr);
            }

            Ternary<String, Long, Long> result = createTemplateFromSnapshot(template.getPath(), uniqueName, nfsSvr.getUrl(), snapshot.getPath(), template.getId(),
                    cmd.getWait() * 1000, _nfsVersion);

            TemplateObjectTO newTemplate = new TemplateObjectTO();

            newTemplate.setPath(result.first());
            newTemplate.setPhysicalSize(result.second());
            newTemplate.setSize(result.third());
            newTemplate.setFormat(ImageFormat.OVA);
            newTemplate.setName(uniqueName);

            return new CopyCmdAnswer(newTemplate);
        } catch (Throwable e) {
            return new CopyCmdAnswer(hostService.createLogMessageException(e, cmd));
        }
    }

    // return Pair<String(divice bus name), String[](disk chain)>
    private Pair<String, String[]> exportVolumeToSecondaryStorage(VmwareContext context, VirtualMachineMO vmMo, VmwareHypervisorHost hyperHost, String volumePath, String secStorageUrl, String secStorageDir,
                                                                  String exportName, String workerVmName, String nfsVersion, boolean clonedWorkerVMNeeded) throws Exception {

        String secondaryMountPoint = mountService.getMountPoint(secStorageUrl, nfsVersion);
        String exportPath = secondaryMountPoint + "/" + secStorageDir + "/" + exportName;

        synchronized (exportPath.intern()) {
            if (!new File(exportPath).exists()) {
                Script command = new Script(false, "mkdir", _timeout, logger);
                command.add("-p");
                command.add(exportPath);
                String result = command.execute();
                if (result != null) {
                    String errorMessage = String.format("Unable to prepare snapshot backup directory: [%s] due to [%s].", exportPath, result);
                    logger.error(errorMessage);
                    throw new Exception(errorMessage);
                }
            }
        }

        VirtualMachineMO clonedVm = null;
        try {

            Pair<VirtualDisk, String> volumeDeviceInfo = vmMo.getDiskDevice(volumePath);
            if (volumeDeviceInfo == null) {
                String msg = "Unable to find related disk device for volume. volume path: " + volumePath;
                logger.error(msg);
                throw new Exception(msg);
            }

            String virtualHardwareVersion = String.valueOf(vmMo.getVirtualHardwareVersion());

            String diskDevice = volumeDeviceInfo.second();
            String disks[] = vmMo.getCurrentSnapshotDiskChainDatastorePaths(diskDevice);
            if (clonedWorkerVMNeeded) {
                // 4 MB is the minimum requirement for VM memory in VMware
                DatacenterMO dcMo = new DatacenterMO(context, hyperHost.getHyperHostDatacenter());
                ManagedObjectReference morPool = hyperHost.getHyperHostOwnerResourcePool();
                VirtualDisk requiredDisk = volumeDeviceInfo.first();
                clonedVm = vmMo.createFullCloneWithSpecificDisk(exportName, dcMo.getVmFolder(), morPool, requiredDisk);
                if (clonedVm == null) {
                    throw new Exception(String.format("Failed to clone VM with name %s during export volume operation", exportName));
                }
                vmMo = clonedVm;
            }

            vmMo.exportVm(exportPath, exportName, false, false);
            return new Pair<>(diskDevice, disks);
        } finally {
            if (clonedVm != null) {
                logger.debug(String.format("Destroying cloned VM: %s with its disks", clonedVm.getName()));
                clonedVm.destroy();
            }
        }
    }

    // Ternary<String(backup uuid in secondary storage), String(device bus name), String[](original disk chain in the snapshot)>
    private Ternary<String, String, String[]> backupSnapshotToSecondaryStorage(VmwareContext context, VirtualMachineMO vmMo, VmwareHypervisorHost hypervisorHost, String installPath, String volumePath, String snapshotUuid,
                                                                               String secStorageUrl, String prevSnapshotUuid, String prevBackupUuid, String workerVmName,
                                                                               String nfsVersion) throws Exception {

        String backupUuid = UUID.randomUUID().toString();
        Pair<String, String[]> snapshotInfo = exportVolumeToSecondaryStorage(context, vmMo, hypervisorHost, volumePath, secStorageUrl, installPath, backupUuid, workerVmName, nfsVersion, true);
        return new Ternary<>(backupUuid, snapshotInfo.first(), snapshotInfo.second());
    }

    @Override
    public Answer backupSnapshot(CopyCommand cmd) {
        SnapshotObjectTO srcSnapshot = (SnapshotObjectTO)cmd.getSrcTO();
        DataStoreTO primaryStore = srcSnapshot.getDataStore();
        SnapshotObjectTO destSnapshot = (SnapshotObjectTO)cmd.getDestTO();
        DataStoreTO destStore = destSnapshot.getDataStore();
        if (!(destStore instanceof NfsTO)) {
            return new CopyCmdAnswer("unsupported protocol");
        }

        NfsTO destNfsStore = (NfsTO)destStore;

        String secondaryStorageUrl = destNfsStore.getUrl();
        String snapshotUuid = srcSnapshot.getPath();
        String prevSnapshotUuid = srcSnapshot.getParentSnapshotPath();
        String prevBackupUuid = destSnapshot.getParentSnapshotPath();
        VirtualMachineMO workerVm = null;
        String workerVMName = null;
        String volumePath = srcSnapshot.getVolume().getPath();
        ManagedObjectReference morDs;
        DatastoreMO dsMo;

        // By default assume failure
        String details;
        boolean success;
        String snapshotBackupUuid;

        boolean hasOwnerVm = false;
        Ternary<String, String, String[]> backupResult = null;

        VmwareContext context = hostService.getServiceContext(cmd);
        VirtualMachineMO vmMo = null;
        String vmName = srcSnapshot.getVmName();
        try {
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, cmd);
            morDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, primaryStore.getUuid());

            CopyCmdAnswer answer = null;

            try {
                if (vmName != null) {
                    vmMo = hyperHost.findVmOnHyperHost(vmName);
                    if (vmMo == null) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Unable to find owner VM for BackupSnapshotCommand on host " + hyperHost.getHyperHostName() + ", will try within datacenter");
                        }
                        vmMo = hyperHost.findVmOnPeerHyperHost(vmName);
                    }
                }
                if (vmMo == null) {
                    dsMo = new DatastoreMO(hyperHost.getContext(), morDs);
                    workerVMName = hostService.getWorkerName(context, cmd, 0, dsMo);
                    vmMo = HypervisorHostHelper.createWorkerVM(hyperHost, dsMo, workerVMName, null);
                    if (vmMo == null) {
                        throw new Exception("Failed to find the newly create or relocated VM. vmName: " + workerVMName);
                    }
                    workerVm = vmMo;
                    // attach volume to worker VM
                    String datastoreVolumePath = VmwareStorageLayoutHelper.getLegacyDatastorePathFromVmdkFileName(dsMo, volumePath + ".vmdk");
                    vmMo.attachDisk(new String[] { datastoreVolumePath }, morDs);
                } else {
                    logger.info("Using owner VM " + vmName + " for snapshot operation");
                    hasOwnerVm = true;
                }

                logger.debug(String.format("Executing backup snapshot with UUID [%s] to secondary storage.", snapshotUuid));
                backupResult =
                        backupSnapshotToSecondaryStorage(context, vmMo, hyperHost, destSnapshot.getPath(), srcSnapshot.getVolume().getPath(), snapshotUuid, secondaryStorageUrl,
                                prevSnapshotUuid, prevBackupUuid, hostService.getWorkerName(context, cmd, 1, null), _nfsVersion);
                snapshotBackupUuid = backupResult.first();

                success = (snapshotBackupUuid != null);
                if (!success) {
                    details = "Failed to backUp the snapshot with uuid: " + snapshotUuid + " to secondary storage.";
                    answer = new CopyCmdAnswer(details);
                } else {
                    details = "Successfully backedUp the snapshot with Uuid: " + snapshotUuid + " to secondary storage.";

                    // Get snapshot physical size
                    long physicalSize = 0;
                    String secondaryMountPoint = mountService.getMountPoint(secondaryStorageUrl, _nfsVersion);
                    String snapshotDir =  destSnapshot.getPath() + "/" + snapshotBackupUuid;
                    File[] files = new File(secondaryMountPoint + "/" + snapshotDir).listFiles();
                    if (files != null) {
                        for(File file : files) {
                            String fileName = file.getName();
                            if (fileName.toLowerCase().startsWith(snapshotBackupUuid) && fileName.toLowerCase().endsWith(".vmdk")) {
                                physicalSize = new File(secondaryMountPoint + "/" + snapshotDir + "/" + fileName).length();
                                break;
                            }
                        }
                    }

                    SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
                    newSnapshot.setPath(snapshotDir + "/" + snapshotBackupUuid);
                    newSnapshot.setPhysicalSize(physicalSize);
                    answer = new CopyCmdAnswer(newSnapshot);
                }
            } finally {
                if (vmMo != null) {
                    ManagedObjectReference snapshotMor = vmMo.getSnapshotMor(snapshotUuid);
                    if (snapshotMor != null) {
                        vmMo.removeSnapshot(snapshotUuid, false);

                        // Snapshot operation may cause disk consolidation in VMware, when this happens
                        // we need to update CloudStack DB
                        //
                        // TODO: this post operation fixup is not atomic and not safe when management server stops
                        // in the middle
                        if (backupResult != null && hasOwnerVm) {
                            logger.info("Check if we have disk consolidation after snapshot operation");

                            boolean chainConsolidated = false;
                            for (String vmdkDsFilePath : backupResult.third()) {
                                logger.info("Validate disk chain file:" + vmdkDsFilePath);

                                if (vmMo.getDiskDevice(vmdkDsFilePath) == null) {
                                    logger.info("" + vmdkDsFilePath + " no longer exists, consolidation detected");
                                    chainConsolidated = true;
                                    break;
                                } else {
                                    logger.info("" + vmdkDsFilePath + " is found still in chain");
                                }
                            }

                            if (chainConsolidated) {
                                String topVmdkFilePath = null;
                                try {
                                    topVmdkFilePath = vmMo.getDiskCurrentTopBackingFileInChain(backupResult.second());
                                } catch (Exception e) {
                                    logger.error("Unexpected exception", e);
                                }

                                logger.info("Disk has been consolidated, top VMDK is now: " + topVmdkFilePath);
                                if (topVmdkFilePath != null) {
                                    DatastoreFile file = new DatastoreFile(topVmdkFilePath);

                                    SnapshotObjectTO snapshotInfo = (SnapshotObjectTO)answer.getNewData();
                                    VolumeObjectTO vol = new VolumeObjectTO();
                                    vol.setUuid(srcSnapshot.getVolume().getUuid());
                                    vol.setPath(file.getFileBaseName());
                                    snapshotInfo.setVolume(vol);
                                } else {
                                    logger.error("Disk has been consolidated, but top VMDK is not found ?!");
                                }
                            }
                        }
                    } else {
                        logger.info("No snapshots created to be deleted!");
                    }
                }

                try {
                    if (workerVm != null) {
                        workerVm.detachAllDisksAndDestroy();
                    }
                } catch (Throwable e) {
                    logger.warn(String.format("Failed to destroy worker VM [%s] due to: [%s]", workerVMName, e.getMessage()), e);
                }
            }

            return answer;
        } catch (Throwable e) {
            return new CopyCmdAnswer(hostService.createLogMessageException(e, cmd));
        }
    }

    @Override
    public Answer attachIso(AttachCommand cmd) {
        return this.attachIso(cmd.getDisk(), true, cmd.getVmName(), cmd.isForced());
    }

    @Override
    public Answer attachVolume(AttachCommand cmd) {
        Map<String, String> details = cmd.getDisk().getDetails();
        boolean isManaged = Boolean.parseBoolean(details.get(DiskTO.MANAGED));
        String iScsiName = details.get(DiskTO.IQN);
        String storageHost = details.get(DiskTO.STORAGE_HOST);
        int storagePort = Integer.parseInt(details.get(DiskTO.STORAGE_PORT));

        return this.attachVolume(cmd, cmd.getDisk(), true, isManaged, cmd.getVmName(), iScsiName, storageHost, storagePort, cmd.getControllerInfo());
    }

    private Answer attachVolume(Command cmd, DiskTO disk, boolean isAttach, boolean isManaged, String vmName, String iScsiName,
                                String storageHost, int storagePort, Map<String, String> controllerInfo) {
        VolumeObjectTO volumeTO = (VolumeObjectTO)disk.getData();
        DataStoreTO primaryStore = volumeTO.getDataStore();
        String volumePath = volumeTO.getPath();
        String storagePolicyId = volumeTO.getvSphereStoragePolicyId();

        String vmdkPath = isManaged ? resource.getVmdkPath(volumePath) : null;

        try {
            VmwareContext context = hostService.getServiceContext(null);
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);
            VirtualMachineMO vmMo = hyperHost.findVmOnHyperHost(vmName);

            if (vmMo == null) {
                vmMo = hyperHost.findVmOnPeerHyperHost(vmName);

                if (vmMo == null) {
                    String msg = "Unable to find the VM to execute AttachCommand, vmName: " + vmName;

                    logger.error(msg);

                    throw new Exception(msg);
                }
            }

            vmName = vmMo.getName();

            ManagedObjectReference morDs;
            String diskUuid =  volumeTO.getUuid().replace("-", "");

            if (isAttach && isManaged) {
                Map<String, String> details = disk.getDetails();

                morDs = prepareManagedStorage(context, hyperHost, diskUuid, iScsiName, storageHost, storagePort, vmdkPath,
                            details.get(DiskTO.CHAP_INITIATOR_USERNAME), details.get(DiskTO.CHAP_INITIATOR_SECRET),
                            details.get(DiskTO.CHAP_TARGET_USERNAME), details.get(DiskTO.CHAP_TARGET_SECRET),
                            volumeTO.getSize(), cmd);
            }
            else {
                if (storagePort == DEFAULT_NFS_PORT) {
                    morDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, isManaged ? VmwareResource.getDatastoreName(diskUuid) : primaryStore.getUuid());
                } else {
                    morDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, isManaged ? VmwareResource.getDatastoreName(iScsiName) : primaryStore.getUuid());
                }
            }

            if (morDs == null) {
                String msg = "Unable to find the mounted datastore to execute AttachCommand, vmName: " + vmName;
                logger.error(msg);
                throw new Exception(msg);
            }

            DatastoreMO dsMo = new DatastoreMO(context, morDs);
            String datastoreVolumePath;
            boolean datastoreChangeObserved = false;
            boolean volumePathChangeObserved = false;
            String chainInfo = null;

            if (isAttach) {
                if (isManaged) {
                    datastoreVolumePath = dsMo.getDatastorePath((vmdkPath != null ? vmdkPath : dsMo.getName()) + ".vmdk");
                } else {
                    if (dsMo.getDatastoreType().equalsIgnoreCase("VVOL")) {
                        datastoreVolumePath = VmwareStorageLayoutHelper.getDatastoreVolumePath(dsMo, vmName, volumePath);
                    } else {
                        datastoreVolumePath = VmwareStorageLayoutHelper.syncVolumeToVmDefaultFolder(dsMo.getOwnerDatacenter().first(), vmName, dsMo, volumePath, VmwareManager.s_vmwareSearchExcludeFolder.value());
                    }
                }
            } else {
                if (isManaged) {
                    datastoreVolumePath = dsMo.getDatastorePath((vmdkPath != null ? vmdkPath : dsMo.getName()) + ".vmdk");
                } else {
                    String datastoreUUID = primaryStore.getUuid();
                    Pair<Boolean, Boolean> changes = getSyncedVolume(vmMo, context, hyperHost, disk, volumeTO);
                    volumePathChangeObserved = changes.first();
                    datastoreChangeObserved = changes.second();
                    if (datastoreChangeObserved) {
                        datastoreUUID = volumeTO.getDataStoreUuid();
                    }
                    if (volumePathChangeObserved) {
                        volumePath = volumeTO.getPath();
                    }
                    if ((volumePathChangeObserved || datastoreChangeObserved) && StringUtils.isNotEmpty(volumeTO.getChainInfo())) {
                        chainInfo = volumeTO.getChainInfo();
                    }
                    if (storagePort == DEFAULT_NFS_PORT) {
                        morDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, isManaged ? VmwareResource.getDatastoreName(diskUuid) : datastoreUUID);
                    } else {
                        morDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, isManaged ? VmwareResource.getDatastoreName(iScsiName) : datastoreUUID);
                    }
                    dsMo = new DatastoreMO(context, morDs);

                    datastoreVolumePath = VmwareStorageLayoutHelper.getDatastoreVolumePath(dsMo, vmName, volumePath);
                }
            }

            disk.setPath(datastoreVolumePath);

            AttachAnswer answer = new AttachAnswer(disk);

            if (isAttach) {
                String rootDiskControllerDetail = DiskControllerType.ide.toString();
                if (controllerInfo != null && StringUtils.isNotEmpty(controllerInfo.get(VmDetailConstants.ROOT_DISK_CONTROLLER))) {
                    rootDiskControllerDetail = controllerInfo.get(VmDetailConstants.ROOT_DISK_CONTROLLER);
                }
                String dataDiskControllerDetail = getLegacyVmDataDiskController();
                if (controllerInfo != null && StringUtils.isNotEmpty(controllerInfo.get(VmDetailConstants.DATA_DISK_CONTROLLER))) {
                    dataDiskControllerDetail = controllerInfo.get(VmDetailConstants.DATA_DISK_CONTROLLER);
                }

                VmwareHelper.validateDiskControllerDetails(rootDiskControllerDetail, dataDiskControllerDetail);
                Pair<String, String> chosenDiskControllers = VmwareHelper.chooseRequiredDiskControllers(new Pair<>(rootDiskControllerDetail, dataDiskControllerDetail), vmMo, null, null);
                String diskController = VmwareHelper.getControllerBasedOnDiskType(chosenDiskControllers, disk);

                vmMo.attachDisk(new String[] { datastoreVolumePath }, morDs, diskController, storagePolicyId, volumeTO.getIopsReadRate() + volumeTO.getIopsWriteRate());
                VirtualMachineDiskInfoBuilder diskInfoBuilder = vmMo.getDiskInfoBuilder();
                VirtualMachineDiskInfo diskInfo = diskInfoBuilder.getDiskInfoByBackingFileBaseName(volumePath, dsMo.getName());
                chainInfo = _gson.toJson(diskInfo);

                answer.setContextParam("vdiskUuid",vmMo.getExternalDiskUUID(datastoreVolumePath));

                if (isManaged) {
                    expandVirtualDisk(vmMo, datastoreVolumePath, volumeTO.getSize());
                }
            } else {
                vmMo.removeAllSnapshots();
                vmMo.detachDisk(datastoreVolumePath, false);

                if (isManaged) {
                    handleDatastoreAndVmdkDetachManaged(cmd, diskUuid, iScsiName, storageHost, storagePort);
                } else {
                    if (!dsMo.getDatastoreType().equalsIgnoreCase("VVOL")) {
                        VmwareStorageLayoutHelper.syncVolumeToRootFolder(dsMo.getOwnerDatacenter().first(), dsMo, volumePath, vmName, VmwareManager.s_vmwareSearchExcludeFolder.value());
                    }
                }
                if (datastoreChangeObserved) {
                    answer.setContextParam("datastoreName", dsMo.getCustomFieldValue(CustomFieldConstants.CLOUD_UUID));
                }

                if (volumePathChangeObserved) {
                    answer.setContextParam("volumePath", volumePath);
                }
            }

            if (chainInfo != null && !chainInfo.isEmpty())
                answer.setContextParam("chainInfo", chainInfo);

            return answer;
        } catch (Throwable e) {
            String msg = String.format("Failed to %s volume!", isAttach? "attach" : "detach");
            logger.error(msg, e);
            hostService.createLogMessageException(e, cmd);
            // Sending empty error message - too many duplicate errors in UI
            return new AttachAnswer("");
        }
    }

    private VirtualMachineDiskInfo getMatchingExistingDisk(VmwareHypervisorHost hyperHost, VmwareContext context, VirtualMachineMO vmMo, DiskTO vol)
            throws Exception {
        VirtualMachineDiskInfoBuilder diskInfoBuilder = vmMo.getDiskInfoBuilder();
        if (diskInfoBuilder != null) {
            VolumeObjectTO volume = (VolumeObjectTO) vol.getData();

            ManagedObjectReference morDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, volume.getDataStore().getUuid());
            DatastoreMO dsMo = new DatastoreMO(context, morDs);

            String dsName = dsMo.getName();

            String diskBackingFileBaseName = volume.getPath();

            VirtualMachineDiskInfo diskInfo = diskInfoBuilder.getDiskInfoByBackingFileBaseName(diskBackingFileBaseName, dsName);
            if (diskInfo != null) {
                logger.info("Found existing disk info from volume path: " + volume.getPath());
                return diskInfo;
            } else {
                String chainInfo = volume.getChainInfo();
                if (chainInfo != null) {
                    VirtualMachineDiskInfo infoInChain = _gson.fromJson(chainInfo, VirtualMachineDiskInfo.class);
                    if (infoInChain != null) {
                        String[] disks = infoInChain.getDiskChain();
                        if (disks.length > 0) {
                            for (String diskPath : disks) {
                                DatastoreFile file = new DatastoreFile(diskPath);
                                diskInfo = diskInfoBuilder.getDiskInfoByBackingFileBaseName(file.getFileBaseName(), dsName);
                                if (diskInfo != null) {
                                    logger.info("Found existing disk from chain info: " + diskPath);
                                    return diskInfo;
                                }
                            }
                        }

                        if (diskInfo == null) {
                            diskInfo = diskInfoBuilder.getDiskInfoByDeviceBusName(infoInChain.getDiskDeviceBusName());
                            if (diskInfo != null) {
                                logger.info("Found existing disk from chain device bus information: " + infoInChain.getDiskDeviceBusName());
                                return diskInfo;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private DatastoreMO getDiskDatastoreMofromVM(VmwareHypervisorHost hyperHost, VmwareContext context,
                                                 VirtualMachineMO vmMo, DiskTO disk, VirtualMachineDiskInfoBuilder diskInfoBuilder) throws Exception {
        assert (hyperHost != null) && (context != null);
        List<Pair<Integer, ManagedObjectReference>> diskDatastores = vmMo.getAllDiskDatastores();
        VolumeObjectTO volume = (VolumeObjectTO) disk.getData();
        String diskBackingFileBaseName = volume.getPath();
        for (Pair<Integer, ManagedObjectReference> diskDatastore : diskDatastores) {
            DatastoreMO dsMo = new DatastoreMO(hyperHost.getContext(), diskDatastore.second());
            String dsName = dsMo.getName();

            VirtualMachineDiskInfo diskInfo = diskInfoBuilder.getDiskInfoByBackingFileBaseName(diskBackingFileBaseName, dsName);
            if (diskInfo != null) {
                logger.info("Found existing disk info from volume path: " + volume.getPath());
                return dsMo;
            } else {
                String chainInfo = volume.getChainInfo();
                if (chainInfo != null) {
                    VirtualMachineDiskInfo infoInChain = _gson.fromJson(chainInfo, VirtualMachineDiskInfo.class);
                    if (infoInChain != null) {
                        String[] disks = infoInChain.getDiskChain();
                        if (disks.length > 0) {
                            for (String diskPath : disks) {
                                DatastoreFile file = new DatastoreFile(diskPath);
                                diskInfo = diskInfoBuilder.getDiskInfoByBackingFileBaseName(file.getFileBaseName(), dsName);
                                if (diskInfo != null) {
                                    logger.info("Found existing disk from chain info: " + diskPath);
                                    return dsMo;
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean expandVirtualDisk(VirtualMachineMO vmMo, String datastoreVolumePath, long currentSizeInBytes) throws Exception {
        long currentSizeInKB = currentSizeInBytes / 1024;

        Pair<VirtualDisk, String> vDiskPair = vmMo.getDiskDevice(datastoreVolumePath);

        VirtualDisk vDisk = vDiskPair.first();

        if (vDisk.getCapacityInKB() < currentSizeInKB) {
            // IDE virtual disk cannot be re-sized if VM is running
            if (vDiskPair.second() != null && vDiskPair.second().contains("ide")) {
                throw new Exception("Re-sizing a virtual disk over an IDE controller is not supported in VMware hypervisor. " +
                        "Please re-try when virtual disk is attached to a VM using a SCSI controller.");
            }

            String vmdkAbsFile = VmwareHelper.getAbsoluteVmdkFile(vDisk);

            if (vmdkAbsFile != null && !vmdkAbsFile.isEmpty()) {
                vmMo.updateAdapterTypeIfRequired(vmdkAbsFile);
            }

            vDisk.setCapacityInKB(currentSizeInKB);

            VirtualDeviceConfigSpec deviceConfigSpec = new VirtualDeviceConfigSpec();

            deviceConfigSpec.setDevice(vDisk);
            deviceConfigSpec.setOperation(VirtualDeviceConfigSpecOperation.EDIT);

            VirtualMachineConfigSpec vmConfigSpec = new VirtualMachineConfigSpec();

            vmConfigSpec.getDeviceChange().add(deviceConfigSpec);

            if (!vmMo.configureVm(vmConfigSpec)) {
                throw new Exception("Failed to configure VM to resize disk. vmName: " + vmMo.getName());
            }

            return true;
        }

        return false;
    }

    private String getSecondaryDatastoreUUID(String storeUrl) {
        String uuid = null;
        try{
            uuid=UUID.nameUUIDFromBytes(storeUrl.getBytes("UTF-8")).toString();
        }catch(UnsupportedEncodingException e){
            logger.warn("Failed to create UUID from string " + storeUrl + ". Bad storeUrl or UTF-8 encoding error." );
        }
        return uuid;
    }

    private synchronized ManagedObjectReference prepareSecondaryDatastoreOnHost(String storeUrl) throws Exception {
        String storeName = getSecondaryDatastoreUUID(storeUrl);
        URI uri = new URI(storeUrl);

        VmwareHypervisorHost hyperHost = hostService.getHyperHost(hostService.getServiceContext(null), null);
        ManagedObjectReference morDatastore = hyperHost.mountDatastore(false, uri.getHost(), 0, uri.getPath(), storeName.replace("-", ""), false);

        if (morDatastore == null) {
            throw new Exception("Unable to mount secondary storage on host. storeUrl: " + storeUrl);
        }

        return morDatastore;
    }

    private Answer attachIso(DiskTO disk, boolean isAttach, String vmName, boolean force) {
        try {
            VmwareContext context = hostService.getServiceContext(null);
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);
            VirtualMachineMO vmMo = HypervisorHostHelper.findVmOnHypervisorHostOrPeer(hyperHost, vmName);
            if (vmMo == null) {
                String msg = "Unable to find VM in vSphere to execute AttachIsoCommand, vmName: " + vmName;
                logger.error(msg);
                throw new Exception(msg);
            }
            TemplateObjectTO iso = (TemplateObjectTO)disk.getData();
            NfsTO nfsImageStore = (NfsTO)iso.getDataStore();
            String storeUrl = null;
            if (nfsImageStore != null) {
                storeUrl = nfsImageStore.getUrl();
            }
            if (storeUrl == null) {
                if (!iso.getName().equalsIgnoreCase(TemplateManager.VMWARE_TOOLS_ISO)) {
                    String msg = "ISO store root url is not found in AttachIsoCommand";
                    logger.error(msg);
                    throw new Exception(msg);
                } else {
                    if (isAttach) {
                        vmMo.mountToolsInstaller();
                    } else {
                        try{
                            if (!vmMo.unmountToolsInstaller()) {
                                return new AttachAnswer("Failed to unmount vmware-tools installer ISO as the corresponding CDROM device is locked by VM. Please unmount the CDROM device inside the VM and ret-try.");
                            }
                        } catch(Throwable e){
                            vmMo.detachIso(null, force);
                        }
                    }

                    return new AttachAnswer(disk);
                }
            }

            ManagedObjectReference morSecondaryDs = prepareSecondaryDatastoreOnHost(storeUrl);
            String isoPath = nfsImageStore.getUrl() + File.separator + iso.getPath();
            if (!isoPath.startsWith(storeUrl)) {
                assert (false);
                String msg = "ISO path does not start with the secondary storage root";
                logger.error(msg);
                throw new Exception(msg);
            }

            int isoNameStartPos = isoPath.lastIndexOf('/');
            String isoFileName = isoPath.substring(isoNameStartPos + 1);
            String isoStorePathFromRoot = isoPath.substring(storeUrl.length() + 1, isoNameStartPos);

            // TODO, check if iso is already attached, or if there is a previous
            // attachment
            DatastoreMO secondaryDsMo = new DatastoreMO(context, morSecondaryDs);
            String storeName = secondaryDsMo.getName();
            String isoDatastorePath = String.format("[%s] %s/%s", storeName, isoStorePathFromRoot, isoFileName);

            if (isAttach) {
                vmMo.attachIso(isoDatastorePath, morSecondaryDs, true, false, force);
            } else {
                vmMo.detachIso(isoDatastorePath, force);
            }

            return new AttachAnswer(disk);
        } catch (Throwable e) {
            if (e instanceof RemoteException) {
                logger.warn("Encounter remote exception to vCenter, invalidate VMware session context");
                hostService.invalidateServiceContext(null);
            }

            String message = String.format("AttachIsoCommand(%s) failed due to: [%s]. Also check if your guest os is a supported version", isAttach? "attach" : "detach", VmwareHelper.getExceptionMessage(e));
            logger.error(message, e);
            return new AttachAnswer(message);
        }
    }

    @Override
    public Answer dettachIso(DettachCommand cmd) {
        return this.attachIso(cmd.getDisk(), false, cmd.getVmName(), cmd.isForced());
    }

    @Override
    public Answer dettachVolume(DettachCommand cmd) {
        return this.attachVolume(cmd, cmd.getDisk(), false, cmd.isManaged(), cmd.getVmName(), cmd.get_iScsiName(), cmd.getStorageHost(), cmd.getStoragePort(), null);
    }

    @Override
    public Answer createVolume(CreateObjectCommand cmd) {
        logger.debug(LogUtils.logGsonWithoutException("Executing CreateObjectCommand cmd: [%s].", cmd));
        VolumeObjectTO volume = (VolumeObjectTO)cmd.getData();
        DataStoreTO primaryStore = volume.getDataStore();
        String vSphereStoragePolicyId = volume.getvSphereStoragePolicyId();

        try {
            VmwareContext context = hostService.getServiceContext(null);
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);
            DatacenterMO dcMo = new DatacenterMO(context, hyperHost.getHyperHostDatacenter());

            ManagedObjectReference morDatastore = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, primaryStore.getUuid());
            if (morDatastore == null) {
                throw new CloudRuntimeException(String.format("Unable to find datastore [%s] in vSphere.", primaryStore.getUuid()));
            }

            DatastoreMO dsMo = new DatastoreMO(context, morDatastore);
            // create data volume
            VirtualMachineMO vmMo = null;
            String volumeUuid = UUID.randomUUID().toString().replace("-", "");

            String volumeDatastorePath = VmwareStorageLayoutHelper.getDatastorePathBaseFolderFromVmdkFileName(dsMo, volumeUuid + ".vmdk");
            VolumeObjectTO newVol = new VolumeObjectTO();

            try {
                VirtualStorageObjectManagerMO vStorageObjectManagerMO = new VirtualStorageObjectManagerMO(context);
                VStorageObject virtualDisk = vStorageObjectManagerMO.createDisk(morDatastore, volume.getProvisioningType(), volume.getSize(), volumeDatastorePath, volumeUuid);
                DatastoreFile file = new DatastoreFile(((BaseConfigInfoDiskFileBackingInfo)virtualDisk.getConfig().getBacking()).getFilePath());
                newVol.setPath(file.getFileBaseName());
                newVol.setSize(volume.getSize());
            } catch (Exception e) {
                logger.error(String.format("Create disk using vStorageObject manager failed due to [%s], retrying using worker VM.", e.getMessage()), e);
                String dummyVmName = hostService.getWorkerName(context, cmd, 0, dsMo);
                try {
                    logger.info(String.format("Creating worker VM [%s].", dummyVmName));
                    vmMo = HypervisorHostHelper.createWorkerVM(hyperHost, dsMo, dummyVmName, null);
                    if (vmMo == null) {
                        throw new CloudRuntimeException("Unable to create a dummy VM for volume creation.");
                    }

                    synchronized (this) {
                        try {
                            vmMo.createDisk(volumeDatastorePath, (int)(volume.getSize() / (1024L * 1024L)), morDatastore, vmMo.getScsiDeviceControllerKey(), vSphereStoragePolicyId);
                            vmMo.detachDisk(volumeDatastorePath, false);
                        }
                        catch (Exception e1) {
                            logger.error(String.format("Deleting file [%s] due to [%s].", volumeDatastorePath, e1.getMessage()), e1);
                            VmwareStorageLayoutHelper.deleteVolumeVmdkFiles(dsMo, volumeUuid, dcMo, VmwareManager.s_vmwareSearchExcludeFolder.value());
                            throw new CloudRuntimeException(String.format("Unable to create volume due to [%s].", e1.getMessage()));
                        }
                    }

                    newVol = new VolumeObjectTO();
                    newVol.setPath(volumeUuid);
                    newVol.setSize(volume.getSize());
                    return new CreateObjectAnswer(newVol);
                } finally {
                    logger.info("Destroying dummy VM after volume creation.");
                    if (vmMo != null) {
                        vmMo.detachAllDisksAndDestroy();
                    }
                }
            }
            return new CreateObjectAnswer(newVol);
        } catch (Throwable e) {
            return new CreateObjectAnswer(hostService.createLogMessageException(e, cmd));
        }
    }

    @Override
    public Answer createSnapshot(CreateObjectCommand cmd) {
        // snapshot operation (create or destroy) is handled inside BackupSnapshotCommand(), we just fake
        // a success return here
        String snapshotUUID = UUID.randomUUID().toString();
        SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
        newSnapshot.setPath(snapshotUUID);
        return new CreateObjectAnswer(newSnapshot);
    }

    // format: [datastore_name] file_name.vmdk (the '[' and ']' chars should only be used to denote the datastore)
    private String getManagedDatastoreNameFromPath(String path) {
        int lastIndexOf = path.lastIndexOf("]");

        return path.substring(1, lastIndexOf);
    }

    @Override
    public Answer deleteVolume(DeleteCommand cmd) {
        try {
            VmwareContext context = hostService.getServiceContext(null);
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);
            VolumeObjectTO vol = (VolumeObjectTO)cmd.getData();
            DataStoreTO store = vol.getDataStore();
            PrimaryDataStoreTO primaryDataStoreTO = (PrimaryDataStoreTO)store;

            Map<String, String> details = primaryDataStoreTO.getDetails();
            boolean isManaged = false;
            String managedDatastoreName = null;

            if (details != null) {
                isManaged = Boolean.parseBoolean(details.get(PrimaryDataStoreTO.MANAGED));

                if (isManaged) {
                    managedDatastoreName = getManagedDatastoreNameFromPath(vol.getPath());
                }
            }

            ManagedObjectReference morDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost,
                    isManaged ? managedDatastoreName : store.getUuid());

            if (morDs == null) {
                String msg = "Unable to find datastore based on volume mount point " + store.getUuid();
                logger.error(msg);
                throw new Exception(msg);
            }

            DatastoreMO dsMo = new DatastoreMO(context, morDs);

            ManagedObjectReference morDc = hyperHost.getHyperHostDatacenter();
            DatacenterMO dcMo = new DatacenterMO(context, morDc);

            ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
            ClusterMO clusterMo = new ClusterMO(context, morCluster);

            if (vol.getVolumeType() == Volume.Type.ROOT) {

                String vmName = vol.getVmName();
                if (vmName != null) {
                    VirtualMachineMO vmMo = clusterMo.findVmOnHyperHost(vmName);
                    if (vmMo == null) {
                        // Volume might be on a zone-wide storage pool, look for VM in datacenter
                        vmMo = dcMo.findVm(vmName);
                    }

                    List<Map<String, String>> dynamicTargetsToRemove = null;

                    boolean deployAsIs = vol.isDeployAsIs();
                    if (vmMo != null) {
                        if (logger.isInfoEnabled()) {
                            if (deployAsIs) {
                                logger.info(String.format("Destroying root volume %s of deploy-as-is VM %s", vol, vmName));
                            } else {
                                logger.info("Destroy root volume and VM itself. vmName " + vmName);
                            }
                        }

                        VirtualMachineDiskInfo diskInfo = null;
                        if (vol.getChainInfo() != null)
                            diskInfo = _gson.fromJson(vol.getChainInfo(), VirtualMachineDiskInfo.class);

                        HostMO hostMo = vmMo.getRunningHost();
                        List<NetworkDetails> networks = vmMo.getNetworksWithDetails();

                        // tear down all devices first before we destroy the VM to avoid accidentally delete disk backing files
                        if (VmwareResource.getVmState(vmMo) != PowerState.PowerOff) {
                            vmMo.safePowerOff(_shutdownWaitMs);
                        }

                        // call this before calling detachAllDisksExcept
                        // when expunging a VM, we need to see if any of its disks are serviced by managed storage
                        // if there is one or more disk serviced by managed storage, remove the iSCSI connection(s)
                        // don't remove the iSCSI connection(s) until the supported disk(s) is/are removed from the VM
                        // (removeManagedTargetsFromCluster should be called after detachAllDisksExcept and vm.destroy)
                        List<VirtualDisk> virtualDisks = vmMo.getVirtualDisks();
                        List<String> managedDatastoreNames = getManagedDatastoreNamesFromVirtualDisks(virtualDisks);

                        // Preserve other disks of the VM
                        List<String> detachedDisks = vmMo.detachAllDisksExcept(vol.getPath(), diskInfo != null ? diskInfo.getDiskDeviceBusName() : null);
                        VmwareStorageLayoutHelper.moveVolumeToRootFolder(new DatacenterMO(context, morDc), detachedDisks);
                        // let vmMo.destroy to delete volume for us
                        // vmMo.tearDownDevices(new Class<?>[] { VirtualDisk.class, VirtualEthernetCard.class });
                        if (isManaged) {
                            vmMo.unregisterVm();
                        } else {
                            vmMo.destroy();
                        }

                        // this.hostService.handleDatastoreAndVmdkDetach(iScsiName, storageHost, storagePort);
                        if (managedDatastoreNames != null && !managedDatastoreNames.isEmpty()) {
                            removeManagedTargetsFromCluster(managedDatastoreNames);
                        }

                        for (NetworkDetails netDetails : networks) {
                            if (netDetails.getGCTag() != null && netDetails.getGCTag().equalsIgnoreCase("true")) {
                                if (netDetails.getVMMorsOnNetwork() == null || netDetails.getVMMorsOnNetwork().length == 1) {
                                    resource.cleanupNetwork(dcMo, netDetails);
                                }
                            }
                        }
                    } else if (deployAsIs) {
                        if (logger.isInfoEnabled()) {
                            logger.info(String.format("Destroying root volume %s of already removed deploy-as-is VM %s", vol, vmName));
                        }
                        // The disks of the deploy-as-is VM have been detached from the VM and moved to root folder
                        String deployAsIsRootDiskPath = dsMo.searchFileInSubFolders(vol.getPath() + VmwareResource.VMDK_EXTENSION,
                                true, null);
                        if (StringUtils.isNotBlank(deployAsIsRootDiskPath)) {
                            if (logger.isInfoEnabled()) {
                                logger.info("Removing disk " + deployAsIsRootDiskPath);
                            }
                            dsMo.deleteFile(deployAsIsRootDiskPath, morDc, true);
                            String deltaFilePath = dsMo.searchFileInSubFolders(vol.getPath() + "-delta" + VmwareResource.VMDK_EXTENSION,
                                    true, null);
                            if (StringUtils.isNotBlank(deltaFilePath)) {
                                dsMo.deleteFile(deltaFilePath, morDc, true);
                            }
                        }
                    }

                    /*
                    if (logger.isInfoEnabled()) {
                        logger.info("Destroy volume by original name: " + vol.getPath() + ".vmdk");
                    }

                    VmwareStorageLayoutHelper.deleteVolumeVmdkFiles(dsMo, vol.getPath(), new DatacenterMO(context, morDc));
                     */

                    return new Answer(cmd, true, "");
                }

                if (logger.isInfoEnabled()) {
                    logger.info("Destroy root volume directly from datastore");
                }
            }

            if (!isManaged) {
                VmwareStorageLayoutHelper.deleteVolumeVmdkFiles(dsMo, vol.getPath(), new DatacenterMO(context, morDc), VmwareManager.s_vmwareSearchExcludeFolder.value());
            }

            return new Answer(cmd, true, "Success");
        } catch (Throwable e) {
            return new Answer(cmd, false, hostService.createLogMessageException(e, cmd));
        }
    }

    public ManagedObjectReference prepareManagedDatastore(VmwareContext context, VmwareHypervisorHost hyperHost, String datastoreName,
                                                          String iScsiName, String storageHost, int storagePort) throws Exception {
        return getVmfsDatastore(context, hyperHost, datastoreName, storageHost, storagePort, trimIqn(iScsiName), null, null, null, null);
    }

    private ManagedObjectReference prepareManagedDatastore(VmwareContext context, VmwareHypervisorHost hyperHost, String diskUuid, String iScsiName,
                                                           String storageHost, int storagePort, String chapInitiatorUsername, String chapInitiatorSecret,
                                                           String chapTargetUsername, String chapTargetSecret) throws Exception {
        if (storagePort == DEFAULT_NFS_PORT) {
            logger.info("creating the NFS datastore with the following configuration - storageHost: " + storageHost + ", storagePort: " + storagePort +
                    ", exportpath: " + iScsiName + "and diskUuid : " + diskUuid);
            ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
            ClusterMO cluster = new ClusterMO(context, morCluster);
            List<Pair<ManagedObjectReference, String>> lstHosts = cluster.getClusterHosts();

            HostMO host = new HostMO(context, lstHosts.get(0).first());
            HostDatastoreSystemMO hostDatastoreSystem = host.getHostDatastoreSystemMO();

            return hostDatastoreSystem.createNfsDatastore(storageHost, storagePort, iScsiName, diskUuid);
        } else {
            return getVmfsDatastore(context, hyperHost, VmwareResource.getDatastoreName(iScsiName), storageHost, storagePort,
                    trimIqn(iScsiName), chapInitiatorUsername, chapInitiatorSecret, chapTargetUsername, chapTargetSecret);
        }
    }

    private List<HostInternetScsiHbaStaticTarget> getTargets(String storageIpAddress, int storagePortNumber, String iqn,
                                                             String chapName, String chapSecret, String mutualChapName, String mutualChapSecret) {
        HostInternetScsiHbaStaticTarget target = new HostInternetScsiHbaStaticTarget();

        target.setAddress(storageIpAddress);
        target.setPort(storagePortNumber);
        target.setIScsiName(iqn);

        if (StringUtils.isNoneBlank(chapName, chapSecret)) {
            HostInternetScsiHbaAuthenticationProperties auth = new HostInternetScsiHbaAuthenticationProperties();

            String strAuthType = "chapRequired";

            auth.setChapAuthEnabled(true);
            auth.setChapInherited(false);
            auth.setChapAuthenticationType(strAuthType);
            auth.setChapName(chapName);
            auth.setChapSecret(chapSecret);

            if (StringUtils.isNoneBlank(mutualChapName, mutualChapSecret)) {
                auth.setMutualChapInherited(false);
                auth.setMutualChapAuthenticationType(strAuthType);
                auth.setMutualChapName(mutualChapName);
                auth.setMutualChapSecret(mutualChapSecret);
            }

            target.setAuthenticationProperties(auth);
        }

        final List<HostInternetScsiHbaStaticTarget> lstTargets = new ArrayList<>();

        lstTargets.add(target);

        return lstTargets;
    }

    private class HostDiscoveryMethod {
        private final List<HostMO> hostsUsingDynamicDiscovery;
        private final List<HostMO> hostsUsingStaticDiscovery;

        HostDiscoveryMethod(List<HostMO> hostsUsingDynamicDiscovery, List<HostMO> hostsUsingStaticDiscovery) {
            this.hostsUsingDynamicDiscovery = hostsUsingDynamicDiscovery;
            this.hostsUsingStaticDiscovery = hostsUsingStaticDiscovery;
        }

        List<HostMO> getHostsUsingDynamicDiscovery() {
            return hostsUsingDynamicDiscovery;
        }

        List<HostMO> getHostsUsingStaticDiscovery() {
            return hostsUsingStaticDiscovery;
        }
    }

    private HostDiscoveryMethod getHostDiscoveryMethod(VmwareContext context, String address,
                                                       List<Pair<ManagedObjectReference, String>> hostPairs) throws Exception {
        List<HostMO> hosts = new ArrayList<>();

        for (Pair<ManagedObjectReference, String> hostPair : hostPairs) {
            HostMO host = new HostMO(context, hostPair.first());

            hosts.add(host);
        }

        return getHostDiscoveryMethod(address, hosts);
    }

    private HostDiscoveryMethod getHostDiscoveryMethod(String address, List<HostMO> lstHosts) throws Exception {
        List<HostMO> hostsUsingDynamicDiscovery = new ArrayList<>();
        List<HostMO> hostsUsingStaticDiscovery = new ArrayList<>();

        for (HostMO host : lstHosts) {
            boolean usingDynamicDiscovery = false;

            HostStorageSystemMO hostStorageSystem = host.getHostStorageSystemMO();

            for (HostHostBusAdapter hba : hostStorageSystem.getStorageDeviceInfo().getHostBusAdapter()) {
                if (hba instanceof HostInternetScsiHba) {
                    HostInternetScsiHba hostInternetScsiHba = (HostInternetScsiHba)hba;

                    if (hostInternetScsiHba.isIsSoftwareBased()) {
                        List<HostInternetScsiHbaSendTarget> sendTargets = hostInternetScsiHba.getConfiguredSendTarget();

                        if (sendTargets != null) {
                            for (HostInternetScsiHbaSendTarget sendTarget : sendTargets) {
                                String sendTargetAddress = sendTarget.getAddress();

                                if (sendTargetAddress.contains(address)) {
                                    usingDynamicDiscovery = true;
                                }
                            }
                        }
                    }
                }
            }

            if (usingDynamicDiscovery) {
                hostsUsingDynamicDiscovery.add(host);
            }
            else {
                hostsUsingStaticDiscovery.add(host);
            }
        }

        return new HostDiscoveryMethod(hostsUsingDynamicDiscovery, hostsUsingStaticDiscovery);
    }

    private ManagedObjectReference getVmfsDatastore(VmwareContext context, VmwareHypervisorHost hyperHost, String datastoreName, String storageIpAddress, int storagePortNumber,
                                                    String iqn, String chapName, String chapSecret, String mutualChapName, String mutualChapSecret) throws Exception {
        ManagedObjectReference morDs;

        ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
        ClusterMO cluster = new ClusterMO(context, morCluster);
        List<Pair<ManagedObjectReference, String>> lstHosts = cluster.getClusterHosts();

        Pair<ManagedObjectReference, String> firstHost = lstHosts.get(0);
        HostMO firstHostMO = new HostMO(context, firstHost.first());
        HostDatastoreSystemMO firstHostDatastoreSystemMO = firstHostMO.getHostDatastoreSystemMO();

        HostDiscoveryMethod hostDiscoveryMethod = getHostDiscoveryMethod(context, storageIpAddress, lstHosts);
        List<HostMO> hostsUsingStaticDiscovery = hostDiscoveryMethod.getHostsUsingStaticDiscovery();

        if (hostsUsingStaticDiscovery != null && hostsUsingStaticDiscovery.size() > 0) {
            List<HostInternetScsiHbaStaticTarget> lstTargets = getTargets(storageIpAddress, storagePortNumber, iqn,
                    chapName, chapSecret, mutualChapName, mutualChapSecret);

            addRemoveInternetScsiTargetsToAllHosts(true, lstTargets, hostsUsingStaticDiscovery);
        }

        rescanAllHosts(context, lstHosts, true, false);

        HostStorageSystemMO firstHostStorageSystem = firstHostMO.getHostStorageSystemMO();
        List<HostScsiDisk> lstHostScsiDisks = firstHostDatastoreSystemMO.queryAvailableDisksForVmfs();

        HostScsiDisk hostScsiDisk = getHostScsiDisk(firstHostStorageSystem.getStorageDeviceInfo().getScsiTopology(), lstHostScsiDisks, iqn);

        if (hostScsiDisk == null) {
            rescanAllHosts(context, lstHosts, false, true);

            morDs = firstHostDatastoreSystemMO.findDatastoreByName(datastoreName);

            if (morDs != null) {
                waitForAllHostsToSeeDatastore(lstHosts, new DatastoreMO(context, morDs));

                mountVmfsDatastore(new DatastoreMO(context, morDs), lstHosts);

                expandDatastore(firstHostDatastoreSystemMO, new DatastoreMO(context, morDs));

                return morDs;
            }

            throw new Exception("A relevant SCSI disk could not be located to use to create a datastore.");
        }

        morDs = firstHostDatastoreSystemMO.findDatastoreByName(datastoreName);
        if (morDs == null) {
            final String hostVersion = firstHostMO.getProductVersion();
            if (hostVersion.compareTo(VmwareHelper.MIN_VERSION_VMFS6) >= 0) {
                morDs = firstHostDatastoreSystemMO.createVmfs6Datastore(datastoreName, hostScsiDisk);
            } else {
                morDs = firstHostDatastoreSystemMO.createVmfs5Datastore(datastoreName, hostScsiDisk);
            }
        } else {
            // in case of iSCSI/solidfire 1:1 VMFS datastore could be inaccessible
            mountVmfsDatastore(new DatastoreMO(context, morDs), lstHosts);
        }

        if (morDs != null) {
            waitForAllHostsToMountDatastore(lstHosts, new DatastoreMO(context, morDs));

            expandDatastore(firstHostDatastoreSystemMO, new DatastoreMO(context, morDs));

            return morDs;
        }

        throw new Exception("Unable to create a datastore");
    }

    private void waitForAllHostsToSeeDatastore(List<Pair<ManagedObjectReference, String>> lstHosts, DatastoreMO dsMO) throws Exception {
        long endWaitTime = System.currentTimeMillis() + SECONDS_TO_WAIT_FOR_DATASTORE * 1000;

        boolean isConditionMet = false;

        while (System.currentTimeMillis() < endWaitTime && !isConditionMet) {
            Thread.sleep(5000);

            isConditionMet = verifyAllHostsSeeDatastore(lstHosts, dsMO);
        }

        if (!isConditionMet) {
            throw new CloudRuntimeException("Not all hosts mounted the datastore");
        }
    }

    private boolean verifyAllHostsSeeDatastore(List<Pair<ManagedObjectReference, String>> lstHosts, DatastoreMO dsMO) throws Exception {
        int numHostsChecked = 0;

        for (Pair<ManagedObjectReference, String> host : lstHosts) {
            ManagedObjectReference morHostToMatch = host.first();
            HostMO hostToMatchMO = new HostMO(dsMO.getContext(), morHostToMatch);

            List<DatastoreHostMount> datastoreHostMounts = dsMO.getHostMounts();

            for (DatastoreHostMount datastoreHostMount : datastoreHostMounts) {
                ManagedObjectReference morHost = datastoreHostMount.getKey();
                HostMO hostMO = new HostMO(dsMO.getContext(), morHost);

                if (hostMO.getHostName().equals(hostToMatchMO.getHostName())) {
                    numHostsChecked++;
                }
            }
        }

        return lstHosts.size() == numHostsChecked;
    }

    private void waitForAllHostsToMountDatastore(List<Pair<ManagedObjectReference, String>> lstHosts, DatastoreMO dsMO) throws Exception {
        long endWaitTime = System.currentTimeMillis() + SECONDS_TO_WAIT_FOR_DATASTORE * 1000;

        boolean isConditionMet = false;

        while (System.currentTimeMillis() < endWaitTime && !isConditionMet) {
            Thread.sleep(5000);

            isConditionMet = verifyAllHostsMountedDatastore(lstHosts, dsMO);
        }

        if (!isConditionMet) {
            throw new CloudRuntimeException("Not all hosts mounted the datastore");
        }
    }

    private void waitForAllHostsToMountDatastore2(List<HostMO> lstHosts, DatastoreMO dsMO) throws Exception {
        long endWaitTime = System.currentTimeMillis() + SECONDS_TO_WAIT_FOR_DATASTORE * 1000;

        boolean isConditionMet = false;

        while (System.currentTimeMillis() < endWaitTime && !isConditionMet) {
            Thread.sleep(5000);

            isConditionMet = verifyAllHostsMountedDatastore2(lstHosts, dsMO);
        }

        if (!isConditionMet) {
            throw new CloudRuntimeException("Not all hosts mounted the datastore");
        }
    }

    private boolean verifyAllHostsMountedDatastore(List<Pair<ManagedObjectReference, String>> lstHosts, DatastoreMO dsMO) throws Exception {
        List<HostMO> hostMOs = new ArrayList<>(lstHosts.size());

        for (Pair<ManagedObjectReference, String> host : lstHosts) {
            ManagedObjectReference morHostToMatch = host.first();
            HostMO hostToMatchMO = new HostMO(dsMO.getContext(), morHostToMatch);

            hostMOs.add(hostToMatchMO);
        }

        return verifyAllHostsMountedDatastore2(hostMOs, dsMO);
    }

    private boolean verifyAllHostsMountedDatastore2(List<HostMO> lstHosts, DatastoreMO dsMO) throws Exception {
        int numHostsChecked = 0;

        for (HostMO hostToMatchMO : lstHosts) {
            List<DatastoreHostMount> datastoreHostMounts = dsMO.getHostMounts();

            for (DatastoreHostMount datastoreHostMount : datastoreHostMounts) {
                ManagedObjectReference morHost = datastoreHostMount.getKey();
                HostMO hostMO = new HostMO(dsMO.getContext(), morHost);

                if (hostMO.getHostName().equals(hostToMatchMO.getHostName())) {
                    if (datastoreHostMount.getMountInfo().isMounted() && datastoreHostMount.getMountInfo().isAccessible()) {
                        numHostsChecked++;
                    }
                    else {
                        return false;
                    }
                }
            }
        }

        return lstHosts.size() == numHostsChecked;
    }

    // the purpose of this method is to find the HostScsiDisk in the passed-in array that exists (if any) because
    // we added the static iqn to an iSCSI HBA
    private static HostScsiDisk getHostScsiDisk(HostScsiTopology hst, List<HostScsiDisk> lstHostScsiDisks, String iqn) {
        for (HostScsiTopologyInterface adapter : hst.getAdapter()) {
            if (adapter.getTarget() != null) {
                for (HostScsiTopologyTarget target : adapter.getTarget()) {
                    if (target.getTransport() instanceof HostInternetScsiTargetTransport) {
                        String iScsiName = ((HostInternetScsiTargetTransport)target.getTransport()).getIScsiName();

                        if (iqn.equals(iScsiName)) {
                            for (HostScsiDisk hostScsiDisk : lstHostScsiDisks) {
                                for (HostScsiTopologyLun hstl : target.getLun()) {
                                    if (hstl.getScsiLun().contains(hostScsiDisk.getUuid())) {
                                        return hostScsiDisk;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean isDatastoreMounted(DatastoreMO dsMO, HostMO hostToMatchMO) throws Exception {
        List<DatastoreHostMount> datastoreHostMounts = dsMO.getHostMounts();

        for (DatastoreHostMount datastoreHostMount : datastoreHostMounts) {
            ManagedObjectReference morHost = datastoreHostMount.getKey();
            HostMO hostMO = new HostMO(dsMO.getContext(), morHost);

            if (hostMO.getHostName().equals(hostToMatchMO.getHostName())) {
                return datastoreHostMount.getMountInfo().isMounted();
            }
        }

        throw new CloudRuntimeException("Unable to locate the applicable host");
    }

    private String getDatastoreUuid(DatastoreMO dsMO, HostMO hostToMatchMO) throws Exception {
        List<DatastoreHostMount> datastoreHostMounts = dsMO.getHostMounts();

        for (DatastoreHostMount datastoreHostMount : datastoreHostMounts) {
            ManagedObjectReference morHost = datastoreHostMount.getKey();
            HostMO hostMO = new HostMO(dsMO.getContext(), morHost);

            if (hostMO.getHostName().equals(hostToMatchMO.getHostName())) {
                String path = datastoreHostMount.getMountInfo().getPath();

                String searchStr = "/vmfs/volumes/";
                int index = path.indexOf(searchStr);

                if (index == -1) {
                    throw new CloudRuntimeException("Unable to find the following search string: " + searchStr);
                }

                return path.substring(index + searchStr.length());
            }
        }

        throw new CloudRuntimeException("Unable to locate the UUID of the datastore");
    }

    private void mountVmfsDatastore(DatastoreMO dsMO, List<Pair<ManagedObjectReference, String>> hosts) throws Exception {
        for (Pair<ManagedObjectReference, String> host : hosts) {
            HostMO hostMO = new HostMO(dsMO.getContext(), host.first());

            List<HostMO> hostMOs = new ArrayList<>(1);

            hostMOs.add(hostMO);

            mountVmfsDatastore2(dsMO, hostMOs);
        }
    }

    private void mountVmfsDatastore2(DatastoreMO dsMO, List<HostMO> hosts) throws Exception {
        for (HostMO hostMO : hosts) {
            if (!isDatastoreMounted(dsMO, hostMO)) {
                HostStorageSystemMO hostStorageSystemMO = hostMO.getHostStorageSystemMO();

                try {
                    hostStorageSystemMO.mountVmfsVolume(getDatastoreUuid(dsMO, hostMO));
                }
                catch (InvalidStateFaultMsg ex) {
                    logger.trace("'" + ex.getClass().getName() + "' exception thrown: " + ex.getMessage());

                    List<HostMO> currentHosts = new ArrayList<>(1);

                    currentHosts.add(hostMO);

                    logger.trace("Waiting for host " + hostMO.getHostName() + " to mount datastore " + dsMO.getName());

                    waitForAllHostsToMountDatastore2(currentHosts, dsMO);
                }
            }
        }
    }

    private void unmountVmfsDatastore(VmwareContext context, VmwareHypervisorHost hyperHost, String datastoreName,
                                      List<Pair<ManagedObjectReference, String>> hosts) throws Exception {
        for (Pair<ManagedObjectReference, String> host : hosts) {
            HostMO hostMO = new HostMO(context, host.first());

            List<HostMO> hostMOs = new ArrayList<>(1);

            hostMOs.add(hostMO);

            unmountVmfsDatastore2(context, hyperHost, datastoreName, hostMOs);
        }
    }

    private void unmountVmfsDatastore2(VmwareContext context, VmwareHypervisorHost hyperHost, String datastoreName,
                                       List<HostMO> hosts) throws Exception {
        ManagedObjectReference morDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, datastoreName);
        DatastoreMO dsMO = new DatastoreMO(context, morDs);

        for (HostMO hostMO : hosts) {
            unmountVmfsVolume(dsMO, hostMO);
        }
    }

    private void unmountVmfsVolume(DatastoreMO dsMO, HostMO hostMO) throws Exception {
        if (isDatastoreMounted(dsMO, hostMO)) {
            HostStorageSystemMO hostStorageSystemMO = hostMO.getHostStorageSystemMO();

            hostStorageSystemMO.unmountVmfsVolume(getDatastoreUuid(dsMO, hostMO));
        }
    }

    private List<HostInternetScsiHbaStaticTarget> getTargets(List<Map<String, String>> targets) {
        List<HostInternetScsiHbaStaticTarget> iScsiTargets = new ArrayList<>();

        for (Map<String, String> target : targets) {
            HostInternetScsiHbaStaticTarget iScsiTarget = new HostInternetScsiHbaStaticTarget();

            iScsiTarget.setAddress(target.get(ModifyTargetsCommand.STORAGE_HOST));
            iScsiTarget.setPort(Integer.parseInt(target.get(ModifyTargetsCommand.STORAGE_PORT)));
            iScsiTarget.setIScsiName(trimIqn(target.get(ModifyTargetsCommand.IQN)));

            iScsiTargets.add(iScsiTarget);
        }

        return iScsiTargets;
    }

    private void removeVmfsDatastore(Command cmd, VmwareHypervisorHost hyperHost, String datastoreName, String storageIpAddress, int storagePortNumber,
                                     String iqn) throws Exception {
        VmwareContext context = hostService.getServiceContext(cmd);
        ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
        ClusterMO cluster = new ClusterMO(context, morCluster);
        List<Pair<ManagedObjectReference, String>> lstHosts = cluster.getClusterHosts();

        removeVmfsDatastore(cmd, hyperHost, datastoreName, storageIpAddress, storagePortNumber, iqn, lstHosts);
    }

    private void removeVmfsDatastore(Command cmd, VmwareHypervisorHost hyperHost, String datastoreName, String storageIpAddress, int storagePortNumber,
                                     String iqn, List<Pair<ManagedObjectReference, String>> lstHosts) throws Exception {
        VmwareContext context = hostService.getServiceContext(cmd);

        unmountVmfsDatastore(context, hyperHost, datastoreName, lstHosts);

        HostDiscoveryMethod hostDiscoveryMethod = getHostDiscoveryMethod(context, storageIpAddress, lstHosts);
        List<HostMO> hostsUsingStaticDiscovery = hostDiscoveryMethod.getHostsUsingStaticDiscovery();

        if (hostsUsingStaticDiscovery != null && hostsUsingStaticDiscovery.size() > 0) {
            HostInternetScsiHbaStaticTarget target = new HostInternetScsiHbaStaticTarget();

            target.setAddress(storageIpAddress);
            target.setPort(storagePortNumber);
            target.setIScsiName(iqn);

            final List<HostInternetScsiHbaStaticTarget> lstTargets = new ArrayList<>();

            lstTargets.add(target);

            addRemoveInternetScsiTargetsToAllHosts(false, lstTargets, hostsUsingStaticDiscovery);

            rescanAllHosts(hostsUsingStaticDiscovery, true, false);
        }
    }

    private void createVmdk(Command cmd, DatastoreMO dsMo, String vmdkDatastorePath, Long volumeSize) throws Exception {
        VmwareContext context = hostService.getServiceContext(null);
        VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);

        String dummyVmName = hostService.getWorkerName(context, cmd, 0, dsMo);

        VirtualMachineMO vmMo = HypervisorHostHelper.createWorkerVM(hyperHost, dsMo, dummyVmName, null);

        if (vmMo == null) {
            throw new Exception("Unable to create a dummy VM for volume creation");
        }

        Long volumeSizeToUse = volumeSize < dsMo.getDatastoreSummary().getFreeSpace() ? volumeSize : dsMo.getDatastoreSummary().getFreeSpace();

        vmMo.createDisk(vmdkDatastorePath, getMBsFromBytes(volumeSizeToUse), dsMo.getMor(), vmMo.getScsiDeviceControllerKey(), null);
        vmMo.detachDisk(vmdkDatastorePath, false);
        vmMo.destroy();
    }

    private static int getMBsFromBytes(long bytes) {
        return (int)(bytes / (1024L * 1024L));
    }

    public void handleTargets(boolean add, ModifyTargetsCommand.TargetTypeToRemove targetTypeToRemove, boolean isRemoveAsync,
                              List<Map<String, String>> targets, List<HostMO> lstHosts) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(lstHosts.size());

        for (HostMO host : lstHosts) {
            List<HostMO> hosts = new ArrayList<>();

            hosts.add(host);

            List<Map<String, String>> dynamicTargetsForHost = new ArrayList<>();
            List<Map<String, String>> staticTargetsForHost = new ArrayList<>();

            for (Map<String, String> target : targets) {
                String storageAddress = target.get(ModifyTargetsCommand.STORAGE_HOST);

                HostDiscoveryMethod hostDiscoveryMethod = getHostDiscoveryMethod(storageAddress, hosts);
                List<HostMO> hostsUsingDynamicDiscovery = hostDiscoveryMethod.getHostsUsingDynamicDiscovery();

                if (hostsUsingDynamicDiscovery != null && hostsUsingDynamicDiscovery.size() > 0) {
                    dynamicTargetsForHost.add(target);
                }
                else {
                    staticTargetsForHost.add(target);
                }
            }

            if (add) {
                executorService.submit(new Thread(() -> {
                    try {
                        boolean rescan = false;

                        if (staticTargetsForHost.size() > 0) {
                            addRemoveInternetScsiTargetsToAllHosts(true, getTargets(staticTargetsForHost), hosts);

                            rescan = true;
                        }

                        if (dynamicTargetsForHost.size() > 0) {
                            rescan = true;
                        }

                        if (rescan) {
                            rescanAllHosts(hosts, true, false);

                            List<HostInternetScsiHbaStaticTarget> targetsToAdd = new ArrayList<>();

                            targetsToAdd.addAll(getTargets(staticTargetsForHost));
                            targetsToAdd.addAll(getTargets(dynamicTargetsForHost));

                            for (HostInternetScsiHbaStaticTarget targetToAdd : targetsToAdd) {
                                HostDatastoreSystemMO hostDatastoreSystemMO = host.getHostDatastoreSystemMO();
                                String datastoreName = waitForDatastoreName(hostDatastoreSystemMO, targetToAdd.getIScsiName());
                                ManagedObjectReference morDs = hostDatastoreSystemMO.findDatastoreByName(datastoreName);
                                DatastoreMO datastoreMO = new DatastoreMO(host.getContext(), morDs);

                                mountVmfsDatastore2(datastoreMO, hosts);
                            }
                        }
                    }
                    catch (Exception ex) {
                        logger.warn(ex.getMessage());
                    }
                }));
            }
            else {
                List<HostInternetScsiHbaStaticTarget> targetsToRemove = new ArrayList<>();

                if (staticTargetsForHost.size() > 0 &&
                        (ModifyTargetsCommand.TargetTypeToRemove.STATIC.equals(targetTypeToRemove) || ModifyTargetsCommand.TargetTypeToRemove.BOTH.equals(targetTypeToRemove))) {
                    targetsToRemove.addAll(getTargets(staticTargetsForHost));
                }

                if (dynamicTargetsForHost.size() > 0 &&
                        (ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC.equals(targetTypeToRemove) || ModifyTargetsCommand.TargetTypeToRemove.BOTH.equals(targetTypeToRemove))) {
                    targetsToRemove.addAll(getTargets(dynamicTargetsForHost));
                }

                if (targetsToRemove.size() > 0) {
                    if (isRemoveAsync) {
                        new Thread(() -> handleRemove(targetsToRemove, host, hosts)).start();
                    } else {
                        executorService.submit(new Thread(() -> handleRemove(targetsToRemove, host, hosts)));
                    }
                }
            }
        }

        executorService.shutdown();

        if (!executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.MINUTES)) {
            throw new Exception("The system timed out before completing the task 'handleTargets'.");
        }
    }

    private String waitForDatastoreName(HostDatastoreSystemMO hostDatastoreSystemMO, String iqn) throws Exception {
        long endWaitTime = System.currentTimeMillis() + SECONDS_TO_WAIT_FOR_DATASTORE * 1000;

        do {
            String datastoreName = getDatastoreName(hostDatastoreSystemMO, iqn);

            if (datastoreName != null) {
                return datastoreName;
            }

            Thread.sleep(5000);
        }
        while (System.currentTimeMillis() < endWaitTime);

        throw new CloudRuntimeException("Could not find the datastore name");
    }

    private String getDatastoreName(HostDatastoreSystemMO hostDatastoreSystemMO, String iqn) throws Exception {
        String datastoreName = "-" + iqn + "-0";

        ManagedObjectReference morDs = hostDatastoreSystemMO.findDatastoreByName(datastoreName);

        if (morDs != null) {
            return datastoreName;
        }

        datastoreName = "_" + iqn + "_0";

        morDs = hostDatastoreSystemMO.findDatastoreByName(datastoreName);

        if (morDs != null) {
            return datastoreName;
        }

        return null;
    }

    private void handleRemove(List<HostInternetScsiHbaStaticTarget> targetsToRemove, HostMO host, List<HostMO> hosts) {
        try {
            for (HostInternetScsiHbaStaticTarget target : targetsToRemove) {
                String datastoreName = waitForDatastoreName(host.getHostDatastoreSystemMO(), target.getIScsiName());

                unmountVmfsDatastore2(host.getContext(), host, datastoreName, hosts);
            }

            addRemoveInternetScsiTargetsToAllHosts(false, targetsToRemove, hosts);

            rescanAllHosts(hosts, true, false);
        }
        catch (Exception ex) {
            logger.warn(ex.getMessage());
        }
    }

    private void addRemoveInternetScsiTargetsToAllHosts(VmwareContext context, final boolean add, final List<HostInternetScsiHbaStaticTarget> targets,
                                                        List<Pair<ManagedObjectReference, String>> hostPairs) throws Exception {
        List<HostMO> hosts = new ArrayList<>();

        for (Pair<ManagedObjectReference, String> hostPair : hostPairs) {
            HostMO host = new HostMO(context, hostPair.first());

            hosts.add(host);
        }

        addRemoveInternetScsiTargetsToAllHosts(add, targets, hosts);
    }

    private void addRemoveInternetScsiTargetsToAllHosts(boolean add, List<HostInternetScsiHbaStaticTarget> targets,
                                                        List<HostMO> hosts) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(hosts.size());

        final List<Exception> exceptions = new ArrayList<>();

        for (HostMO host : hosts) {
            HostStorageSystemMO hostStorageSystem = host.getHostStorageSystemMO();

            boolean iScsiHbaConfigured = false;

            for (HostHostBusAdapter hba : hostStorageSystem.getStorageDeviceInfo().getHostBusAdapter()) {
                if (hba instanceof HostInternetScsiHba && ((HostInternetScsiHba)hba).isIsSoftwareBased()) {
                    iScsiHbaConfigured = true;

                    final String iScsiHbaDevice = hba.getDevice();

                    final HostStorageSystemMO hss = hostStorageSystem;

                    executorService.submit(new Thread(() -> {
                        try {
                            if (add) {
                                hss.addInternetScsiStaticTargets(iScsiHbaDevice, targets);
                            } else {
                                hss.removeInternetScsiStaticTargets(iScsiHbaDevice, targets);
                            }
                        } catch (Exception ex) {
                            synchronized (exceptions) {
                                exceptions.add(ex);
                            }
                        }
                    }));
                }
            }

            if (!iScsiHbaConfigured) {
                throw new Exception("An iSCSI HBA must be configured before a host can use iSCSI storage.");
            }
        }

        executorService.shutdown();

        if (!executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.MINUTES)) {
            throw new Exception("The system timed out before completing the task 'addRemoveInternetScsiTargetsToAllHosts'.");
        }

        if (exceptions.size() > 0) {
            throw new Exception(exceptions.get(0).getMessage());
        }
    }

    public void rescanAllHosts(VmwareContext context, List<Pair<ManagedObjectReference, String>> lstHostPairs, boolean rescanHba, boolean rescanVmfs) throws Exception {
        List<HostMO> hosts = new ArrayList<>(lstHostPairs.size());

        for (Pair<ManagedObjectReference, String> hostPair : lstHostPairs) {
            HostMO host = new HostMO(context, hostPair.first());

            hosts.add(host);
        }

        rescanAllHosts(hosts, rescanHba, rescanVmfs);
    }

    private void rescanAllHosts(List<HostMO> lstHosts, boolean rescanHba, boolean rescanVmfs) throws Exception {
        if (!rescanHba && !rescanVmfs) {
            // nothing to do
            return;
        }

        ExecutorService executorService = Executors.newFixedThreadPool(lstHosts.size());

        final List<Exception> exceptions = new ArrayList<>();

        for (HostMO host : lstHosts) {
            HostStorageSystemMO hostStorageSystem = host.getHostStorageSystemMO();

            boolean iScsiHbaConfigured = false;

            for (HostHostBusAdapter hba : hostStorageSystem.getStorageDeviceInfo().getHostBusAdapter()) {
                if (hba instanceof HostInternetScsiHba && ((HostInternetScsiHba)hba).isIsSoftwareBased()) {
                    iScsiHbaConfigured = true;

                    final String iScsiHbaDevice = hba.getDevice();

                    final HostStorageSystemMO hss = hostStorageSystem;

                    executorService.submit(new Thread(() -> {
                        try {
                            if (rescanHba) {
                                hss.rescanHba(iScsiHbaDevice);
                            }

                            if (rescanVmfs) {
                                hss.rescanVmfs();
                            }
                        } catch (Exception ex) {
                            synchronized (exceptions) {
                                exceptions.add(ex);
                            }
                        }
                    }));
                }
            }

            if (!iScsiHbaConfigured) {
                throw new Exception("An iSCSI HBA must be configured before a host can use iSCSI storage.");
            }
        }

        executorService.shutdown();

        if (!executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.MINUTES)) {
            throw new Exception("The system timed out before completing the task 'rescanAllHosts'.");
        }

        if (exceptions.size() > 0) {
            throw new Exception(exceptions.get(0).getMessage());
        }
    }

    private String trimIqn(String iqn) {
        String[] tmp = iqn.split("/");

        if (tmp.length != 3) {
            String msg = "Wrong format for iSCSI path: " + iqn + ". It should be formatted as '/targetIQN/LUN'.";

            logger.warn(msg);

            throw new CloudRuntimeException(msg);
        }

        return tmp[1].trim();
    }

    public ManagedObjectReference prepareManagedStorage(VmwareContext context, VmwareHypervisorHost hyperHost, String diskUuid, String iScsiName,
                                                        String storageHost, int storagePort, String volumeName, String chapInitiatorUsername, String chapInitiatorSecret,
                                                        String chapTargetUsername, String chapTargetSecret, long size, Command cmd) throws Exception {

        ManagedObjectReference morDs = prepareManagedDatastore(context, hyperHost, diskUuid, iScsiName, storageHost, storagePort,
                chapInitiatorUsername, chapInitiatorSecret, chapTargetUsername, chapTargetSecret);

        DatastoreMO dsMo = new DatastoreMO(hostService.getServiceContext(null), morDs);

        String volumeDatastorePath = String.format("[%s] %s.vmdk", dsMo.getName(), volumeName != null ? volumeName : dsMo.getName());

        if (!dsMo.fileExists(volumeDatastorePath)) {
            createVmdk(cmd, dsMo, volumeDatastorePath, size);
        }

        return morDs;
    }

    public void handleDatastoreAndVmdkDetach(Command cmd, String datastoreName, String iqn, String storageHost, int storagePort) throws Exception {
        VmwareContext context = hostService.getServiceContext(null);
        VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);

        removeVmfsDatastore(cmd, hyperHost, datastoreName, storageHost, storagePort, trimIqn(iqn));
    }

    private void handleDatastoreAndVmdkDetachManaged(Command cmd, String diskUuid, String iqn, String storageHost, int storagePort) throws Exception {
        if (storagePort == DEFAULT_NFS_PORT) {
            VmwareContext context = hostService.getServiceContext(null);
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);
            // for managed NFS datastore
            hyperHost.unmountDatastore(diskUuid);
        } else {
            handleDatastoreAndVmdkDetach(cmd, VmwareResource.getDatastoreName(iqn), iqn, storageHost, storagePort);
        }
    }

    private class ManagedTarget {
        private final String storageAddress;
        private final int storagePort;
        private final String iqn;

        ManagedTarget(String storageAddress, int storagePort, String iqn) {
            this.storageAddress = storageAddress;
            this.storagePort = storagePort;
            this.iqn = iqn;
        }

        public String toString() {
            return storageAddress + storagePort + iqn;
        }
    }

    private void removeManagedTargetsFromCluster(List<String> managedDatastoreNames) throws Exception {
        List<HostInternetScsiHbaStaticTarget> lstManagedTargets = new ArrayList<>();

        VmwareContext context = hostService.getServiceContext(null);
        VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);
        ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
        ClusterMO cluster = new ClusterMO(context, morCluster);
        List<Pair<ManagedObjectReference, String>> lstHosts = cluster.getClusterHosts();
        HostMO hostMO = new HostMO(context, lstHosts.get(0).first());
        HostStorageSystemMO hostStorageSystem = hostMO.getHostStorageSystemMO();

        for (String managedDatastoreName : managedDatastoreNames) {
            unmountVmfsDatastore(context, hyperHost, managedDatastoreName, lstHosts);
        }

        for (HostHostBusAdapter hba : hostStorageSystem.getStorageDeviceInfo().getHostBusAdapter()) {
            if (hba instanceof HostInternetScsiHba && ((HostInternetScsiHba)hba).isIsSoftwareBased()) {
                List<HostInternetScsiHbaStaticTarget> lstTargets = ((HostInternetScsiHba)hba).getConfiguredStaticTarget();

                if (lstTargets != null) {
                    for (HostInternetScsiHbaStaticTarget target : lstTargets) {
                        if (managedDatastoreNames.contains(VmwareResource.createDatastoreNameFromIqn(target.getIScsiName()))) {
                            lstManagedTargets.add(target);
                        }
                    }
                }
            }
        }

        ExecutorService executorService = Executors.newFixedThreadPool(lstHosts.size());

        for (Pair<ManagedObjectReference, String> host : lstHosts) {
            List<Pair<ManagedObjectReference, String>> hosts = new ArrayList<>();

            hosts.add(host);

            List<HostInternetScsiHbaStaticTarget> staticTargetsForHost = new ArrayList<>();

            for (HostInternetScsiHbaStaticTarget iScsiManagedTarget : lstManagedTargets) {
                String storageAddress = iScsiManagedTarget.getAddress();

                HostDiscoveryMethod hostDiscoveryMethod = getHostDiscoveryMethod(context, storageAddress, hosts);
                List<HostMO> hostsUsingStaticDiscovery = hostDiscoveryMethod.getHostsUsingStaticDiscovery();

                if (hostsUsingStaticDiscovery != null && hostsUsingStaticDiscovery.size() > 0) {
                    staticTargetsForHost.add(iScsiManagedTarget);
                }
            }

            if (staticTargetsForHost.size() > 0) {
                executorService.submit(new Thread(() -> {
                    try {
                        addRemoveInternetScsiTargetsToAllHosts(context, false, staticTargetsForHost, hosts);

                        rescanAllHosts(context, hosts, true, false);
                    }
                    catch (Exception ex) {
                        logger.warn(ex.getMessage());
                    }
                }));
            }
        }

        executorService.shutdown();

        if (!executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.MINUTES)) {
            throw new Exception("The system timed out before completing the task 'removeManagedTargetsFromCluster'.");
        }
    }

    private List<String> getManagedDatastoreNamesFromVirtualDisks(List<VirtualDisk> virtualDisks) {
        List<String> managedDatastoreNames = new ArrayList<>();

        if (virtualDisks != null) {
            for (VirtualDisk virtualDisk : virtualDisks) {
                if (virtualDisk.getBacking() instanceof VirtualDiskFlatVer2BackingInfo) {
                    VirtualDiskFlatVer2BackingInfo backingInfo = (VirtualDiskFlatVer2BackingInfo)virtualDisk.getBacking();
                    String path = backingInfo.getFileName();

                    String search = "[-";
                    int index = path.indexOf(search);

                    if (index > -1) {
                        path = path.substring(index + search.length());

                        String search2 = "-0]";

                        index = path.lastIndexOf(search2);

                        if (index > -1) {
                            path = path.substring(0, index);

                            if (path.startsWith("iqn.")) {
                                managedDatastoreNames.add("-" + path + "-0");
                            }
                        }
                    }
                }
            }
        }

        return managedDatastoreNames;
    }

    private Long restoreVolumeFromSecStorage(VmwareHypervisorHost hyperHost, DatastoreMO primaryDsMo, String newVolumeName, String secStorageUrl, String secStorageDir,
                                             String backupName, long wait, String nfsVersion) throws Exception {

        String secondaryMountPoint = mountService.getMountPoint(secStorageUrl, null);
        String srcOVAFileName;
        String srcOVFFileName;

        srcOVAFileName = secondaryMountPoint + "/" + secStorageDir + "/" + backupName + "." + ImageFormat.OVA.getFileExtension();
        srcOVFFileName = secondaryMountPoint + "/" + secStorageDir + "/" + backupName + ".ovf";

        String snapshotDir = "";
        if (backupName.contains("/")) {
            snapshotDir = backupName.split("/")[0];
        }

        File ovafile = new File(srcOVAFileName);

        File ovfFile = new File(srcOVFFileName);
        // String srcFileName = getOVFFilePath(srcOVAFileName);
        if (!ovfFile.exists()) {
            srcOVFFileName = getOVFFilePath(srcOVAFileName);
            if (srcOVFFileName == null && ovafile.exists()) {  // volss: ova file exists; o/w can't do tar
                Script command = new Script("tar", wait, logger);
                command.add("--no-same-owner");
                command.add("-xf", srcOVAFileName);
                command.setWorkDir(secondaryMountPoint + "/" + secStorageDir + "/" + snapshotDir);
                logger.info("Executing command: " + command.toString());
                String result = command.execute();
                if (result != null) {
                    String msg = "Unable to unpack snapshot OVA file at: " + srcOVAFileName;
                    logger.error(msg);
                    throw new Exception(msg);
                }
                srcOVFFileName = getOVFFilePath(srcOVAFileName);
            } else if (srcOVFFileName == null) {
                String msg = "Unable to find snapshot OVA file at: " + srcOVAFileName;
                logger.error(msg);
                throw new Exception(msg);
            }
        }
        if (srcOVFFileName == null) {
            String msg = "Unable to locate OVF file in template package directory: " + srcOVAFileName;
            logger.error(msg);
            throw new Exception(msg);
        }

        VirtualMachineMO workerVm = null;
        try {
            hyperHost.importVmFromOVF(srcOVFFileName, newVolumeName, primaryDsMo, "thin", null);
            workerVm = hyperHost.findVmOnHyperHost(newVolumeName);
            if (workerVm == null) {
                throw new Exception("Unable to create container VM for volume creation");
            }
            workerVm.tagAsWorkerVM();

            if (!primaryDsMo.getDatastoreType().equalsIgnoreCase("VVOL")) {
                HypervisorHostHelper.createBaseFolderInDatastore(primaryDsMo, primaryDsMo.getDataCenterMor());
                workerVm.moveAllVmDiskFiles(primaryDsMo, HypervisorHostHelper.VSPHERE_DATASTORE_BASE_FOLDER, false);
            }
            workerVm.detachAllDisks();
            return _storage.getSize(srcOVFFileName);
        } finally {
            if (workerVm != null) {
                workerVm.detachAllDisksAndDestroy();
            }
        }
    }

    @Override
    public Answer createVolumeFromSnapshot(CopyCommand cmd) {
        DataTO srcData = cmd.getSrcTO();
        SnapshotObjectTO snapshot = (SnapshotObjectTO)srcData;
        DataTO destData = cmd.getDestTO();
        DataStoreTO pool = destData.getDataStore();
        DataStoreTO imageStore = srcData.getDataStore();

        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("unsupported protocol");
        }

        NfsTO nfsImageStore = (NfsTO)imageStore;
        String primaryStorageNameLabel = pool.getUuid();

        String secondaryStorageUrl = nfsImageStore.getUrl();
        String backedUpSnapshotUuid = snapshot.getPath();
        int index = backedUpSnapshotUuid.lastIndexOf(File.separator);
        String backupPath = backedUpSnapshotUuid.substring(0, index);
        backedUpSnapshotUuid = backedUpSnapshotUuid.substring(index + 1);
        String details;

        VmwareContext context = hostService.getServiceContext(cmd);
        try {
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, cmd);
            ManagedObjectReference morPrimaryDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, primaryStorageNameLabel);
            if (morPrimaryDs == null) {
                String msg = "Unable to find datastore: " + primaryStorageNameLabel;
                logger.error(msg);
                throw new Exception(msg);
            }

            DatastoreMO primaryDsMo = new DatastoreMO(hyperHost.getContext(), morPrimaryDs);
            String newVolumeName = VmwareHelper.getVCenterSafeUuid(primaryDsMo);
            // strip off the extension since restoreVolumeFromSecStorage internally will append suffix there.
            if (backedUpSnapshotUuid.endsWith(".ova")){
                backedUpSnapshotUuid = backedUpSnapshotUuid.replace(".ova", "");
            } else if (backedUpSnapshotUuid.endsWith(".ovf")){
                backedUpSnapshotUuid = backedUpSnapshotUuid.replace(".ovf", "");
            }
            restoreVolumeFromSecStorage(hyperHost, primaryDsMo, newVolumeName, secondaryStorageUrl, backupPath, backedUpSnapshotUuid, (long)cmd.getWait() * 1000, _nfsVersion);

            VolumeObjectTO newVol = new VolumeObjectTO();
            newVol.setPath(newVolumeName);
            return new CopyCmdAnswer(newVol);
        } catch (Throwable e) {
            hostService.createLogMessageException(e, cmd);
            details = String.format("Failed to create volume from snapshot due to exception: [%s]", VmwareHelper.getExceptionMessage(e));
        }
        return new CopyCmdAnswer(details);
    }

    @Override
    public Answer deleteSnapshot(DeleteCommand cmd) {
        SnapshotObjectTO snapshot = (SnapshotObjectTO)cmd.getData();
        DataStoreTO store = snapshot.getDataStore();
        if (store.getRole() == DataStoreRole.Primary) {
            return new Answer(cmd);
        } else {
            return new Answer(cmd, false, "unsupported command");
        }
    }

    @Override
    public Answer introduceObject(IntroduceObjectCmd cmd) {
        return new Answer(cmd, false, "not implememented yet");
    }

    @Override
    public Answer forgetObject(ForgetObjectCmd cmd) {
        return new Answer(cmd, false, "not implememented yet");
    }

    private String deriveTemplateUuidOnHost(VmwareHypervisorHost hyperHost, String storeIdentifier, String templateName) {
        String templateUuid;
        try {
            templateUuid = UUID.nameUUIDFromBytes((templateName + "@" + storeIdentifier + "-" + hyperHost.getMor().getValue()).getBytes("UTF-8")).toString();
        } catch(UnsupportedEncodingException e){
            logger.warn("unexpected encoding error, using default Charset: " + e.getLocalizedMessage());
            templateUuid = UUID.nameUUIDFromBytes((templateName + "@" + storeIdentifier + "-" + hyperHost.getMor().getValue()).getBytes(Charset.defaultCharset()))
                    .toString();
        }
        templateUuid = templateUuid.replaceAll("-", "");
        return templateUuid;
    }

    private String getLegacyVmDataDiskController() throws Exception {
        return DiskControllerType.lsilogic.toString();
    }

    void setNfsVersion(String nfsVersion){
        this._nfsVersion = nfsVersion;
        logger.debug("VmwareProcessor instance now using NFS version: " + nfsVersion);
    }

    void setFullCloneFlag(boolean value){
        this._fullCloneFlag = value;
        logger.debug("VmwareProcessor instance - create full clone = " + (value ? "TRUE" : "FALSE"));
    }

    void setDiskProvisioningStrictness(boolean value){
        this._diskProvisioningStrictness = value;
        logger.debug("VmwareProcessor instance - diskProvisioningStrictness = " + (value ? "TRUE" : "FALSE"));
    }

    @Override
    public Answer handleDownloadTemplateToPrimaryStorage(DirectDownloadCommand cmd) {
        return null;
    }

    @Override
    public Answer checkDataStoreStoragePolicyCompliance(CheckDataStoreStoragePolicyComplainceCommand cmd) {
        String primaryStorageNameLabel = cmd.getStoragePool().getUuid();
        String storagePolicyId = cmd.getStoragePolicyId();
        VmwareContext context = hostService.getServiceContext(cmd);
        try {
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, cmd);
            ManagedObjectReference morPrimaryDs = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, primaryStorageNameLabel);
            if (morPrimaryDs == null) {
                String msg = "Unable to find datastore: " + primaryStorageNameLabel;
                logger.error(msg);
                throw new Exception(msg);
            }

            DatastoreMO primaryDsMo = new DatastoreMO(hyperHost.getContext(), morPrimaryDs);
            boolean isDatastoreStoragePolicyComplaint = primaryDsMo.isDatastoreStoragePolicyComplaint(storagePolicyId);

            String failedMessage = String.format("DataStore %s is not complaince with storage policy id %s", primaryStorageNameLabel, storagePolicyId);
            if (!isDatastoreStoragePolicyComplaint)
                return new Answer(cmd, isDatastoreStoragePolicyComplaint, failedMessage);
            else
                return new Answer(cmd, isDatastoreStoragePolicyComplaint, null);
        } catch (Throwable e) {
            hostService.createLogMessageException(e, cmd);
            String details = String.format("Exception while checking if datastore [%s] is storage policy [%s] complaince due to: [%s]", primaryStorageNameLabel, storagePolicyId, VmwareHelper.getExceptionMessage(e));
            return new Answer(cmd, false, details);
        }
    }

    @Override
    public Answer copyVolumeFromPrimaryToPrimary(CopyCommand cmd) {
        return null;
    }

    /**
     * Return the cloned VM from the template
     */
    public VirtualMachineMO cloneVMFromTemplate(VmwareHypervisorHost hyperHost, TemplateObjectTO template, VolumeObjectTO volume, String cloneName, String templatePrimaryStoreUuid) {
        try {
            String templateName = template.getPath();
            VmwareContext context = hyperHost.getContext();
            DatacenterMO dcMo = new DatacenterMO(context, hyperHost.getHyperHostDatacenter());
            VirtualMachineMO templateMo = dcMo.findVm(templateName);
            if (templateMo == null) {
                throw new CloudRuntimeException(String.format("Unable to find template %s in vSphere", templateName));
            }
            ManagedObjectReference morDatastore = HypervisorHostHelper.findDatastoreWithBackwardsCompatibility(hyperHost, templatePrimaryStoreUuid);
            DatastoreMO dsMo = new DatastoreMO(context, morDatastore);
            ManagedObjectReference morPool = hyperHost.getHyperHostOwnerResourcePool();
            if (morDatastore == null) {
                throw new CloudRuntimeException("Unable to find datastore in vSphere");
            }
            logger.info("Cloning VM " + cloneName + " from template " + templateName + " into datastore " + templatePrimaryStoreUuid);
            if (template.getSize() != null) {
                _fullCloneFlag = volume.getSize() > template.getSize() ? true : _fullCloneFlag;
            }
            if (!_fullCloneFlag) {
                createVMLinkedClone(templateMo, dcMo, cloneName, morDatastore, morPool, null);
            } else {
                createVMFullClone(templateMo, dcMo, dsMo, cloneName, morDatastore, morPool, null);
            }
            VirtualMachineMO vm = dcMo.findVm(cloneName);
            if (vm == null) {
                throw new CloudRuntimeException("Unable to get the cloned VM " + cloneName);
            }
            return vm;
        } catch (Throwable e) {
            String msg = "Error cloning VM from template in primary storage: %s" + e.getMessage();
            logger.error(msg, e);
            throw new CloudRuntimeException(msg, e);
        }
    }

    public Pair<Boolean, Boolean> getSyncedVolume(VirtualMachineMO vmMo, VmwareContext context,VmwareHypervisorHost hyperHost, DiskTO disk, VolumeObjectTO volumeTO) throws Exception {
        DataStoreTO primaryStore = volumeTO.getDataStore();
        boolean datastoreChangeObserved = false;
        boolean volumePathChangeObserved = false;
        if (!"DatastoreCluster".equalsIgnoreCase(disk.getDetails().get(DiskTO.PROTOCOL_TYPE))) {
            return new Pair<>(volumePathChangeObserved, datastoreChangeObserved);
        }
        VirtualMachineDiskInfo matchingExistingDisk = getMatchingExistingDisk(hyperHost, context, vmMo, disk);
        VirtualMachineDiskInfoBuilder diskInfoBuilder = vmMo.getDiskInfoBuilder();
        if (diskInfoBuilder != null && matchingExistingDisk != null) {
            String[] diskChain = matchingExistingDisk.getDiskChain();
            assert (diskChain.length > 0);
            DatastoreFile file = new DatastoreFile(diskChain[0]);
            String volumePath = volumeTO.getPath();
            if (!file.getFileBaseName().equalsIgnoreCase(volumePath)) {
                if (logger.isInfoEnabled()) {
                    logger.info(String.format("Detected disk-chain top file change on volume: %s -> %s", volumeTO, file.getFileBaseName()));
                }
                volumePathChangeObserved = true;
                volumePath = file.getFileBaseName();
                volumeTO.setPath(volumePath);
                volumeTO.setChainInfo(_gson.toJson(matchingExistingDisk));
            }

            DatastoreMO diskDatastoreMoFromVM = getDiskDatastoreMofromVM(hyperHost, context, vmMo, disk, diskInfoBuilder);
            if (diskDatastoreMoFromVM != null) {
                String actualPoolUuid = diskDatastoreMoFromVM.getCustomFieldValue(CustomFieldConstants.CLOUD_UUID);
                if (!actualPoolUuid.equalsIgnoreCase(primaryStore.getUuid())) {
                    logger.warn(String.format("Volume %s found to be in a different storage pool %s", volumeTO, actualPoolUuid));
                    datastoreChangeObserved = true;
                    volumeTO.setDataStoreUuid(actualPoolUuid);
                    volumeTO.setChainInfo(_gson.toJson(matchingExistingDisk));
                }
            }
        }
        return new Pair<>(volumePathChangeObserved, datastoreChangeObserved);
    }

    @Override
    public Answer syncVolumePath(SyncVolumePathCommand cmd) {
        DiskTO disk = cmd.getDisk();
        VolumeObjectTO volumeTO = (VolumeObjectTO)disk.getData();
        String vmName = volumeTO.getVmName();

        try {
            VmwareContext context = hostService.getServiceContext(null);
            VmwareHypervisorHost hyperHost = hostService.getHyperHost(context, null);
            VirtualMachineMO vmMo = hyperHost.findVmOnHyperHost(vmName);
            if (vmMo == null) {
                vmMo = hyperHost.findVmOnPeerHyperHost(vmName);
                if (vmMo == null) {
                    String msg = "Unable to find the VM to execute SyncVolumePathCommand, vmName: " + vmName;
                    logger.error(msg);
                    throw new Exception(msg);
                }
            }
            Pair<Boolean, Boolean> changes = getSyncedVolume(vmMo, context, hyperHost, disk, volumeTO);
            boolean volumePathChangeObserved = changes.first();
            boolean datastoreChangeObserved = changes.second();

            SyncVolumePathAnswer answer = new SyncVolumePathAnswer(disk);
            if (datastoreChangeObserved) {
                answer.setContextParam("datastoreName", volumeTO.getDataStoreUuid());
            }
            if (volumePathChangeObserved) {
                answer.setContextParam("volumePath", volumeTO.getPath());
            }
            if ((volumePathChangeObserved || datastoreChangeObserved) && StringUtils.isNotEmpty(volumeTO.getChainInfo())) {
                answer.setContextParam("chainInfo", volumeTO.getChainInfo());
            }

            return answer;
        }  catch (Throwable e) {
            return new SyncVolumePathAnswer(hostService.createLogMessageException(e, cmd));
        }
    }
}
