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
package com.cloud.agent.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.cloud.agent.api.CleanupPersistentNetworkResourceCommand;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.agent.lb.SetupMSListCommand;
import org.apache.cloudstack.command.ReconcileAnswer;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.agent.Listener;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.CheckOnHostCommand;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.CleanupNetworkRulesCmd;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.CreateStoragePoolCommand;
import com.cloud.agent.api.DeleteStoragePoolCommand;
import com.cloud.agent.api.HandleConfigDriveIsoCommand;
import com.cloud.agent.api.MaintainCommand;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.ModifySshKeysCommand;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.agent.api.PingTestCommand;
import com.cloud.agent.api.PvlanSetupCommand;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.RollingMaintenanceCommand;
import com.cloud.agent.api.SetupCommand;
import com.cloud.agent.api.ShutdownCommand;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.storage.CreateCommand;
import com.cloud.agent.transport.Request;
import com.cloud.agent.transport.Response;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Status;
import com.cloud.utils.concurrency.NamedThreadFactory;

/**
 *  AgentAttache provides basic commands to be implemented.
 */
public abstract class AgentAttache {
    protected Logger logger = LogManager.getLogger(getClass());

    private static final ScheduledExecutorService s_listenerExecutor = Executors.newScheduledThreadPool(10, new NamedThreadFactory("ListenerTimer"));
    private static final Random s_rand = new Random(System.currentTimeMillis());

    protected static final Comparator<Request> s_reqComparator = new Comparator<Request>() {
        @Override
        public int compare(final Request o1, final Request o2) {
            long seq1 = o1.getSequence();
            long seq2 = o2.getSequence();
            if (seq1 < seq2) {
                return -1;
            } else if (seq1 > seq2) {
                return 1;
            } else {
                return 0;
            }
        }
    };

    protected static final Comparator<Object> s_seqComparator = new Comparator<Object>() {
        @Override
        public int compare(final Object o1, final Object o2) {
            long seq1 = ((Request)o1).getSequence();
            long seq2 = (Long)o2;
            if (seq1 < seq2) {
                return -1;
            } else if (seq1 > seq2) {
                return 1;
            } else {
                return 0;
            }
        }
    };

    protected static String LOG_SEQ_FORMATTED_STRING;

    protected final long _id;
    protected String _uuid;
    protected String _name = null;
    protected HypervisorType _hypervisorType;
    protected final ConcurrentHashMap<Long, Listener> _waitForList;
    protected final LinkedList<Request> _requests;
    protected Long _currentSequence;
    protected Status _status = Status.Connecting;
    protected boolean _maintenance;
    protected long _nextSequence;

    protected AgentManagerImpl _agentMgr;

    public final static String[] s_commandsAllowedInMaintenanceMode = new String[] { MaintainCommand.class.toString(), MigrateCommand.class.toString(),
        StopCommand.class.toString(), CheckVirtualMachineCommand.class.toString(), PingTestCommand.class.toString(), CheckHealthCommand.class.toString(),
        ReadyCommand.class.toString(), ShutdownCommand.class.toString(), SetupCommand.class.toString(), CleanupNetworkRulesCmd.class.toString(),
        CheckNetworkCommand.class.toString(), PvlanSetupCommand.class.toString(), CheckOnHostCommand.class.toString(), ModifyTargetsCommand.class.toString(),
        ModifySshKeysCommand.class.toString(), CreateStoragePoolCommand.class.toString(), DeleteStoragePoolCommand.class.toString(), ModifyStoragePoolCommand.class.toString(),
        SetupMSListCommand.class.toString(), RollingMaintenanceCommand.class.toString(), CleanupPersistentNetworkResourceCommand.class.toString(), HandleConfigDriveIsoCommand.class.toString()};
    protected final static String[] s_commandsNotAllowedInConnectingMode = new String[] { StartCommand.class.toString(), CreateCommand.class.toString() };
    static {
        Arrays.sort(s_commandsAllowedInMaintenanceMode);
        Arrays.sort(s_commandsNotAllowedInConnectingMode);
    }

