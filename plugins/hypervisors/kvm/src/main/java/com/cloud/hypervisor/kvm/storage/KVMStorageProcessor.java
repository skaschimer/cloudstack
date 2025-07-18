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
package com.cloud.hypervisor.kvm.storage;

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;
import static com.cloud.utils.storage.S3.S3Utils.putFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.naming.ConfigurationException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import com.cloud.agent.api.Command;
import com.cloud.hypervisor.kvm.resource.LibvirtXMLParser;
import org.apache.cloudstack.agent.directdownload.DirectDownloadAnswer;
import org.apache.cloudstack.agent.directdownload.DirectDownloadCommand;
import org.apache.cloudstack.direct.download.DirectDownloadHelper;
import org.apache.cloudstack.direct.download.DirectTemplateDownloader;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.storage.command.AttachAnswer;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.CheckDataStoreStoragePolicyComplainceCommand;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.DettachAnswer;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.command.ForgetObjectCmd;
import org.apache.cloudstack.storage.command.IntroduceObjectCmd;
import org.apache.cloudstack.storage.command.ResignatureAnswer;
import org.apache.cloudstack.storage.command.ResignatureCommand;
import org.apache.cloudstack.storage.command.SnapshotAndCopyAnswer;
import org.apache.cloudstack.storage.command.SnapshotAndCopyCommand;
import org.apache.cloudstack.storage.command.SyncVolumePathCommand;
import org.apache.cloudstack.storage.formatinspector.Qcow2Inspector;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.cryptsetup.KeyFile;
import org.apache.cloudstack.utils.qemu.QemuImageOptions;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.cloudstack.utils.qemu.QemuObject;
import org.apache.cloudstack.utils.qemu.QemuObject.EncryptFormat;
import org.apache.cloudstack.utils.security.ParserUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainInfo;
import org.libvirt.DomainSnapshot;
import org.libvirt.LibvirtException;

