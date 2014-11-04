/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.distributed.dht.preloader;

import org.gridgain.grid.*;
import org.gridgain.grid.events.*;
import org.gridgain.grid.kernal.managers.eventstorage.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.future.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import static org.gridgain.grid.events.GridEventType.*;
import static org.gridgain.grid.kernal.managers.communication.GridIoPolicy.*;
import static org.gridgain.grid.util.GridConcurrentFactory.*;

/**
 * DHT cache preloader.
 */
public class GridDhtPreloader<K, V> extends GridCachePreloaderAdapter<K, V> {
    /** Default preload resend timeout. */
    public static final long DFLT_PRELOAD_RESEND_TIMEOUT = 1500;

    /** */
    private GridDhtPartitionTopology<K, V> top;

    /** Topology version. */
    private final GridAtomicLong topVer = new GridAtomicLong();

    /** Force key futures. */
    private final ConcurrentMap<GridUuid, GridDhtForceKeysFuture<K, V>> forceKeyFuts = newMap();

    /** Partition suppliers. */
    private GridDhtPartitionSupplyPool<K, V> supplyPool;

    /** Partition demanders. */
    private GridDhtPartitionDemandPool<K, V> demandPool;

    /** Start future. */
    private final GridFutureAdapter<?> startFut;

    /** Busy lock to prevent activities from accessing exchanger while it's stopping. */
    private final ReadWriteLock busyLock = new ReentrantReadWriteLock();

    /** Pending affinity assignment futures. */
    private ConcurrentMap<Long, GridDhtAssignmentFetchFuture<K, V>> pendingAssignmentFetchFuts =
        new ConcurrentHashMap8<>();

    /** Discovery listener. */
    private final GridLocalEventListener discoLsnr = new GridLocalEventListener() {
        @Override public void onEvent(GridEvent evt) {
            if (!enterBusy())
                return;

            GridDiscoveryEvent e = (GridDiscoveryEvent)evt;

            try {
                GridNode loc = cctx.localNode();

                assert e.type() == EVT_NODE_JOINED || e.type() == EVT_NODE_LEFT || e.type() == EVT_NODE_FAILED;

                final GridNode n = e.eventNode();

                assert !loc.id().equals(n.id());

                for (GridDhtForceKeysFuture<K, V> f : forceKeyFuts.values())
                    f.onDiscoveryEvent(e);

                assert e.type() != EVT_NODE_JOINED || n.order() > loc.order() : "Node joined with smaller-than-local " +
                    "order [newOrder=" + n.order() + ", locOrder=" + loc.order() + ']';

                boolean set = topVer.setIfGreater(e.topologyVersion());

                assert set : "Have you configured GridTcpDiscoverySpi for your in-memory data grid?";

                if (e.type() == EVT_NODE_LEFT || e.type() == EVT_NODE_FAILED) {
                    for (GridDhtAssignmentFetchFuture<K, V> fut : pendingAssignmentFetchFuts.values())
                        fut.onNodeLeft(e.eventNode().id());
                }
            }
            finally {
                leaveBusy();
            }
        }
    };

    /**
     * @param cctx Cache context.
     */
    public GridDhtPreloader(GridCacheContext<K, V> cctx) {
        super(cctx);

        top = cctx.dht().topology();

        startFut = new GridFutureAdapter<Object>(cctx.kernalContext());
    }

