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
package com.cloud.hypervisor.kvm.storage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.reflections.Reflections;

import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.hypervisor.kvm.resource.KVMHABase;
import com.cloud.hypervisor.kvm.resource.KVMHABase.PoolType;
import com.cloud.hypervisor.kvm.resource.KVMHAMonitor;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageLayer;
import com.cloud.storage.Volume;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;

public class KVMStoragePoolManager {
    protected Logger logger = LogManager.getLogger(getClass());

    private KVMHAMonitor _haMonitor;
    private final Map<String, StoragePoolInformation> _storagePools = new ConcurrentHashMap<String, StoragePoolInformation>();
    private final Map<String, StorageAdaptor> _storageMapper = new HashMap<String, StorageAdaptor>();

    private StorageAdaptor getStorageAdaptor(StoragePoolType type) {
        // type can be null: LibVirtComputingResource:3238
        if (type == null) {
            return _storageMapper.get("libvirt");
        }
        StorageAdaptor adaptor = _storageMapper.get(type.toString());
        if (adaptor == null) {
            // LibvirtStorageAdaptor is selected by default
            adaptor = _storageMapper.get("libvirt");
        }
        return adaptor;
    }

    private void addStoragePool(String uuid, StoragePoolInformation pool) {
        synchronized (_storagePools) {
            if (!_storagePools.containsKey(uuid)) {
                _storagePools.put(uuid, pool);
            }
        }
    }

    public KVMStoragePoolManager(StorageLayer storagelayer, KVMHAMonitor monitor) {
        this._haMonitor = monitor;
        this._storageMapper.put("libvirt", new LibvirtStorageAdaptor(storagelayer));
        // add other storage adaptors manually here

        // add any adaptors that wish to register themselves via call to adaptor.getStoragePoolType()
        Reflections reflections = new Reflections("com.cloud.hypervisor.kvm.storage");
        Set<Class<? extends StorageAdaptor>> storageAdaptorClasses = reflections.getSubTypesOf(StorageAdaptor.class);
        for (Class<? extends StorageAdaptor> storageAdaptorClass : storageAdaptorClasses) {
            logger.debug("Checking pool type for adaptor " + storageAdaptorClass.getName());
            if (Modifier.isAbstract(storageAdaptorClass.getModifiers()) || storageAdaptorClass.isInterface()) {
                logger.debug("Skipping registration of abstract class / interface " + storageAdaptorClass.getName());
                continue;
            }
            if (storageAdaptorClass.isAssignableFrom(LibvirtStorageAdaptor.class)) {
                logger.debug("Skipping re-registration of LibvirtStorageAdaptor");
                continue;
            }
            try {
                Constructor<?> storageLayerConstructor = Arrays.stream(storageAdaptorClass.getConstructors())
                        .filter(c -> c.getParameterCount() == 1)
                        .filter(c -> c.getParameterTypes()[0].isAssignableFrom(StorageLayer.class))
                        .findFirst().orElse(null);
                StorageAdaptor adaptor;

                if (storageLayerConstructor == null) {
                    adaptor = storageAdaptorClass.getDeclaredConstructor().newInstance();
                } else {
                    adaptor = (StorageAdaptor) storageLayerConstructor.newInstance(storagelayer);
                }

                StoragePoolType storagePoolType = adaptor.getStoragePoolType();
                if (storagePoolType != null) {
                    if (this._storageMapper.containsKey(storagePoolType.toString())) {
                        logger.warn(String.format("Duplicate StorageAdaptor type %s, not loading %s", storagePoolType, storageAdaptorClass.getName()));
                    } else {
                        logger.info(String.format("Adding storage adaptor for %s", storageAdaptorClass.getName()));
                        this._storageMapper.put(storagePoolType.toString(), adaptor);
                    }
                }
            } catch (Exception ex) {
                throw new CloudRuntimeException("Failed to set up storage adaptors", ex);
            }
        }

        for (Map.Entry<String, StorageAdaptor> adaptors : this._storageMapper.entrySet()) {
            logger.debug("Registered a StorageAdaptor for " + adaptors.getKey());
        }
    }

