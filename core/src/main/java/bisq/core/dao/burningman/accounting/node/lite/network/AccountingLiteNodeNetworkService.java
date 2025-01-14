/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.burningman.accounting.node.lite.network;

import bisq.core.dao.burningman.accounting.node.messages.GetAccountingBlocksResponse;
import bisq.core.dao.burningman.accounting.node.messages.NewAccountingBlockBroadcastMessage;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.network.CloseConnectionReason;
import bisq.network.p2p.network.Connection;
import bisq.network.p2p.network.ConnectionListener;
import bisq.network.p2p.network.MessageListener;
import bisq.network.p2p.network.NetworkNode;
import bisq.network.p2p.peers.Broadcaster;
import bisq.network.p2p.peers.PeerManager;
import bisq.network.p2p.seed.SeedNodeRepository;
import bisq.network.p2p.storage.P2PDataStorage;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.DevEnv;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.util.Tuple2;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

// Taken from LiteNodeNetworkService
@Slf4j
@Singleton
public class AccountingLiteNodeNetworkService implements MessageListener, ConnectionListener, PeerManager.Listener {
    private static final long RETRY_DELAY_SEC = 10;
    private static final long CLEANUP_TIMER = 120;
    private static final int MAX_RETRY = 12;

    private int retryCounter = 0;
    private int lastRequestedBlockHeight;
    private int lastReceivedBlockHeight;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    public interface Listener {
        void onRequestedBlocksReceived(GetAccountingBlocksResponse getBlocksResponse);

        void onNewBlockReceived(NewAccountingBlockBroadcastMessage newBlockBroadcastMessage);
    }

