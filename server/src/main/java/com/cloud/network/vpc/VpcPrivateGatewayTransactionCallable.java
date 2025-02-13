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

import java.util.List;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import com.cloud.network.dao.NetworkDao;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.stereotype.Component;

import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.network.vpc.dao.VpcGatewayDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;

@Component
public class VpcPrivateGatewayTransactionCallable implements Callable<Boolean> {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private VpcGatewayDao _vpcGatewayDao;
    @Inject
    private PrivateIpDao _privateIpDao;
    @Inject
    private NetworkDao networkDao;

    private PrivateGateway gateway;
    private boolean deleteNetwork = true;

    @Override
    public Boolean call() throws Exception {
        final long networkId = gateway.getNetworkId();

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {

                final List<PrivateIpVO> privateIps = _privateIpDao.listByNetworkId(networkId);
                if (privateIps.size() > 1 || !privateIps.get(0).getIpAddress().equalsIgnoreCase(gateway.getIp4Address())) {
                    logger.debug("Not removing network {} as it has private ip addresses for other gateways", networkDao.findById(gateway.getNetworkId()));
                    deleteNetwork = false;
                }

                final PrivateIpVO ip = _privateIpDao.findByIpAndVpcId(gateway.getVpcId(), gateway.getIp4Address());
                if (ip != null) {
                    _privateIpDao.remove(ip.getId());
                    logger.debug("Deleted private ip " + ip);
                }

                _vpcGatewayDao.remove(gateway.getId());
                logger.debug("Deleted private gateway " + gateway);
            }
        });

        return deleteNetwork;
    }

    public void setGateway(final PrivateGateway gateway) {
        this.gateway = gateway;
    }
}