    /**
     * Returns true if physical disk copy functionality supported.
     */
    public boolean supportsPhysicalDiskCopy(StoragePoolType type) {
        return getStorageAdaptor(type).supportsPhysicalDiskCopy(type);
    }

    public boolean connectPhysicalDisk(StoragePoolType type, String poolUuid, String volPath, Map<String, String> details) {
        StorageAdaptor adaptor = getStorageAdaptor(type);
        KVMStoragePool pool = adaptor.getStoragePool(poolUuid);

        return adaptor.connectPhysicalDisk(volPath, pool, details, false);
    }

    public boolean connectPhysicalDisksViaVmSpec(VirtualMachineTO vmSpec, boolean isVMMigrate) {
        boolean result = false;

        final String vmName = vmSpec.getName();

        List<DiskTO> disks = Arrays.asList(vmSpec.getDisks());

        for (DiskTO disk : disks) {
            if (disk.getType() == Volume.Type.ISO) {
                result = true;
                continue;
            }

            VolumeObjectTO vol = (VolumeObjectTO)disk.getData();
            PrimaryDataStoreTO store = (PrimaryDataStoreTO)vol.getDataStore();
            if (!store.isManaged() && VirtualMachine.State.Migrating.equals(vmSpec.getState())) {
                result = true;
                continue;
            }

            KVMStoragePool pool = getStoragePool(store.getPoolType(), store.getUuid());
            StorageAdaptor adaptor = getStorageAdaptor(pool.getType());

            result = adaptor.connectPhysicalDisk(vol.getPath(), pool, disk.getDetails(), isVMMigrate);

            if (!result) {
                logger.error("Failed to connect disks via vm spec for vm: " + vmName + " volume:" + vol.toString());
                return result;
            }
        }

        return result;
    }

    public boolean disconnectPhysicalDisk(Map<String, String> volumeToDisconnect) {
        logger.debug(String.format("Disconnect physical disks using volume map: %s", volumeToDisconnect.toString()));
        if (MapUtils.isEmpty(volumeToDisconnect)) {
            return false;
        }

        if (volumeToDisconnect.get(DiskTO.PROTOCOL_TYPE) != null) {
            String poolType = volumeToDisconnect.get(DiskTO.PROTOCOL_TYPE);
            StorageAdaptor adaptor = _storageMapper.get(poolType);
            if (adaptor != null) {
                logger.info(String.format("Disconnecting physical disk using the storage adaptor found for pool type: %s", poolType));
                return adaptor.disconnectPhysicalDisk(volumeToDisconnect);
            }

            logger.debug(String.format("Couldn't find the storage adaptor for pool type: %s to disconnect the physical disk, trying with others", poolType));
        }

        for (Map.Entry<String, StorageAdaptor> set : _storageMapper.entrySet()) {
            StorageAdaptor adaptor = set.getValue();

            if (adaptor.disconnectPhysicalDisk(volumeToDisconnect)) {
                logger.debug(String.format("Disconnected physical disk using the storage adaptor for pool type: %s", set.getKey()));
                return true;
            }
        }

        return false;
    }

    public boolean disconnectPhysicalDiskByPath(String path) {
        logger.debug(String.format("Disconnect physical disk by path: %s", path));
        for (Map.Entry<String, StorageAdaptor> set : _storageMapper.entrySet()) {
            StorageAdaptor adaptor = set.getValue();

            if (adaptor.disconnectPhysicalDiskByPath(path)) {
                logger.debug(String.format("Disconnected physical disk by local path: %s, using the storage adaptor for pool type: %s", path, set.getKey()));
                return true;
            }
        }

        return false;
    }