    /** {@inheritDoc} */
    @Override public void start() {
        if (log.isDebugEnabled())
            log.debug("Starting DHT preloader...");

        cctx.io().addHandler(GridDhtForceKeysRequest.class,
            new MessageHandler<GridDhtForceKeysRequest<K, V>>() {
                @Override public void onMessage(GridNode node, GridDhtForceKeysRequest<K, V> msg) {
                    processForceKeysRequest(node, msg);
                }
            });

        cctx.io().addHandler(GridDhtForceKeysResponse.class,
            new MessageHandler<GridDhtForceKeysResponse<K, V>>() {
                @Override public void onMessage(GridNode node, GridDhtForceKeysResponse<K, V> msg) {
                    processForceKeyResponse(node, msg);
                }
            });

        cctx.io().addHandler(GridDhtAffinityAssignmentRequest.class,
            new MessageHandler<GridDhtAffinityAssignmentRequest<K, V>>() {
                @Override protected void onMessage(GridNode node, GridDhtAffinityAssignmentRequest<K, V> msg) {
                    processAffinityAssignmentRequest(node, msg);
                }
            });

        cctx.io().addHandler(GridDhtAffinityAssignmentResponse.class,
            new MessageHandler<GridDhtAffinityAssignmentResponse<K, V>>() {
                @Override protected void onMessage(GridNode node, GridDhtAffinityAssignmentResponse<K, V> msg) {
                    processAffinityAssignmentResponse(node, msg);
                }
            });

        supplyPool = new GridDhtPartitionSupplyPool<>(cctx, busyLock);
        demandPool = new GridDhtPartitionDemandPool<>(cctx, busyLock);

        cctx.events().addListener(discoLsnr, EVT_NODE_JOINED, EVT_NODE_LEFT, EVT_NODE_FAILED);
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws GridException {
        if (log.isDebugEnabled())
            log.debug("DHT preloader onKernalStart callback.");

        GridNode loc = cctx.localNode();

        long startTime = loc.metrics().getStartTime();

        assert startTime > 0;

        final long startTopVer = loc.order();

        topVer.setIfGreater(startTopVer);

        // Generate dummy discovery event for local node joining.
        GridDiscoveryEvent discoEvt = cctx.discovery().localJoinEvent();

        assert discoEvt != null;

        assert discoEvt.topologyVersion() == startTopVer;

        supplyPool.start();
        demandPool.start();
    }

    /** {@inheritDoc} */
    @Override public void preloadPredicate(GridPredicate<GridCacheEntryInfo<K, V>> preloadPred) {
        super.preloadPredicate(preloadPred);

        assert supplyPool != null && demandPool != null : "preloadPredicate may be called only after start()";

        supplyPool.preloadPredicate(preloadPred);
        demandPool.preloadPredicate(preloadPred);
    }

    /** {@inheritDoc} */
    @SuppressWarnings( {"LockAcquiredButNotSafelyReleased"})
    @Override public void onKernalStop() {
        if (log.isDebugEnabled())
            log.debug("DHT preloader onKernalStop callback.");

        cctx.events().removeListener(discoLsnr);

        // Acquire write busy lock.
        busyLock.writeLock().lock();

        if (supplyPool != null)
            supplyPool.stop();

        if (demandPool != null)
            demandPool.stop();

        top = null;
    }

    /** {@inheritDoc} */
    @Override public void onInitialExchangeComplete(@Nullable Throwable err) {
        if (err == null) {
            startFut.onDone();

            final long start = U.currentTimeMillis();

            if (cctx.config().getPreloadPartitionedDelay() >= 0) {
                U.log(log, "Starting preloading in " + cctx.config().getPreloadMode() + " mode: " + cctx.name());

                demandPool.syncFuture().listenAsync(new CI1<Object>() {
                    @Override public void apply(Object t) {
                        U.log(log, "Completed preloading in " + cctx.config().getPreloadMode() + " mode " +
                            "[cache=" + cctx.name() + ", time=" + (U.currentTimeMillis() - start) + " ms]");
                    }
                });
            }
        }
        else
            startFut.onDone(err);
    }

    /** {@inheritDoc} */
    @Override public void onExchangeFutureAdded() {
        demandPool.onExchangeFutureAdded();
    }

    /** {@inheritDoc} */
    @Override public void updateLastExchangeFuture(GridDhtPartitionsExchangeFuture<K, V> lastFut) {
        demandPool.updateLastExchangeFuture(lastFut);
    }

    /** {@inheritDoc} */
    @Override public GridDhtPreloaderAssignments<K, V> assign(GridDhtPartitionsExchangeFuture<K, V> exchFut) {
        return demandPool.assign(exchFut);
    }

    /** {@inheritDoc} */
    @Override public void addAssignments(GridDhtPreloaderAssignments<K, V> assignments, boolean forcePreload) {
        demandPool.addAssignments(assignments, forcePreload);
    }

    /**
     * @return Start future.
     */
    @Override public GridFuture<?> startFuture() {
        return startFut;
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> syncFuture() {
        return demandPool.syncFuture();
    }

    /**
     * @param topVer Requested topology version.
     * @param fut Future to add.
     */
    public void addDhtAssignmentFetchFuture(long topVer, GridDhtAssignmentFetchFuture<K, V> fut) {
        GridDhtAssignmentFetchFuture<K, V> old = pendingAssignmentFetchFuts.putIfAbsent(topVer, fut);

        assert old == null : "More than one thread is trying to fetch partition assignments: " + topVer;
    }

    /**
     * @param topVer Requested topology version.
     * @param fut Future to remove.
     */
    public void removeDhtAssignmentFetchFuture(long topVer, GridDhtAssignmentFetchFuture<K, V> fut) {
        boolean rmv = pendingAssignmentFetchFuts.remove(topVer, fut);

        assert rmv : "Failed to remove assignment fetch future: " + topVer;
    }

    /**
     * @return {@code true} if entered to busy state.
     */
    private boolean enterBusy() {
        if (busyLock.readLock().tryLock())
            return true;

        if (log.isDebugEnabled())
            log.debug("Failed to enter busy state on node (exchanger is stopping): " + cctx.nodeId());

        return false;
    }

    /**
     *
     */
    private void leaveBusy() {
        busyLock.readLock().unlock();
    }

    /**
     * @param node Node originated request.
     * @param msg Force keys message.
     */
    private void processForceKeysRequest(final GridNode node, final GridDhtForceKeysRequest<K, V> msg) {
        GridFuture<?> fut = cctx.mvcc().finishKeys(msg.keys(), msg.topologyVersion());

        if (fut.isDone())
            processForceKeysRequest0(node, msg);
        else
            fut.listenAsync(new CI1<GridFuture<?>>() {
                @Override public void apply(GridFuture<?> t) {
                    processForceKeysRequest0(node, msg);
                }
            });
    }

    /**
     * @param node Node originated request.
     * @param msg Force keys message.
     */
    private void processForceKeysRequest0(GridNode node, GridDhtForceKeysRequest<K, V> msg) {
        if (!enterBusy())
            return;

        try {
            GridNode loc = cctx.localNode();

            GridDhtForceKeysResponse<K, V> res = new GridDhtForceKeysResponse<>(msg.futureId(), msg.miniId());

            for (K k : msg.keys()) {
                int p = cctx.affinity().partition(k);

                GridDhtLocalPartition<K, V> locPart = top.localPartition(p, -1, false);

                // If this node is no longer an owner.
                if (locPart == null && !top.owners(p).contains(loc))
                    res.addMissed(k);

                GridCacheEntryEx<K, V> entry = cctx.dht().peekEx(k);

                // If entry is null, then local partition may have left
                // after the message was received. In that case, we are
                // confident that primary node knows of any changes to the key.
                if (entry != null) {
                    GridCacheEntryInfo<K, V> info = entry.info();

                    if (info != null && !info.isNew())
                        res.addInfo(info);
                }
                else if (log.isDebugEnabled())
                    log.debug("Key is not present in DHT cache: " + k);
            }

            if (log.isDebugEnabled())
                log.debug("Sending force key response [node=" + node.id() + ", res=" + res + ']');

            cctx.io().send(node, res);
        }
        catch (GridTopologyException ignore) {
            if (log.isDebugEnabled())
                log.debug("Received force key request form failed node (will ignore) [nodeId=" + node.id() +
                    ", req=" + msg + ']');
        }
        catch (GridException e) {
            U.error(log, "Failed to reply to force key request [nodeId=" + node.id() + ", req=" + msg + ']', e);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param node Node.
     * @param msg Message.
     */
    private void processForceKeyResponse(GridNode node, GridDhtForceKeysResponse<K, V> msg) {
        if (!enterBusy())
            return;

        try {
            GridDhtForceKeysFuture<K, V> f = forceKeyFuts.get(msg.futureId());

            if (f != null)
                f.onResult(node.id(), msg);
            else if (log.isDebugEnabled())
                log.debug("Receive force key response for unknown future (is it duplicate?) [nodeId=" + node.id() +
                    ", res=" + msg + ']');
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param node Node.
     * @param req Request.
     */
    private void processAffinityAssignmentRequest(final GridNode node,
        final GridDhtAffinityAssignmentRequest<K, V> req) {
        final long topVer = req.topologyVersion();

        if (log.isDebugEnabled())
            log.debug("Processing affinity assignment request [node=" + node + ", req=" + req + ']');

        cctx.affinity().affinityReadyFuture(req.topologyVersion()).listenAsync(new CI1<GridFuture<Long>>() {
            @Override public void apply(GridFuture<Long> fut) {
                if (log.isDebugEnabled())
                    log.debug("Affinity is ready for topology version, will send response [topVer=" + topVer +
                        ", node=" + node + ']');

                List<List<GridNode>> assignment = cctx.affinity().assignments(topVer);

                try {
                    cctx.io().send(node, new GridDhtAffinityAssignmentResponse<K, V>(topVer, assignment),
                        AFFINITY_POOL);
                }
                catch (GridException e) {
                    U.error(log, "Failed to send affinity assignment response to remote node [node=" + node + ']', e);
                }
            }
        });
    }

    /**
     * @param node Node.
     * @param res Response.
     */
    private void processAffinityAssignmentResponse(GridNode node, GridDhtAffinityAssignmentResponse<K, V> res) {
        if (log.isDebugEnabled())
            log.debug("Processing affinity assignment response [node=" + node + ", res=" + res + ']');

        for (GridDhtAssignmentFetchFuture<K, V> fut : pendingAssignmentFetchFuts.values())
            fut.onResponse(node, res);
    }

    /**
     * Resends partitions on partition evict within configured timeout.
     *
     * @param part Evicted partition.
     * @param updateSeq Update sequence.
     */
    public void onPartitionEvicted(GridDhtLocalPartition<K, V> part, boolean updateSeq) {
        top.onEvicted(part, updateSeq);

        if (cctx.events().isRecordable(EVT_CACHE_PRELOAD_PART_UNLOADED))
            cctx.events().addUnloadEvent(part.id());

        if (updateSeq)
            cctx.shared().exchange().scheduleResendPartitions();
    }

    /**
     * @param keys Keys to request.
     * @return Future for request.
     */
    @SuppressWarnings( {"unchecked", "RedundantCast"})
    @Override public GridDhtFuture<Object> request(Collection<? extends K> keys, long topVer) {
        final GridDhtForceKeysFuture<K, V> fut = new GridDhtForceKeysFuture<>(cctx, topVer, keys, this);

        GridFuture<?> topReadyFut = cctx.affinity().affinityReadyFuturex(topVer);

        if (startFut.isDone() && topReadyFut == null)
            fut.init();
        else {
            if (topReadyFut == null)
                startFut.listenAsync(new CI1<GridFuture<?>>() {
                    @Override public void apply(GridFuture<?> syncFut) {
                        fut.init();
                    }
                });
            else {
                GridCompoundFuture<Object, Object> compound = new GridCompoundFuture<>(cctx.kernalContext());

                compound.add((GridFuture<Object>)startFut);
                compound.add((GridFuture<Object>)topReadyFut);

                compound.markInitialized();

                compound.listenAsync(new CI1<GridFuture<?>>() {
                    @Override public void apply(GridFuture<?> syncFut) {
                        fut.init();
                    }
                });
            }
        }

        return (GridDhtFuture)fut;
    }

    /** {@inheritDoc} */
    @Override public void forcePreload() {
        demandPool.forcePreload();
    }

    /** {@inheritDoc} */
    @Override public void unwindUndeploys() {
        demandPool.unwindUndeploys();
    }

    /**
     * Adds future to future map.
     *
     * @param fut Future to add.
     */
    void addFuture(GridDhtForceKeysFuture<K, V> fut) {
        forceKeyFuts.put(fut.futureId(), fut);
    }

    /**
     * Removes future from future map.
     *
     * @param fut Future to remove.
     */
    void remoteFuture(GridDhtForceKeysFuture<K, V> fut) {
        forceKeyFuts.remove(fut.futureId(), fut);
    }

    /**
     *
     */
    private abstract class MessageHandler<M> implements GridBiInClosure<UUID, M> {
        /** */
        private static final long serialVersionUID = 0L;

        /** {@inheritDoc} */
        @Override public void apply(UUID nodeId, M msg) {
            GridNode node = cctx.node(nodeId);

            if (node == null) {
                if (log.isDebugEnabled())
                    log.debug("Received message from failed node [node=" + nodeId + ", msg=" + msg + ']');

                return;
            }

            if (log.isDebugEnabled())
                log.debug("Received message from node [node=" + nodeId + ", msg=" + msg + ']');

            onMessage(node , msg);
        }

        /**
         * @param node Node.
         * @param msg Message.
         */
        protected abstract void onMessage(GridNode node, M msg);
    }
}
