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
package com.cloud.network.guru;

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;

import com.cloud.configuration.ZoneConfig;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.Pod;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Networks.AddressFormat;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.IsolationType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.googlecode.ipv6.IPv6Address;

public class DirectPodBasedNetworkGuru extends DirectNetworkGuru {

    @Inject
    DataCenterDao _dcDao;
    @Inject
    VlanDao _vlanDao;
    @Inject
    NetworkOrchestrationService _networkMgr;
    @Inject
    IPAddressDao _ipAddressDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    PodVlanMapDao _podVlanDao;
    @Inject
    IpAddressManager _ipAddrMgr;

    @Override
    protected boolean canHandle(NetworkOffering offering, DataCenter dc, PhysicalNetwork physnet) {
        // this guru handles system Direct pod based network in Basic zones only (no isolation type specified)
        if (dc.getNetworkType() == NetworkType.Basic && isMyTrafficType(offering.getTrafficType())) {
            return true;
        } else {
            logger.trace("We only take care of Guest Direct Pod based networks");
            return false;
        }
    }

    @Override
    public NicProfile allocate(Network network, NicProfile nic, VirtualMachineProfile vm) throws InsufficientVirtualNetworkCapacityException,
        InsufficientAddressCapacityException, ConcurrentOperationException {

        DataCenterVO dc = _dcDao.findById(network.getDataCenterId());
        ReservationStrategy rsStrategy = ReservationStrategy.Start;
        _dcDao.loadDetails(dc);
        String dhcpStrategy = dc.getDetail(ZoneConfig.DhcpStrategy.key());
        if ("external".equalsIgnoreCase(dhcpStrategy)) {
            rsStrategy = ReservationStrategy.Create;
        }

        if (nic == null) {
            nic = new NicProfile(rsStrategy, null, null, null, null);
        } else if (nic.getIPv4Address() == null) {
            nic.setReservationStrategy(ReservationStrategy.Start);
        } else {
            nic.setReservationStrategy(ReservationStrategy.Create);
        }

        if (rsStrategy == ReservationStrategy.Create) {
            String mac = _networkModel.getNextAvailableMacAddressInNetwork(network.getId());
            nic.setMacAddress(mac);
        }
        return nic;
    }

    @Override
    @DB
    public void reserve(NicProfile nic, Network network, VirtualMachineProfile vm, DeployDestination dest, ReservationContext context)
        throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException, ConcurrentOperationException {

        String oldIp = nic.getIPv4Address();
        boolean getNewIp = false;

        if (oldIp == null) {
            getNewIp = true;
        } else {
            // we need to get a new ip address if we try to deploy a vm in a different pod
            final IPAddressVO ipVO = _ipAddressDao.findByIpAndSourceNetworkId(network.getId(), oldIp);
            if (ipVO != null) {
                PodVlanMapVO mapVO = _podVlanDao.listPodVlanMapsByVlan(ipVO.getVlanId());
                if (mapVO.getPodId() != dest.getPod().getId()) {
                    Transaction.execute(new TransactionCallbackNoReturn() {
                        @Override
                        public void doInTransactionWithoutResult(TransactionStatus status) {
                            //release the old ip here
                            _ipAddrMgr.markIpAsUnavailable(ipVO.getId());
                            _ipAddressDao.unassignIpAddress(ipVO.getId());
                        }
                    });

                    nic.setIPv4Address(null);
                    getNewIp = true;
                }
            }
        }

        if (getNewIp) {
            //we don't set reservationStrategy to Create because we need this method to be called again for the case when vm fails to deploy in Pod1, and we try to redeploy it in Pod2
            getIp(nic, dest.getPod(), vm, network);
        }

        DataCenter dc = _dcDao.findById(network.getDataCenterId());
        Pair<String, String> dns = _networkModel.getNetworkIp4Dns(network, dc);
        nic.setIPv4Dns1(dns.first());
        nic.setIPv4Dns2(dns.second());
    }

