// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.usage.parser;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import com.cloud.usage.UsageManagerImpl;
import com.cloud.utils.DateUtil;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.usage.UsageTypes;

import com.cloud.usage.UsageIPAddressVO;
import com.cloud.usage.UsageVO;
import com.cloud.usage.dao.UsageIPAddressDao;
import com.cloud.user.AccountVO;
import com.cloud.utils.Pair;

@Component
public class IPAddressUsageParser extends UsageParser {
    @Inject
    private UsageIPAddressDao usageIPAddressDao;

    @Override
    public String getParserName() {
        return "IP Address";
    }

    @Override
    protected boolean parse(AccountVO account, Date startDate, Date endDate) {
        if ((endDate == null) || endDate.after(new Date())) {
            endDate = new Date();
        }

        // - query usage_ip_address table with the following criteria:
        //     - look for an entry for accountId with start date in the given range
        //     - look for an entry for accountId with end date in the given range
        //     - look for an entry for accountId with end date null (currently running vm or owned IP)
        //     - look for an entry for accountId with start date before given range *and* end date after given range
        List<UsageIPAddressVO> usageIPAddress = usageIPAddressDao.getUsageRecords(account.getId(), account.getDomainId(), startDate, endDate);

        if (usageIPAddress.isEmpty()) {
            logger.debug("No IP Address usage for this period");
            return true;
        }

        // This map has both the running time *and* the usage amount.
        Map<String, Pair<Long, Long>> usageMap = new HashMap<String, Pair<Long, Long>>();

        Map<String, IpInfo> IPMap = new HashMap<String, IpInfo>();

        // loop through all the usage IPs, create a usage record for each
        for (UsageIPAddressVO usageIp : usageIPAddress) {
            long IpId = usageIp.getIpId();

            String key = "" + IpId;

            // store the info in the IP map
            IPMap.put(key, new IpInfo(usageIp.getZoneId(), IpId, usageIp.getAddress(), usageIp.isSourceNat(), usageIp.isSystem(), usageIp.isHidden()));

            Date IpAssignDate = usageIp.getAssigned();
            Date IpReleaseDeleteDate = usageIp.getReleased();

            if ((IpReleaseDeleteDate == null) || IpReleaseDeleteDate.after(endDate)) {
                IpReleaseDeleteDate = endDate;
            }

            // clip the start date to the beginning of our aggregation range if the vm has been running for a while
            if (IpAssignDate.before(startDate)) {
                IpAssignDate = startDate;
            }

            if (IpAssignDate.after(endDate)) {
                //Ignore records created after endDate
                continue;
            }

            long currentDuration = (IpReleaseDeleteDate.getTime() - IpAssignDate.getTime()) + 1; // make sure this is an inclusive check for milliseconds (i.e. use n - m + 1 to find total number of millis to charge)

            updateIpUsageData(usageMap, key, usageIp.getIpId(), currentDuration);
        }

        for (String ipIdKey : usageMap.keySet()) {
            Pair<Long, Long> ipTimeInfo = usageMap.get(ipIdKey);
            long useTime = ipTimeInfo.second().longValue();

            // Only create a usage record if we have a runningTime of bigger than zero.
            if (useTime > 0L) {
                IpInfo info = IPMap.get(ipIdKey);
                createUsageRecord(info.getZoneId(), useTime, startDate, endDate, account, info.getIpId(), info.getIPAddress(), info.isSourceNat(), info.isSystem, info.isHidden());
            }
        }

        return true;
    }

    private void updateIpUsageData(Map<String, Pair<Long, Long>> usageDataMap, String key, long ipId, long duration) {
        Pair<Long, Long> ipUsageInfo = usageDataMap.get(key);
        if (ipUsageInfo == null) {
            ipUsageInfo = new Pair<Long, Long>(new Long(ipId), new Long(duration));
        } else {
            Long runningTime = ipUsageInfo.second();
            runningTime = new Long(runningTime.longValue() + duration);
            ipUsageInfo = new Pair<Long, Long>(ipUsageInfo.first(), runningTime);
        }
        usageDataMap.put(key, ipUsageInfo);
    }

    private void createUsageRecord(long zoneId, long runningTime, Date startDate, Date endDate, AccountVO account, long ipId, String ipAddress,
        boolean isSourceNat, boolean isSystem, boolean isHidden) {

        logger.debug("Total usage time {} ms" , runningTime);

        float usage = runningTime / 1000f / 60f / 60f;

        DecimalFormat dFormat = new DecimalFormat("#.######");
        String usageDisplay = dFormat.format(usage);

        logger.debug("Creating IP usage record with id [{}], usage [{}], startDate [{}], and endDate [{}], for account [{}].",
                ipId, usageDisplay, DateUtil.displayDateInTimezone(UsageManagerImpl.getUsageAggregationTimeZone(), startDate),
                DateUtil.displayDateInTimezone(UsageManagerImpl.getUsageAggregationTimeZone(), endDate), account.getId());

        String usageDesc = "IPAddress: " + ipAddress;

        // Create the usage record

        UsageVO usageRecord =
            new UsageVO(zoneId, account.getAccountId(), account.getDomainId(), usageDesc, usageDisplay + " Hrs", UsageTypes.IP_ADDRESS, new Double(usage), ipId,
                (isSystem ? 1 : 0), (isSourceNat ? "SourceNat" : ""), startDate, endDate, isHidden);
        usageDao.persist(usageRecord);
    }

    private static class IpInfo {
        private long zoneId;
        private long ipId;
        private String ipAddress;
        private boolean isSourceNat;
        private boolean isSystem;
        private boolean isHidden;

        public IpInfo(long zoneId, long ipId, String ipAddress, boolean isSourceNat, boolean isSystem, boolean isHidden) {
            this.zoneId = zoneId;
            this.ipId = ipId;
            this.ipAddress = ipAddress;
            this.isSourceNat = isSourceNat;
            this.isSystem = isSystem;
            this.isHidden = isHidden;
        }

        public long getZoneId() {
            return zoneId;
        }

        public long getIpId() {
            return ipId;
        }

        public String getIPAddress() {
            return ipAddress;
        }

        public boolean isSourceNat() {
            return isSourceNat;
        }

        public boolean isHidden() {
            return isHidden;
        }
    }
}
