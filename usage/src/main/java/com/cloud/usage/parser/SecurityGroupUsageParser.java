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

import com.cloud.usage.UsageSecurityGroupVO;
import com.cloud.usage.UsageVO;
import com.cloud.usage.dao.UsageSecurityGroupDao;
import com.cloud.user.AccountVO;
import com.cloud.utils.Pair;

@Component
public class SecurityGroupUsageParser extends UsageParser {
    @Inject
    private UsageSecurityGroupDao usageSecurityGroupDao;

    @Override
    public String getParserName() {
        return "Security Group";
    }

    @Override
    protected boolean parse(AccountVO account, Date startDate, Date endDate) {
        if ((endDate == null) || endDate.after(new Date())) {
            endDate = new Date();
        }

        // - query usage_volume table with the following criteria:
        //     - look for an entry for accountId with start date in the given range
        //     - look for an entry for accountId with end date in the given range
        //     - look for an entry for accountId with end date null (currently running vm or owned IP)
        //     - look for an entry for accountId with start date before given range *and* end date after given range
        List<UsageSecurityGroupVO> usageSGs = usageSecurityGroupDao.getUsageRecords(account.getId(), account.getDomainId(), startDate, endDate, false, 0);

        if (usageSGs.isEmpty()) {
            logger.debug("No SecurityGroup usage events for this period");
            return true;
        }

        // This map has both the running time *and* the usage amount.
        Map<String, Pair<Long, Long>> usageMap = new HashMap<String, Pair<Long, Long>>();
        Map<String, SGInfo> sgMap = new HashMap<String, SGInfo>();

        // loop through all the security groups, create a usage record for each
        for (UsageSecurityGroupVO usageSG : usageSGs) {
            long vmId = usageSG.getVmInstanceId();
            long sgId = usageSG.getSecurityGroupId();
            String key = "" + vmId + "SG" + sgId;

            sgMap.put(key, new SGInfo(vmId, usageSG.getZoneId(), sgId));

            Date sgCreateDate = usageSG.getCreated();
            Date sgDeleteDate = usageSG.getDeleted();

            if ((sgDeleteDate == null) || sgDeleteDate.after(endDate)) {
                sgDeleteDate = endDate;
            }

            // clip the start date to the beginning of our aggregation range if the vm has been running for a while
            if (sgCreateDate.before(startDate)) {
                sgCreateDate = startDate;
            }

            if (sgCreateDate.after(endDate)) {
                //Ignore records created after endDate
                continue;
            }

            long currentDuration = (sgDeleteDate.getTime() - sgCreateDate.getTime()) + 1; // make sure this is an inclusive check for milliseconds (i.e. use n - m + 1 to find total number of millis to charge)

            updateSGUsageData(usageMap, key, usageSG.getVmInstanceId(), currentDuration);
        }

        for (String sgIdKey : usageMap.keySet()) {
            Pair<Long, Long> sgtimeInfo = usageMap.get(sgIdKey);
            long useTime = sgtimeInfo.second().longValue();

            // Only create a usage record if we have a runningTime of bigger than zero.
            if (useTime > 0L) {
                SGInfo info = sgMap.get(sgIdKey);
                createUsageRecord(UsageTypes.SECURITY_GROUP, useTime, startDate, endDate, account, info.getVmId(), info.getSGId(), info.getZoneId());
            }
        }

        return true;
    }

    private void updateSGUsageData(Map<String, Pair<Long, Long>> usageDataMap, String key, long vmId, long duration) {
        Pair<Long, Long> sgUsageInfo = usageDataMap.get(key);
        if (sgUsageInfo == null) {
            sgUsageInfo = new Pair<Long, Long>(new Long(vmId), new Long(duration));
        } else {
            Long runningTime = sgUsageInfo.second();
            runningTime = new Long(runningTime.longValue() + duration);
            sgUsageInfo = new Pair<Long, Long>(sgUsageInfo.first(), runningTime);
        }
        usageDataMap.put(key, sgUsageInfo);
    }

    private void createUsageRecord(int type, long runningTime, Date startDate, Date endDate, AccountVO account, long vmId, long sgId, long zoneId) {
        // Our smallest increment is hourly for now
        logger.debug("Total running time {} ms", runningTime);

        float usage = runningTime / 1000f / 60f / 60f;

        DecimalFormat dFormat = new DecimalFormat("#.######");
        String usageDisplay = dFormat.format(usage);

        logger.debug("Creating security group usage record for id [{}], vm [{}], usage [{}], startDate [{}], and endDate [{}], for account [{}].",
                sgId, vmId, usageDisplay, DateUtil.displayDateInTimezone(UsageManagerImpl.getUsageAggregationTimeZone(), startDate),
                DateUtil.displayDateInTimezone(UsageManagerImpl.getUsageAggregationTimeZone(), endDate), account.getId());

        // Create the usage record
        String usageDesc = "Security Group: " + sgId + " for Vm : " + vmId + " usage time";

        UsageVO usageRecord =
            new UsageVO(zoneId, account.getId(), account.getDomainId(), usageDesc, usageDisplay + " Hrs", type, new Double(usage), vmId, null, null, null, sgId, null,
                startDate, endDate);
        usageDao.persist(usageRecord);
    }

    private static class SGInfo {
        private long vmId;
        private long zoneId;
        private long sgId;

        public SGInfo(long vmId, long zoneId, long sgId) {
            this.vmId = vmId;
            this.zoneId = zoneId;
            this.sgId = sgId;
        }

        public long getZoneId() {
            return zoneId;
        }

        public long getVmId() {
            return vmId;
        }

        public long getSGId() {
            return sgId;
        }
    }

}