    @DB
    protected void getIp(final NicProfile nic, final Pod pod, final VirtualMachineProfile vm, final Network network) throws InsufficientVirtualNetworkCapacityException,
        InsufficientAddressCapacityException, ConcurrentOperationException {
        final DataCenter dc = _dcDao.findById(pod.getDataCenterId());
            Transaction.execute(new TransactionCallbackWithExceptionNoReturn<InsufficientAddressCapacityException>() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) throws InsufficientAddressCapacityException {
                    PublicIp ip = null;
                    List<PodVlanMapVO> podRefs = _podVlanDao.listPodVlanMapsByPod(pod.getId());
                    VlanVO vlan = _vlanDao.findById(podRefs.get(0).getVlanDbId());

                    if (nic.getIPv4Address() == null) {
                        String podRangeGateway = null;
                        if (!podRefs.isEmpty()) {
                            podRangeGateway = vlan.getVlanGateway();
                        }
                        //Get ip address from the placeholder and don't allocate a new one
                        if (vm.getType() == VirtualMachine.Type.DomainRouter) {
                            Nic placeholderNic = _networkModel.getPlaceholderNicForRouter(network, pod.getId());
                            if (placeholderNic != null) {
                                IPAddressVO userIp = _ipAddressDao.findByIpAndSourceNetworkId(network.getId(), placeholderNic.getIPv4Address());
                                ip = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                                logger.debug("Nic got an ip address " + placeholderNic.getIPv4Address() + " stored in placeholder nic for the network " + network +
                                    " and gateway " + podRangeGateway);
                            }
                        }

                        if (ip == null) {
                            ip = _ipAddrMgr.assignPublicIpAddress(dc.getId(), pod.getId(), vm.getOwner(), VlanType.DirectAttached, network.getId(), null, false, false);
                        }

                        nic.setIPv4Address(ip.getAddress().toString());
                        nic.setFormat(AddressFormat.Ip4);
                        nic.setIPv4Gateway(ip.getGateway());
                        nic.setIPv4Netmask(ip.getNetmask());
                        if (ip.getVlanTag() != null && ip.getVlanTag().equalsIgnoreCase(Vlan.UNTAGGED)) {
                            nic.setIsolationUri(IsolationType.Ec2.toUri(Vlan.UNTAGGED));
                            nic.setBroadcastUri(BroadcastDomainType.Vlan.toUri(Vlan.UNTAGGED));
                            nic.setBroadcastType(BroadcastDomainType.Native);
                        }
                        nic.setReservationId(String.valueOf(ip.getVlanTag()));
                        nic.setMacAddress(ip.getMacAddress());

                        //save the placeholder nic if the vm is the Virtual router
                        if (vm.getType() == VirtualMachine.Type.DomainRouter) {
                            Nic placeholderNic = _networkModel.getPlaceholderNicForRouter(network, pod.getId());
                            if (placeholderNic == null) {
                                logger.debug("Saving placeholder nic with ip4 address " + nic.getIPv4Address() + " for the network " + network);
                                _networkMgr.savePlaceholderNic(network, nic.getIPv4Address(), null, VirtualMachine.Type.DomainRouter);
                            }
                        }
                }

                /**
                 * Calculate the IPv6 Address the Instance will obtain using SLAAC and IPv6 EUI-64
                 *
                 * Linux, FreeBSD and Windows all calculate the same IPv6 address when configured properly.
                 *
                 * Using Router Advertisements the routers in the network should announce the IPv6 CIDR which is configured
                 * in the vlan table in the database.
                 *
                 * This way the NIC will be populated with a IPv6 address on which the Instance is reachable.
                 */
                if (vlan.getIp6Cidr() != null) {
                    if (nic.getIPv6Address() == null) {
                        logger.debug("Found IPv6 CIDR {} for VLAN {}", vlan.getIp6Cidr(), vlan);
                        nic.setIPv6Cidr(vlan.getIp6Cidr());
                        nic.setIPv6Gateway(vlan.getIp6Gateway());

                        IPv6Address ipv6addr = NetUtils.EUI64Address(vlan.getIp6Cidr(), nic.getMacAddress());
                        logger.info("Calculated IPv6 address {} using EUI-64 for NIC {}", ipv6addr, nic);
                        nic.setIPv6Address(ipv6addr.toString());
                    }
                } else {
                    logger.debug("No IPv6 CIDR configured for VLAN {}", vlan);
                }
            }
        });

        Pair<String, String> dns = _networkModel.getNetworkIp4Dns(network, dc);
        nic.setIPv4Dns1(dns.first());
        nic.setIPv4Dns2(dns.second());
    }

}