    public boolean disconnectPhysicalDisksViaVmSpec(VirtualMachineTO vmSpec) {
        if (vmSpec == null) {
            /* CloudStack often tries to stop VMs that shouldn't be running, to ensure a known state,
               for example if we lose communication with the agent and the VM is brought up elsewhere.
               We may not know about these yet. This might mean that we can't use the vmspec map, because
               when we restart the agent we lose all of the info about running VMs. */

            logger.debug("disconnectPhysicalDiskViaVmSpec: Attempted to stop a VM that is not yet in our hash map");

            return true;
        }

        boolean result = true;

        final String vmName = vmSpec.getName();

        List<DiskTO> disks = Arrays.asList(vmSpec.getDisks());

        for (DiskTO disk : disks) {
            if (disk.getType() != Volume.Type.ISO) {
                logger.debug("Disconnecting disk " + disk.getPath());

                VolumeObjectTO vol = (VolumeObjectTO)disk.getData();
                PrimaryDataStoreTO store = (PrimaryDataStoreTO)vol.getDataStore();

                KVMStoragePool pool = getStoragePool(store.getPoolType(), store.getUuid());

                if (pool == null) {
                    logger.error("Pool " + store.getUuid() + " of type " + store.getPoolType() + " was not found, skipping disconnect logic");
                    continue;
                }

                StorageAdaptor adaptor = getStorageAdaptor(pool.getType());

                // if a disk fails to disconnect, still try to disconnect remaining

                boolean subResult = adaptor.disconnectPhysicalDisk(vol.getPath(), pool);

                if (!subResult) {
                    logger.error("Failed to disconnect disks via vm spec for vm: " + vmName + " volume:" + vol.toString());

                    result = false;
                }
            }
        }

        return result;
    }

    public KVMStoragePool getStoragePool(StoragePoolType type, String uuid) {
        return this.getStoragePool(type, uuid, false);
    }

    public KVMStoragePool getStoragePool(StoragePoolType type, String uuid, boolean refreshInfo) {

        StorageAdaptor adaptor = getStorageAdaptor(type);
        KVMStoragePool pool = null;
        try {
            pool = adaptor.getStoragePool(uuid, refreshInfo);
        } catch (Exception e) {
            StoragePoolInformation info = _storagePools.get(uuid);
            if (info != null) {
                pool = createStoragePool(info.getName(), info.getHost(), info.getPort(), info.getPath(), info.getUserInfo(), info.getPoolType(), info.getDetails(), info.isType());
            } else {
                throw new CloudRuntimeException("Could not fetch storage pool " + uuid + " from libvirt due to " + e.getMessage());
            }
        }

        if (pool instanceof LibvirtStoragePool) {
            addPoolDetails(uuid, (LibvirtStoragePool) pool);
        }

        return pool;
    }

    /**
     * As the class {@link LibvirtStoragePool} is constrained to the {@link org.libvirt.StoragePool} class, there is no way of saving a generic parameter such as the details, hence,
     * this method was created to always make available the details of libvirt primary storages for when they are needed.
     */
    private void addPoolDetails(String uuid, LibvirtStoragePool pool) {
        StoragePoolInformation storagePoolInformation = _storagePools.get(uuid);
        Map<String, String> details = storagePoolInformation.getDetails();

        if (MapUtils.isNotEmpty(details)) {
            logger.trace("Adding the details {} to the pool with UUID {}.", details, uuid);
            pool.setDetails(details);
        }
    }

    public KVMStoragePool getStoragePoolByURI(String uri) {
        URI storageUri = null;

        try {
            logger.debug("Get storage pool by uri: " + uri);
            storageUri = new URI(uri);
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException(e.toString());
        }

        String sourcePath = null;
        String uuid = null;
        String sourceHost = "";
        StoragePoolType protocol = null;
        final String scheme = (storageUri.getScheme() != null) ? storageUri.getScheme().toLowerCase() : "";
        List<String> acceptedSchemes = List.of("nfs", "networkfilesystem", "filesystem");
        if (acceptedSchemes.contains(scheme)) {
            sourcePath = storageUri.getPath();
            sourcePath = sourcePath.replace("//", "/");
            sourceHost = storageUri.getHost();
            uuid = UUID.nameUUIDFromBytes(new String(sourceHost + sourcePath).getBytes()).toString();
            protocol = scheme.equals("filesystem") ? StoragePoolType.Filesystem: StoragePoolType.NetworkFilesystem;
        }

        // secondary storage registers itself through here
        return createStoragePool(uuid, sourceHost, 0, sourcePath, "", protocol, null, false);
    }