    private final NetworkNode networkNode;
    private final PeerManager peerManager;
    private final Broadcaster broadcaster;
    private final Collection<NodeAddress> seedNodeAddresses;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    // Key is tuple of seedNode address and requested blockHeight
    private final Map<Tuple2<NodeAddress, Integer>, RequestAccountingBlocksHandler> requestBlocksHandlerMap = new HashMap<>();
    private Timer retryTimer;
    private boolean stopped;
    private final Set<P2PDataStorage.ByteArray> receivedBlockMessages = new HashSet<>();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public AccountingLiteNodeNetworkService(NetworkNode networkNode,
                                            PeerManager peerManager,
                                            Broadcaster broadcaster,
                                            SeedNodeRepository seedNodesRepository) {
        this.networkNode = networkNode;
        this.peerManager = peerManager;
        this.broadcaster = broadcaster;
        // seedNodeAddresses can be empty (in case there is only 1 seed node, the seed node starting up has no other seed nodes)
        this.seedNodeAddresses = new HashSet<>(seedNodesRepository.getSeedNodeAddresses());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addListeners() {
        networkNode.addMessageListener(this);
        networkNode.addConnectionListener(this);
        peerManager.addListener(this);
    }

    public void shutDown() {
        stopped = true;
        stopRetryTimer();
        networkNode.removeMessageListener(this);
        networkNode.removeConnectionListener(this);
        peerManager.removeListener(this);
        closeAllHandlers();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void requestBlocks(int startBlockHeight) {
        lastRequestedBlockHeight = startBlockHeight;
        Optional<Connection> connectionToSeedNodeOptional = networkNode.getConfirmedConnections().stream()
                .filter(peerManager::isSeedNode)
                .findAny();

        connectionToSeedNodeOptional.flatMap(Connection::getPeersNodeAddressOptional)
                .ifPresentOrElse(candidate -> {
                    seedNodeAddresses.remove(candidate);
                    requestBlocks(candidate, startBlockHeight);
                }, () -> {
                    tryWithNewSeedNode(startBlockHeight);
                });
    }

    public void reset() {
        lastRequestedBlockHeight = 0;
        lastReceivedBlockHeight = 0;
        retryCounter = 0;
        requestBlocksHandlerMap.values().forEach(RequestAccountingBlocksHandler::terminate);
        requestBlocksHandlerMap.clear();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ConnectionListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onConnection(Connection connection) {
    }

    @Override
    public void onDisconnect(CloseConnectionReason closeConnectionReason, Connection connection) {
        closeHandler(connection);

        if (peerManager.isPeerBanned(closeConnectionReason, connection)) {
            connection.getPeersNodeAddressOptional().ifPresent(nodeAddress -> {
                seedNodeAddresses.remove(nodeAddress);
                removeFromRequestBlocksHandlerMap(nodeAddress);
            });
        }
    }

    @Override
    public void onError(Throwable throwable) {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PeerManager.Listener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onAllConnectionsLost() {
        log.info("onAllConnectionsLost");
        closeAllHandlers();
        stopRetryTimer();
        stopped = true;
        tryWithNewSeedNode(lastRequestedBlockHeight);
    }

    @Override
    public void onNewConnectionAfterAllConnectionsLost() {
        log.info("onNewConnectionAfterAllConnectionsLost");
        closeAllHandlers();
        stopped = false;
        tryWithNewSeedNode(lastRequestedBlockHeight);
    }

    @Override
    public void onAwakeFromStandby() {
        log.info("onAwakeFromStandby");
        closeAllHandlers();
        stopped = false;
        tryWithNewSeedNode(lastRequestedBlockHeight);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (networkEnvelope instanceof NewAccountingBlockBroadcastMessage) {
            NewAccountingBlockBroadcastMessage newBlockBroadcastMessage = (NewAccountingBlockBroadcastMessage) networkEnvelope;
            P2PDataStorage.ByteArray truncatedHash = new P2PDataStorage.ByteArray(newBlockBroadcastMessage.getBlock().getTruncatedHash());
            if (receivedBlockMessages.contains(truncatedHash)) {
                log.debug("We had that message already and do not further broadcast it. hash={}", truncatedHash);
                return;
            }

            log.info("We received a NewAccountingBlockBroadcastMessage from peer {} and broadcast it to our peers. height={} truncatedHash={}",
                    connection.getPeersNodeAddressOptional().orElse(null), newBlockBroadcastMessage.getBlock().getHeight(), truncatedHash);
            receivedBlockMessages.add(truncatedHash);
            broadcaster.broadcast(newBlockBroadcastMessage, connection.getPeersNodeAddressOptional().orElse(null));
            listeners.forEach(listener -> listener.onNewBlockReceived(newBlockBroadcastMessage));
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // RequestData
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void requestBlocks(NodeAddress peersNodeAddress, int startBlockHeight) {
        if (stopped) {
            log.warn("We have stopped already. We ignore that requestData call.");
            return;
        }

        Tuple2<NodeAddress, Integer> key = new Tuple2<>(peersNodeAddress, startBlockHeight);
        if (requestBlocksHandlerMap.containsKey(key)) {
            log.warn("We have started already a requestDataHandshake for startBlockHeight {} to peer. nodeAddress={}\n" +
                            "We start a cleanup timer if the handler has not closed by itself in between 2 minutes.",
                    peersNodeAddress, startBlockHeight);

            UserThread.runAfter(() -> {
                if (requestBlocksHandlerMap.containsKey(key)) {
                    RequestAccountingBlocksHandler handler = requestBlocksHandlerMap.get(key);
                    handler.terminate();
                    requestBlocksHandlerMap.remove(key);
                }
            }, CLEANUP_TIMER);
            return;
        }

        if (startBlockHeight < lastReceivedBlockHeight) {
            log.warn("startBlockHeight must not be smaller than lastReceivedBlockHeight. That should never happen." +
                    "startBlockHeight={},lastReceivedBlockHeight={}", startBlockHeight, lastReceivedBlockHeight);
            DevEnv.logErrorAndThrowIfDevMode("startBlockHeight must be larger than lastReceivedBlockHeight. startBlockHeight=" +
                    startBlockHeight + " / lastReceivedBlockHeight=" + lastReceivedBlockHeight);
            return;
        }

        RequestAccountingBlocksHandler requestAccountingBlocksHandler = new RequestAccountingBlocksHandler(networkNode,
                peerManager,
                peersNodeAddress,
                startBlockHeight,
                new RequestAccountingBlocksHandler.Listener() {
                    @Override
                    public void onComplete(GetAccountingBlocksResponse getBlocksResponse) {
                        log.info("requestBlocksHandler to {} completed", peersNodeAddress);
                        stopRetryTimer();

                        // need to remove before listeners are notified as they cause the update call
                        requestBlocksHandlerMap.remove(key);
                        // we only notify if our request was latest
                        if (startBlockHeight > lastReceivedBlockHeight) {
                            lastReceivedBlockHeight = startBlockHeight;

                            listeners.forEach(listener -> listener.onRequestedBlocksReceived(getBlocksResponse));
                        } else {
                            log.warn("We got a response which is already obsolete because we received a " +
                                    "response from a request with same or higher block height.");
                        }
                    }

                    @Override
                    public void onFault(String errorMessage, @Nullable Connection connection) {
                        log.warn("requestBlocksHandler with outbound connection failed.\n\tnodeAddress={}\n\t" +
                                "ErrorMessage={}", peersNodeAddress, errorMessage);

                        peerManager.handleConnectionFault(peersNodeAddress);
                        requestBlocksHandlerMap.remove(key);

                        tryWithNewSeedNode(startBlockHeight);
                    }
                });
        requestBlocksHandlerMap.put(key, requestAccountingBlocksHandler);
        requestAccountingBlocksHandler.requestBlocks();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void tryWithNewSeedNode(int startBlockHeight) {
        if (networkNode.getAllConnections().isEmpty()) {
            return;
        }

        if (lastRequestedBlockHeight == 0) {
            return;
        }

        if (stopped) {
            return;
        }

        if (retryTimer != null) {
            log.warn("We have a retry timer already running.");
            return;
        }

        retryCounter++;

        if (retryCounter > MAX_RETRY) {
            log.warn("We tried {} times but could not connect to a seed node.", retryCounter);
            return;
        }

        retryTimer = UserThread.runAfter(() -> {
                    stopped = false;

                    stopRetryTimer();

                    List<NodeAddress> list = seedNodeAddresses.stream()
                            .filter(e -> peerManager.isSeedNode(e) && !peerManager.isSelf(e))
                            .collect(Collectors.toList());
                    Collections.shuffle(list);

                    if (!list.isEmpty()) {
                        NodeAddress nextCandidate = list.get(0);
                        seedNodeAddresses.remove(nextCandidate);
                        log.info("We try requestBlocks from {} with startBlockHeight={}", nextCandidate, startBlockHeight);
                        requestBlocks(nextCandidate, startBlockHeight);
                    } else {
                        log.warn("No more seed nodes available we could try.");
                    }
                },
                RETRY_DELAY_SEC);
    }

    private void stopRetryTimer() {
        if (retryTimer != null) {
            retryTimer.stop();
            retryTimer = null;
        }
    }

    private void closeHandler(Connection connection) {
        Optional<NodeAddress> peersNodeAddressOptional = connection.getPeersNodeAddressOptional();
        if (peersNodeAddressOptional.isPresent()) {
            NodeAddress nodeAddress = peersNodeAddressOptional.get();
            removeFromRequestBlocksHandlerMap(nodeAddress);
        } else {
            log.trace("closeHandler: nodeAddress not set in connection {}", connection);
        }
    }

    private void removeFromRequestBlocksHandlerMap(NodeAddress nodeAddress) {
        requestBlocksHandlerMap.entrySet().stream()
                .filter(e -> e.getKey().first.equals(nodeAddress))
                .findAny()
                .ifPresent(e -> {
                    e.getValue().terminate();
                    requestBlocksHandlerMap.remove(e.getKey());
                });
    }

    private void closeAllHandlers() {
        requestBlocksHandlerMap.values().forEach(RequestAccountingBlocksHandler::terminate);
        requestBlocksHandlerMap.clear();
    }
}
