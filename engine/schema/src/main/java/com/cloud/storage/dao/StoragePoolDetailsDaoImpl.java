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
package com.cloud.storage.dao;


import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ConfigKey.Scope;
import org.apache.cloudstack.framework.config.ScopedConfigStorage;
import org.apache.cloudstack.resourcedetail.ResourceDetailsDaoBase;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.utils.Pair;

public class StoragePoolDetailsDaoImpl extends ResourceDetailsDaoBase<StoragePoolDetailVO> implements StoragePoolDetailsDao, ScopedConfigStorage {

    @Inject
    PrimaryDataStoreDao _storagePoolDao;

    public StoragePoolDetailsDaoImpl() {
    }

    @Override
    public Scope getScope() {
        return ConfigKey.Scope.StoragePool;
    }

    @Override
    public String getConfigValue(long id, String key) {
        StoragePoolDetailVO vo = findDetail(id, key);
        return vo == null ? null : getActualValue(vo);
    }

    @Override
    public void addDetail(long resourceId, String key, String value, boolean display) {
        List<StoragePoolVO> ChildPools = _storagePoolDao.listChildStoragePoolsInDatastoreCluster(resourceId);
        for(StoragePoolVO childPool : ChildPools) {
            super.addDetail(new StoragePoolDetailVO(childPool.getId(), key, value, display));
        }
        super.addDetail(new StoragePoolDetailVO(resourceId, key, value, display));
    }

    @Override
    public Pair<Scope, Long> getParentScope(long id) {
        StoragePoolVO pool = _storagePoolDao.findById(id);
        if (pool != null) {
            if (pool.getClusterId() != null) {
                return new Pair<>(getScope().getParent(), pool.getClusterId());
            } else {
                return new Pair<>(ConfigKey.Scope.Zone, pool.getDataCenterId());
            }
        }
        return null;
    }
}