    public KVMPhysicalDisk getPhysicalDisk(StoragePoolType type, String poolUuid, String volName) {
        int cnt = 0;
        int retries = 100;
        KVMPhysicalDisk vol = null;
        //harden get volume, try cnt times to get volume, in case volume is created on other host
        //Poll more frequently and return immediately once disk is found
        String errMsg = "";
        while (cnt < retries) {
            try {
                KVMStoragePool pool = getStoragePool(type, poolUuid);
                vol = pool.getPhysicalDisk(volName);
                if (vol != null) {
                    return vol;
                }
            } catch (Exception e) {
                logger.debug("Failed to find volume:" + volName + " due to " + e.toString() + ", retry:" + cnt);
                errMsg = e.toString();
            }

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                logger.debug("[ignored] interrupted while trying to get storage pool.");
            }
            cnt++;
        }

        KVMStoragePool pool = getStoragePool(type, poolUuid);
        vol = pool.getPhysicalDisk(volName);
        if (vol == null) {
            throw new CloudRuntimeException(errMsg);
        } else {
            return vol;
        }
    }

    public KVMStoragePool createStoragePool(String name, String host, int port, String path, String userInfo, StoragePoolType type) {
        // primary storage registers itself through here
        return createStoragePool(name, host, port, path, userInfo, type, null, true);
    }

    /**
     * Primary Storage registers itself through here
     */
    public KVMStoragePool createStoragePool(String name, String host, int port, String path, String userInfo, StoragePoolType type, Map<String, String> details) {
        return createStoragePool(name, host, port, path, userInfo, type, details, true);
    }

    //Note: due to bug CLOUDSTACK-4459, createStoragepool can be called in parallel, so need to be synced.
    private synchronized KVMStoragePool createStoragePool(String name, String host, int port, String path, String userInfo, StoragePoolType type, Map<String, String> details, boolean primaryStorage) {
        StorageAdaptor adaptor = getStorageAdaptor(type);
        KVMStoragePool pool = adaptor.createStoragePool(name, host, port, path, userInfo, type, details, primaryStorage);

        // LibvirtStorageAdaptor-specific statement
        if (pool.isPoolSupportHA() && primaryStorage) {
            KVMHABase.HAStoragePool storagePool = new KVMHABase.HAStoragePool(pool, host, path, PoolType.PrimaryStorage);
            _haMonitor.addStoragePool(storagePool);
        }
        StoragePoolInformation info = new StoragePoolInformation(name, host, port, path, userInfo, type, details, primaryStorage);
        addStoragePool(pool.getUuid(), info);
        return pool;
    }

    public boolean disconnectPhysicalDisk(StoragePoolType type, String poolUuid, String volPath) {
        StorageAdaptor adaptor = getStorageAdaptor(type);
        KVMStoragePool pool = adaptor.getStoragePool(poolUuid);

        return adaptor.disconnectPhysicalDisk(volPath, pool);
    }

    public boolean deleteStoragePool(StoragePoolType type, String uuid) {
        StorageAdaptor adaptor = getStorageAdaptor(type);
        if (type == StoragePoolType.NetworkFilesystem) {
            _haMonitor.removeStoragePool(uuid);
        }
        boolean deleteStatus = adaptor.deleteStoragePool(uuid);;
        synchronized (_storagePools) {
            _storagePools.remove(uuid);
        }
        return deleteStatus;
    }

    public boolean deleteStoragePool(StoragePoolType type, String uuid, Map<String, String> details) {
        StorageAdaptor adaptor = getStorageAdaptor(type);
        if (type == StoragePoolType.NetworkFilesystem) {
            _haMonitor.removeStoragePool(uuid);
        }
        boolean deleteStatus = adaptor.deleteStoragePool(uuid, details);
        synchronized (_storagePools) {
            _storagePools.remove(uuid);
        }
        return deleteStatus;
    }

    public KVMPhysicalDisk createDiskFromTemplate(KVMPhysicalDisk template, String name, Storage.ProvisioningType provisioningType,
                                                    KVMStoragePool destPool, int timeout, byte[] passphrase) {
        return createDiskFromTemplate(template, name, provisioningType, destPool, template.getSize(), timeout, passphrase);
    }

    public KVMPhysicalDisk createDiskFromTemplate(KVMPhysicalDisk template, String name, Storage.ProvisioningType provisioningType,
                                                    KVMStoragePool destPool, long size, int timeout, byte[] passphrase) {
        StorageAdaptor adaptor = getStorageAdaptor(destPool.getType());

        // LibvirtStorageAdaptor-specific statement
        if (destPool.getType() == StoragePoolType.RBD) {
            return adaptor.createDiskFromTemplate(template, name,
                    PhysicalDiskFormat.RAW, provisioningType,
                    size, destPool, timeout, passphrase);
        } else if (destPool.getType() == StoragePoolType.CLVM) {
            return adaptor.createDiskFromTemplate(template, name,
                    PhysicalDiskFormat.RAW, provisioningType,
                    size, destPool, timeout, passphrase);
        } else if (template.getFormat() == PhysicalDiskFormat.DIR) {
            return adaptor.createDiskFromTemplate(template, name,
                    PhysicalDiskFormat.DIR, provisioningType,
                    size, destPool, timeout, passphrase);
        } else if (destPool.getType() == StoragePoolType.PowerFlex || destPool.getType() == StoragePoolType.Linstor) {
            return adaptor.createDiskFromTemplate(template, name,
                    PhysicalDiskFormat.RAW, provisioningType,
                    size, destPool, timeout, passphrase);
        } else {
            return adaptor.createDiskFromTemplate(template, name,
                    PhysicalDiskFormat.QCOW2, provisioningType,
                    size, destPool, timeout, passphrase);
        }
    }

    public KVMPhysicalDisk createTemplateFromDisk(KVMPhysicalDisk disk, String name, PhysicalDiskFormat format, long size, KVMStoragePool destPool) {
        StorageAdaptor adaptor = getStorageAdaptor(destPool.getType());
        return adaptor.createTemplateFromDisk(disk, name, format, size, destPool);
    }

    public KVMPhysicalDisk copyPhysicalDisk(KVMPhysicalDisk disk, String name, KVMStoragePool destPool, int timeout) {
        StorageAdaptor adaptor = getStorageAdaptor(destPool.getType());
        return adaptor.copyPhysicalDisk(disk, name, destPool, timeout, null, null, null);
    }

    public KVMPhysicalDisk copyPhysicalDisk(KVMPhysicalDisk disk, String name, KVMStoragePool destPool, int timeout, byte[] srcPassphrase, byte[] dstPassphrase, Storage.ProvisioningType provisioningType) {
        StorageAdaptor adaptor = getStorageAdaptor(destPool.getType());
        return adaptor.copyPhysicalDisk(disk, name, destPool, timeout, srcPassphrase, dstPassphrase, provisioningType);
    }

    public KVMPhysicalDisk createDiskWithTemplateBacking(KVMPhysicalDisk template, String name, PhysicalDiskFormat format, long size,
                                                         KVMStoragePool destPool, int timeout, byte[] passphrase) {
        StorageAdaptor adaptor = getStorageAdaptor(destPool.getType());
        return adaptor.createDiskFromTemplateBacking(template, name, format, size, destPool, timeout, passphrase);
    }

    public KVMPhysicalDisk createPhysicalDiskFromDirectDownloadTemplate(String templateFilePath, String destTemplatePath, KVMStoragePool destPool, Storage.ImageFormat format, int timeout) {
        StorageAdaptor adaptor = getStorageAdaptor(destPool.getType());
        return adaptor.createTemplateFromDirectDownloadFile(templateFilePath, destTemplatePath, destPool, format, timeout);
    }

    public Ternary<Boolean, Map<String, String>, String> prepareStorageClient(StoragePoolType type, String uuid, Map<String, String> details) {
        StorageAdaptor adaptor = getStorageAdaptor(type);
        return adaptor.prepareStorageClient(uuid, details);
    }

    public Pair<Boolean, String> unprepareStorageClient(StoragePoolType type, String uuid, Map<String, String> details) {
        StorageAdaptor adaptor = getStorageAdaptor(type);
        return adaptor.unprepareStorageClient(uuid, details);
    }
}
