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

package org.apache.cloudstack.api.command.admin.zone;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;


import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VmwareDatacenterResponse;
import org.apache.cloudstack.api.response.ZoneResponse;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.dc.VmwareDatacenter;
import com.cloud.hypervisor.vmware.VmwareDatacenterService;
import com.cloud.user.Account;

@APICommand(name = "listVmwareDcs", responseObject = VmwareDatacenterResponse.class, description = "Retrieves Vmware DC(s) associated with a zone.",
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ListVmwareDcsCmd extends BaseListCmd {

    @Inject
    public VmwareDatacenterService _vmwareDatacenterService;


    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, required = true, description = "Id of the CloudStack zone.")
    private Long zoneId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getZoneId() {
        return zoneId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException, ConcurrentOperationException,
        ResourceAllocationException {
        List<? extends VmwareDatacenter> vmwareDcList;

        try {
            vmwareDcList = _vmwareDatacenterService.listVmwareDatacenters(this);
        } catch (InvalidParameterValueException ie) {
            throw new InvalidParameterValueException("Invalid zone id " + getZoneId());
        } catch (Exception e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to find associated Vmware DCs associated with zone " + getZoneId());
        }

        ListResponse<VmwareDatacenterResponse> response = new ListResponse<>();
        List<VmwareDatacenterResponse> vmwareDcResponses = getVmwareDatacenterResponses(vmwareDcList);
        response.setResponses(vmwareDcResponses);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    private List<VmwareDatacenterResponse> getVmwareDatacenterResponses(List<? extends VmwareDatacenter> vmwareDcList) {
        List<VmwareDatacenterResponse> vmwareDcResponses = new ArrayList<>();

        if (vmwareDcList != null && !vmwareDcList.isEmpty()) {
            for (VmwareDatacenter vmwareDc : vmwareDcList) {
                VmwareDatacenterResponse vmwareDcResponse = new VmwareDatacenterResponse();

                vmwareDcResponse.setId(vmwareDc.getUuid());
                vmwareDcResponse.setVcenter(vmwareDc.getVcenterHost());
                vmwareDcResponse.setName(vmwareDc.getVmwareDatacenterName());
                vmwareDcResponse.setZoneId(getZoneId());
                vmwareDcResponse.setObjectName(ApiConstants.VMWARE_DC);

                vmwareDcResponses.add(vmwareDcResponse);
            }
        }
        return vmwareDcResponses;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

}