    protected AgentAttache(final AgentManagerImpl agentMgr, final long id, final String uuid, final String name, final HypervisorType hypervisorType, final boolean maintenance) {
        _id = id;
        _uuid = uuid;
        _name = name;
        _hypervisorType = hypervisorType;
        _waitForList = new ConcurrentHashMap<Long, Listener>();
        _currentSequence = null;
        _maintenance = maintenance;
        _requests = new LinkedList<Request>();
        _agentMgr = agentMgr;
        _nextSequence = new Long(s_rand.nextInt(Short.MAX_VALUE)).longValue() << 48;
        LOG_SEQ_FORMATTED_STRING = String.format("Seq %d-{}: {}", _id);
    }

    @Override
    public String toString() {
        return String.format("AgentAttache %s",
                ReflectionToStringBuilderUtils.reflectOnlySelectedFields(
                        this, "_id", "_uuid", "_name"));
    }

    public synchronized long getNextSequence() {
        return ++_nextSequence;
    }

    public synchronized void setMaintenanceMode(final boolean value) {
        _maintenance = value;
    }

    public void ready() {
        _status = Status.Up;
    }

    public boolean isReady() {
        return _status == Status.Up;
    }

    public boolean isConnecting() {
        return _status == Status.Connecting;
    }

    public boolean forForward() {
        return false;
    }

    protected void checkAvailability(final Command[] cmds) throws AgentUnavailableException {
        if (!_maintenance && _status != Status.Connecting) {
            return;
        }

        if (_maintenance) {
            for (final Command cmd : cmds) {
                if (Arrays.binarySearch(s_commandsAllowedInMaintenanceMode, cmd.getClass().toString()) < 0 && !cmd.isBypassHostMaintenance()) {
                    throw new AgentUnavailableException("Unable to send " + cmd.getClass().toString() + " because agent " + _name + " is in maintenance mode", _id);
                }
            }
        }

        if (_status == Status.Connecting) {
            for (final Command cmd : cmds) {
                if (Arrays.binarySearch(s_commandsNotAllowedInConnectingMode, cmd.getClass().toString()) >= 0) {
                    throw new AgentUnavailableException("Unable to send " + cmd.getClass().toString() + " because agent " + _name + " is in connecting mode", _id);
                }
            }
        }
    }

    protected synchronized void addRequest(final Request req) {
        int index = findRequest(req);
        assert (index < 0) : "How can we get index again? " + index + ":" + req.toString();
        _requests.add(-index - 1, req);
    }

    protected void cancel(final Request req) {
        long seq = req.getSequence();
        cancel(seq);
    }

    protected synchronized void cancel(final long seq) {
        logger.debug(LOG_SEQ_FORMATTED_STRING, seq, "Cancelling.");
        final Listener listener = _waitForList.remove(seq);
        if (listener != null) {
            listener.processDisconnect(_id, _uuid, _name, Status.Disconnected);
        }
        int index = findRequest(seq);
        if (index >= 0) {
            _requests.remove(index);
        }
    }

    protected synchronized int findRequest(final Request req) {
        return Collections.binarySearch(_requests, req, s_reqComparator);
    }

    protected synchronized int findRequest(final long seq) {
        return Collections.binarySearch(_requests, seq, s_seqComparator);
    }

    protected void registerListener(final long seq, final Listener listener) {
        logger.trace(LOG_SEQ_FORMATTED_STRING, seq, "Registering listener");
        if (listener.getTimeout() != -1) {
            s_listenerExecutor.schedule(new Alarm(seq), listener.getTimeout(), TimeUnit.SECONDS);
        }
        _waitForList.put(seq, listener);
    }

    protected Listener unregisterListener(final long sequence) {
        logger.trace(LOG_SEQ_FORMATTED_STRING, sequence, "Unregistering listener");
        return _waitForList.remove(sequence);
    }

