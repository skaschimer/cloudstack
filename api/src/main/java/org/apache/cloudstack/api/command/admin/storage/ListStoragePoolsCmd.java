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
package org.apache.cloudstack.api.command.admin.storage;


import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.PodResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.ZoneResponse;

@APICommand(name = "listStoragePools", description = "Lists storage pools.", responseObject = StoragePoolResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ListStoragePoolsCmd extends BaseListCmd {


    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.CLUSTER_ID,
               type = CommandType.UUID,
               entityType = ClusterResponse.class,
               description = "list storage pools belonging to the specific cluster")
    private Long clusterId;

    @Parameter(name = ApiConstants.IP_ADDRESS, type = CommandType.STRING, description = "the IP address for the storage pool")
    private String ipAddress;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, description = "the name of the storage pool")
    private String storagePoolName;

    @Parameter(name = ApiConstants.PATH, type = CommandType.STRING, description = "the storage pool path")
    private String path;

    @Parameter(name = ApiConstants.POD_ID, type = CommandType.UUID, entityType = PodResponse.class, description = "the Pod ID for the storage pool")
    private Long podId;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, description = "the Zone ID for the storage pool")
    private Long zoneId;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = StoragePoolResponse.class, description = "the ID of the storage pool")
    private Long id;

    @Parameter(name = ApiConstants.SCOPE, type = CommandType.STRING, entityType = StoragePoolResponse.class, description = "the scope of the storage pool")
    private String scope;


    @Parameter(name = ApiConstants.STATUS, type = CommandType.STRING, description = "the status of the storage pool")
    private String status;

    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class, description = "host ID of the storage pools")
    private Long hostId;

    @Parameter(name = ApiConstants.STORAGE_CUSTOM_STATS, type = CommandType.BOOLEAN, description = "If true, lists the custom stats of the storage pool", since = "4.18.1")
    private Boolean customStats;

    @Parameter(name = ApiConstants.STORAGE_ACCESS_GROUP, type = CommandType.STRING, description = "the name of the storage access group", since = "4.21.0")
    private String storageAccessGroup;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getHostId() {
        return hostId;
    }

    public Long getClusterId() {
        return clusterId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getStoragePoolName() {
        return storagePoolName;
    }

    public String getPath() {
        return path;
    }

    public Long getPodId() {
        return podId;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public String getStatus() {
        return status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Boolean getCustomStats() {
        return customStats != null && customStats;
    }

    public String getStorageAccessGroup() {
        return storageAccessGroup;
    }

    public ListStoragePoolsCmd() {
    }

    public ListStoragePoolsCmd(String storageAccessGroup) {
        this.storageAccessGroup = storageAccessGroup;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.StoragePool;
    }

    @Override
    public void execute() {
        ListResponse<StoragePoolResponse> response = _queryService.searchForStoragePools(this);
        response.setResponseName(getCommandName());
        this.setResponseObject(response);
    }
}