import com.ceph.rados.IoCTX;
import com.ceph.rados.Rados;
import com.ceph.rados.exceptions.ErrorCode;
import com.ceph.rados.exceptions.RadosException;
import com.ceph.rbd.Rbd;
import com.ceph.rbd.RbdException;
import com.ceph.rbd.RbdImage;
import com.ceph.rbd.jna.RbdSnapInfo;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.PrimaryStorageDownloadAnswer;
import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.S3TO;
import com.cloud.agent.properties.AgentProperties;
import com.cloud.agent.properties.AgentPropertiesFileHandler;
import com.cloud.exception.InternalErrorException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.LibvirtConnection;
import com.cloud.hypervisor.kvm.resource.LibvirtDomainXMLParser;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DeviceType;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiscardType;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskProtocol;
import com.cloud.storage.JavaStorageLayer;
import com.cloud.storage.MigrationOptions;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageLayer;
import com.cloud.storage.Volume;
import com.cloud.storage.resource.StorageProcessor;
import com.cloud.storage.template.Processor;
import com.cloud.storage.template.Processor.FormatInfo;
import com.cloud.storage.template.QCOW2Processor;
import com.cloud.storage.template.TemplateConstants;
import com.cloud.storage.template.TemplateLocation;
import com.cloud.utils.Pair;
import com.cloud.utils.UriUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.cloud.utils.storage.S3.S3Utils;
import com.cloud.vm.VmDetailConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class KVMStorageProcessor implements StorageProcessor {
    protected Logger logger = LogManager.getLogger(getClass());
    private final KVMStoragePoolManager storagePoolMgr;
    private final LibvirtComputingResource resource;
    private StorageLayer storageLayer;
    private String _createTmplPath;
    private String _manageSnapshotPath;
    private int _cmdsTimeout;

    private static final String MANAGE_SNAPSTHOT_CREATE_OPTION = "-c";
    private static final String NAME_OPTION = "-n";
    private static final String CEPH_MON_HOST = "mon_host";
    private static final String CEPH_AUTH_KEY = "key";
    private static final String CEPH_CLIENT_MOUNT_TIMEOUT = "client_mount_timeout";
    private static final String CEPH_DEFAULT_MOUNT_TIMEOUT = "30";
    /**
     * Time interval before rechecking virsh commands
     */
    private final long waitDelayForVirshCommands = 1000L;

    private int incrementalSnapshotTimeout;

    private static final String CHECKPOINT_XML_TEMP_DIR = "/tmp/cloudstack/checkpointXMLs";

    private static final String BACKUP_XML_TEMP_DIR = "/tmp/cloudstack/backupXMLs";

    private static final String BACKUP_BEGIN_COMMAND = "virsh backup-begin --domain %s --backupxml %s --checkpointxml %s";

    private static final String BACKUP_XML = "<domainbackup><disks><disk name='%s' type='file'><target file='%s'/><driver type='qcow2'/></disk></disks></domainbackup>";

    private static final String INCREMENTAL_BACKUP_XML = "<domainbackup><incremental>%s</incremental><disks><disk name='%s' type='file'><target file='%s'/><driver type='qcow2'/></disk></disks></domainbackup>";

    private static final String CHECKPOINT_XML = "<domaincheckpoint><name>%s</name><disks><disk name='%s' checkpoint='bitmap'/></disks></domaincheckpoint>";

    private static final String CHECKPOINT_DUMP_XML_COMMAND = "virsh checkpoint-dumpxml --domain %s --checkpointname %s --no-domain";

    private static final String DOMJOBINFO_COMPLETED_COMMAND = "virsh domjobinfo --domain %s --completed";

    private static final String DOMJOBABORT_COMMAND = "virsh domjobabort --domain %s";

    private static final String DUMMY_VM_XML = "<domain type='qemu'>\n" +
            "  <name>%s</name>\n" +
            "  <memory unit='MiB'>256</memory>\n" +
            "  <currentMemory unit='MiB'>256</currentMemory>\n" +
            "  <vcpu>1</vcpu>\n" +
            "  <os>\n" +
            "    <type arch='%s' machine='%s'>hvm</type>\n" +
            "    <boot dev='hd'/>\n" +
            "  </os>\n" +
            "  <devices>\n" +
            "    <emulator>%s</emulator>\n" +
            "    <disk type='file' device='disk'>\n" +
            "      <driver name='qemu' type='qcow2' cache='none'/>\n"+
            "      <source file='%s'/>\n" +
            "      <target dev='sda'/>\n" +
            "    </disk>\n" +
            "    <graphics type='vnc' port='-1'/>\n" +
            "  </devices>\n" +
            "</domain>";


    public KVMStorageProcessor(final KVMStoragePoolManager storagePoolMgr, final LibvirtComputingResource resource) {
        this.storagePoolMgr = storagePoolMgr;
        this.resource = resource;
    }

    protected String getDefaultStorageScriptsDir() {
        return "scripts/storage/qcow2";
    }

    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        storageLayer = new JavaStorageLayer();
        storageLayer.configure("StorageLayer", params);

        String storageScriptsDir = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.STORAGE_SCRIPTS_DIR);

        _createTmplPath = Script.findScript(storageScriptsDir, "createtmplt.sh");
        if (_createTmplPath == null) {
            throw new ConfigurationException("Unable to find the createtmplt.sh");
        }

        _manageSnapshotPath = Script.findScript(storageScriptsDir, "managesnapshot.sh");
        if (_manageSnapshotPath == null) {
            throw new ConfigurationException("Unable to find the managesnapshot.sh");
        }

        _cmdsTimeout = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.CMDS_TIMEOUT) * 1000;

        incrementalSnapshotTimeout = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.INCREMENTAL_SNAPSHOT_TIMEOUT) * 1000;
        return true;
    }

    @Override
    public SnapshotAndCopyAnswer snapshotAndCopy(final SnapshotAndCopyCommand cmd) {
        logger.info("'SnapshotAndCopyAnswer snapshotAndCopy(SnapshotAndCopyCommand)' not currently used for KVMStorageProcessor");

        return new SnapshotAndCopyAnswer();
    }

    @Override
    public ResignatureAnswer resignature(final ResignatureCommand cmd) {
        logger.info("'ResignatureAnswer resignature(ResignatureCommand)' not currently used for KVMStorageProcessor");

        return new ResignatureAnswer();
    }

    @Override
    public Answer copyTemplateToPrimaryStorage(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final TemplateObjectTO template = (TemplateObjectTO)srcData;
        final DataStoreTO imageStore = template.getDataStore();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)destData.getDataStore();

        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("unsupported protocol");
        }

        final NfsTO nfsImageStore = (NfsTO)imageStore;
        final String tmplturl = nfsImageStore.getUrl() + File.separator + template.getPath();
        final int index = tmplturl.lastIndexOf("/");
        final String mountpoint = tmplturl.substring(0, index);
        String tmpltname = null;
        if (index < tmplturl.length() - 1) {
            tmpltname = tmplturl.substring(index + 1);
        }

        KVMPhysicalDisk tmplVol = null;
        KVMStoragePool secondaryPool = null;
        try {
            secondaryPool = storagePoolMgr.getStoragePoolByURI(mountpoint);

            /* Get template vol */
            if (tmpltname == null) {
                secondaryPool.refresh();
                final List<KVMPhysicalDisk> disks = secondaryPool.listPhysicalDisks();
                if (disks == null || disks.isEmpty()) {
                    return new PrimaryStorageDownloadAnswer("Failed to get volumes from pool: " + secondaryPool.getUuid());
                }
                for (final KVMPhysicalDisk disk : disks) {
                    if (disk.getName().endsWith("qcow2")) {
                        tmplVol = disk;
                        break;
                    }
                }
            } else {
                tmplVol = secondaryPool.getPhysicalDisk(tmpltname);
            }

            if (tmplVol == null) {
                return new PrimaryStorageDownloadAnswer("Failed to get template from pool: " + secondaryPool.getUuid());
            }

            /* Copy volume to primary storage */
            tmplVol.setUseAsTemplate();
            logger.debug("Copying template to primary storage, template format is " + tmplVol.getFormat() );
            final KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());

            KVMPhysicalDisk primaryVol;
            if (destData instanceof VolumeObjectTO) {
                final VolumeObjectTO volume = (VolumeObjectTO)destData;
                // pass along volume's target size if it's bigger than template's size, for storage types that copy template rather than cloning on deploy
                if (volume.getSize() != null && volume.getSize() > tmplVol.getVirtualSize()) {
                    logger.debug("Using configured size of " + toHumanReadableSize(volume.getSize()));
                    tmplVol.setSize(volume.getSize());
                    tmplVol.setVirtualSize(volume.getSize());
                } else {
                    logger.debug("Using template's size of " + toHumanReadableSize(tmplVol.getVirtualSize()));
                }
                primaryVol = storagePoolMgr.copyPhysicalDisk(tmplVol, volume.getUuid(), primaryPool, cmd.getWaitInMillSeconds());
            } else if (destData instanceof TemplateObjectTO) {
                TemplateObjectTO destTempl = (TemplateObjectTO)destData;

                Map<String, String> details = primaryStore.getDetails();

                String path = derivePath(primaryStore, destData, details);

                if (path == null) {
                    path = destTempl.getUuid();
                }

                if (path != null && !storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path, details)) {
                    logger.warn("Failed to connect physical disk at path: {}, in storage pool [id: {}, name: {}]", path, primaryStore.getUuid(), primaryStore.getName());
                    return new PrimaryStorageDownloadAnswer("Failed to spool template disk at path: " + path + ", in storage pool id: " + primaryStore.getUuid());
                }

                primaryVol = storagePoolMgr.copyPhysicalDisk(tmplVol, path != null ? path : destTempl.getUuid(), primaryPool, cmd.getWaitInMillSeconds());

                if (!storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path)) {
                    logger.warn("Failed to disconnect physical disk at path: {}, in storage pool [id: {}, name: {}]", path, primaryStore.getUuid(), primaryStore.getName());
                }
            } else {
                primaryVol = storagePoolMgr.copyPhysicalDisk(tmplVol, UUID.randomUUID().toString(), primaryPool, cmd.getWaitInMillSeconds());
            }

            DataTO data = null;
            /**
             * Force the ImageFormat for RBD templates to RAW
             *
             */
            if (destData.getObjectType() == DataObjectType.TEMPLATE) {
                final TemplateObjectTO newTemplate = new TemplateObjectTO();
                newTemplate.setPath(primaryVol.getName());
                newTemplate.setSize(primaryVol.getSize());

                if(List.of(
                    StoragePoolType.RBD,
                    StoragePoolType.PowerFlex,
                    StoragePoolType.Linstor,
                    StoragePoolType.FiberChannel).contains(primaryPool.getType())) {
                    newTemplate.setFormat(ImageFormat.RAW);
                } else {
                    newTemplate.setFormat(ImageFormat.QCOW2);
                }
                data = newTemplate;
            } else if (destData.getObjectType() == DataObjectType.VOLUME) {
                final VolumeObjectTO volumeObjectTO = new VolumeObjectTO();
                volumeObjectTO.setPath(primaryVol.getName());
                volumeObjectTO.setSize(primaryVol.getSize());
                if (primaryVol.getFormat() == PhysicalDiskFormat.RAW) {
                    volumeObjectTO.setFormat(ImageFormat.RAW);
                } else if (primaryVol.getFormat() == PhysicalDiskFormat.QCOW2) {
                    volumeObjectTO.setFormat(ImageFormat.QCOW2);
                }
                data = volumeObjectTO;
            }
            return new CopyCmdAnswer(data);
        } catch (final CloudRuntimeException e) {
            return new CopyCmdAnswer(e.toString());
        } finally {
            try {
                if (secondaryPool != null) {
                    secondaryPool.delete();
                }
            } catch(final Exception e) {
                logger.debug("Failed to clean up secondary storage", e);
            }
        }
    }

    public static String derivePath(PrimaryDataStoreTO primaryStore, DataTO destData, Map<String, String> details) {
        String path = null;
        if (primaryStore.getPoolType() == StoragePoolType.FiberChannel) {
            path = destData.getPath();
        } else {
            path = details != null ? details.get("managedStoreTarget") : null;
        }

        return path;
    }

    // this is much like PrimaryStorageDownloadCommand, but keeping it separate. copies template direct to root disk
    private KVMPhysicalDisk templateToPrimaryDownload(final String templateUrl, final KVMStoragePool primaryPool, final String volUuid, final Long size, final int timeout) {
        final int index = templateUrl.lastIndexOf("/");
        final String mountpoint = templateUrl.substring(0, index);
        String templateName = null;
        if (index < templateUrl.length() - 1) {
            templateName = templateUrl.substring(index + 1);
        }

        KVMPhysicalDisk templateVol = null;
        KVMStoragePool secondaryPool = null;
        try {
            secondaryPool = storagePoolMgr.getStoragePoolByURI(mountpoint);
            /* Get template vol */
            if (templateName == null) {
                secondaryPool.refresh();
                final List<KVMPhysicalDisk> disks = secondaryPool.listPhysicalDisks();
                if (disks == null || disks.isEmpty()) {
                    logger.error("Failed to get volumes from pool: " + secondaryPool.getUuid());
                    return null;
                }
                for (final KVMPhysicalDisk disk : disks) {
                    if (disk.getName().endsWith("qcow2")) {
                        templateVol = disk;
                        break;
                    }
                }
                if (templateVol == null) {
                    logger.error("Failed to get template from pool: " + secondaryPool.getUuid());
                    return null;
                }
            } else {
                templateVol = secondaryPool.getPhysicalDisk(templateName);
            }

            /* Copy volume to primary storage */

            if (size > templateVol.getSize()) {
                logger.debug("Overriding provided template's size with new size " + toHumanReadableSize(size));
                templateVol.setSize(size);
                templateVol.setVirtualSize(size);
            } else {
                logger.debug("Using templates disk size of " + toHumanReadableSize(templateVol.getVirtualSize()) + "since size passed was " + toHumanReadableSize(size));
            }

            return storagePoolMgr.copyPhysicalDisk(templateVol, volUuid, primaryPool, timeout);
        } catch (final CloudRuntimeException e) {
            logger.error("Failed to download template to primary storage", e);
            return null;
        } finally {
            if (secondaryPool != null) {
                secondaryPool.delete();
            }
        }
    }

    @Override
    public Answer cloneVolumeFromBaseTemplate(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final TemplateObjectTO template = (TemplateObjectTO)srcData;
        final DataStoreTO imageStore = template.getDataStore();
        final VolumeObjectTO volume = (VolumeObjectTO)destData;
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)volume.getDataStore();
        KVMPhysicalDisk BaseVol;
        KVMStoragePool primaryPool;
        KVMPhysicalDisk vol;

        try {
            primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());

            String templatePath = template.getPath();

            if (primaryPool.getType() == StoragePoolType.CLVM) {
                templatePath = imageStore.getUrl() + File.separator + templatePath;
                vol = templateToPrimaryDownload(templatePath, primaryPool, volume.getUuid(), volume.getSize(), cmd.getWaitInMillSeconds());
            } if (storagePoolMgr.supportsPhysicalDiskCopy(primaryPool.getType())) {
                Map<String, String> details = primaryStore.getDetails();
                String path = derivePath(primaryStore, destData, details);

                if (!storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), templatePath, details)) {
                    logger.warn("Failed to connect base template volume [id: {}, name: {}, path:" +
                            " {}], in storage pool [id: {}, name: {}]", template.getUuid(),
                            template.getName(), templatePath, primaryStore.getUuid(), primaryStore.getName());
                }

                BaseVol = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), templatePath);
                if (BaseVol == null) {
                    logger.debug("Failed to get the physical disk for base template volume [id: {}, name: {}, path: {}]", template.getUuid(), template.getName(), templatePath);
                    throw new CloudRuntimeException(String.format("Failed to get the physical disk for base template volume [id: %s, name: %s, path: %s]", template.getUuid(), template.getName(), templatePath));
                }

                if (!storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path, details)) {
                    logger.warn("Failed to connect new volume at path: {}, in storage pool [id: {}, name: {}]", path, primaryStore.getUuid(), primaryStore.getName());
                }
                BaseVol.setDispName(template.getName());

                vol = storagePoolMgr.copyPhysicalDisk(BaseVol, path != null ? path : volume.getUuid(), primaryPool, cmd.getWaitInMillSeconds(), null, volume.getPassphrase(), volume.getProvisioningType());

                storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path);
            } else {
                if (templatePath.contains("/mnt")) {
                    //upgrade issue, if the path contains path, need to extract the volume uuid from path
                    templatePath = templatePath.substring(templatePath.lastIndexOf(File.separator) + 1);
                }
                BaseVol = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), templatePath);
                vol = storagePoolMgr.createDiskFromTemplate(BaseVol, volume.getUuid(), volume.getProvisioningType(),
                        BaseVol.getPool(), volume.getSize(), cmd.getWaitInMillSeconds(), volume.getPassphrase());
            }
            if (vol == null) {
                return new CopyCmdAnswer(" Can't create storage volume on storage pool");
            }

            final VolumeObjectTO newVol = new VolumeObjectTO();
            newVol.setPath(vol.getName());
            newVol.setSize(volume.getSize());
            if (vol.getQemuEncryptFormat() != null) {
                newVol.setEncryptFormat(vol.getQemuEncryptFormat().toString());
            }

            if (vol.getFormat() == PhysicalDiskFormat.RAW) {
                newVol.setFormat(ImageFormat.RAW);
            } else if (vol.getFormat() == PhysicalDiskFormat.QCOW2) {
                newVol.setFormat(ImageFormat.QCOW2);
            } else if (vol.getFormat() == PhysicalDiskFormat.DIR) {
                newVol.setFormat(ImageFormat.DIR);
            }

            return new CopyCmdAnswer(newVol);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to create volume: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            volume.clearPassphrase();
        }
    }

    @Override
    public Answer copyVolumeFromImageCacheToPrimary(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final DataStoreTO srcStore = srcData.getDataStore();
        final DataStoreTO destStore = destData.getDataStore();
        final VolumeObjectTO srcVol = (VolumeObjectTO)srcData;
        final ImageFormat srcFormat = srcVol.getFormat();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)destStore;
        if (!(srcStore instanceof NfsTO)) {
            return new CopyCmdAnswer("can only handle nfs storage");
        }
        final NfsTO nfsStore = (NfsTO)srcStore;
        final String srcVolumePath = srcData.getPath();
        final String secondaryStorageUrl = nfsStore.getUrl();
        KVMStoragePool secondaryStoragePool = null;
        KVMStoragePool primaryPool = null;
        try {
            try {
                primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
            } catch (final CloudRuntimeException e) {
                if (e.getMessage().contains("not found")) {
                    primaryPool =
                            storagePoolMgr.createStoragePool(primaryStore.getUuid(), primaryStore.getHost(), primaryStore.getPort(), primaryStore.getPath(), null,
                                    primaryStore.getPoolType());
                } else {
                    return new CopyCmdAnswer(e.getMessage());
                }
            }

            Map<String, String> details = cmd.getOptions2();

            String path = details != null ? details.get(DiskTO.IQN) : null;

            storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path, details);

            final String volumeName = UUID.randomUUID().toString();

            // Update path in the command for reconciliation
            if (destData.getPath() == null) {
                ((VolumeObjectTO) destData).setPath(volumeName);
            }

            final int index = srcVolumePath.lastIndexOf(File.separator);
            final String volumeDir = srcVolumePath.substring(0, index);
            String srcVolumeName = srcVolumePath.substring(index + 1);

            secondaryStoragePool = storagePoolMgr.getStoragePoolByURI(secondaryStorageUrl + File.separator + volumeDir);

            if (!srcVolumeName.endsWith(".qcow2") && srcFormat == ImageFormat.QCOW2) {
                srcVolumeName = srcVolumeName + ".qcow2";
            }

            final KVMPhysicalDisk volume = secondaryStoragePool.getPhysicalDisk(srcVolumeName);

            volume.setFormat(PhysicalDiskFormat.valueOf(srcFormat.toString()));
            volume.setDispName(srcVol.getName());
            volume.setVmName(srcVol.getVmName());

            resource.createOrUpdateLogFileForCommand(cmd, Command.State.PROCESSING_IN_BACKEND);
            final KVMPhysicalDisk newDisk = storagePoolMgr.copyPhysicalDisk(volume, path != null ? path : volumeName, primaryPool, cmd.getWaitInMillSeconds());
            resource.createOrUpdateLogFileForCommand(cmd, Command.State.COMPLETED);

            storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path);

            final VolumeObjectTO newVol = new VolumeObjectTO();

            newVol.setFormat(ImageFormat.valueOf(newDisk.getFormat().toString().toUpperCase()));
            newVol.setPath(path != null ? path : volumeName);

            return new CopyCmdAnswer(newVol);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to copyVolumeFromImageCacheToPrimary: ", e);

            return new CopyCmdAnswer(e.toString());
        } finally {
            srcVol.clearPassphrase();
            if (secondaryStoragePool != null) {
                storagePoolMgr.deleteStoragePool(secondaryStoragePool.getType(), secondaryStoragePool.getUuid());
            }
        }
    }

    @Override
    public Answer copyVolumeFromPrimaryToSecondary(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final VolumeObjectTO srcVol = (VolumeObjectTO)srcData;
        final VolumeObjectTO destVol = (VolumeObjectTO)destData;
        final ImageFormat srcFormat = srcVol.getFormat();
        final ImageFormat destFormat = destVol.getFormat();
        final DataStoreTO srcStore = srcData.getDataStore();
        final DataStoreTO destStore = destData.getDataStore();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)srcStore;
        if (!(destStore instanceof NfsTO)) {
            return new CopyCmdAnswer("can only handle nfs storage");
        }
        final NfsTO nfsStore = (NfsTO)destStore;
        final String srcVolumePath = srcData.getPath();
        final String destVolumePath = destData.getPath();
        final String secondaryStorageUrl = nfsStore.getUrl();
        KVMStoragePool secondaryStoragePool = null;

        try {
            final String volumeName = UUID.randomUUID().toString();

            final String destVolumeName = volumeName + "." + destFormat.getFileExtension();
            final KVMPhysicalDisk volume = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), srcVolumePath);
            volume.setFormat(PhysicalDiskFormat.valueOf(srcFormat.toString()));

            secondaryStoragePool = storagePoolMgr.getStoragePoolByURI(secondaryStorageUrl);
            secondaryStoragePool.createFolder(destVolumePath);
            storagePoolMgr.deleteStoragePool(secondaryStoragePool.getType(), secondaryStoragePool.getUuid());
            secondaryStoragePool = storagePoolMgr.getStoragePoolByURI(secondaryStorageUrl + File.separator + destVolumePath);
            storagePoolMgr.copyPhysicalDisk(volume, destVolumeName, secondaryStoragePool, cmd.getWaitInMillSeconds());
            final VolumeObjectTO newVol = new VolumeObjectTO();
            newVol.setPath(destVolumePath + File.separator + destVolumeName);
            newVol.setFormat(destFormat);
            return new CopyCmdAnswer(newVol);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to copyVolumeFromPrimaryToSecondary: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            srcVol.clearPassphrase();
            destVol.clearPassphrase();
            if (secondaryStoragePool != null) {
                storagePoolMgr.deleteStoragePool(secondaryStoragePool.getType(), secondaryStoragePool.getUuid());
            }
        }
    }

    @Override
    public Answer createTemplateFromVolume(final CopyCommand cmd) {
        Map<String, String> details = cmd.getOptions();

        // handle cases where the managed storage driver had to make a temporary volume from
        // the snapshot in order to support the copy
        if (details != null && (details.get(DiskTO.IQN) != null || details.get(DiskTO.PATH) != null)) {
            // use the managed-storage approach
            return createTemplateFromVolumeOrSnapshot(cmd);
        }

        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final int wait = cmd.getWaitInMillSeconds();
        final TemplateObjectTO template = (TemplateObjectTO)destData;
        final DataStoreTO imageStore = template.getDataStore();
        final VolumeObjectTO volume = (VolumeObjectTO)srcData;
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)volume.getDataStore();

        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("unsupported protocol");
        }
        final NfsTO nfsImageStore = (NfsTO)imageStore;

        KVMStoragePool secondaryStorage = null;
        KVMStoragePool primary;

        try {
            final String templateFolder = template.getPath();

            secondaryStorage = storagePoolMgr.getStoragePoolByURI(nfsImageStore.getUrl());

            primary = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());

            final KVMPhysicalDisk disk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volume.getPath());
            final String tmpltPath = secondaryStorage.getLocalPath() + File.separator + templateFolder;
            storageLayer.mkdirs(tmpltPath);
            final String templateName = UUID.randomUUID().toString();

            if (primary.getType() != StoragePoolType.RBD) {
                final Script command = new Script(_createTmplPath, wait, logger);
                command.add("-f", disk.getPath());
                command.add("-t", tmpltPath);
                command.add(NAME_OPTION, templateName + ".qcow2");

                final String result = command.execute();

                if (result != null) {
                    logger.debug("failed to create template: " + result);
                    return new CopyCmdAnswer(result);
                }
            } else {
                logger.debug("Converting RBD disk " + disk.getPath() + " into template " + templateName);

                final QemuImgFile srcFile = new QemuImgFile(KVMPhysicalDisk.RBDStringBuilder(primary, disk.getPath()));
                srcFile.setFormat(PhysicalDiskFormat.RAW);

                final QemuImgFile destFile = new QemuImgFile(tmpltPath + "/" + templateName + ".qcow2");
                destFile.setFormat(PhysicalDiskFormat.QCOW2);

                final QemuImg q = new QemuImg(cmd.getWaitInMillSeconds());
                try {
                    q.convert(srcFile, destFile);
                } catch (final QemuImgException | LibvirtException e) {
                    final String message = "Failed to create new template while converting " + srcFile.getFileName() + " to " + destFile.getFileName() + " the error was: " +
                            e.getMessage();

                    throw new QemuImgException(message);
                }

                final File templateProp = new File(tmpltPath + "/template.properties");
                if (!templateProp.exists()) {
                    templateProp.createNewFile();
                }

                String templateContent = "filename=" + templateName + ".qcow2" + System.getProperty("line.separator");

                final DateFormat dateFormat = new SimpleDateFormat("MM_dd_yyyy");
                final Date date = new Date();
                templateContent += "snapshot.name=" + dateFormat.format(date) + System.getProperty("line.separator");


                try(FileOutputStream templFo = new FileOutputStream(templateProp);){
                    templFo.write(templateContent.getBytes());
                    templFo.flush();
                } catch (final IOException e) {
                    throw e;
                }
            }

            final Map<String, Object> params = new HashMap<String, Object>();
            params.put(StorageLayer.InstanceConfigKey, storageLayer);
            final Processor qcow2Processor = new QCOW2Processor();

            qcow2Processor.configure("QCOW2 Processor", params);

            final FormatInfo info = qcow2Processor.process(tmpltPath, null, templateName);

            final TemplateLocation loc = new TemplateLocation(storageLayer, tmpltPath);
            loc.create(1, true, templateName);
            loc.addFormat(info);
            loc.save();

            final TemplateObjectTO newTemplate = new TemplateObjectTO();
            newTemplate.setPath(templateFolder + File.separator + templateName + ".qcow2");
            newTemplate.setSize(info.virtualSize);
            newTemplate.setPhysicalSize(info.size);
            newTemplate.setFormat(ImageFormat.QCOW2);
            newTemplate.setName(templateName);
            return new CopyCmdAnswer(newTemplate);

        } catch (final QemuImgException e) {
            logger.error(e.getMessage());
            return new CopyCmdAnswer(e.toString());
        } catch (final IOException e) {
            logger.debug("Failed to createTemplateFromVolume: ", e);
            return new CopyCmdAnswer(e.toString());
        } catch (final Exception e) {
            logger.debug("Failed to createTemplateFromVolume: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            volume.clearPassphrase();
            if (secondaryStorage != null) {
                secondaryStorage.delete();
            }
        }
    }

    @Override
    public Answer createTemplateFromSnapshot(CopyCommand cmd) {
        Map<String, String> details = cmd.getOptions();

        if (details != null && (details.get(DiskTO.IQN) != null || details.get(DiskTO.PATH) != null)) {
            // use the managed-storage approach
            return createTemplateFromVolumeOrSnapshot(cmd);
        }

        return new CopyCmdAnswer("operation not supported");
    }

    private Answer createTemplateFromVolumeOrSnapshot(CopyCommand cmd) {
        DataTO srcData = cmd.getSrcTO();

        final boolean isVolume;

        if (srcData instanceof VolumeObjectTO) {
            isVolume = true;
        }
        else if (srcData instanceof SnapshotObjectTO) {
            isVolume = false;
        }
        else {
            return new CopyCmdAnswer("unsupported object type");
        }

        PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)srcData.getDataStore();

        DataTO destData = cmd.getDestTO();
        TemplateObjectTO template = (TemplateObjectTO)destData;
        DataStoreTO imageStore = template.getDataStore();

        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("unsupported protocol");
        }

        NfsTO nfsImageStore = (NfsTO)imageStore;

        KVMStoragePool secondaryStorage = null;

        String path = null;
        try {
            // look for options indicating an overridden path or IQN.  Used when snapshots have to be
            // temporarily copied on the manaaged storage device before the actual copy to target object
            Map<String, String> details = cmd.getOptions();
            path = details != null ? details.get(DiskTO.PATH) : null;
            if (path == null) {
                path = details != null ? details.get(DiskTO.IQN) : null;
                if (path == null) {
                    path = srcData.getPath();
                    if (path == null) {
                        new CloudRuntimeException("The 'path' or 'iqn' field must be specified.");
                    }
                }
            }

            storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path, details);

            KVMPhysicalDisk srcDisk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path);

            secondaryStorage = storagePoolMgr.getStoragePoolByURI(nfsImageStore.getUrl());

            String templateFolder = template.getPath();
            String tmpltPath = secondaryStorage.getLocalPath() + File.separator + templateFolder;

            storageLayer.mkdirs(tmpltPath);

            String templateName = UUID.randomUUID().toString();

            logger.debug("Converting " + srcDisk.getFormat().toString() + " disk " + srcDisk.getPath() + " into template " + templateName);

            String destName = templateFolder + "/" + templateName + ".qcow2";

            storagePoolMgr.copyPhysicalDisk(srcDisk, destName, secondaryStorage, cmd.getWaitInMillSeconds());

            File templateProp = new File(tmpltPath + "/template.properties");

            if (!templateProp.exists()) {
                templateProp.createNewFile();
            }

            String templateContent = "filename=" + templateName + ".qcow2" + System.getProperty("line.separator");

            DateFormat dateFormat = new SimpleDateFormat("MM_dd_yyyy");
            Date date = new Date();

            if (isVolume) {
                templateContent += "volume.name=" + dateFormat.format(date) + System.getProperty("line.separator");
            }
            else {
                templateContent += "snapshot.name=" + dateFormat.format(date) + System.getProperty("line.separator");
            }

            FileOutputStream templFo = new FileOutputStream(templateProp);

            templFo.write(templateContent.getBytes());
            templFo.flush();
            templFo.close();

            Map<String, Object> params = new HashMap<>();

            params.put(StorageLayer.InstanceConfigKey, storageLayer);

            Processor qcow2Processor = new QCOW2Processor();

            qcow2Processor.configure("QCOW2 Processor", params);

            FormatInfo info = qcow2Processor.process(tmpltPath, null, templateName);

            TemplateLocation loc = new TemplateLocation(storageLayer, tmpltPath);

            loc.create(1, true, templateName);
            loc.addFormat(info);
            loc.save();

            TemplateObjectTO newTemplate = new TemplateObjectTO();

            newTemplate.setPath(templateFolder + File.separator + templateName + ".qcow2");
            newTemplate.setSize(info.virtualSize);
            newTemplate.setPhysicalSize(info.size);
            newTemplate.setFormat(ImageFormat.QCOW2);
            newTemplate.setName(templateName);

            return new CopyCmdAnswer(newTemplate);
        } catch (Exception ex) {
            if (isVolume) {
                logger.debug("Failed to create template from volume: ", ex);
            }
            else {
                logger.debug("Failed to create template from snapshot: ", ex);
            }

            return new CopyCmdAnswer(ex.toString());
        } finally {
            if (path != null) {
                storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path);
            }

            if (secondaryStorage != null) {
                secondaryStorage.delete();
            }
        }
    }

    protected String copyToS3(final File srcFile, final S3TO destStore, final String destPath) throws InterruptedException {
        final String key = destPath + S3Utils.SEPARATOR + srcFile.getName();

        putFile(destStore, srcFile, destStore.getBucketName(), key).waitForCompletion();

        return key;
    }

    protected Answer copyToObjectStore(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final DataStoreTO imageStore = destData.getDataStore();
        final NfsTO srcStore = (NfsTO)srcData.getDataStore();
        final String srcPath = srcData.getPath();
        final int index = srcPath.lastIndexOf(File.separator);
        final String srcSnapshotDir = srcPath.substring(0, index);
        final String srcFileName = srcPath.substring(index + 1);
        KVMStoragePool srcStorePool = null;
        File srcFile = null;
        try {
            srcStorePool = storagePoolMgr.getStoragePoolByURI(srcStore.getUrl() + File.separator + srcSnapshotDir);
            if (srcStorePool == null) {
                return new CopyCmdAnswer("Can't get store:" + srcStore.getUrl());
            }
            srcFile = new File(srcStorePool.getLocalPath() + File.separator + srcFileName);
            if (!srcFile.exists()) {
                return new CopyCmdAnswer("Can't find src file: " + srcPath);
            }
            String destPath = null;
            if (imageStore instanceof S3TO) {
                destPath = copyToS3(srcFile, (S3TO)imageStore, destData.getPath());
            } else {
                return new CopyCmdAnswer("Unsupported protocol");
            }
            final SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
            newSnapshot.setPath(destPath);
            return new CopyCmdAnswer(newSnapshot);
        } catch (final Exception e) {
            logger.error("failed to upload" + srcPath, e);
            return new CopyCmdAnswer("failed to upload" + srcPath + e.toString());
        } finally {
            try {
                if (srcFile != null) {
                    srcFile.delete();
                }
                if (srcStorePool != null) {
                    srcStorePool.delete();
                }
            } catch (final Exception e) {
                logger.debug("Failed to clean up:", e);
            }
        }
    }

    protected Answer backupSnapshotForObjectStore(final CopyCommand cmd) {
        final DataTO destData = cmd.getDestTO();
        final DataStoreTO imageStore = destData.getDataStore();
        final DataTO cacheData = cmd.getCacheTO();
        if (cacheData == null) {
            return new CopyCmdAnswer("Failed to copy to object store without cache store");
        }
        final DataStoreTO cacheStore = cacheData.getDataStore();
        ((SnapshotObjectTO)destData).setDataStore(cacheStore);
        final CopyCmdAnswer answer = (CopyCmdAnswer)backupSnapshot(cmd);
        if (!answer.getResult()) {
            return answer;
        }
        final SnapshotObjectTO snapshotOnCacheStore = (SnapshotObjectTO)answer.getNewData();
        snapshotOnCacheStore.setDataStore(cacheStore);
        ((SnapshotObjectTO)destData).setDataStore(imageStore);
        final CopyCommand newCpyCmd = new   CopyCommand(snapshotOnCacheStore, destData, cmd.getWaitInMillSeconds(), cmd.executeInSequence());
        return copyToObjectStore(newCpyCmd);
    }

    @Override
    public Answer backupSnapshot(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final SnapshotObjectTO snapshot = (SnapshotObjectTO)srcData;
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)snapshot.getDataStore();
        final SnapshotObjectTO destSnapshot = (SnapshotObjectTO)destData;
        final DataStoreTO imageStore = destData.getDataStore();

        if (!(imageStore instanceof NfsTO)) {
            return backupSnapshotForObjectStore(cmd);
        }
        final NfsTO nfsImageStore = (NfsTO)imageStore;

        final String secondaryStoragePoolUrl = nfsImageStore.getUrl();
        // NOTE: snapshot name is encoded in snapshot path
        final int index = snapshot.getPath().lastIndexOf("/");
        final boolean isCreatedFromVmSnapshot = index == -1; // -1 means the snapshot is created from existing vm snapshot

        final String snapshotName = snapshot.getPath().substring(index + 1);
        String descName = snapshotName;
        final String volumePath = snapshot.getVolume().getPath();
        String snapshotDestPath = null;
        String snapshotRelPath = null;
        final String vmName = snapshot.getVmName();
        KVMStoragePool secondaryStoragePool = null;
        Connect conn = null;
        KVMPhysicalDisk snapshotDisk = null;
        KVMStoragePool primaryPool = null;

        final VolumeObjectTO srcVolume = snapshot.getVolume();
        try {
            conn = LibvirtConnection.getConnectionByVmName(vmName);

            secondaryStoragePool = storagePoolMgr.getStoragePoolByURI(secondaryStoragePoolUrl);

            final String ssPmountPath = secondaryStoragePool.getLocalPath();
            snapshotRelPath = destSnapshot.getPath();

            snapshotDestPath = ssPmountPath + File.separator + snapshotRelPath;
            snapshotDisk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volumePath);
            primaryPool = snapshotDisk.getPool();

            long size = 0;
            /**
             * Since Ceph version Dumpling (0.67.X) librbd / Qemu supports converting RBD
             * snapshots to RAW/QCOW2 files directly.
             *
             * This reduces the amount of time and storage it takes to back up a snapshot dramatically
             */
            if (primaryPool.getType() == StoragePoolType.RBD) {
                final String rbdSnapshot = snapshotDisk.getPath() +  "@" + snapshotName;
                final String snapshotFile = snapshotDestPath + "/" + snapshotName;
                try {
                    logger.debug("Attempting to backup RBD snapshot " + rbdSnapshot);

                    final File snapDir = new File(snapshotDestPath);
                    logger.debug("Attempting to create " + snapDir.getAbsolutePath() + " recursively for snapshot storage");
                    FileUtils.forceMkdir(snapDir);

                    final QemuImgFile srcFile = new QemuImgFile(KVMPhysicalDisk.RBDStringBuilder(primaryPool, rbdSnapshot));
                    srcFile.setFormat(snapshotDisk.getFormat());

                    final QemuImgFile destFile = new QemuImgFile(snapshotFile);
                    destFile.setFormat(PhysicalDiskFormat.QCOW2);

                    logger.debug("Backing up RBD snapshot " + rbdSnapshot + " to " + snapshotFile);
                    final QemuImg q = new QemuImg(cmd.getWaitInMillSeconds());
                    q.convert(srcFile, destFile);

                    final File snapFile = new File(snapshotFile);
                    if(snapFile.exists()) {
                        size = snapFile.length();
                    }

                    logger.debug("Finished backing up RBD snapshot " + rbdSnapshot + " to " + snapshotFile + " Snapshot size: " + toHumanReadableSize(size));
                } catch (final FileNotFoundException e) {
                    logger.error("Failed to open " + snapshotDestPath + ". The error was: " + e.getMessage());
                    return new CopyCmdAnswer(e.toString());
                } catch (final IOException e) {
                    logger.error("Failed to create " + snapshotDestPath + ". The error was: " + e.getMessage());
                    return new CopyCmdAnswer(e.toString());
                }  catch (final QemuImgException | LibvirtException e) {
                    logger.error("Failed to backup the RBD snapshot from " + rbdSnapshot +
                            " to " + snapshotFile + " the error was: " + e.getMessage());
                    return new CopyCmdAnswer(e.toString());
                }
            } else {
                final Script command = new Script(_manageSnapshotPath, cmd.getWaitInMillSeconds(), logger);
                command.add("-b", isCreatedFromVmSnapshot ? snapshotDisk.getPath() : snapshot.getPath());
                command.add(NAME_OPTION, snapshotName);
                command.add("-p", snapshotDestPath);

                if (isCreatedFromVmSnapshot) {
                    descName = UUID.randomUUID().toString();
                }

                command.add("-t", descName);
                final String result = command.execute();
                if (result != null) {
                    logger.debug("Failed to backup snaptshot: " + result);
                    return new CopyCmdAnswer(result);
                }
                final File snapFile = new File(snapshotDestPath + "/" + descName);
                if(snapFile.exists()){
                    size = snapFile.length();
                }
            }

            final SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
            newSnapshot.setPath(snapshotRelPath + File.separator + descName);
            newSnapshot.setPhysicalSize(size);
            return new CopyCmdAnswer(newSnapshot);
        } catch (final LibvirtException | CloudRuntimeException e) {
            logger.debug("Failed to backup snapshot: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            srcVolume.clearPassphrase();
            if (isCreatedFromVmSnapshot) {
                logger.debug("Ignoring removal of vm snapshot on primary as this snapshot is created from vm snapshot");
            } else if (primaryPool != null && primaryPool.getType() != StoragePoolType.RBD) {
                deleteSnapshotOnPrimary(cmd, snapshot, primaryPool);
            }

            try {
                if (secondaryStoragePool != null) {
                    secondaryStoragePool.delete();
                }
            } catch (final Exception ex) {
                logger.debug("Failed to delete secondary storage", ex);
            }
        }
    }

    private void deleteSnapshotOnPrimary(final CopyCommand cmd, final SnapshotObjectTO snapshot,
            KVMStoragePool primaryPool) {
        String snapshotPath = snapshot.getPath();
        String backupSnapshotAfterTakingSnapshot = null;
        boolean deleteSnapshotOnPrimary = true;
        if (cmd.getOptions() != null) {
            backupSnapshotAfterTakingSnapshot = cmd.getOptions().get(SnapshotInfo.BackupSnapshotAfterTakingSnapshot.key());
            deleteSnapshotOnPrimary = cmd.getOptions().get("typeDescription") == null;
        }

        if ((backupSnapshotAfterTakingSnapshot == null || BooleanUtils.toBoolean(backupSnapshotAfterTakingSnapshot)) && deleteSnapshotOnPrimary) {
            try {
                Files.deleteIfExists(Paths.get(snapshotPath));
            } catch (IOException ex) {
                logger.error("Failed to delete snapshot [{}] on primary storage [{}].", snapshot.getId(), snapshot.getName(), ex);
            }
        } else {
            logger.debug("This backup is temporary, not deleting snapshot [{}] on primary storage [{}]", snapshot.getId(), snapshot.getName());
        }
    }

    protected synchronized void attachOrDetachISO(final Connect conn, final String vmName, String isoPath, final boolean isAttach, Map<String, String> params, DataStoreTO store) throws
            LibvirtException, InternalErrorException {
        DiskDef iso = new DiskDef();
        boolean isUefiEnabled = MapUtils.isNotEmpty(params) && params.containsKey("UEFI");
        if (isoPath != null && isAttach) {
            final int index = isoPath.lastIndexOf("/");
            final String path = isoPath.substring(0, index);
            final String name = isoPath.substring(index + 1);
            KVMStoragePool storagePool;
            if (store instanceof PrimaryDataStoreTO) {
                PrimaryDataStoreTO primaryDataStoreTO = (PrimaryDataStoreTO)store;
                storagePool = storagePoolMgr.getStoragePool(primaryDataStoreTO.getPoolType(), store.getUuid());
            } else {
                storagePool = storagePoolMgr.getStoragePoolByURI(path);
            }
            final KVMPhysicalDisk isoVol = storagePool.getPhysicalDisk(name);
            final DiskDef.DiskType isoDiskType = LibvirtComputingResource.getDiskType(isoVol);
            isoPath = isoVol.getPath();

            iso.defISODisk(isoPath, isUefiEnabled, isoDiskType);
        } else {
            iso.defISODisk(null, isUefiEnabled, DiskDef.DiskType.FILE);
        }

        final List<DiskDef> disks = resource.getDisks(conn, vmName);
        attachOrDetachDevice(conn, true, vmName, iso);
        if (!isAttach) {
            for (final DiskDef disk : disks) {
                if (disk.getDeviceType() == DiskDef.DeviceType.CDROM) {
                    resource.cleanupDisk(disk);
                }
            }

        }
    }

    @Override
    public Answer attachIso(final AttachCommand cmd) {
        final DiskTO disk = cmd.getDisk();
        final TemplateObjectTO isoTO = (TemplateObjectTO)disk.getData();
        final DataStoreTO store = isoTO.getDataStore();

        try {
            String dataStoreUrl = getDataStoreUrlFromStore(store);
            final Connect conn = LibvirtConnection.getConnectionByVmName(cmd.getVmName());
            attachOrDetachISO(conn, cmd.getVmName(), dataStoreUrl + File.separator + isoTO.getPath(), true, cmd.getControllerInfo(), store);
        } catch (final LibvirtException e) {
            return new Answer(cmd, false, e.toString());
        } catch (final InternalErrorException e) {
            return new Answer(cmd, false, e.toString());
        } catch (final InvalidParameterValueException e) {
            return new Answer(cmd, false, e.toString());
        }

        return new Answer(cmd);
    }

    @Override
    public Answer dettachIso(final DettachCommand cmd) {
        final DiskTO disk = cmd.getDisk();
        final TemplateObjectTO isoTO = (TemplateObjectTO)disk.getData();
        final DataStoreTO store = isoTO.getDataStore();

        try {
            String dataStoreUrl = getDataStoreUrlFromStore(store);
            final Connect conn = LibvirtConnection.getConnectionByVmName(cmd.getVmName());
            attachOrDetachISO(conn, cmd.getVmName(), dataStoreUrl + File.separator + isoTO.getPath(), false, cmd.getParams(), store);
        } catch (final LibvirtException e) {
            return new Answer(cmd, false, e.toString());
        } catch (final InternalErrorException e) {
            return new Answer(cmd, false, e.toString());
        } catch (final InvalidParameterValueException e) {
            return new Answer(cmd, false, e.toString());
        }

        return new Answer(cmd);
    }

    /**
     * Return data store URL from store
     */
    private String getDataStoreUrlFromStore(DataStoreTO store) {
        List<StoragePoolType> supportedPoolType = List.of(StoragePoolType.NetworkFilesystem, StoragePoolType.Filesystem);
        if (!(store instanceof NfsTO) && (!(store instanceof PrimaryDataStoreTO) || !supportedPoolType.contains(((PrimaryDataStoreTO) store).getPoolType()))) {
            logger.error(String.format("Unsupported protocol, store: %s", store.getUuid()));
            throw new InvalidParameterValueException("unsupported protocol");
        }

        if (store instanceof NfsTO) {
            NfsTO nfsStore = (NfsTO)store;
            return nfsStore.getUrl();
        } else if (store instanceof PrimaryDataStoreTO) {
            //In order to support directly downloaded ISOs
            StoragePoolType poolType = ((PrimaryDataStoreTO)store).getPoolType();
            String psHost = ((PrimaryDataStoreTO) store).getHost();
            String psPath = ((PrimaryDataStoreTO) store).getPath();
            if (StoragePoolType.NetworkFilesystem.equals(poolType)) {
                return "nfs://" + psHost + File.separator + psPath;
            } else if (StoragePoolType.Filesystem.equals(poolType)) {
                return StoragePoolType.Filesystem.toString().toLowerCase() + "://" + psHost + File.separator + psPath;
            }
        }
        return store.getUrl();
    }
    protected synchronized void attachOrDetachDevice(final Connect conn, final boolean attach, final String vmName, final DiskDef xml)
            throws LibvirtException, InternalErrorException {
        attachOrDetachDevice(conn, attach, vmName, xml, 0l);
    }

    /**
     * Attaches or detaches a device (ISO or disk) to an instance.
     * @param conn libvirt connection
     * @param attach boolean that determines whether the device will be attached or detached
     * @param vmName instance name
     * @param diskDef disk definition or iso to be attached or detached
     * @param waitDetachDevice value set in milliseconds to wait before assuming device removal failed
     * @throws LibvirtException
     * @throws InternalErrorException
     */
    protected synchronized void attachOrDetachDevice(final Connect conn, final boolean attach, final String vmName, final DiskDef diskDef, long waitDetachDevice)
            throws LibvirtException, InternalErrorException {
        Domain dm = null;
        String diskXml = diskDef.toString();
        String diskPath = diskDef.getDiskPath();
        try {
            dm = conn.domainLookupByName(vmName);

            if (attach) {
                logger.debug("Attaching device: " + diskXml);
                dm.attachDevice(diskXml);
                return;
            }
            logger.debug(String.format("Detaching device: [%s].", diskXml));
            dm.detachDevice(diskXml);
            long wait = waitDetachDevice;
            while (!checkDetachSuccess(diskPath, dm) && wait > 0) {
                wait = getWaitAfterSleep(dm, diskPath, wait);
            }
            if (wait <= 0) {
                throw new InternalErrorException(String.format("Could not detach volume after sending the command and waiting for [%s] milliseconds. Probably the VM does " +
                                "not support the sent detach command or the device is busy at the moment. Try again in a couple of minutes.",
                        waitDetachDevice));
            }
            logger.debug(String.format("The detach command was executed successfully. The device [%s] was removed from the VM instance with UUID [%s].",
                    diskPath, dm.getUUIDString()));
        } catch (final LibvirtException e) {
            if (attach) {
                logger.warn("Failed to attach device to " + vmName + ": " + e.getMessage());
            } else {
                logger.warn("Failed to detach device from " + vmName + ": " + e.getMessage());
            }
            throw e;
        } finally {
            if (dm != null) {
                try {
                    dm.free();
                } catch (final LibvirtException l) {
                    logger.trace("Ignoring libvirt error.", l);
                }
            }
        }
    }

    /**
     * Waits {@link #waitDelayForVirshCommands} milliseconds before checking again if the device has been removed.
     * @return The configured value in wait.detach.device reduced by {@link #waitDelayForVirshCommands}
     * @throws LibvirtException
     */
    private long getWaitAfterSleep(Domain dm, String diskPath, long wait) throws LibvirtException {
        try {
            wait -= waitDelayForVirshCommands;
            Thread.sleep(waitDelayForVirshCommands);
            logger.trace(String.format("Trying to detach device [%s] from VM instance with UUID [%s]. " +
                    "Waiting [%s] milliseconds before assuming the VM was unable to detach the volume.", diskPath, dm.getUUIDString(), wait));
        } catch (InterruptedException e) {
            throw new CloudRuntimeException(e);
        }
        return wait;
    }

    /**
     * Checks if the device has been removed from the instance
     * @param diskPath Path to the device that was removed
     * @param dm instance to be checked if the device was properly removed
     * @throws LibvirtException
     */
    protected boolean checkDetachSuccess(String diskPath, Domain dm) throws LibvirtException {
        LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        parser.parseDomainXML(dm.getXMLDesc(0));
        List<DiskDef> disks = parser.getDisks();
        for (DiskDef diskDef : disks) {
            if (StringUtils.equals(diskPath, diskDef.getDiskPath())) {
                logger.debug(String.format("The hypervisor sent the detach command, but it is still possible to identify the device [%s] in the instance with UUID [%s].",
                        diskPath, dm.getUUIDString()));
                return false;
            }
        }
        return true;
    }

    /**
     * Attaches or detaches a disk to an instance.
     * @param conn libvirt connection
     * @param attach boolean that determines whether the device will be attached or detached
     * @param vmName instance name
     * @param attachingDisk kvm physical disk
     * @param devId device id in instance
     * @param serial
     * @param bytesReadRate bytes read rate
     * @param bytesReadRateMax bytes read rate max
     * @param bytesReadRateMaxLength bytes read rate max length
     * @param bytesWriteRate bytes write rate
     * @param bytesWriteRateMax bytes write rate amx
     * @param bytesWriteRateMaxLength bytes write rate max length
     * @param iopsReadRate iops read rate
     * @param iopsReadRateMax iops read rate max
     * @param iopsReadRateMaxLength iops read rate max length
     * @param iopsWriteRate iops write rate
     * @param iopsWriteRateMax iops write rate max
     * @param iopsWriteRateMaxLength iops write rate max length
     * @param cacheMode cache mode
     * @param encryptDetails encrypt details
     * @throws LibvirtException
     * @throws InternalErrorException
     */
    protected synchronized void attachOrDetachDisk(final Connect conn, final boolean attach, final String vmName, final KVMPhysicalDisk attachingDisk, final int devId,
                                                   final String serial, final Long bytesReadRate, final Long bytesReadRateMax, final Long bytesReadRateMaxLength,
                                                   final Long bytesWriteRate, final Long bytesWriteRateMax, final Long bytesWriteRateMaxLength, final Long iopsReadRate,
                                                   final Long iopsReadRateMax, final Long iopsReadRateMaxLength, final Long iopsWriteRate, final Long iopsWriteRateMax,
                                                   final Long iopsWriteRateMaxLength, final String cacheMode, final DiskDef.LibvirtDiskEncryptDetails encryptDetails, Map<String, String> details)
            throws LibvirtException, InternalErrorException {
        attachOrDetachDisk(conn, attach, vmName, attachingDisk, devId, serial, bytesReadRate, bytesReadRateMax, bytesReadRateMaxLength,
                bytesWriteRate, bytesWriteRateMax, bytesWriteRateMaxLength, iopsReadRate, iopsReadRateMax, iopsReadRateMaxLength, iopsWriteRate,
                iopsWriteRateMax, iopsWriteRateMaxLength, cacheMode, encryptDetails, 0l, details);
    }

    /**
     *
     * Attaches or detaches a disk to an instance.
     * @param conn libvirt connection
     * @param attach boolean that determines whether the device will be attached or detached
     * @param vmName instance name
     * @param attachingDisk kvm physical disk
     * @param devId device id in instance
     * @param serial
     * @param bytesReadRate bytes read rate
     * @param bytesReadRateMax bytes read rate max
     * @param bytesReadRateMaxLength bytes read rate max length
     * @param bytesWriteRate bytes write rate
     * @param bytesWriteRateMax bytes write rate amx
     * @param bytesWriteRateMaxLength bytes write rate max length
     * @param iopsReadRate iops read rate
     * @param iopsReadRateMax iops read rate max
     * @param iopsReadRateMaxLength iops read rate max length
     * @param iopsWriteRate iops write rate
     * @param iopsWriteRateMax iops write rate max
     * @param iopsWriteRateMaxLength iops write rate max length
     * @param cacheMode cache mode
     * @param encryptDetails encrypt details
     * @param waitDetachDevice value set in milliseconds to wait before assuming device removal failed
     * @throws LibvirtException
     * @throws InternalErrorException
     */
    protected synchronized void attachOrDetachDisk(final Connect conn, final boolean attach, final String vmName, final KVMPhysicalDisk attachingDisk, final int devId,
                                                   final String serial, final Long bytesReadRate, final Long bytesReadRateMax, final Long bytesReadRateMaxLength,
                                                   final Long bytesWriteRate, final Long bytesWriteRateMax, final Long bytesWriteRateMaxLength, final Long iopsReadRate,
                                                   final Long iopsReadRateMax, final Long iopsReadRateMaxLength, final Long iopsWriteRate, final Long iopsWriteRateMax,
                                                   final Long iopsWriteRateMaxLength, final String cacheMode, final DiskDef.LibvirtDiskEncryptDetails encryptDetails,
                                                   long waitDetachDevice, Map<String, String> details)
            throws LibvirtException, InternalErrorException {

        List<DiskDef> disks = null;
        Domain dm = null;
        DiskDef diskdef = null;
        final KVMStoragePool attachingPool = attachingDisk.getPool();
        try {
            dm = conn.domainLookupByName(vmName);
            final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
            final String domXml = dm.getXMLDesc(0);
            parser.parseDomainXML(domXml);
            disks = parser.getDisks();
            if (!attach) {
                if (attachingPool.getType() == StoragePoolType.RBD) {
                    if (resource.getHypervisorType() == Hypervisor.HypervisorType.LXC) {
                        final String device = resource.mapRbdDevice(attachingDisk);
                        if (device != null) {
                            logger.debug("RBD device on host is: "+device);
                            attachingDisk.setPath(device);
                        }
                    }
                }

                for (final DiskDef disk : disks) {
                    final String file = disk.getDiskPath();
                    if (file != null && file.equalsIgnoreCase(attachingDisk.getPath())) {
                        diskdef = disk;
                        break;
                    }
                }
                if (diskdef == null) {
                    logger.warn(String.format("Could not find disk [%s] attached to VM instance with UUID [%s]. We will set it as detached in the database to ensure consistency.",
                            attachingDisk.getPath(), dm.getUUIDString()));
                    return;
                }
            } else {
                DiskDef.DiskBus busT = DiskDef.DiskBus.VIRTIO;
                for (final DiskDef disk : disks) {
                    if (disk.getDeviceType() == DeviceType.DISK) {
                        if (disk.getBusType() == DiskDef.DiskBus.SCSI) {
                            busT = DiskDef.DiskBus.SCSI;
                        } else if (disk.getBusType() == DiskDef.DiskBus.VIRTIOBLK) {
                            busT = DiskDef.DiskBus.VIRTIOBLK;
                        }
                        break;
                    }
                }
                diskdef = new DiskDef();
                if (busT == DiskDef.DiskBus.SCSI || busT == DiskDef.DiskBus.VIRTIOBLK) {
                    diskdef.setQemuDriver(true);
                    diskdef.setDiscard(DiscardType.UNMAP);
                }
                diskdef.setSerial(serial);
                if (attachingPool.getType() == StoragePoolType.RBD) {
                    if(resource.getHypervisorType() == Hypervisor.HypervisorType.LXC){
                        // For LXC, map image to host and then attach to Vm
                        final String device = resource.mapRbdDevice(attachingDisk);
                        if (device != null) {
                            logger.debug("RBD device on host is: "+device);
                            diskdef.defBlockBasedDisk(device, devId, busT);
                        } else {
                            throw new InternalErrorException("Error while mapping disk "+attachingDisk.getPath()+" on host");
                        }
                    } else {
                        diskdef.defNetworkBasedDisk(attachingDisk.getPath(), attachingPool.getSourceHost(), attachingPool.getSourcePort(), attachingPool.getAuthUserName(),
                                attachingPool.getUuid(), devId, busT, DiskProtocol.RBD, DiskDef.DiskFmtType.RAW);
                    }
                } else if (attachingPool.getType() == StoragePoolType.Gluster) {
                    final String mountpoint = attachingPool.getLocalPath();
                    final String path = attachingDisk.getPath();
                    final String glusterVolume = attachingPool.getSourceDir().replace("/", "");
                    diskdef.defNetworkBasedDisk(glusterVolume + path.replace(mountpoint, ""), attachingPool.getSourceHost(), attachingPool.getSourcePort(), null,
                            null, devId, busT, DiskProtocol.GLUSTER, DiskDef.DiskFmtType.QCOW2);
                } else if (attachingPool.getType() == StoragePoolType.PowerFlex) {
                    diskdef.defBlockBasedDisk(attachingDisk.getPath(), devId, busT);
                    if (attachingDisk.getFormat() == PhysicalDiskFormat.QCOW2) {
                        diskdef.setDiskFormatType(DiskDef.DiskFmtType.QCOW2);
                    }
                } else if (attachingDisk.getFormat() == PhysicalDiskFormat.QCOW2) {
                    diskdef.defFileBasedDisk(attachingDisk.getPath(), devId, busT, DiskDef.DiskFmtType.QCOW2);
                } else if (attachingDisk.getFormat() == PhysicalDiskFormat.RAW) {
                    diskdef.defBlockBasedDisk(attachingDisk.getPath(), devId, busT);
                    if (attachingPool.getType() == StoragePoolType.Linstor && resource.isQemuDiscardBugFree(busT)) {
                        diskdef.setDiscard(DiscardType.UNMAP);
                    }
                }

                if (encryptDetails != null &&
                        attachingPool.getType().encryptionSupportMode() == Storage.EncryptionSupport.Hypervisor) {
                    diskdef.setLibvirtDiskEncryptDetails(encryptDetails);
                }

                if ((bytesReadRate != null) && (bytesReadRate > 0)) {
                    diskdef.setBytesReadRate(bytesReadRate);
                }
                if ((bytesReadRateMax != null) && (bytesReadRateMax > 0)) {
                    diskdef.setBytesReadRateMax(bytesReadRateMax);
                }
                if ((bytesReadRateMaxLength != null) && (bytesReadRateMaxLength > 0)) {
                    diskdef.setBytesReadRateMaxLength(bytesReadRateMaxLength);
                }
                if ((bytesWriteRate != null) && (bytesWriteRate > 0)) {
                    diskdef.setBytesWriteRate(bytesWriteRate);
                }
                if ((bytesWriteRateMax != null) && (bytesWriteRateMax > 0)) {
                    diskdef.setBytesWriteRateMax(bytesWriteRateMax);
                }
                if ((bytesWriteRateMaxLength != null) && (bytesWriteRateMaxLength > 0)) {
                    diskdef.setBytesWriteRateMaxLength(bytesWriteRateMaxLength);
                }
                if ((iopsReadRate != null) && (iopsReadRate > 0)) {
                    diskdef.setIopsReadRate(iopsReadRate);
                }
                if ((iopsReadRateMax != null) && (iopsReadRateMax > 0)) {
                    diskdef.setIopsReadRateMax(iopsReadRateMax);
                }
                if ((iopsReadRateMaxLength != null) && (iopsReadRateMaxLength > 0)) {
                    diskdef.setIopsReadRateMaxLength(iopsReadRateMaxLength);
                }
                if ((iopsWriteRate != null) && (iopsWriteRate > 0)) {
                    diskdef.setIopsWriteRate(iopsWriteRate);
                }
                if ((iopsWriteRateMax != null) && (iopsWriteRateMax > 0)) {
                    diskdef.setIopsWriteRateMax(iopsWriteRateMax);
                }
                if ((iopsWriteRateMaxLength != null) && (iopsWriteRateMaxLength > 0)) {
                    diskdef.setIopsWriteRateMaxLength(iopsWriteRateMaxLength);
                }
                if(cacheMode != null) {
                    diskdef.setCacheMode(DiskDef.DiskCacheMode.valueOf(cacheMode.toUpperCase()));
                }

                diskdef.isIothreadsEnabled(details != null && details.containsKey(VmDetailConstants.IOTHREADS));

                String ioDriver = (details != null && details.containsKey(VmDetailConstants.IO_POLICY)) ? details.get(VmDetailConstants.IO_POLICY) : null;
                if (ioDriver != null) {
                    resource.setDiskIoDriver(diskdef, resource.getIoDriverForTheStorage(ioDriver.toUpperCase()));
                }
                diskdef.setPhysicalBlockIOSize(attachingPool.getSupportedPhysicalBlockSize());
                diskdef.setLogicalBlockIOSize(attachingPool.getSupportedLogicalBlockSize());
                attachingPool.customizeLibvirtDiskDef(diskdef);
            }

            attachOrDetachDevice(conn, attach, vmName, diskdef, waitDetachDevice);
        } finally {
            if (dm != null) {
                dm.free();
            }
        }
    }

    @Override
    public Answer attachVolume(final AttachCommand cmd) {
        final DiskTO disk = cmd.getDisk();
        final VolumeObjectTO vol = (VolumeObjectTO)disk.getData();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)vol.getDataStore();
        final String vmName = cmd.getVmName();
        final String serial = resource.diskUuidToSerial(vol.getUuid());

        try {
            final Connect conn = LibvirtConnection.getConnectionByVmName(vmName);
            DiskDef.LibvirtDiskEncryptDetails encryptDetails = null;
            if (vol.requiresEncryption()) {
                String secretUuid = resource.createLibvirtVolumeSecret(conn, vol.getPath(), vol.getPassphrase());
                encryptDetails = new DiskDef.LibvirtDiskEncryptDetails(secretUuid, QemuObject.EncryptFormat.enumValue(vol.getEncryptFormat()));
                vol.clearPassphrase();
            }

            storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), vol.getPath(), disk.getDetails());

            final KVMPhysicalDisk phyDisk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), vol.getPath());
            final String volCacheMode = vol.getCacheMode() == null ? null : vol.getCacheMode().toString();
            logger.debug(String.format("Attaching physical disk %s with format %s", phyDisk.getPath(), phyDisk.getFormat()));

            attachOrDetachDisk(conn, true, vmName, phyDisk, disk.getDiskSeq().intValue(), serial,
                    vol.getBytesReadRate(), vol.getBytesReadRateMax(), vol.getBytesReadRateMaxLength(),
                    vol.getBytesWriteRate(), vol.getBytesWriteRateMax(), vol.getBytesWriteRateMaxLength(),
                    vol.getIopsReadRate(), vol.getIopsReadRateMax(), vol.getIopsReadRateMaxLength(),
                    vol.getIopsWriteRate(), vol.getIopsWriteRateMax(), vol.getIopsWriteRateMaxLength(), volCacheMode, encryptDetails, disk.getDetails());

            resource.recreateCheckpointsOnVm(List.of((VolumeObjectTO) disk.getData()), vmName, conn);

            return new AttachAnswer(disk);
        } catch (final LibvirtException e) {
            logger.debug(String.format("Failed to attach volume [id: %d, uuid: %s, name: %s, path: %s], due to ",
                    vol.getId(), vol.getUuid(), vol.getName(), vol.getPath()), e);
            storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), vol.getPath());
            return new AttachAnswer(e.toString());
        } catch (final InternalErrorException e) {
            logger.debug(String.format("Failed to attach volume [id: %d, uuid: %s, name: %s, path: %s], due to ", vol.getId(), vol.getUuid(), vol.getName(), vol.getPath()), e);
            return new AttachAnswer(e.toString());
        } catch (final CloudRuntimeException e) {
            logger.debug(String.format("Failed to attach volume: [id: %d, uuid: %s, name: %s, path: %s], due to ", vol.getId(), vol.getUuid(), vol.getName(), vol.getPath()), e);
            return new AttachAnswer(e.toString());
        } finally {
            vol.clearPassphrase();
        }
    }

    @Override
    public Answer dettachVolume(final DettachCommand cmd) {
        final DiskTO disk = cmd.getDisk();
        final VolumeObjectTO vol = (VolumeObjectTO)disk.getData();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)vol.getDataStore();
        final String vmName = cmd.getVmName();
        final String serial = resource.diskUuidToSerial(vol.getUuid());
        long waitDetachDevice = cmd.getWaitDetachDevice();
        try {
            final Connect conn = LibvirtConnection.getConnectionByVmName(vmName);

            final KVMPhysicalDisk phyDisk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), vol.getPath());
            final String volCacheMode = vol.getCacheMode() == null ? null : vol.getCacheMode().toString();

            attachOrDetachDisk(conn, false, vmName, phyDisk, disk.getDiskSeq().intValue(), serial,
                    vol.getBytesReadRate(), vol.getBytesReadRateMax(), vol.getBytesReadRateMaxLength(),
                    vol.getBytesWriteRate(), vol.getBytesWriteRateMax(), vol.getBytesWriteRateMaxLength(),
                    vol.getIopsReadRate(), vol.getIopsReadRateMax(), vol.getIopsReadRateMaxLength(),
                    vol.getIopsWriteRate(), vol.getIopsWriteRateMax(), vol.getIopsWriteRateMaxLength(), volCacheMode, null, waitDetachDevice, null);

            storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), vol.getPath());

            resource.removeCheckpointsOnVm(vmName, vol.getUuid(), vol.getCheckpointPaths());

            return new DettachAnswer(disk);
        } catch (final LibvirtException | InternalErrorException | CloudRuntimeException e) {
            logger.debug(String.format("Failed to detach volume [id: %d, uuid: %s, name: %s, path: %s], due to ", vol.getId(), vol.getUuid(), vol.getName(), vol.getPath()), e);
            return new DettachAnswer(e.toString());
        } finally {
            vol.clearPassphrase();
        }
    }

    /**
     * Create volume with backing file (linked clone)
     */
    protected KVMPhysicalDisk createLinkedCloneVolume(MigrationOptions migrationOptions, KVMStoragePool srcPool, KVMStoragePool primaryPool, VolumeObjectTO volume, PhysicalDiskFormat format, int timeout) {
        String srcBackingFilePath = migrationOptions.getSrcBackingFilePath();
        boolean copySrcTemplate = migrationOptions.isCopySrcTemplate();
        KVMPhysicalDisk srcTemplate = srcPool.getPhysicalDisk(srcBackingFilePath);
        KVMPhysicalDisk destTemplate;
        if (copySrcTemplate) {
            KVMPhysicalDisk copiedTemplate = storagePoolMgr.copyPhysicalDisk(srcTemplate, srcTemplate.getName(), primaryPool, 10000 * 1000);
            destTemplate = primaryPool.getPhysicalDisk(copiedTemplate.getPath());
        } else {
            destTemplate = primaryPool.getPhysicalDisk(srcBackingFilePath);
        }
        return storagePoolMgr.createDiskWithTemplateBacking(destTemplate, volume.getUuid(), format, volume.getSize(),
                primaryPool, timeout, volume.getPassphrase());
    }

    /**
     * Create full clone volume from VM snapshot
     */
    protected KVMPhysicalDisk createFullCloneVolume(MigrationOptions migrationOptions, VolumeObjectTO volume, KVMStoragePool primaryPool, PhysicalDiskFormat format) {
            logger.debug("For VM migration with full-clone volume: Creating empty stub disk for source disk " + migrationOptions.getSrcVolumeUuid() + " and size: " + toHumanReadableSize(volume.getSize()) + " and format: " + format);
        return primaryPool.createPhysicalDisk(volume.getUuid(), format, volume.getProvisioningType(), volume.getSize(), volume.getPassphrase());
    }

    @Override
    public Answer createVolume(final CreateObjectCommand cmd) {
        final VolumeObjectTO volume = (VolumeObjectTO)cmd.getData();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)volume.getDataStore();

        KVMStoragePool primaryPool = null;
        KVMPhysicalDisk vol = null;
        long disksize;
        try {
            primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
            disksize = volume.getSize();
            PhysicalDiskFormat format;
            if (volume.getFormat() == null || StoragePoolType.RBD.equals(primaryStore.getPoolType())) {
                format = primaryPool.getDefaultFormat();
            } else {
                format = PhysicalDiskFormat.valueOf(volume.getFormat().toString().toUpperCase());
            }

            MigrationOptions migrationOptions = volume.getMigrationOptions();
            if (migrationOptions != null) {
                int timeout = migrationOptions.getTimeout();

                if (migrationOptions.getType() == MigrationOptions.Type.LinkedClone) {
                    KVMStoragePool srcPool = getTemplateSourcePoolUsingMigrationOptions(primaryPool, migrationOptions);
                    vol = createLinkedCloneVolume(migrationOptions, srcPool, primaryPool, volume, format, timeout);
                } else if (migrationOptions.getType() == MigrationOptions.Type.FullClone) {
                    vol = createFullCloneVolume(migrationOptions, volume, primaryPool, format);
                }
            } else {
                vol = primaryPool.createPhysicalDisk(volume.getUuid(), format,
                        volume.getProvisioningType(), disksize, volume.getUsableSize(), volume.getPassphrase());
            }

            final VolumeObjectTO newVol = new VolumeObjectTO();
            if(vol != null) {
                newVol.setPath(vol.getName());
                if (vol.getQemuEncryptFormat() != null) {
                    newVol.setEncryptFormat(vol.getQemuEncryptFormat().toString());
                }
                if (vol.getFormat() != null) {
                    format = vol.getFormat();
                }
            }
            newVol.setSize(volume.getSize());
            newVol.setFormat(ImageFormat.valueOf(format.toString().toUpperCase()));

            return new CreateObjectAnswer(newVol);
        } catch (final Exception e) {
            logger.debug("Failed to create volume: ", e);
            return new CreateObjectAnswer(e.toString());
        } finally {
            volume.clearPassphrase();
        }
    }

    /**
     * XML to take disk-only snapshot of the VM.<br><br>
     * 1st parameter: snapshot's name;<br>
     * 2nd parameter: disk's label (target.dev tag from VM's XML);<br>
     * 3rd parameter: absolute path to create the snapshot;<br>
     * 4th parameter: list of disks to avoid on snapshot {@link #TAG_AVOID_DISK_FROM_SNAPSHOT};
     */
    private static final String XML_CREATE_DISK_SNAPSHOT = "<domainsnapshot><name>%s</name><disks><disk name='%s' snapshot='external'><source file='%s'/></disk>%s</disks>"
      + "</domainsnapshot>";

    /**
     * XML to take full VM snapshot.<br><br>
     * 1st parameter: snapshot's name;<br>
     * 2nd parameter: domain's UUID;<br>
     */
    private static final String XML_CREATE_FULL_VM_SNAPSHOT = "<domainsnapshot><name>%s</name><domain><uuid>%s</uuid></domain></domainsnapshot>";

    /**
     * Tag to avoid disk from snapshot.<br><br>
     * 1st parameter: disk's label (target.dev tag from VM's XML);
     */
    private static final String TAG_AVOID_DISK_FROM_SNAPSHOT = "<disk name='%s' snapshot='no' />";

    /**
     * Flag to take disk-only snapshots from VM.<br><br>
     * Libvirt lib for java does not have the enum virDomainSnapshotCreateFlags.
     * @see <a href="https://libvirt.org/html/libvirt-libvirt-domain-snapshot.html">Module libvirt-domain-snapshot from libvirt</a>
     */
    private static final int VIR_DOMAIN_SNAPSHOT_CREATE_DISK_ONLY = 16;

    /**
     * Min rate between available pool and disk size to take disk snapshot.<br><br>
     * As we are copying the base disk to a folder in the same primary storage, we need at least once more disk size of available space in the primary storage, plus 5% as a
     * security margin.
     */
    private static final double MIN_RATE_BETWEEN_AVAILABLE_POOL_AND_DISK_SIZE_TO_TAKE_DISK_SNAPSHOT = 1.05;

    /**
     * Message that can occurs when using a QEMU binary that does not support live disk snapshot (e.g. CentOS 7 QEMU binaries).
     */
    private static final String LIBVIRT_OPERATION_NOT_SUPPORTED_MESSAGE = "Operation not supported";

    @Override
    public Answer createSnapshot(final CreateObjectCommand cmd) {
        final SnapshotObjectTO snapshotTO = (SnapshotObjectTO)cmd.getData();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)snapshotTO.getDataStore();
        DataStoreTO imageStoreTo = snapshotTO.getImageStore();
        final VolumeObjectTO volume = snapshotTO.getVolume();
        final String snapshotName = UUID.randomUUID().toString();
        final String vmName = volume.getVmName();

        try {
            final Connect conn = LibvirtConnection.getConnectionByVmName(vmName);
            DomainInfo.DomainState state = null;
            Domain vm = null;
            if (vmName != null) {
                try {
                    vm = resource.getDomain(conn, vmName);
                    state = vm.getInfo().state;
                } catch (final LibvirtException e) {
                    logger.trace("Ignoring libvirt error.", e);
                }
            }

            if (DomainInfo.DomainState.VIR_DOMAIN_RUNNING.equals(state) && volume.requiresEncryption()) {
                throw new CloudRuntimeException("VM is running, encrypted volume snapshots aren't supported");
            }

            KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
            KVMStoragePool secondaryPool = imageStoreTo != null ? storagePoolMgr.getStoragePoolByURI(imageStoreTo.getUrl()) : null;

            KVMPhysicalDisk disk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volume.getPath());

            String diskPath = disk.getPath();
            String snapshotPath = diskPath + File.separator + snapshotName;
            SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
            if (DomainInfo.DomainState.VIR_DOMAIN_RUNNING.equals(state) && !primaryPool.isExternalSnapshot()) {
                if (snapshotTO.isKvmIncrementalSnapshot()) {
                    newSnapshot = takeIncrementalVolumeSnapshotOfRunningVm(snapshotTO, primaryPool, secondaryPool, imageStoreTo != null ? imageStoreTo.getUrl() : null, snapshotName, volume, vm, conn, cmd.getWait());
                } else {
                    newSnapshot = takeFullVolumeSnapshotOfRunningVm(cmd, primaryPool, secondaryPool, disk, snapshotName, conn, vmName, diskPath, vm, volume, snapshotPath);
                }
            } else {
                if (primaryPool.getType() == StoragePoolType.RBD) {
                    takeRbdVolumeSnapshotOfStoppedVm(primaryPool, disk, snapshotName);
                    newSnapshot.setPath(snapshotPath);
                } else if (primaryPool.getType() == StoragePoolType.CLVM) {
                    CreateObjectAnswer result = takeClvmVolumeSnapshotOfStoppedVm(disk, snapshotName);
                    if (result != null) return result;
                    newSnapshot.setPath(snapshotPath);
                } else {
                    if (snapshotTO.isKvmIncrementalSnapshot()) {
                        newSnapshot = takeIncrementalVolumeSnapshotOfStoppedVm(snapshotTO, primaryPool, secondaryPool, imageStoreTo != null ? imageStoreTo.getUrl() : null, snapshotName, volume, conn, cmd.getWait());
                    } else {
                        newSnapshot = takeFullVolumeSnapshotOfStoppedVm(cmd, primaryPool, secondaryPool, snapshotName, disk, volume);
                    }
                }
            }

            if (secondaryPool != null) {
                storagePoolMgr.deleteStoragePool(secondaryPool.getType(), secondaryPool.getUuid());
            }

            return new CreateObjectAnswer(newSnapshot);
        } catch (CloudRuntimeException | LibvirtException | IOException ex) {
            String errorMsg = String.format("Failed take snapshot for volume [%s], in VM [%s], due to [%s].", volume, vmName, ex.getMessage());
            logger.error(errorMsg, ex);
            return new CreateObjectAnswer(errorMsg);
        } finally {
            volume.clearPassphrase();
        }
    }

    private SnapshotObjectTO createSnapshotToAndUpdatePathAndSize(String path, String fullPath) {
        final File snapFile = new File(fullPath);
        long size = 0;

        if (snapFile.exists()) {
            size = snapFile.length();
        }

        SnapshotObjectTO snapshotObjectTo = new SnapshotObjectTO();

        snapshotObjectTo.setPath(path);
        snapshotObjectTo.setPhysicalSize(size);

        return snapshotObjectTo;
    }

    private SnapshotObjectTO takeIncrementalVolumeSnapshotOfStoppedVm(SnapshotObjectTO snapshotObjectTO, KVMStoragePool primaryPool, KVMStoragePool secondaryPool,
                                                                      String secondaryPoolUrl, String snapshotName, VolumeObjectTO volumeObjectTo, Connect conn, int wait) throws LibvirtException {
        resource.validateLibvirtAndQemuVersionForIncrementalSnapshots();
        Domain vm = null;
        logger.debug("Taking incremental volume snapshot of volume [{}]. Snapshot will be copied to [{}].", volumeObjectTo,
                ObjectUtils.defaultIfNull(secondaryPool, primaryPool));
        try {
            String vmName = String.format("DUMMY-VM-%s", snapshotName);

            String vmXml = getVmXml(primaryPool, volumeObjectTo, vmName);

            logger.debug("Creating dummy VM with volume [{}] to take an incremental snapshot of it.", volumeObjectTo);
            resource.startVM(conn, vmName, vmXml, Domain.CreateFlags.PAUSED);

            vm = resource.getDomain(conn, vmName);

            resource.recreateCheckpointsOnVm(List.of(volumeObjectTo), vmName, conn);

            return takeIncrementalVolumeSnapshotOfRunningVm(snapshotObjectTO, primaryPool, secondaryPool, secondaryPoolUrl, snapshotName, volumeObjectTo, vm, conn, wait);
        } catch (InternalErrorException | LibvirtException | CloudRuntimeException e) {
            logger.error("Failed to take incremental volume snapshot of volume [{}] due to {}.", volumeObjectTo, e.getMessage(), e);
            throw new CloudRuntimeException(e);
        } finally {
            if (vm != null) {
                vm.destroy();
            }
        }
    }

    private String getVmXml(KVMStoragePool primaryPool, VolumeObjectTO volumeObjectTo, String vmName) {
        String machine = resource.isGuestAarch64() ? LibvirtComputingResource.VIRT : LibvirtComputingResource.PC;
        String cpuArch = resource.getGuestCpuArch() != null ? resource.getGuestCpuArch() : "x86_64";

        return String.format(DUMMY_VM_XML, vmName, cpuArch, machine, resource.getHypervisorPath(), primaryPool.getLocalPathFor(volumeObjectTo.getPath()));
    }

    private SnapshotObjectTO takeIncrementalVolumeSnapshotOfRunningVm(SnapshotObjectTO snapshotObjectTO, KVMStoragePool primaryPool, KVMStoragePool secondaryPool,
                                                                      String secondaryPoolUrl, String snapshotName, VolumeObjectTO volumeObjectTo, Domain vm, Connect conn, int wait) {
        logger.debug("Taking incremental volume snapshot of volume [{}] attached to running VM [{}]. Snapshot will be copied to [{}].", volumeObjectTo, volumeObjectTo.getVmName(),
                ObjectUtils.defaultIfNull(secondaryPool, primaryPool));
        resource.validateLibvirtAndQemuVersionForIncrementalSnapshots();

        Pair<String, String> fullSnapshotPathAndDirPath = getFullSnapshotOrCheckpointPathAndDirPathOnCorrectStorage(primaryPool, secondaryPool, snapshotName, volumeObjectTo, false);

        String diskLabel;
        String vmName;
        try {
            List<DiskDef> disks = resource.getDisks(conn, vm.getName());
            diskLabel = getDiskLabelToSnapshot(disks, volumeObjectTo.getPath(), vm);
            vmName = vm.getName();
        } catch (LibvirtException e) {
            logger.error("Failed to get VM's disks or VM name due to: [{}].", e.getMessage(), e);
            throw new CloudRuntimeException(e);
        }

        String[] parents = snapshotObjectTO.getParents();
        String fullSnapshotPath = fullSnapshotPathAndDirPath.first();

        String backupXml = generateBackupXml(volumeObjectTo, parents, diskLabel, fullSnapshotPath);
        String checkpointXml = String.format(CHECKPOINT_XML, snapshotName, diskLabel);

        Path backupXmlPath = createFileAndWrite(backupXml, BACKUP_XML_TEMP_DIR, snapshotName);
        Path checkpointXmlPath = createFileAndWrite(checkpointXml, CHECKPOINT_XML_TEMP_DIR, snapshotName);

        String backupCommand = String.format(BACKUP_BEGIN_COMMAND, vmName, backupXmlPath.toString(), checkpointXmlPath.toString());

        createFolderOnCorrectStorage(primaryPool, secondaryPool, fullSnapshotPathAndDirPath);

        if (Script.runSimpleBashScript(backupCommand) == null) {
            throw new CloudRuntimeException(String.format("Error backing up using backupXML [%s], checkpointXML [%s] for volume [%s].", backupXml, checkpointXml,
                    volumeObjectTo));
        }

        try {
            waitForBackup(vmName);
        } catch (CloudRuntimeException ex) {
            cancelBackupJob(snapshotObjectTO);
            throw ex;
        }

        rebaseSnapshot(snapshotObjectTO, secondaryPool, secondaryPoolUrl, fullSnapshotPath, snapshotName, parents, wait);

        try {
            Files.setPosixFilePermissions(Path.of(fullSnapshotPath), PosixFilePermissions.fromString("rw-r--r--"));
        } catch (IOException ex) {
            logger.warn("Failed to change permissions of snapshot [{}], snapshot download will not be possible.", snapshotName);
        }

        String checkpointPath = dumpCheckpoint(primaryPool, secondaryPool, snapshotName, volumeObjectTo, vmName, parents);

        SnapshotObjectTO result = createSnapshotToAndUpdatePathAndSize(secondaryPool == null ? fullSnapshotPath : fullSnapshotPathAndDirPath.second() + File.separator + snapshotName,
                fullSnapshotPath);

        result.setCheckpointPath(checkpointPath);

        return result;
    }

    protected void createFolderOnCorrectStorage(KVMStoragePool primaryPool, KVMStoragePool secondaryPool, Pair<String, String> fullSnapshotPathAndDirPath) {
        if (secondaryPool == null) {
            primaryPool.createFolder(fullSnapshotPathAndDirPath.second());
        } else {
            secondaryPool.createFolder(fullSnapshotPathAndDirPath.second());
        }
    }

    protected String generateBackupXml(VolumeObjectTO volumeObjectTo, String[] parents, String diskLabel, String fullSnapshotPath) {
        if (parents == null) {
            logger.debug("Snapshot of volume [{}] does not have a parent, taking a full snapshot.", volumeObjectTo);
            return String.format(BACKUP_XML, diskLabel, fullSnapshotPath);
        } else {
            logger.debug("Snapshot of volume [{}] has parents [{}], taking an incremental snapshot.", volumeObjectTo, Arrays.toString(parents));
            String parentCheckpointName = getParentCheckpointName(parents);
            return String.format(INCREMENTAL_BACKUP_XML, parentCheckpointName, diskLabel, fullSnapshotPath);
        }
    }

    private void waitForBackup(String vmName) throws CloudRuntimeException {
        int timeout = incrementalSnapshotTimeout;
        logger.debug("Waiting for backup of VM [{}] to finish, timeout is [{}].", vmName, timeout);

        String result;

        while (timeout > 0) {
            result = checkBackupJob(vmName);

            if (result.contains("Completed") && result.contains("Backup")) {
                return;
            }

            timeout -= 10000;
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new CloudRuntimeException(e);
            }
        }

        throw new CloudRuntimeException(String.format("Timeout while waiting for incremental snapshot for VM [%s] to finish.", vmName));
    }

    private void cancelBackupJob(SnapshotObjectTO snapshotObjectTO) {
        Script.runSimpleBashScript(String.format(DOMJOBABORT_COMMAND, snapshotObjectTO.getVmName()));

        String result = checkBackupJob(snapshotObjectTO.getVmName());

        if (result.contains("Backup") && result.contains("Cancelled")) {
            logger.debug("Successfully canceled incremental snapshot job.");
        } else {
            logger.warn("Couldn't cancel the incremental snapshot job correctly. Job status is [{}].", result);
        }
    }

    private String checkBackupJob(String vmName) {
        return Script.runSimpleBashScriptWithFullResult(String.format(DOMJOBINFO_COMPLETED_COMMAND, vmName), 10);
    }

    protected void rebaseSnapshot(SnapshotObjectTO snapshotObjectTO, KVMStoragePool secondaryPool, String secondaryUrl, String snapshotPath, String snapshotName, String[] parents, int wait) {
        if (parents == null) {
            logger.debug("No need to rebase snapshot [{}], this snapshot has no parents, therefore it is the first on its backing chain.", snapshotName);
            return;
        }
        String parentSnapshotPath;

        if (secondaryPool == null) {
            parentSnapshotPath = parents[parents.length - 1];
        } else if (!secondaryUrl.equals(snapshotObjectTO.getParentStore().getUrl())) {
            KVMStoragePool parentPool = storagePoolMgr.getStoragePoolByURI(snapshotObjectTO.getParentStore().getUrl());
            parentSnapshotPath = parentPool.getLocalPath() + File.separator + parents[parents.length - 1];
            storagePoolMgr.deleteStoragePool(parentPool.getType(), parentPool.getUuid());
        } else {
            parentSnapshotPath = secondaryPool.getLocalPath() + File.separator + parents[parents.length - 1];
        }

        QemuImgFile snapshotFile = new QemuImgFile(snapshotPath);
        QemuImgFile parentSnapshotFile = new QemuImgFile(parentSnapshotPath);

        logger.debug("Rebasing snapshot [{}] with parent [{}].", snapshotName, parentSnapshotPath);

        try {
            QemuImg qemuImg = new QemuImg(wait);
            qemuImg.rebase(snapshotFile, parentSnapshotFile, PhysicalDiskFormat.QCOW2.toString(), false);
        } catch (LibvirtException | QemuImgException e) {
            logger.error("Exception while rebasing incremental snapshot [{}] due to: [{}].", snapshotName, e.getMessage(), e);
            throw new CloudRuntimeException(e);
        }
    }

    protected String getParentCheckpointName(String[] parents) {
        String immediateParentPath = parents[parents.length - 1];
        return immediateParentPath.substring(immediateParentPath.lastIndexOf(File.separator) + 1);
    }

    private Path createFileAndWrite(String content, String dir, String fileName) {
        File dirFile = new File(dir);
        if (!dirFile.exists()) {
            dirFile.mkdirs();
        }

        Path filePath = Path.of(dirFile.getPath(), fileName);
        try {
            return Files.write(filePath, content.getBytes());
        } catch (IOException ex) {
            String message = String.format("Error while writing file [%s].", filePath);
            logger.error(message, ex);
            throw new CloudRuntimeException(message, ex);
        }
    }

    private String dumpCheckpoint(KVMStoragePool primaryPool, KVMStoragePool secondaryPool, String snapshotName, VolumeObjectTO volumeObjectTo, String vmName, String[] snapshotParents) {
        String result = Script.runSimpleBashScriptWithFullResult(String.format(CHECKPOINT_DUMP_XML_COMMAND, vmName, snapshotName), 10);

        String snapshotParent = null;
        if (snapshotParents != null) {
            String snapshotParentPath = snapshotParents[snapshotParents.length - 1];
            snapshotParent = snapshotParentPath.substring(snapshotParentPath.lastIndexOf(File.separator) + 1);
        }

        return cleanupCheckpointXmlDumpCheckpointAndRedefine(result, primaryPool, secondaryPool, snapshotName, volumeObjectTo, snapshotParent, vmName);
    }

    private String cleanupCheckpointXmlDumpCheckpointAndRedefine(String checkpointXml, KVMStoragePool primaryPool, KVMStoragePool secondaryPool, String snapshotName, VolumeObjectTO volumeObjectTo, String snapshotParent, String vmName) {
        String updatedCheckpointXml;
        try {
            updatedCheckpointXml = updateCheckpointXml(checkpointXml, snapshotParent);
        } catch (TransformerException | ParserConfigurationException | IOException | SAXException |
                 XPathExpressionException e) {
            logger.error("Exception while parsing checkpoint XML [{}].", checkpointXml, e);
            throw new CloudRuntimeException(e);
        }

        Pair<String, String> checkpointFullPathAndDirPath = getFullSnapshotOrCheckpointPathAndDirPathOnCorrectStorage(primaryPool, secondaryPool, snapshotName, volumeObjectTo, true);

        String fullPath = checkpointFullPathAndDirPath.first();
        String dirPath = checkpointFullPathAndDirPath.second();

        KVMStoragePool workPool = ObjectUtils.defaultIfNull(secondaryPool, primaryPool);
        workPool.createFolder(dirPath);

        logger.debug("Saving checkpoint of volume [{}], attached to VM [{}], referring to snapshot [{}] to path [{}].", volumeObjectTo, vmName, snapshotName, fullPath);
        createFileAndWrite(updatedCheckpointXml, workPool.getLocalPath() + File.separator + dirPath, snapshotName);

        logger.debug("Redefining checkpoint on VM [{}].", vmName);
        Script.runSimpleBashScript(String.format(LibvirtComputingResource.CHECKPOINT_CREATE_COMMAND, vmName, fullPath));

        return fullPath;
    }

    /**
     * Updates the checkpoint XML, setting the parent to {@code snapshotParent} and removing any disks that were not backed up.
     * @param checkpointXml checkpoint XML to be parsed
     * @param snapshotParent snapshot parent
     * */
    private String updateCheckpointXml(String checkpointXml, String snapshotParent) throws ParserConfigurationException, XPathExpressionException, IOException, SAXException, TransformerException {
        logger.debug("Parsing checkpoint XML [{}].", checkpointXml);

        InputStream in = IOUtils.toInputStream(checkpointXml);
        DocumentBuilderFactory docFactory = ParserUtils.getSaferDocumentBuilderFactory();
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
        Document doc = docBuilder.parse(in);
        XPath xPath = XPathFactory.newInstance().newXPath();

        updateParent(snapshotParent, doc, xPath);

        removeUnnecessaryDisks(doc, xPath);

        String finalXml = LibvirtXMLParser.getXml(doc);

        logger.debug("Checkpoint XML after parsing is [{}].", finalXml);

        return finalXml;
    }

    /**
     * Removes all the disk definitions on the checkpoint XML from disks that were not affected.
     * @param checkpointXml the checkpoint XML to be updated.
     * */
    private void removeUnnecessaryDisks(Document checkpointXml, XPath xPath) throws XPathExpressionException {
        Node disksNode = (Node) xPath.compile("/domaincheckpoint/disks").evaluate(checkpointXml, XPathConstants.NODE);
        NodeList disksNodeChildren = disksNode.getChildNodes();
        for (int j = 0; j < disksNodeChildren.getLength(); j++) {
            Node diskNode = disksNodeChildren.item(j);
            if (diskNode == null) {
                continue;
            }
            if ("disk".equals(diskNode.getNodeName()) && "no".equals(diskNode.getAttributes().getNamedItem("checkpoint").getNodeValue())) {
                disksNode.removeChild(diskNode);
                logger.trace("Removing node [{}].", diskNode);
            }
        }
    }

    /**
     * Updates the parent on the {@code checkpointXml} to {@code snapshotParent}. If {@code snapshotParent} is null, removes the parent.
     * @param checkpointXml the checkpoint XML to be updated
     * @param snapshotParent the snapshot parent. Inform null if no parent.
     * */
    private void updateParent(String snapshotParent, Document checkpointXml, XPath xPath) throws XPathExpressionException {
        if (snapshotParent == null) {
            Object parentNodeObject = xPath.compile("/domaincheckpoint/parent").evaluate(checkpointXml, XPathConstants.NODE);
            if (parentNodeObject == null) {
                return;
            }
            Node parentNode = (Node) parentNodeObject;
            parentNode.getParentNode().removeChild(parentNode);
            return;
        }

        Node parentNameNode = (Node) xPath.compile("/domaincheckpoint/parent/name").evaluate(checkpointXml, XPathConstants.NODE);
        parentNameNode.setTextContent(snapshotParent);
    }

    /**
     * If imageStore is not null, copy the snapshot directly to secondary storage, else, copy it to the primary storage.
     *
     * @return SnapshotObjectTO of the new snapshot.
     * */
    private SnapshotObjectTO takeFullVolumeSnapshotOfRunningVm(CreateObjectCommand cmd, KVMStoragePool primaryPool, KVMStoragePool secondaryPool, KVMPhysicalDisk disk, String snapshotName,
                                                               Connect conn, String vmName, String diskPath, Domain vm, VolumeObjectTO volume, String snapshotPath) throws IOException, LibvirtException {
        logger.debug("Taking full volume snapshot of volume [{}] attached to running VM [{}]. Snapshot will be copied to [{}].", volume, vmName,
                ObjectUtils.defaultIfNull(secondaryPool, primaryPool));

        validateAvailableSizeOnPoolToTakeVolumeSnapshot(primaryPool, disk);
        String relativePath = null;
        try {
            String diskLabel = takeVolumeSnapshot(resource.getDisks(conn, vmName), snapshotName, diskPath, vm);

            Pair<String, String> fullSnapPathAndDirPath = getFullSnapshotOrCheckpointPathAndDirPathOnCorrectStorage(primaryPool, secondaryPool, snapshotName, volume, false);

            snapshotPath = fullSnapPathAndDirPath.first();
            String directoryPath = fullSnapPathAndDirPath.second();
            relativePath = directoryPath + File.separator + snapshotName;

            String convertResult = convertBaseFileToSnapshotFileInStorageDir(ObjectUtils.defaultIfNull(secondaryPool, primaryPool), disk, snapshotPath, directoryPath, volume, cmd.getWait());

            resource.mergeSnapshotIntoBaseFile(vm, diskLabel, diskPath, null, true, snapshotName, volume, conn);

            validateConvertResult(convertResult, snapshotPath);
        } catch (LibvirtException e) {
            if (!e.getMessage().contains(LIBVIRT_OPERATION_NOT_SUPPORTED_MESSAGE)) {
                throw e;
            }

            logger.info("It was not possible to take live disk snapshot for volume [{}], in VM [{}], due to [{}]. We will take full snapshot of the VM"
                    + " and extract the disk instead. Consider upgrading your QEMU binary.", volume, vmName, e.getMessage());

            takeFullVmSnapshotForBinariesThatDoesNotSupportLiveDiskSnapshot(vm, snapshotName, vmName);
            primaryPool.createFolder(TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR);
            extractDiskFromFullVmSnapshot(disk, volume, snapshotPath, snapshotName, vmName, vm);
        }

        /*
         * libvirt on RHEL6 doesn't handle resume event emitted from
         * qemu
         */
        vm = resource.getDomain(conn, vmName);
        DomainInfo.DomainState state = vm.getInfo().state;
        if (state == DomainInfo.DomainState.VIR_DOMAIN_PAUSED) {
            vm.resume();
        }

        return createSnapshotToAndUpdatePathAndSize(secondaryPool == null ? snapshotPath : relativePath, snapshotPath);
    }


    private SnapshotObjectTO takeFullVolumeSnapshotOfStoppedVm(CreateObjectCommand cmd, KVMStoragePool primaryPool, KVMStoragePool secondaryPool, String snapshotName, KVMPhysicalDisk disk, VolumeObjectTO volume) throws IOException {
        logger.debug("Taking full volume snapshot of volume [{}]. Snapshot will be copied to [{}].", volume,
                ObjectUtils.defaultIfNull(secondaryPool, primaryPool));
        Pair<String, String> fullSnapPathAndDirPath = getFullSnapshotOrCheckpointPathAndDirPathOnCorrectStorage(primaryPool, secondaryPool, snapshotName, volume, false);

        String snapshotPath = fullSnapPathAndDirPath.first();
        String directoryPath = fullSnapPathAndDirPath.second();
        String relativePath = directoryPath + File.separator + snapshotName;

        String convertResult = convertBaseFileToSnapshotFileInStorageDir(ObjectUtils.defaultIfNull(secondaryPool, primaryPool), disk, snapshotPath, directoryPath, volume, cmd.getWait());

        validateConvertResult(convertResult, snapshotPath);

        return createSnapshotToAndUpdatePathAndSize(secondaryPool == null ? snapshotPath : relativePath, snapshotPath);
    }

    private CreateObjectAnswer takeClvmVolumeSnapshotOfStoppedVm(KVMPhysicalDisk disk, String snapshotName) {
        /* VM is not running, create a snapshot by ourself */
        final Script command = new Script(_manageSnapshotPath, _cmdsTimeout, logger);
        command.add(MANAGE_SNAPSTHOT_CREATE_OPTION, disk.getPath());
        command.add(NAME_OPTION, snapshotName);
        final String result = command.execute();
        if (result != null) {
            String message = String.format("Failed to manage snapshot [%s] due to: [%s].", snapshotName, result);
            logger.debug(message);
            return new CreateObjectAnswer(message);
        }
        return null;
    }

    /**
     * For RBD we can't use libvirt to do our snapshotting or any Bash scripts.
     * libvirt also wants to store the memory contents of the Virtual Machine,
     * but that's not possible with RBD since there is no way to store the memory
     * contents in RBD.
     * <p>
     * So we rely on the Java bindings for RBD to create our snapshot
     * <p>
     * This snapshot might not be 100% consistent due to writes still being in the
     * memory of the Virtual Machine, but if the VM runs a kernel which supports
     * barriers properly (>2.6.32) this won't be any different then pulling the power
     * cord out of a running machine.
     */
    private void takeRbdVolumeSnapshotOfStoppedVm(KVMStoragePool primaryPool, KVMPhysicalDisk disk, String snapshotName) {
        try {
            Rados r = radosConnect(primaryPool);

            final IoCTX io = r.ioCtxCreate(primaryPool.getSourceDir());
            final Rbd rbd = new Rbd(io);
            final RbdImage image = rbd.open(disk.getName());

            logger.debug("Attempting to create RBD snapshot {}@{}", disk.getName(), snapshotName);
            image.snapCreate(snapshotName);

            rbd.close(image);
            r.ioCtxDestroy(io);
        } catch (final Exception e) {
            logger.error("A RBD snapshot operation on [{}] failed. The error was: {}", disk.getName(), e.getMessage(), e);
        }
    }

    /**
     * Retrieves the disk label to take snapshot;
     * @param disks List of VM's disks;
     * @param diskPath Path of the disk to take snapshot;
     * @param vm VM in which disks are;
     * @return the label to take snapshot. If the disk path is not found in VM's XML, it will throw a CloudRuntimeException.
     * @throws org.libvirt.LibvirtException if the disk is not found
     */
    protected String getDiskLabelToSnapshot(List<DiskDef> disks, String diskPath, Domain vm) throws LibvirtException {
        logger.debug("Searching disk label of disk with path [{}] on VM [{}].", diskPath, vm.getName());
        for (DiskDef disk : disks) {
            String diskDefPath = disk.getDiskPath();

            if (StringUtils.isEmpty(diskDefPath)) {
                continue;
            }

            if (!diskDefPath.contains(diskPath)) {
                continue;
            }
            logger.debug("Found disk label [{}] for volume with path [{}] on VM [{}].", disk.getDiskLabel(), diskPath, vm.getName());

            return disk.getDiskLabel();
        }

        throw new CloudRuntimeException(String.format("VM [%s] has no disk with path [%s]. VM's XML [%s].", vm.getName(), diskPath, vm.getXMLDesc(0)));
    }

    /**
     * Gets the fully qualified path of the snapshot or checkpoint and the directory path. If a secondary pool is informed, the path will be on the secondary pool,
     * otherwise, the path will be on the primary pool.
     * @param primaryPool Primary pool definition, the path returned will be here if no secondary pool is informed;
     * @param secondaryPool Secondary pool definition. If informed, the primary pool will be ignored and the path returned will be on the secondary pool;
     * @param snapshotName Name of the snapshot;
     * @param volume Volume that is being snapshot;
     * @param checkpoint Whether to return a path for a snapshot or a snapshot's checkpoint;
     * @return Fully qualified path and the directory path of the snapshot/checkpoint.
     * */
    private Pair<String, String> getFullSnapshotOrCheckpointPathAndDirPathOnCorrectStorage(KVMStoragePool primaryPool, KVMStoragePool secondaryPool, String snapshotName,
                                                                                           VolumeObjectTO volume, boolean checkpoint) {
        String fullSnapshotPath;
        String dirPath;

        if (secondaryPool == null) {
            fullSnapshotPath = getSnapshotOrCheckpointPathInPrimaryStorage(primaryPool.getLocalPath(), snapshotName, checkpoint);
            dirPath = checkpoint ? TemplateConstants.DEFAULT_CHECKPOINT_ROOT_DIR : TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR;
        } else {
            Pair<String, String> fullPathAndDirectoryPath = getSnapshotOrCheckpointPathAndDirectoryPathInSecondaryStorage(secondaryPool.getLocalPath(), snapshotName,
                    volume.getAccountId(), volume.getVolumeId(), checkpoint);

            fullSnapshotPath = fullPathAndDirectoryPath.first();
            dirPath = fullPathAndDirectoryPath.second();
        }
        return new Pair<>(fullSnapshotPath, dirPath);
    }

    protected void deleteFullVmSnapshotAfterConvertingItToExternalDiskSnapshot(Domain vm, String snapshotName, VolumeObjectTO volume, String vmName) throws LibvirtException {
        logger.debug(String.format("Deleting full VM snapshot [%s] of VM [%s] as we already converted it to an external disk snapshot of the volume [%s].", snapshotName, vmName,
                volume));

        DomainSnapshot domainSnapshot = vm.snapshotLookupByName(snapshotName);
        domainSnapshot.delete(0);
    }

    protected void extractDiskFromFullVmSnapshot(KVMPhysicalDisk disk, VolumeObjectTO volume, String snapshotPath, String snapshotName, String vmName, Domain vm)
            throws LibvirtException {
        QemuImgFile srcFile = new QemuImgFile(disk.getPath(), disk.getFormat());
        QemuImgFile destFile = new QemuImgFile(snapshotPath, disk.getFormat());

        try {
            QemuImg qemuImg = new QemuImg(_cmdsTimeout);
            logger.debug(String.format("Converting full VM snapshot [%s] of VM [%s] to external disk snapshot of the volume [%s].", snapshotName, vmName, volume));
            qemuImg.convert(srcFile, destFile, null, snapshotName, true);
        } catch (QemuImgException qemuException) {
            String message = String.format("Could not convert full VM snapshot [%s] of VM [%s] to external disk snapshot of volume [%s] due to [%s].", snapshotName, vmName, volume,
                    qemuException.getMessage());

            logger.error(message, qemuException);
            throw new CloudRuntimeException(message, qemuException);
        } finally {
            deleteFullVmSnapshotAfterConvertingItToExternalDiskSnapshot(vm, snapshotName, volume, vmName);
        }
    }

    protected void takeFullVmSnapshotForBinariesThatDoesNotSupportLiveDiskSnapshot(Domain vm, String snapshotName, String vmName) throws LibvirtException {
        String vmUuid = vm.getUUIDString();

        long start = System.currentTimeMillis();
        vm.snapshotCreateXML(String.format(XML_CREATE_FULL_VM_SNAPSHOT, snapshotName, vmUuid));
        logger.debug(String.format("Full VM Snapshot [%s] of VM [%s] took [%s] seconds to finish.", snapshotName, vmName, (System.currentTimeMillis() - start)/1000));
    }

    protected void validateConvertResult(String convertResult, String snapshotPath) throws CloudRuntimeException, IOException {
        if (convertResult == null) {
            return;
        }

        Files.deleteIfExists(Paths.get(snapshotPath));
        throw new CloudRuntimeException(convertResult);
    }

    /**
     * Creates the snapshot directory in the primary storage, if it does not exist; then, converts the base file (VM's old writing file) to the snapshot directory.
     * @param pool         Storage to create folder, if not exists;
     * @param baseFile     Base file of VM, which will be converted;
     * @param snapshotPath Path to convert the base file;
     * @param snapshotFolder Folder where the snapshot will be converted to;
     * @param volume Volume being snapshot, used for logging only;
     * @param wait timeout;
     * @return null if the conversion occurs successfully or an error message that must be handled.
     */

    protected String convertBaseFileToSnapshotFileInStorageDir(KVMStoragePool pool,
            KVMPhysicalDisk baseFile, String snapshotPath, String snapshotFolder, VolumeObjectTO volume, int wait) {
        try (KeyFile srcKey = new KeyFile(volume.getPassphrase())) {
            logger.debug(
                    "Trying to convert volume [{}] ({}) to snapshot [{}].", volume, baseFile, snapshotPath);

            pool.createFolder(snapshotFolder);
            convertTheBaseFileToSnapshot(baseFile, snapshotPath, wait, srcKey);
        } catch (QemuImgException | LibvirtException | IOException ex) {
            return String.format("Failed to convert %s snapshot of volume [%s] to [%s] due to [%s].", volume, baseFile,
                    snapshotPath, ex.getMessage());
        }

        logger.debug(String.format("Converted volume [%s] (from path \"%s\") to snapshot [%s].", volume, baseFile,
                snapshotPath));
        return null;
    }

    private void convertTheBaseFileToSnapshot(KVMPhysicalDisk baseFile, String snapshotPath, int wait, KeyFile srcKey)
            throws LibvirtException, QemuImgException {
        List<QemuObject> qemuObjects = new ArrayList<>();
        Map<String, String> options = new HashMap<>();
        QemuImageOptions qemuImageOpts = new QemuImageOptions(baseFile.getPath());
        if (srcKey.isSet()) {
            String srcKeyName = "sec0";
            qemuObjects.add(QemuObject.prepareSecretForQemuImg(baseFile.getFormat(), EncryptFormat.LUKS,
                    srcKey.toString(), srcKeyName, options));
            qemuImageOpts = new QemuImageOptions(baseFile.getFormat(), baseFile.getPath(), srcKeyName);
        }
        QemuImgFile srcFile = new QemuImgFile(baseFile.getPath());
        srcFile.setFormat(PhysicalDiskFormat.QCOW2);

        QemuImgFile destFile = new QemuImgFile(snapshotPath);
        destFile.setFormat(PhysicalDiskFormat.QCOW2);

        QemuImg q = new QemuImg(wait);
        q.convert(srcFile, destFile, options, qemuObjects, qemuImageOpts, null, true);
    }

    protected String getSnapshotOrCheckpointPathInPrimaryStorage(String primaryStoragePath, String snapshotName, boolean checkpoint) {
        String rootDir = checkpoint ? TemplateConstants.DEFAULT_CHECKPOINT_ROOT_DIR : TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR;
        return String.format("%s%s%s%s%s", primaryStoragePath, File.separator, rootDir, File.separator, snapshotName);
    }

    /**
     * Retrieves the path of the snapshot or snapshot's checkpoint on secondary storage snapshot's dir.
     * @param secondaryStoragePath Path of the secondary storage;
     * @param snapshotName Snapshot name;
     * @param accountId accountId;
     * @param volumeId volumeId;
     * @param checkpoint Whether to return a path for a snapshot or a snapshot's checkpoint;
     * @return the path of the snapshot or snapshot's checkpoint in secondary storage and the snapshot's dir.
     */
    protected Pair<String, String> getSnapshotOrCheckpointPathAndDirectoryPathInSecondaryStorage(String secondaryStoragePath, String snapshotName, long accountId, long volumeId, boolean checkpoint) {
        String rootDir = checkpoint ? TemplateConstants.DEFAULT_CHECKPOINT_ROOT_DIR : TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR;
        String snapshotParentDirectories = String.format("%s%s%s%s%s", rootDir, File.separator, accountId, File.separator, volumeId);
        String fullSnapPath = String.format("%s%s%s%s%s", secondaryStoragePath, File.separator, snapshotParentDirectories, File.separator, snapshotName);

        return new Pair<>(fullSnapPath, snapshotParentDirectories);
    }

    /**
     * Take a volume snapshot of the specified volume.
     * @param disks List of VM's disks;
     * @param snapshotName Name of the snapshot;
     * @param diskPath Path of the disk to take snapshot;
     * @param vm VM in which disk stay;
     * @return the disk label in VM's XML.
     * @throws LibvirtException
     */
    protected String takeVolumeSnapshot(List<DiskDef> disks, String snapshotName, String diskPath, Domain vm) throws LibvirtException{
        Pair<String, Set<String>> diskToSnapshotAndDisksToAvoid = getDiskToSnapshotAndDisksToAvoid(disks, diskPath, vm);
        String diskLabelToSnapshot = diskToSnapshotAndDisksToAvoid.first();
        String disksToAvoidsOnSnapshot = diskToSnapshotAndDisksToAvoid.second().stream().map(diskLabel -> String.format(TAG_AVOID_DISK_FROM_SNAPSHOT, diskLabel))
          .collect(Collectors.joining());
        String snapshotTemporaryPath = resource.getSnapshotTemporaryPath(diskPath, snapshotName);

        String createSnapshotXmlFormated = String.format(XML_CREATE_DISK_SNAPSHOT, snapshotName, diskLabelToSnapshot, snapshotTemporaryPath, disksToAvoidsOnSnapshot);

        long start = System.currentTimeMillis();
        vm.snapshotCreateXML(createSnapshotXmlFormated, VIR_DOMAIN_SNAPSHOT_CREATE_DISK_ONLY);
        logger.debug(String.format("Snapshot [%s] took [%s] seconds to finish.", snapshotName, (System.currentTimeMillis() - start)/1000));

        return diskLabelToSnapshot;
    }

    /**
     * Retrieves the disk label to take snapshot and, in case that there is more than one disk attached to VM, the disk labels to avoid the snapshot;
     * @param disks List of VM's disks;
     * @param diskPath Path of the disk to take snapshot;
     * @param vm VM in which disks stay;
     * @return the label to take snapshot and the labels to avoid it. If the disk path not be found in VM's XML or be found more than once, it will throw a CloudRuntimeException.
     * @throws org.libvirt.LibvirtException
     */
    protected Pair<String, Set<String>> getDiskToSnapshotAndDisksToAvoid(List<DiskDef> disks, String diskPath, Domain vm) throws LibvirtException {
        String diskLabelToSnapshot = null;
        Set<String> disksToAvoid = new HashSet<>();

        for (DiskDef disk : disks) {
            String diskDefPath = disk.getDiskPath();

            if (StringUtils.isEmpty(diskDefPath)) {
                continue;
            }

            String diskLabel = disk.getDiskLabel();

            if (!diskPath.equals(diskDefPath)) {
                disksToAvoid.add(diskLabel);
                continue;
            }

            if (diskLabelToSnapshot != null) {
                throw new CloudRuntimeException(String.format("VM [%s] has more than one disk with path [%s]. VM's XML [%s].", vm.getName(), diskPath, vm.getXMLDesc(0)));
            }

            diskLabelToSnapshot = diskLabel;
        }

        if (diskLabelToSnapshot == null) {
            throw new CloudRuntimeException(String.format("VM [%s] has no disk with path [%s]. VM's XML [%s].", vm.getName(), diskPath, vm.getXMLDesc(0)));
        }

        return new Pair<>(diskLabelToSnapshot, disksToAvoid);
    }

    /**
     * Validate if the primary storage has enough capacity to take a disk snapshot, as the snapshot will duplicate the disk to backup.
     * @param primaryPool Primary storage to verify capacity;
     * @param disk Disk that will be snapshotted.
     */
    protected void validateAvailableSizeOnPoolToTakeVolumeSnapshot(KVMStoragePool primaryPool, KVMPhysicalDisk disk) {
        long availablePoolSize = primaryPool.getAvailable();
        String poolDescription = new ToStringBuilder(primaryPool, ToStringStyle.JSON_STYLE).append("uuid", primaryPool.getUuid()).append("localPath", primaryPool.getLocalPath())
                .toString();
        String diskDescription = new ToStringBuilder(disk, ToStringStyle.JSON_STYLE).append("name", disk.getName()).append("path", disk.getPath()).append("size", disk.getSize())
                .toString();

        if (isAvailablePoolSizeDividedByDiskSizeLesserThanMinRate(availablePoolSize, disk.getSize())) {
            throw new CloudRuntimeException(String.format("Pool [%s] available size [%s] must be at least once more of disk [%s] size, plus 5%%. Not taking snapshot.", poolDescription, availablePoolSize,
                diskDescription));
        }

        logger.debug(String.format("Pool [%s] has enough available size [%s] to take volume [%s] snapshot.", poolDescription, availablePoolSize, diskDescription));
    }

    protected boolean isAvailablePoolSizeDividedByDiskSizeLesserThanMinRate(long availablePoolSize, long diskSize) {
        return ((availablePoolSize * 1d) / (diskSize * 1d)) < MIN_RATE_BETWEEN_AVAILABLE_POOL_AND_DISK_SIZE_TO_TAKE_DISK_SNAPSHOT;
    }

    private Rados radosConnect(final KVMStoragePool primaryPool) throws RadosException {
        Rados r = new Rados(primaryPool.getAuthUserName());
        r.confSet(CEPH_MON_HOST, primaryPool.getSourceHost() + ":" + primaryPool.getSourcePort());
        r.confSet(CEPH_AUTH_KEY, primaryPool.getAuthSecret());
        r.confSet(CEPH_CLIENT_MOUNT_TIMEOUT, CEPH_DEFAULT_MOUNT_TIMEOUT);
        r.connect();
        logger.debug("Successfully connected to Ceph cluster at " + r.confGet(CEPH_MON_HOST));
        return r;
    }

    @Override
    public Answer deleteVolume(final DeleteCommand cmd) {
        final VolumeObjectTO vol = (VolumeObjectTO)cmd.getData();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)vol.getDataStore();
        try {
            final KVMStoragePool pool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
            try {
                pool.getPhysicalDisk(vol.getPath());
            } catch (final Exception e) {
                logger.debug(String.format("can't find volume: %s, return true", vol));
                return new Answer(null);
            }
            pool.deletePhysicalDisk(vol.getPath(), vol.getFormat());
            return new Answer(null);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to delete volume: ", e);
            return new Answer(null, false, e.toString());
        } finally {
            vol.clearPassphrase();
        }
    }

    @Override
    public Answer createVolumeFromSnapshot(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final SnapshotObjectTO snapshot = (SnapshotObjectTO)srcData;
        final VolumeObjectTO volume = snapshot.getVolume();
        try {
            final DataTO destData = cmd.getDestTO();
            final PrimaryDataStoreTO pool = (PrimaryDataStoreTO)destData.getDataStore();
            final DataStoreTO imageStore = srcData.getDataStore();

            if (!(imageStore instanceof NfsTO || imageStore instanceof PrimaryDataStoreTO)) {
                return new CopyCmdAnswer("unsupported protocol");
            }

            final String snapshotFullPath = snapshot.getPath();
            final int index = snapshotFullPath.lastIndexOf("/");
            final String snapshotPath = snapshotFullPath.substring(0, index);
            final String snapshotName = snapshotFullPath.substring(index + 1);
            KVMPhysicalDisk disk = null;
            if (imageStore instanceof NfsTO) {
                disk = createVolumeFromSnapshotOnNFS(cmd, pool, imageStore, volume, snapshotPath, snapshotName);
            } else {
                disk = createVolumeFromRBDSnapshot(cmd, destData, pool, imageStore, volume, snapshotName, disk);
            }

            if (disk == null) {
                return new CopyCmdAnswer("Could not create volume from snapshot");
            }
            final VolumeObjectTO newVol = new VolumeObjectTO();
            newVol.setPath(disk.getName());
            newVol.setSize(disk.getVirtualSize());
            newVol.setFormat(ImageFormat.valueOf(disk.getFormat().toString().toUpperCase()));

            return new CopyCmdAnswer(newVol);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to createVolumeFromSnapshot: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            volume.clearPassphrase();
        }
    }

    private List<StoragePoolType> storagePoolTypesToDeleteSnapshotFile = Arrays.asList(StoragePoolType.Filesystem, StoragePoolType.NetworkFilesystem,
            StoragePoolType.SharedMountPoint);

    private KVMPhysicalDisk createVolumeFromRBDSnapshot(CopyCommand cmd, DataTO destData,
            PrimaryDataStoreTO pool, DataStoreTO imageStore, VolumeObjectTO volume, String snapshotName, KVMPhysicalDisk disk) {
        PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO) imageStore;
        KVMStoragePool srcPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
        KVMPhysicalDisk snapshotDisk = srcPool.getPhysicalDisk(volume.getPath());
        KVMStoragePool destPool = storagePoolMgr.getStoragePool(pool.getPoolType(), pool.getUuid());
        VolumeObjectTO newVol = (VolumeObjectTO) destData;

        if (StoragePoolType.RBD.equals(primaryStore.getPoolType())) {
            logger.debug(String.format("Attempting to create volume from RBD snapshot %s", snapshotName));
            if (StoragePoolType.RBD.equals(pool.getPoolType())) {
                disk = createRBDvolumeFromRBDSnapshot(snapshotDisk, snapshotName, newVol.getUuid(),
                        PhysicalDiskFormat.RAW, newVol.getSize(), destPool, cmd.getWaitInMillSeconds());
                logger.debug(String.format("Created RBD volume %s from snapshot %s", disk, snapshotDisk));
            } else {
                Map<String, String> details = cmd.getOptions2();

                String path = details != null ? details.get(DiskTO.IQN) : null;

                storagePoolMgr.connectPhysicalDisk(pool.getPoolType(), pool.getUuid(), path, details);

                snapshotDisk.setPath(snapshotDisk.getPath() + "@" + snapshotName);
                disk = storagePoolMgr.copyPhysicalDisk(snapshotDisk, path != null ? path : newVol.getUuid(),
                        destPool, cmd.getWaitInMillSeconds());

                storagePoolMgr.disconnectPhysicalDisk(pool.getPoolType(), pool.getUuid(), path);
                logger.debug(String.format("Created RBD volume %s from snapshot %s", disk, snapshotDisk));

            }
        }
        return disk;
    }

    private KVMPhysicalDisk createVolumeFromSnapshotOnNFS(CopyCommand cmd, PrimaryDataStoreTO pool,
            DataStoreTO imageStore, VolumeObjectTO volume, String snapshotPath, String snapshotName) {
        NfsTO nfsImageStore = (NfsTO)imageStore;
        KVMStoragePool secondaryPool = storagePoolMgr.getStoragePoolByURI(nfsImageStore.getUrl() + File.separator + snapshotPath);
        KVMPhysicalDisk snapshotDisk = secondaryPool.getPhysicalDisk(snapshotName);
        if (volume.getFormat() == ImageFormat.RAW) {
            snapshotDisk.setFormat(PhysicalDiskFormat.RAW);
        } else if (volume.getFormat() == ImageFormat.QCOW2) {
            snapshotDisk.setFormat(PhysicalDiskFormat.QCOW2);
        }

        final String primaryUuid = pool.getUuid();
        final KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(pool.getPoolType(), primaryUuid);
        final String volUuid = UUID.randomUUID().toString();

        Map<String, String> details = cmd.getOptions2();

        String path = cmd.getDestTO().getPath();
        if (path == null) {
            path = details != null ? details.get(DiskTO.PATH) : null;
            if (path == null) {
                path = details != null ? details.get(DiskTO.IQN) : null;
                if (path == null) {
                    new CloudRuntimeException("The 'path' or 'iqn' field must be specified.");
                }
            }
        }

        storagePoolMgr.connectPhysicalDisk(pool.getPoolType(), pool.getUuid(), path, details);

        KVMPhysicalDisk disk = storagePoolMgr.copyPhysicalDisk(snapshotDisk, path != null ? path : volUuid, primaryPool, cmd.getWaitInMillSeconds());

        storagePoolMgr.disconnectPhysicalDisk(pool.getPoolType(), pool.getUuid(), path);
        secondaryPool.delete();
        return disk;
    }

    private KVMPhysicalDisk createRBDvolumeFromRBDSnapshot(KVMPhysicalDisk volume, String snapshotName, String name,
            PhysicalDiskFormat format, long size, KVMStoragePool destPool, int timeout) {

        KVMStoragePool srcPool = volume.getPool();
        KVMPhysicalDisk disk = null;
        String newUuid = name;

        disk = new KVMPhysicalDisk(destPool.getSourceDir() + "/" + newUuid, newUuid, destPool);
        disk.setFormat(format);
        disk.setSize(size > volume.getVirtualSize() ? size : volume.getVirtualSize());
        disk.setVirtualSize(size > volume.getVirtualSize() ? size : disk.getSize());

        try {

            Rados r = new Rados(srcPool.getAuthUserName());
            r.confSet("mon_host", srcPool.getSourceHost() + ":" + srcPool.getSourcePort());
            r.confSet("key", srcPool.getAuthSecret());
            r.confSet("client_mount_timeout", "30");
            r.connect();

            IoCTX io = r.ioCtxCreate(srcPool.getSourceDir());
            Rbd rbd = new Rbd(io);
            RbdImage srcImage = rbd.open(volume.getName());

            List<RbdSnapInfo> snaps = srcImage.snapList();
            boolean snapFound = false;
            for (RbdSnapInfo snap : snaps) {
                if (snapshotName.equals(snap.name)) {
                    snapFound = true;
                    break;
                }
            }

            if (!snapFound) {
                logger.debug(String.format("Could not find snapshot %s on RBD", snapshotName));
                return null;
            }
            srcImage.snapProtect(snapshotName);

            logger.debug(String.format("Try to clone snapshot %s on RBD", snapshotName));
            rbd.clone(volume.getName(), snapshotName, io, disk.getName(), LibvirtStorageAdaptor.RBD_FEATURES, 0);
            RbdImage diskImage = rbd.open(disk.getName());
            if (disk.getVirtualSize() > volume.getVirtualSize()) {
                diskImage.resize(disk.getVirtualSize());
            }

            diskImage.flatten();
            rbd.close(diskImage);

            srcImage.snapUnprotect(snapshotName);
            rbd.close(srcImage);
            r.ioCtxDestroy(io);
        } catch (RadosException | RbdException e) {
            logger.error(String.format("Failed due to %s", e.getMessage()), e);
            disk = null;
        }

        return disk;
    }

    @Override
    public Answer deleteSnapshot(final DeleteCommand cmd) {
        String snapshotFullName = "";
        SnapshotObjectTO snapshotTO = (SnapshotObjectTO) cmd.getData();
        VolumeObjectTO volume = snapshotTO.getVolume();
        try {
            PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO) snapshotTO.getDataStore();
            KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
            String snapshotFullPath = snapshotTO.getPath();
            String snapshotName = snapshotFullPath.substring(snapshotFullPath.lastIndexOf("/") + 1);
            snapshotFullName = snapshotName;
            if (primaryPool.getType() == StoragePoolType.RBD) {
                KVMPhysicalDisk disk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volume.getPath());
                snapshotFullName = disk.getName() + "@" + snapshotName;
                Rados r = radosConnect(primaryPool);
                IoCTX io = r.ioCtxCreate(primaryPool.getSourceDir());
                Rbd rbd = new Rbd(io);
                RbdImage image = rbd.open(disk.getName());
                try {
                    logger.info("Attempting to remove RBD snapshot " + snapshotFullName);
                    if (image.snapIsProtected(snapshotName)) {
                        logger.debug("Unprotecting RBD snapshot " + snapshotFullName);
                        image.snapUnprotect(snapshotName);
                    }
                    image.snapRemove(snapshotName);
                    logger.info("Snapshot " + snapshotFullName + " successfully removed from " +
                            primaryPool.getType().toString() + "  pool.");
                } catch (RbdException e) {
                    logger.error("Failed to remove snapshot " + snapshotFullName + ", with exception: " + e.toString() +
                        ", RBD error: " + ErrorCode.getErrorMessage(e.getReturnValue()));
                } finally {
                    rbd.close(image);
                    r.ioCtxDestroy(io);
                }

            } else if (storagePoolTypesToDeleteSnapshotFile.contains(primaryPool.getType())) {
                logger.info(String.format("Deleting snapshot (id=%s, name=%s, path=%s, storage type=%s) on primary storage", snapshotTO.getId(), snapshotTO.getName(),
                        snapshotTO.getPath(), primaryPool.getType()));
                deleteSnapshotFile(snapshotTO);
                if (snapshotTO.isKvmIncrementalSnapshot()) {
                    deleteCheckpoint(snapshotTO);
                }
            } else {
                logger.warn("Operation not implemented for storage pool type of " + primaryPool.getType().toString());
                throw new InternalErrorException("Operation not implemented for storage pool type of " + primaryPool.getType().toString());
            }
            return new Answer(cmd, true, "Snapshot " + snapshotFullName + " removed successfully.");
        } catch (RadosException e) {
            logger.error("Failed to remove snapshot " + snapshotFullName + ", with exception: " + e.toString() +
                ", RBD error: " + ErrorCode.getErrorMessage(e.getReturnValue()));
            return new Answer(cmd, false, "Failed to remove snapshot " + snapshotFullName);
        } catch (RbdException e) {
            logger.error("Failed to remove snapshot " + snapshotFullName + ", with exception: " + e.toString() +
                ", RBD error: " + ErrorCode.getErrorMessage(e.getReturnValue()));
            return new Answer(cmd, false, "Failed to remove snapshot " + snapshotFullName);
        } catch (Exception e) {
            logger.error("Failed to remove snapshot " + snapshotFullName + ", with exception: " + e.toString());
            return new Answer(cmd, false, "Failed to remove snapshot " + snapshotFullName);
        } finally {
            volume.clearPassphrase();
        }
    }

    /**
     * Deletes the checkpoint dump if it exists. And deletes the checkpoint definition on the VM if it is running.
     * */
    protected void deleteCheckpoint(SnapshotObjectTO snapshotTO) throws IOException {
        if (snapshotTO.getCheckpointPath() != null) {
            Files.deleteIfExists(Path.of(snapshotTO.getCheckpointPath()));
        }

        String vmName = snapshotTO.getVmName();
        if (vmName == null) {
            return;
        }
        String checkpointName = snapshotTO.getPath().substring(snapshotTO.getPath().lastIndexOf(File.separator) + 1);

        logger.debug("Deleting checkpoint [{}] of VM [{}].", checkpointName, vmName);
        Script.runSimpleBashScript(String.format(LibvirtComputingResource.CHECKPOINT_DELETE_COMMAND, vmName, checkpointName));
    }

    /**
     * Deletes the snapshot's file.
     * @throws CloudRuntimeException If can't delete the snapshot file.
     */
    protected void deleteSnapshotFile(SnapshotObjectTO snapshotObjectTo) throws CloudRuntimeException {
        try {
            Files.deleteIfExists(Paths.get(snapshotObjectTo.getPath()));
            logger.debug(String.format("Deleted snapshot [%s].", snapshotObjectTo));
        } catch (IOException ex) {
            throw new CloudRuntimeException(String.format("Unable to delete snapshot [%s] due to [%s].", snapshotObjectTo, ex.getMessage()));
        }
    }

    @Override
    public Answer introduceObject(final IntroduceObjectCmd cmd) {
        return new Answer(cmd, false, "not implememented yet");
    }

    @Override
    public Answer forgetObject(final ForgetObjectCmd cmd) {
        return new Answer(cmd, false, "not implememented yet");
    }

    @Override
    public Answer handleDownloadTemplateToPrimaryStorage(DirectDownloadCommand cmd) {
        final PrimaryDataStoreTO pool = cmd.getDestPool();
        DirectTemplateDownloader downloader;
        KVMPhysicalDisk template;
        KVMStoragePool destPool = null;

        try {
            logger.debug("Verifying temporary location for downloading the template exists on the host");
            String temporaryDownloadPath = resource.getDirectDownloadTemporaryDownloadPath();
            if (!isLocationAccessible(temporaryDownloadPath)) {
                String msg = "The temporary location path for downloading templates does not exist: " +
                        temporaryDownloadPath + " on this host";
                logger.error(msg);
                return new DirectDownloadAnswer(false, msg, true);
            }

            Long templateSize = null;
            if (StringUtils.isNotBlank(cmd.getUrl())) {
                String url = cmd.getUrl();
                templateSize = UriUtils.getRemoteSize(url, cmd.isFollowRedirects());
            }

            logger.debug("Checking for free space on the host for downloading the template with physical size: " + templateSize + " and virtual size: " + cmd.getTemplateSize());
            if (!isEnoughSpaceForDownloadTemplateOnTemporaryLocation(templateSize)) {
                String msg = String.format("Not enough space on the defined temporary location to download the template %s with id %d", cmd.getDestData(), cmd.getTemplateId());
                logger.error(msg);
                return new DirectDownloadAnswer(false, msg, true);
            }

            destPool = storagePoolMgr.getStoragePool(pool.getPoolType(), pool.getUuid());
            downloader = DirectDownloadHelper.getDirectTemplateDownloaderFromCommand(cmd, destPool.getLocalPath(), temporaryDownloadPath);
            logger.debug("Trying to download template");
            Pair<Boolean, String> result = downloader.downloadTemplate();
            if (!result.first()) {
                logger.warn("Couldn't download template");
                return new DirectDownloadAnswer(false, "Unable to download template", true);
            }
            String tempFilePath = result.second();
            if (!downloader.validateChecksum()) {
                logger.warn("Couldn't validate template checksum");
                return new DirectDownloadAnswer(false, "Checksum validation failed", false);
            }

            final TemplateObjectTO destTemplate = cmd.getDestData();
            String destTemplatePath = (destTemplate != null) ? destTemplate.getPath() : null;

            if (!storagePoolMgr.connectPhysicalDisk(pool.getPoolType(), pool.getUuid(), destTemplatePath, null)) {
                logger.warn(String.format("Unable to connect physical disk at path: %s, in storage pool [id: %d, uuid: %s, name: %s, path: %s]",
                        destTemplatePath, pool.getId(), pool.getUuid(), pool.getName(), pool.getPath()));
            }

            template = storagePoolMgr.createPhysicalDiskFromDirectDownloadTemplate(tempFilePath, destTemplatePath, destPool, cmd.getFormat(), cmd.getWaitInMillSeconds());

            String templatePath = null;
            if (template != null) {
                templatePath = template.getPath();
            }
            if (StringUtils.isEmpty(templatePath)) {
                logger.warn("Skipped validation whether downloaded file is QCOW2 for template {}, due to downloaded template path is empty", template.getName());
            } else if (!new File(templatePath).exists()) {
                logger.warn("Skipped validation whether downloaded file is QCOW2 for template {}, due to downloaded template path is not valid: {}", template.getName(), templatePath);
            } else {
                try {
                    Qcow2Inspector.validateQcow2File(templatePath);
                } catch (RuntimeException e) {
                    try {
                        Files.deleteIfExists(Path.of(templatePath));
                    } catch (IOException ioException) {
                        logger.warn("Unable to remove file [name: {}, path: {}]; consider removing it manually.", template.getName(), templatePath, ioException);
                    }

                    logger.error("The downloaded file [{}] is not a valid QCOW2.", templatePath, e);
                    return new DirectDownloadAnswer(false, "The downloaded file is not a valid QCOW2. Ask the administrator to check the logs for more details.", true);
                }
            }

            if (!storagePoolMgr.disconnectPhysicalDisk(pool.getPoolType(), pool.getUuid(), destTemplatePath)) {
                logger.warn(String.format("Unable to disconnect physical disk at path: %s, in storage pool [id: %d, uuid: %s, name: %s, path: %s]", destTemplatePath, pool.getId(), pool.getUuid(), pool.getName(), pool.getUuid()));
            }
        } catch (CloudRuntimeException e) {
            logger.warn(String.format("Error downloading template %s with id %d due to: %s", cmd.getDestData(), cmd.getTemplateId(), e.getMessage()));
            return new DirectDownloadAnswer(false, "Unable to download template: " + e.getMessage(), true);
        } catch (IllegalArgumentException e) {
            return new DirectDownloadAnswer(false, "Unable to create direct downloader: " + e.getMessage(), true);
        }

        return new DirectDownloadAnswer(true, template.getSize(), template.getName());
    }

    @Override
    public Answer copyVolumeFromPrimaryToPrimary(CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final VolumeObjectTO srcVol = (VolumeObjectTO)srcData;
        final VolumeObjectTO destVol = (VolumeObjectTO)destData;
        final ImageFormat srcFormat = srcVol.getFormat();
        final ImageFormat destFormat = destVol.getFormat();
        final DataStoreTO srcStore = srcData.getDataStore();
        final DataStoreTO destStore = destData.getDataStore();
        final PrimaryDataStoreTO srcPrimaryStore = (PrimaryDataStoreTO)srcStore;
        final PrimaryDataStoreTO destPrimaryStore = (PrimaryDataStoreTO)destStore;
        final String srcVolumePath = srcData.getPath();
        final String destVolumePath = destData.getPath();
        KVMStoragePool destPool = null;

        try {
            logger.debug(String.format("Copying src volume (id: %d, uuid: %s, name: %s, format:" +
                            " %s, path: %s, primary storage: [id: %d, uuid: %s, name: %s, type: " +
                            "%s]) to dest volume (id: %d, uuid: %s, name: %s, format: %s, path: " +
                            "%s, primary storage: [id: %d, uuid: %s, name: %s, type: %s]).",
                    srcVol.getId(), srcVol.getUuid(), srcVol.getName(), srcFormat, srcVolumePath,
                    srcPrimaryStore.getId(), srcPrimaryStore.getUuid(), srcPrimaryStore.getName(),
                    srcPrimaryStore.getPoolType(), destVol.getId(), destVol.getUuid(), destVol.getName(),
                    destFormat, destVolumePath, destPrimaryStore.getId(), destPrimaryStore.getUuid(),
                    destPrimaryStore.getName(), destPrimaryStore.getPoolType()));

            if (srcPrimaryStore.isManaged()) {
                if (!storagePoolMgr.connectPhysicalDisk(srcPrimaryStore.getPoolType(), srcPrimaryStore.getUuid(), srcVolumePath, srcPrimaryStore.getDetails())) {
                    logger.warn(String.format("Failed to connect src volume %s, in storage pool %s", srcVol, srcPrimaryStore));
                }
            }

            final KVMPhysicalDisk volume = storagePoolMgr.getPhysicalDisk(srcPrimaryStore.getPoolType(), srcPrimaryStore.getUuid(), srcVolumePath);
            if (volume == null) {
                logger.debug("Failed to get physical disk for volume: " + srcVol);
                throw new CloudRuntimeException("Failed to get physical disk for volume at path: " + srcVolumePath);
            }

            volume.setFormat(PhysicalDiskFormat.valueOf(srcFormat.toString()));
            volume.setDispName(srcVol.getName());
            volume.setVmName(srcVol.getVmName());

            String destVolumeName = null;
            if (destPrimaryStore.isManaged()) {
                if (!storagePoolMgr.connectPhysicalDisk(destPrimaryStore.getPoolType(), destPrimaryStore.getUuid(), destVolumePath, destPrimaryStore.getDetails())) {
                    logger.warn("Failed to connect dest volume {}, in storage pool {}", destVol, destPrimaryStore);
                }
                destVolumeName = derivePath(destPrimaryStore, destData, destPrimaryStore.getDetails());
            } else {
                final String volumeName = UUID.randomUUID().toString();
                destVolumeName = volumeName + "." + destFormat.getFileExtension();

                // Update path in the command for reconciliation
                if (destData.getPath() == null) {
                    ((VolumeObjectTO) destData).setPath(destVolumeName);
                }
            }

            destPool = storagePoolMgr.getStoragePool(destPrimaryStore.getPoolType(), destPrimaryStore.getUuid());
            try {
                Volume.Type volumeType = srcVol.getVolumeType();

                resource.createOrUpdateLogFileForCommand(cmd, Command.State.PROCESSING_IN_BACKEND);
                if (srcVol.getPassphrase() != null && (Volume.Type.ROOT.equals(volumeType) || Volume.Type.DATADISK.equals(volumeType))) {
                    volume.setQemuEncryptFormat(QemuObject.EncryptFormat.LUKS);
                    storagePoolMgr.copyPhysicalDisk(volume, destVolumeName, destPool, cmd.getWaitInMillSeconds(), srcVol.getPassphrase(), destVol.getPassphrase(), srcVol.getProvisioningType());
                } else {
                    storagePoolMgr.copyPhysicalDisk(volume, destVolumeName, destPool, cmd.getWaitInMillSeconds());
                }
                resource.createOrUpdateLogFileForCommand(cmd, Command.State.COMPLETED);
            } catch (Exception e) { // Any exceptions while copying the disk, should send failed answer with the error message
                String errMsg = String.format("Failed to copy volume [uuid: %s, name: %s] to dest storage [id: %s, name: %s], due to %s",
                        srcVol.getUuid(), srcVol.getName(), destPrimaryStore.getUuid(), destPrimaryStore.getName(), e.toString());
                logger.debug(errMsg, e);
                resource.createOrUpdateLogFileForCommand(cmd, Command.State.FAILED);
                throw new CloudRuntimeException(errMsg);
            } finally {
                if (srcPrimaryStore.isManaged()) {
                    storagePoolMgr.disconnectPhysicalDisk(srcPrimaryStore.getPoolType(), srcPrimaryStore.getUuid(), srcVolumePath);
                }

                if (destPrimaryStore.isManaged()) {
                    storagePoolMgr.disconnectPhysicalDisk(destPrimaryStore.getPoolType(), destPrimaryStore.getUuid(), destVolumePath);
                }
            }

            final VolumeObjectTO newVol = new VolumeObjectTO();
            String path = destPrimaryStore.isManaged() ? destVolumeName : destVolumePath + File.separator + destVolumeName;
            newVol.setPath(path);
            newVol.setFormat(destFormat);
            newVol.setEncryptFormat(destVol.getEncryptFormat());
            return new CopyCmdAnswer(newVol);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to copyVolumeFromPrimaryToPrimary: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            srcVol.clearPassphrase();
            destVol.clearPassphrase();
        }
    }

    /**
     * True if location exists
     */
    private boolean isLocationAccessible(String temporaryDownloadPath) {
        File dir = new File(temporaryDownloadPath);
        return dir.exists();
    }

    /**
     * Perform a free space check on the host for downloading the direct download templates
     * @param templateSize template size obtained from remote server when registering the template (in bytes)
     */
    protected boolean isEnoughSpaceForDownloadTemplateOnTemporaryLocation(Long templateSize) {
        if (templateSize == null || templateSize == 0L) {
            logger.info("The server did not provide the template size, assuming there is enough space to download it");
            return true;
        }
        String cmd = String.format("df --output=avail %s -B 1 | tail -1", resource.getDirectDownloadTemporaryDownloadPath());
        String resultInBytes = Script.runSimpleBashScript(cmd);
        Long availableBytes;
        try {
            availableBytes = Long.parseLong(resultInBytes);
        } catch (NumberFormatException e) {
            String msg = "Could not parse the output " + resultInBytes + " as a number, therefore not able to check for free space";
            logger.error(msg, e);
            return false;
        }
        return availableBytes >= templateSize;
    }

    @Override
    public Answer checkDataStoreStoragePolicyCompliance(CheckDataStoreStoragePolicyComplainceCommand cmd) {
        logger.info("'CheckDataStoreStoragePolicyComplainceCommand' not currently applicable for KVMStorageProcessor");
        return new Answer(cmd,false,"Not currently applicable for KVMStorageProcessor");
    }

    @Override
    public Answer syncVolumePath(SyncVolumePathCommand cmd) {
        logger.info("SyncVolumePathCommand not currently applicable for KVMStorageProcessor");
        return new Answer(cmd, false, "Not currently applicable for KVMStorageProcessor");
    }

    /**
     * Determine if migration is using host-local source pool. If so, return this host's storage as the template source,
     * rather than remote host's
     * @param localPool The host-local storage pool being migrated to
     * @param migrationOptions The migration options provided with a migrating volume
     * @return
     */
    public KVMStoragePool getTemplateSourcePoolUsingMigrationOptions(KVMStoragePool localPool, MigrationOptions migrationOptions) {
        if (migrationOptions == null) {
            throw new CloudRuntimeException("Migration options cannot be null when choosing a storage pool for migration");
        }

        if (migrationOptions.getScopeType().equals(ScopeType.HOST)) {
            return localPool;
        }

        if (migrationOptions.getScopeType().equals(ScopeType.CLUSTER)
                && migrationOptions.getSrcPoolClusterId() != null
                && !migrationOptions.getSrcPoolClusterId().toString().equals(resource.getClusterId())) {
            return localPool;
        }

        return storagePoolMgr.getStoragePool(migrationOptions.getSrcPoolType(), migrationOptions.getSrcPoolUuid());
    }
}
