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
package com.cloud.network.vpc;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;

import com.cloud.utils.db.GenericDao;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;

@Entity
@Table(name = "vpc")
public class VpcVO implements Vpc {

    @Id
    @Column(name = "id")
    long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "name")
    private String name;

    @Column(name = "display_text")
    String displayText;

    @Column(name = "zone_id")
    long zoneId;

    @Column(name = "cidr")
    private String cidr = null;

    @Column(name = "domain_id")
    Long domainId = null;

    @Column(name = "account_id")
    Long accountId = null;

    @Column(name = "state")
    @Enumerated(value = EnumType.STRING)
    State state;

    @Column(name = "redundant")
    boolean redundant;

    @Column(name = "vpc_offering_id")
    long vpcOfferingId;

    @Column(name = GenericDao.REMOVED_COLUMN)
    Date removed;

    @Column(name = GenericDao.CREATED_COLUMN)
    Date created;

    @Column(name = "network_domain")
    String networkDomain;

    @Column(name = "restart_required")
    boolean restartRequired = false;

    @Column(name = "display", updatable = true, nullable = false)
    protected boolean display = true;

    @Column(name="uses_distributed_router")
    boolean usesDistributedRouter = false;

    @Column(name = "region_level_vpc")
    boolean regionLevelVpc = false;

    @Column(name = "public_mtu")
    Integer publicMtu;

    @Column(name = "dns1")
    String ip4Dns1;

    @Column(name = "dns2")
    String ip4Dns2;

    @Column(name = "ip6Dns1")
    String ip6Dns1;

    @Column(name = "ip6Dns2")
    String ip6Dns2;

    @Column(name = "use_router_ip_resolver")
    boolean useRouterIpResolver = false;

    @Transient
    boolean rollingRestart = false;

    public VpcVO() {
        uuid = UUID.randomUUID().toString();
    }

    public VpcVO(final long zoneId, final String name, final String displayText, final long accountId, final long domainId,
                 final long vpcOffId, final String cidr, final String networkDomain, final boolean useDistributedRouter,
                 final boolean regionLevelVpc, final boolean isRedundant, final String ip4Dns1, final String ip4Dns2,
                 final String ip6Dns1, final String ip6Dns2) {
        this.zoneId = zoneId;
        this.name = name;
        this.displayText = displayText;
        this.accountId = accountId;
        this.domainId = domainId;
        this.cidr = cidr;
        uuid = UUID.randomUUID().toString();
        state = State.Enabled;
        this.networkDomain = networkDomain;
        vpcOfferingId = vpcOffId;
        usesDistributedRouter = useDistributedRouter;
        this.regionLevelVpc = regionLevelVpc;
        redundant = isRedundant;
        this.ip4Dns1 = ip4Dns1;
        this.ip4Dns2 = ip4Dns2;
        this.ip6Dns1 = ip6Dns1;
        this.ip6Dns2 = ip6Dns2;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getZoneId() {
        return zoneId;
    }

    @Override
    public String getCidr() {
        return cidr;
    }

    public void setCidr(String cidr) {
        this.cidr = cidr;
    }

    @Override
    public long getDomainId() {
        return domainId;
    }

    @Override
    public long getAccountId() {
        return accountId;
    }

    @Override
    public State getState() {
        return state;
    }

    public void setState(final State state) {
        this.state = state;
    }

    @Override
    public long getVpcOfferingId() {
        return vpcOfferingId;
    }

    public void setVpcOfferingId(final long vpcOfferingId) {
        this.vpcOfferingId = vpcOfferingId;
    }

    public Date getRemoved() {
        return removed;
    }

    @Override
    public String getDisplayText() {
        return displayText;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public void setDisplayText(final String displayText) {
        this.displayText = displayText;
    }

    @Override
    public String toString() {
        return String.format("VPC %s",
                ReflectionToStringBuilderUtils.reflectOnlySelectedFields(
                        this, "id", "uuid", "name"));
    }

    @Override
    public String getNetworkDomain() {
        return networkDomain;
    }

    public void setRestartRequired(final boolean restartRequired) {
        this.restartRequired = restartRequired;
    }

    @Override
    public boolean isRestartRequired() {
        return restartRequired;
    }

    public void setUuid(final String uuid) {
        this.uuid = uuid;
    }

    @Override
    public boolean isRegionLevelVpc() {
        return regionLevelVpc;
    }


    public void setDisplay(final boolean display) {
        this.display = display;
    }

    @Override
    public boolean isDisplay() {
        return display;
    }

    @Override
    public boolean isRedundant() {
        return redundant;
    }

    public void setRedundant(final boolean isRedundant) {
        redundant = isRedundant;
    }

    @Override
    public boolean isRollingRestart() {
        return rollingRestart;
    }

    public void setRollingRestart(boolean rollingRestart) {
        this.rollingRestart = rollingRestart;
    }

    @Override
    public Date getCreated() {
        return created;
    }

    @Override
    public Class<?> getEntityType() {
        return Vpc.class;
    }

    @Override
    public boolean usesDistributedRouter() {
        return usesDistributedRouter;
    }

    public Integer getPublicMtu() {
        return publicMtu;
    }

    public void setPublicMtu(Integer publicMtu) {
        this.publicMtu = publicMtu;
    }

    @Override
    public String getIp4Dns1() {
        return ip4Dns1;
    }

    @Override
    public String getIp4Dns2() {
        return ip4Dns2;
    }

    @Override
    public String getIp6Dns1() {
        return ip6Dns1;
    }

    @Override
    public String getIp6Dns2() {
        return ip6Dns2;
    }

    @Override
    public boolean useRouterIpAsResolver() {
        return useRouterIpResolver;
    }

    public void setUseRouterIpResolver(boolean useRouterIpResolver) {
        this.useRouterIpResolver = useRouterIpResolver;
    }
}
