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
package org.apache.cloudstack.storage.image.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ImageStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.image.ImageStoreDriver;
import org.apache.cloudstack.storage.image.datastore.ImageStoreEntity;
import org.apache.cloudstack.storage.image.datastore.ImageStoreProviderManager;
import org.apache.cloudstack.storage.image.store.ImageStoreImpl;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.stereotype.Component;

import com.cloud.server.StatsCollector;
import com.cloud.storage.ScopeType;
import com.cloud.storage.dao.VMTemplateDao;

@Component
public class ImageStoreProviderManagerImpl implements ImageStoreProviderManager, Configurable {
    protected Logger logger = LogManager.getLogger(getClass());
    @Inject
    ImageStoreDao dataStoreDao;
    @Inject
    VMTemplateDao imageDataDao;
    @Inject
    DataStoreProviderManager providerManager;
    @Inject
    StatsCollector _statsCollector;
    @Inject
    ConfigurationDao configDao;

    Map<String, ImageStoreDriver> driverMaps;

    static final ConfigKey<String> ImageStoreAllocationAlgorithm = new ConfigKey<>(String.class, "image.store.allocation.algorithm", "Advanced", "firstfitleastconsumed",
            "firstfitleastconsumed','random' : Order in which hosts within a cluster will be considered for VM/volume allocation", true, ConfigKey.Scope.Global, null, null, null, null, null, ConfigKey.Kind.Select, "firstfitleastconsumed,random" );

    @PostConstruct
    public void config() {
        driverMaps = new HashMap<String, ImageStoreDriver>();
    }

    @Override
    public ImageStoreEntity getImageStore(long dataStoreId) {
        ImageStoreVO dataStore = dataStoreDao.findById(dataStoreId);
        String providerName = dataStore.getProviderName();
        ImageStoreProvider provider = (ImageStoreProvider)providerManager.getDataStoreProvider(providerName);
        ImageStoreEntity imgStore = ImageStoreImpl.getDataStore(dataStore, driverMaps.get(provider.getName()), provider);
        return imgStore;
    }

    @Override
    public boolean registerDriver(String providerName, ImageStoreDriver driver) {
        if (driverMaps.containsKey(providerName)) {
            return false;
        }
        driverMaps.put(providerName, driver);
        return true;
    }

    @Override
    public ImageStoreEntity getImageStore(String uuid) {
        ImageStoreVO dataStore = dataStoreDao.findByUuid(uuid);
        return dataStore == null ? null : getImageStore(dataStore.getId());
    }

    @Override
    public List<DataStore> listImageStores() {
        List<ImageStoreVO> stores = dataStoreDao.listImageStores();
        List<DataStore> imageStores = new ArrayList<DataStore>();
        for (ImageStoreVO store : stores) {
            imageStores.add(getImageStore(store.getId()));
        }
        return imageStores;
    }

    @Override
    public List<DataStore> listImageCacheStores() {
        List<ImageStoreVO> stores = dataStoreDao.listImageCacheStores();
        List<DataStore> imageStores = new ArrayList<DataStore>();
        for (ImageStoreVO store : stores) {
            imageStores.add(getImageStore(store.getId()));
        }
        return imageStores;
    }

    @Override
    public List<DataStore> listImageStoresByScope(ZoneScope scope) {
        List<ImageStoreVO> stores = dataStoreDao.findByZone(scope, null);
        List<DataStore> imageStores = new ArrayList<DataStore>();
        for (ImageStoreVO store : stores) {
            imageStores.add(getImageStore(store.getId()));
        }
        return imageStores;
    }

    @Override
    public List<DataStore> listImageStoresByScopeExcludingReadOnly(ZoneScope scope) {
        String allocationAlgorithm = ImageStoreAllocationAlgorithm.value();

        List<ImageStoreVO> stores = dataStoreDao.findByZone(scope, Boolean.FALSE);
        List<DataStore> imageStores = new ArrayList<DataStore>();
        for (ImageStoreVO store : stores) {
            imageStores.add(getImageStore(store.getId()));
        }
        if (allocationAlgorithm.equals("random")) {
            Collections.shuffle(imageStores);
            return imageStores;
        } else if (allocationAlgorithm.equals("firstfitleastconsumed")) {
            return orderImageStoresOnFreeCapacity(imageStores);
        }
        return null;
    }