    protected Listener getListener(final long sequence) {
        return _waitForList.get(sequence);
    }

    public long getId() {
        return _id;
    }

    public String getUuid() {
        return _uuid;
    }

    public String getName() {
        return _name;
    }

    public HypervisorType get_hypervisorType() {
        return _hypervisorType;
    }

    public int getQueueSize() {
        return _requests.size();
    }

    public int getNonRecurringListenersSize() {
        List<Listener> nonRecurringListenersList = new ArrayList<Listener>();
        if (_waitForList.isEmpty()) {
            return 0;
        } else {
            final Set<Map.Entry<Long, Listener>> entries = _waitForList.entrySet();
            final Iterator<Map.Entry<Long, Listener>> it = entries.iterator();
            while (it.hasNext()) {
                final Map.Entry<Long, Listener> entry = it.next();
                final Listener monitor = entry.getValue();
                if (!monitor.isRecurring()) {
                    //TODO - remove this debug statement later
                    logger.debug("Listener is {} waiting on {}", entry.getValue(), entry.getKey());
                    nonRecurringListenersList.add(monitor);
                }
            }
        }

        return nonRecurringListenersList.size();
    }

    public boolean processAnswers(final long seq, final Response resp) {
        resp.logD("Processing: ", true);

        final Answer[] answers = resp.getAnswers();

        boolean processed = false;

        try {
            Listener monitor = getListener(seq);

            if (monitor == null) {
                if (answers[0] != null && answers[0].getResult()) {
                    processed = true;
                }
                logger.debug(LOG_SEQ_FORMATTED_STRING, seq, "Unable to find listener.");
            } else {
                processed = monitor.processAnswers(_id, seq, answers);
                logger.trace(LOG_SEQ_FORMATTED_STRING, seq, (processed ? "" : " did not ") + " processed ");
                if (!monitor.isRecurring()) {
                    unregisterListener(seq);
                }
            }

            _agentMgr.notifyAnswersToMonitors(_id, seq, answers);

        } finally {
            // we should always trigger next command execution, even in failure cases - otherwise in exception case all the remaining will be stuck in the sync queue forever
            if (resp.executeInSequence()) {
                sendNext(seq);
            }
        }

        return processed;
    }

    protected void cancelAllCommands(final Status state, final boolean cancelActive) {
        if (cancelActive) {
            final Set<Map.Entry<Long, Listener>> entries = _waitForList.entrySet();
            final Iterator<Map.Entry<Long, Listener>> it = entries.iterator();
            while (it.hasNext()) {
                final Map.Entry<Long, Listener> entry = it.next();
                it.remove();
                final Listener monitor = entry.getValue();
                logger.debug(LOG_SEQ_FORMATTED_STRING, entry.getKey(), "Sending disconnect to " + monitor.getClass());
                monitor.processDisconnect(_id,  _uuid, _name, state);
            }
        }
    }

    public void cleanup(final Status state) {
        cancelAllCommands(state, true);
        _requests.clear();
    }

    @Override
    public boolean equals(final Object obj) {
        // Return false straight away.
        if (obj == null) {
            return false;
        }
        // No need to handle a ClassCastException. If the classes are different, then equals can return false straight ahead.
        if (this.getClass() != obj.getClass()) {
            return false;
        }
        AgentAttache that = (AgentAttache)obj;
        return _id == that._id;
    }

    public void send(final Request req, final Listener listener) throws AgentUnavailableException {
        checkAvailability(req.getCommands());

        long seq = req.getSequence();
        if (listener != null) {
            registerListener(seq, listener);
        }
        logger.debug(LOG_SEQ_FORMATTED_STRING, seq, "Routed from " + req.getManagementServerId());

        synchronized (this) {
            try {
                if (isClosed()) {
                    throw new AgentUnavailableException("The link to the agent " + _name + " has been closed", _id);
                }

                if (req.executeInSequence() && _currentSequence != null) {
                    req.logD("Waiting for Seq " + _currentSequence + " Scheduling: ", true);
                    addRequest(req);
                    return;
                }

                // If we got to here either we're not suppose to set
                // the _currentSequence or it is null already.

                req.logD("Sending ", true);
                send(req);

                if (req.executeInSequence() && _currentSequence == null) {
                    _currentSequence = seq;
                    logger.trace(LOG_SEQ_FORMATTED_STRING, seq, " is current sequence");
                }
            } catch (AgentUnavailableException e) {
                logger.info(LOG_SEQ_FORMATTED_STRING, seq, "Unable to send due to " + e.getMessage());
                cancel(seq);
                throw e;
            } catch (Exception e) {
                logger.warn(LOG_SEQ_FORMATTED_STRING, seq, "Unable to send due to " + e.getMessage(), e);
                cancel(seq);
                throw new AgentUnavailableException("Problem due to other exception " + e.getMessage(), _id);
            }
        }
    }

    public Answer[] send(final Request req, final int wait) throws AgentUnavailableException, OperationTimedoutException {
        SynchronousListener sl = new SynchronousListener(null);

        long seq = req.getSequence();
        send(req, sl);

        try {
            for (int i = 0; i < 2; i++) {
                Answer[] answers = null;
                Command[] cmds = req.getCommands();
                if (cmds != null && cmds.length == 1 && (cmds[0] != null) && cmds[0].isReconcile()
                        && !sl.isDisconnected() && _agentMgr.isReconcileCommandsEnabled(_hypervisorType)) {
                    // only available if (1) the only command is a Reconcile command (2) agent is connected; (3) reconciliation is enabled; (4) hypervisor is KVM;
                    answers = waitForAnswerOfReconcileCommand(sl, seq, cmds[0], wait);
                } else {
                    try {
                        answers = sl.waitFor(wait);
                    } catch (final InterruptedException e) {
                        logger.debug(LOG_SEQ_FORMATTED_STRING, seq, "Interrupted");
                    }
                }
                if (answers != null) {
                    new Response(req, answers).logD("Received: ", false);
                    return answers;
                }

                answers = sl.getAnswers(); // Try it again.
                if (answers != null) {
                    new Response(req, answers).logD("Received after timeout: ", true);

                    _agentMgr.notifyAnswersToMonitors(_id, seq, answers);
                    return answers;
                }

                final Long current = _currentSequence;
                if (current != null && seq != current) {
                    logger.debug(LOG_SEQ_FORMATTED_STRING, seq, "Waited too long.");

                    _agentMgr.updateReconcileCommandsIfNeeded(req.getSequence(), req.getCommands(), Command.State.TIMED_OUT);
                    throw new OperationTimedoutException(req.getCommands(), _id, seq, wait, false);
                }
                logger.debug(LOG_SEQ_FORMATTED_STRING, seq, "Waiting some more time because this is the current command");
            }

            _agentMgr.updateReconcileCommandsIfNeeded(req.getSequence(), req.getCommands(), Command.State.TIMED_OUT);
            throw new OperationTimedoutException(req.getCommands(), _id, seq, wait * 2, true);
        } catch (OperationTimedoutException e) {
            logger.warn(LOG_SEQ_FORMATTED_STRING, seq, "Timed out on " + req.toString());
            cancel(seq);
            final Long current = _currentSequence;
            if (req.executeInSequence() && (current != null && current == seq)) {
                sendNext(seq);
            }
            throw e;
        } catch (Exception e) {
            logger.warn(LOG_SEQ_FORMATTED_STRING, seq, "Exception while waiting for answer", e);
            cancel(seq);
            final Long current = _currentSequence;
            if (req.executeInSequence() && (current != null && current == seq)) {
                sendNext(seq);
            }
            _agentMgr.updateReconcileCommandsIfNeeded(req.getSequence(), req.getCommands(), Command.State.TIMED_OUT);
            throw new OperationTimedoutException(req.getCommands(), _id, seq, wait, false);
        } finally {
            unregisterListener(seq);
        }
    }