    @Override
    public List<DataStore> listImageStoreByProvider(String provider) {
        List<ImageStoreVO> stores = dataStoreDao.findByProvider(provider);
        List<DataStore> imageStores = new ArrayList<DataStore>();
        for (ImageStoreVO store : stores) {
            imageStores.add(getImageStore(store.getId()));
        }
        return imageStores;
    }

    @Override
    public List<DataStore> listImageCacheStores(Scope scope) {
        if (scope.getScopeType() != ScopeType.ZONE) {
            logger.debug("only support zone wide image cache stores");
            return null;
        }
        List<ImageStoreVO> stores = dataStoreDao.findImageCacheByScope(new ZoneScope(scope.getScopeId()));
        List<DataStore> imageStores = new ArrayList<DataStore>();
        for (ImageStoreVO store : stores) {
            imageStores.add(getImageStore(store.getId()));
        }
        return imageStores;
    }

    @Override
    public DataStore getRandomImageStore(List<DataStore> imageStores) {
        if (imageStores.size() > 1) {
            Collections.shuffle(imageStores);
        }
        return imageStores.get(0);
    }

    @Override
    public DataStore getImageStoreWithFreeCapacity(List<DataStore> imageStores) {
        imageStores.sort((store1, store2) -> Long.compare(_statsCollector.imageStoreCurrentFreeCapacity(store2),
                _statsCollector.imageStoreCurrentFreeCapacity(store1)));
        for (DataStore imageStore : imageStores) {
            if (_statsCollector.imageStoreHasEnoughCapacity(imageStore)) {
                return imageStore;
            }
        }
        logger.error(String.format("Could not find an image storage in zone with less than %d usage",
                Math.round(_statsCollector.getImageStoreCapacityThreshold() * 100)));
        return null;
    }

    @Override
    public List<DataStore> orderImageStoresOnFreeCapacity(List<DataStore> imageStores) {
        List<DataStore> stores = new ArrayList<>();
        imageStores.sort((store1, store2) -> Long.compare(_statsCollector.imageStoreCurrentFreeCapacity(store2),
                _statsCollector.imageStoreCurrentFreeCapacity(store1)));
        for (DataStore imageStore : imageStores) {
            if (_statsCollector.imageStoreHasEnoughCapacity(imageStore)) {
                stores.add(imageStore);
            }
        }
        return stores;
    }

    @Override
    public List<DataStore> listImageStoresWithFreeCapacity(List<DataStore> imageStores) {
        List<DataStore> stores = new ArrayList<>();
        for (DataStore imageStore : imageStores) {
            // Return image store if used percentage is less then threshold value i.e. 90%.
            if (_statsCollector.imageStoreHasEnoughCapacity(imageStore)) {
                stores.add(imageStore);
            }
        }

        // No store with space found
        if (stores.isEmpty()) {
            logger.error(String.format("Can't find image storage in zone with less than %d usage",
                    Math.round(_statsCollector.getImageStoreCapacityThreshold() * 100)));
        }
        return stores;
    }

    @Override
    public List<DataStore> listImageStoresFilteringByZoneIds(Long... zoneIds) {
        List<ImageStoreVO> stores = dataStoreDao.listImageStoresByZoneIds(zoneIds);
        List<DataStore> imageStores = new ArrayList<>();
        for (ImageStoreVO store : stores) {
            imageStores.add(getImageStore(store.getId()));
        }
        return imageStores;
    }

    @Override
    public String getConfigComponentName() {
        return ImageStoreProviderManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] { ImageStoreAllocationAlgorithm };
    }

    @Override
    public long getImageStoreZoneId(long dataStoreId) {
        ImageStoreVO dataStore = dataStoreDao.findById(dataStoreId);
        return dataStore.getDataCenterId();
    }
}