    private Answer[] waitForAnswerOfReconcileCommand(SynchronousListener sl, final long seq, final Command command, final int wait) {
        Answer[] answers = null;
        int waitTimeLeft = wait;
        while (waitTimeLeft > 0) {
            int waitTime = Math.min(waitTimeLeft, _agentMgr.getReconcileInterval());
            logger.debug(String.format("Waiting %s seconds for the answer of reconcile command %s-%s", waitTime, seq, command));
            if (sl.isDisconnected()) {
                logger.debug(String.format("Disconnected while waiting for the answer of reconcile command %s-%s", seq, command));
                break;
            }
            try {
                answers = sl.waitFor(waitTime);
            } catch (final InterruptedException e) {
                logger.debug(LOG_SEQ_FORMATTED_STRING, seq, "Interrupted");
            }
            if (answers != null) {
                break;
            }

            logger.debug(String.format("Getting the answer of reconcile command from cloudstack database for %s-%s", seq, command));
            Pair<Command.State, Answer> commandInfo = _agentMgr.getStateAndAnswerOfReconcileCommand(seq, command);
            if (commandInfo == null) {
                logger.debug(String.format("Cannot get the answer of reconcile command from cloudstack database for %s-%s", seq, command));
                continue;
            }
            Command.State state = commandInfo.first();
            if (Command.State.INTERRUPTED.equals(state)) {
                logger.debug(LOG_SEQ_FORMATTED_STRING, seq, "Interrupted by agent, will reconcile it");
                throw new CloudRuntimeException("Interrupted by agent");
            } else if (Command.State.DANGLED_IN_BACKEND.equals(state)) {
                logger.debug(LOG_SEQ_FORMATTED_STRING, seq, "Dangling in backend, it seems the agent was restarted, will reconcile it");
                throw new CloudRuntimeException("It is not being processed by agent");
            }
            Answer answer = commandInfo.second();
            logger.debug(String.format("Got the answer of reconcile command from cloudstack database for %s-%s: %s", seq, command, answer));
            if (answer != null && !(answer instanceof ReconcileAnswer)) {
                answers = new Answer[] { answer };
                break;
            }
            waitTimeLeft -= waitTime;
        }
        return answers;
    }

    protected synchronized void sendNext(final long seq) {
        _currentSequence = null;
        if (_requests.isEmpty()) {
            logger.debug(LOG_SEQ_FORMATTED_STRING, seq, "No more commands found");
            return;
        }

        Request req = _requests.pop();
        logger.debug(LOG_SEQ_FORMATTED_STRING, req.getSequence(), "Sending now.  is current sequence.");
        try {
            send(req);
        } catch (AgentUnavailableException e) {
            logger.debug(LOG_SEQ_FORMATTED_STRING, req.getSequence(), "Unable to send the next sequence");
            cancel(req.getSequence());
        }
        _currentSequence = req.getSequence();
    }

    public void process(final Answer[] answers) {
        //do nothing
    }

    /**
     * sends the request asynchronously.
     *
     * @param req
     * @throws AgentUnavailableException
     */
    public abstract void send(Request req) throws AgentUnavailableException;

    /**
     * Process disconnect.
     * @param state state of the agent.
     */
    public abstract void disconnect(final Status state);

    /**
     * Is the agent closed for more commands?
     * @return true if unable to reach agent or false if reachable.
     */
    protected abstract boolean isClosed();

    protected class Alarm extends ManagedContextRunnable {
        long _seq;

        public Alarm(final long seq) {
            _seq = seq;
        }

        @Override
        protected void runInContext() {
            try {
                Listener listener = unregisterListener(_seq);
                if (listener != null) {
                    cancel(_seq);
                    listener.processTimeout(_id, _seq);
                }
            } catch (Exception e) {
                logger.warn("Exception ", e);
            }
        }
    }
}
