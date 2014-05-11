/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.hadoop.jobtracker;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.query.*;
import org.gridgain.grid.events.*;
import org.gridgain.grid.hadoop.*;
import org.gridgain.grid.kernal.managers.eventstorage.*;
import org.gridgain.grid.kernal.processors.hadoop.*;
import org.gridgain.grid.kernal.processors.hadoop.taskexecutor.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.hadoop.GridHadoopTaskType.*;
import static org.gridgain.grid.kernal.processors.hadoop.jobtracker.GridHadoopJobPhase.*;
import static org.gridgain.grid.kernal.processors.hadoop.taskexecutor.GridHadoopTaskState.*;

/**
 * Hadoop job tracker.
 */
public class GridHadoopJobTracker extends GridHadoopComponent {
    /** System cache. */
    private GridCacheProjection<GridHadoopJobId, GridHadoopJobMetadata> jobMetaPrj;

    /** Map-reduce execution planner. */
    private GridHadoopMapReducePlanner mrPlanner;

    /** Locally active jobs. */
    private ConcurrentMap<GridHadoopJobId, JobLocalState> activeJobs = new ConcurrentHashMap8<>();

    /** Locally requested finish futures. */
    private ConcurrentMap<GridHadoopJobId, GridFutureAdapter<GridHadoopJobId>> activeFinishFuts =
        new ConcurrentHashMap8<>();

    /** Event processing service. */
    private ExecutorService evtProcSvc;

    /** Component busy lock. */
    private GridSpinReadWriteLock busyLock;

    /** {@inheritDoc} */
    @Override public void start(GridHadoopContext ctx) throws GridException {
        super.start(ctx);

        busyLock = new GridSpinReadWriteLock();

        evtProcSvc = Executors.newFixedThreadPool(1);
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws GridException {
        super.onKernalStart();

        GridCache<Object, Object> sysCache = ctx.kernalContext().cache().cache(ctx.systemCacheName());

        assert sysCache != null;

        mrPlanner = ctx.planner();

        ctx.kernalContext().resource().injectGeneric(mrPlanner);

        jobMetaPrj = sysCache.projection(GridHadoopJobId.class, GridHadoopJobMetadata.class);

        GridCacheContinuousQuery<GridHadoopJobId, GridHadoopJobMetadata> qry = jobMetaPrj.queries()
            .createContinuousQuery();

        qry.callback(new GridBiPredicate<UUID,
            Collection<Map.Entry<GridHadoopJobId, GridHadoopJobMetadata>>>() {
            @Override public boolean apply(UUID nodeId,
                final Collection<Map.Entry<GridHadoopJobId, GridHadoopJobMetadata>> evts) {
                if (!busyLock.tryReadLock())
                    return false;

                try {
                    // Must process query callback in a separate thread to avoid deadlocks.
                    evtProcSvc.submit(new EventHandler() {
                        @Override protected void body() {
                            processJobMetadata(evts);
                        }
                    });

                    return true;
                }
                finally {
                    busyLock.readUnlock();
                }
            }
        });

        qry.execute();

        ctx.kernalContext().event().addLocalEventListener(new GridLocalEventListener() {
            @Override public void onEvent(final GridEvent evt) {
                if (!busyLock.tryReadLock())
                    return;

                try {
                    // Must process discovery callback in a separate thread to avoid deadlock.
                    evtProcSvc.submit(new EventHandler() {
                        @Override protected void body() {
                            processNodeLeft((GridDiscoveryEvent)evt);
                        }
                    });
                }
                finally {
                    busyLock.readUnlock();
                }
            }
        }, GridEventType.EVT_NODE_FAILED, GridEventType.EVT_NODE_LEFT);
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        super.onKernalStop(cancel);

        busyLock.writeLock();

        evtProcSvc.shutdown();

        // Fail all pending futures.
        for (GridFutureAdapter<GridHadoopJobId> fut : activeFinishFuts.values())
            fut.onDone(new GridException("Failed to execute Hadoop map-reduce job (grid is stopping)."));
    }

    /**
     * Submits execution of Hadoop job to grid.
     *
     * @param jobId Job ID.
     * @param info Job info.
     * @return Job completion future.
     */
    public GridFuture<GridHadoopJobId> submit(GridHadoopJobId jobId, GridHadoopJobInfo info) {
        if (!busyLock.tryReadLock()) {
            return new GridFinishedFutureEx<>(new GridException("Failed to execute map-reduce job " +
                "(grid is stopping): " + info));
        }

        try {
            GridHadoopJob job = ctx.jobFactory().createJob(jobId, info);

            Collection<GridHadoopInputSplit> splits = job.input();

            GridHadoopMapReducePlan mrPlan = mrPlanner.preparePlan(splits, ctx.nodes(), job, null);

            GridHadoopJobMetadata meta = new GridHadoopJobMetadata(jobId, info);

            meta.mapReducePlan(mrPlan);

            meta.externalExecution(((GridHadoopDefaultJobInfo)info).configuration().getBoolean(
                "gridgain.hadoop.external_execution", false)); // TODO where constants should be?

            meta.pendingSplits(allSplits(mrPlan));
            meta.pendingReducers(allReducers(job));

            GridFutureAdapter<GridHadoopJobId> completeFut = new GridFutureAdapter<>();

            GridFutureAdapter<GridHadoopJobId> old = activeFinishFuts.put(jobId, completeFut);

            assert old == null : "Duplicate completion future [jobId=" + jobId + ", old=" + old + ']';

            if (log.isDebugEnabled())
                log.debug("Submitting job metadata [jobId=" + jobId + ", meta=" + meta + ']');

            jobMetaPrj.put(jobId, meta);

            return completeFut;
        }
        catch (GridException e) {
            return new GridFinishedFutureEx<>(e);
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * Gets hadoop job status for given job ID.
     *
     * @param jobId Job ID to get status for.
     * @return Job status for given job ID or {@code null} if job was not found.
     */
    @Nullable public GridHadoopJobStatus status(GridHadoopJobId jobId) throws GridException {
        if (!busyLock.tryReadLock())
            return null; // Grid is stopping.

        try {
            GridHadoopJobMetadata meta = jobMetaPrj.get(jobId);

            if (meta == null)
                return null;

            if (log.isDebugEnabled())
                log.debug("Got job metadata for status check [locNodeId=" + ctx.localNodeId() + ", meta=" + meta + ']');

            GridHadoopJobInfo info = meta.jobInfo();

            if (meta.phase() == PHASE_COMPLETE) {
                if (log.isDebugEnabled())
                    log.debug("Job is complete, returning finished future: " + jobId);

                return new GridHadoopJobStatus(new GridFinishedFutureEx<>(jobId), info);
            }

            GridFutureAdapter<GridHadoopJobId> fut = F.addIfAbsent(activeFinishFuts, jobId,
                new GridFutureAdapter<GridHadoopJobId>());

            // Get meta from cache one more time to close the window.
            meta = jobMetaPrj.get(jobId);

            if (log.isDebugEnabled())
                log.debug("Re-checking job metadata [locNodeId=" + ctx.localNodeId() + ", meta=" + meta + ']');

            if (meta == null || meta.phase() == PHASE_COMPLETE) {
                fut.onDone(jobId, meta.failCause());

                activeFinishFuts.remove(jobId , fut);
            }

            return new GridHadoopJobStatus(fut, info);
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * Gets job plan by job ID.
     *
     * @param jobId Job ID.
     * @return Job plan.
     * @throws GridException If failed.
     */
    public GridHadoopMapReducePlan plan(GridHadoopJobId jobId) throws GridException {
        if (!busyLock.tryReadLock())
            return null;

        try {
            GridHadoopJobMetadata meta = jobMetaPrj.get(jobId);

            if (meta != null)
                return meta.mapReducePlan();

            return null;
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * Callback from task executor invoked when a task has been finished.
     *
     * @param taskInfo Task info.
     * @param status Task status.
     */
    public void onTaskFinished(GridHadoopTaskInfo taskInfo, GridHadoopTaskStatus status) {
        if (!busyLock.tryReadLock())
            return;

        try {
            assert status.state() != RUNNING;

            if (log.isDebugEnabled())
                log.debug("Received task finished callback [taskInfo=" + taskInfo + ", status=" + status + ']');

            JobLocalState state = activeJobs.get(taskInfo.jobId());

            assert (status.state() != FAILED && status.state() != CRASHED) || status.failCause() != null :
                "Invalid task status [taskInfo=" + taskInfo + ", status=" + status + ']';

            assert state != null;

            switch (taskInfo.type()) {
                case MAP: {
                    state.onMapFinished(taskInfo, status);

                    break;
                }

                case REDUCE: {
                    state.onReduceFinished(taskInfo, status);

                    break;
                }

                case COMBINE: {
                    state.onCombineFinished(taskInfo, status);

                    break;
                }

                case COMMIT:
                case ABORT: {
                    GridCacheEntry<GridHadoopJobId, GridHadoopJobMetadata> entry = jobMetaPrj.entry(taskInfo.jobId());

                    entry.timeToLive(ctx.configuration().getFinishedJobInfoTtl());

                    entry.transformAsync(new UpdatePhaseClosure(PHASE_COMPLETE));

                    break;
                }
            }
        }
        finally {
            busyLock.readUnlock();
        }
    }

    /**
     * Gets all input splits for given hadoop map-reduce plan.
     *
     * @param plan Map-reduce plan.
     * @return Collection of all input splits that should be processed.
     */
    private Collection<GridHadoopInputSplit> allSplits(GridHadoopMapReducePlan plan) {
        Collection<GridHadoopInputSplit> res = new HashSet<>();

        for (UUID nodeId : plan.mapperNodeIds())
            res.addAll(plan.mappers(nodeId));

        return res;
    }

    /**
     * Gets all reducers for this job.
     *
     * @param job Job to get reducers for.
     * @return Collection of reducers.
     */
    private Collection<Integer> allReducers(GridHadoopJob job) {
        Collection<Integer> res = new HashSet<>();

        for (int i = 0; i < job.reducers(); i++)
            res.add(i);

        return res;
    }

    /**
     * Processes node leave (oro fail) event.
     *
     * @param evt Discovery event.
     */
    private void processNodeLeft(GridDiscoveryEvent evt) {
        if (log.isDebugEnabled())
            log.debug("Processing discovery event [locNodeId=" + ctx.localNodeId() + ", evt=" + evt + ']');

        // Check only if this node is responsible for job status updates.
        if (ctx.jobUpdateLeader()) {
            // Iteration over all local entries is correct since system cache is REPLICATED.
            for (GridHadoopJobMetadata meta : jobMetaPrj.values()) {
                try {
                    GridHadoopMapReducePlan plan = meta.mapReducePlan();

                    GridHadoopJobPhase phase = meta.phase();

                    if (phase == PHASE_MAP || phase == PHASE_REDUCE) {
                        // Must check all nodes, even that are not event node ID due to
                        // multiple node failure possibility.
                        Collection<GridHadoopInputSplit> cancelSplits = null;

                        for (UUID nodeId : plan.mapperNodeIds()) {
                            if (ctx.kernalContext().discovery().node(nodeId) == null) {
                                // Node has left the grid.
                                Collection<GridHadoopInputSplit> mappers = plan.mappers(nodeId);

                                if (cancelSplits == null)
                                    cancelSplits = new HashSet<>();

                                cancelSplits.addAll(mappers);
                            }
                        }

                        Collection<Integer> cancelReducers = null;

                        for (UUID nodeId : plan.reducerNodeIds()) {
                            if (ctx.kernalContext().discovery().node(nodeId) == null) {
                                // Node has left the grid.
                                int[] reducers = plan.reducers(nodeId);

                                if (cancelReducers == null)
                                    cancelReducers = new HashSet<>();

                                for (int rdc : reducers)
                                    cancelReducers.add(rdc);
                            }
                        }

                        if (cancelSplits != null || cancelReducers != null)
                            jobMetaPrj.transform(meta.jobId(), new CancelJobClosure(new GridException("One or more nodes " +
                                "participating in map-reduce job execution failed."), cancelSplits, cancelReducers));
                    }
                }
                catch (GridException e) {
                    U.error(log, "Failed to cancel job: " + meta, e);
                }
            }
        }
    }

    /**
     * @param updated Updated cache entries.
     */
    private void processJobMetadata(Iterable<Map.Entry<GridHadoopJobId, GridHadoopJobMetadata>> updated) {
        UUID locNodeId = ctx.localNodeId();

        for (Map.Entry<GridHadoopJobId, GridHadoopJobMetadata> entry : updated) {
            GridHadoopJobId jobId = entry.getKey();
            GridHadoopJobMetadata meta = entry.getValue();

            if (log.isDebugEnabled())
                log.debug("Processing job metadata update callback [locNodeId=" + locNodeId +
                    ", meta=" + meta + ']');

            JobLocalState state = activeJobs.get(jobId);

            GridHadoopJob job = ctx.jobFactory().createJob(jobId, meta.jobInfo());

            GridHadoopMapReducePlan plan = meta.mapReducePlan();

            boolean ext = meta.externalExecution();

            if (ext)
                ctx.taskExecutor(ext).onJobStateChanged(jobId, meta);

            switch (meta.phase()) {
                case PHASE_MAP: {
                    // Check if we should initiate new task on local node.
                    Collection<GridHadoopTaskInfo> tasks = mapperTasks(plan.mappers(locNodeId), job, meta);

                    if (ext)
                        // Must send reducers in one pass when execution is external.
                        tasks = reducerTasks(plan.reducers(locNodeId), job, meta, tasks);

                    if (tasks != null)
                        ctx.taskExecutor(ext).run(job, tasks);

                    break;
                }

                case PHASE_REDUCE: {
                    if (meta.pendingReducers().isEmpty() && ctx.jobUpdateLeader()) {
                        GridHadoopTaskInfo info = new GridHadoopTaskInfo(ctx.localNodeId(), GridHadoopTaskType.COMMIT,
                            jobId, 0, 0, null);

                        if (log.isDebugEnabled())
                            log.debug("Submitting COMMIT task for execution [locNodeId=" + locNodeId +
                                ", jobId=" + jobId + ']');

                        // Always use internal executor to abort or commit.
                        ctx.taskExecutor(false).run(job, Collections.singletonList(info));

                        return;
                    }

                    if (!ext) {
                        Collection<GridHadoopTaskInfo> tasks = reducerTasks(plan.reducers(locNodeId), job, meta, null);

                        if (tasks != null)
                            ctx.taskExecutor(ext).run(job, tasks);
                    }

                    break;
                }

                case PHASE_CANCELLING: {
                    // Prevent multiple task executor notification.
                    if (state != null && state.onCancel()) {
                        if (log.isDebugEnabled())
                            log.debug("Cancelling local task execution for job: " + meta);

                        ctx.taskExecutor(ext).cancelTasks(jobId);
                    }

                    if (meta.pendingSplits().isEmpty() && meta.pendingReducers().isEmpty()) {
                        if (ctx.jobUpdateLeader()) {
                            GridHadoopTaskInfo info = new GridHadoopTaskInfo(ctx.localNodeId(),
                                GridHadoopTaskType.ABORT, jobId, 0, 0, null);

                            if (log.isDebugEnabled())
                                log.debug("Submitting ABORT task for execution [locNodeId=" + locNodeId +
                                    ", jobId=" + jobId + ']');

                            // Always use internal executor to abort or commit.
                            ctx.taskExecutor(false).run(job, Collections.singletonList(info));
                        }

                        return;
                    }
                    else {
                        // Check if there are unscheduled mappers or reducers.
                        Collection<GridHadoopInputSplit> cancelMappers = new ArrayList<>();
                        Collection<Integer> cancelReducers = new ArrayList<>();

                        Collection<GridHadoopInputSplit> mappers = plan.mappers(ctx.localNodeId());

                        if (mappers != null) {
                            for (GridHadoopInputSplit b : mappers) {
                                if (state == null || !state.mapperScheduled(b))
                                    cancelMappers.add(b);
                            }
                        }

                        int[] rdc = plan.reducers(ctx.localNodeId());

                        if (rdc != null) {
                            for (int r : rdc) {
                                if (state == null || !state.reducerScheduled(r))
                                    cancelReducers.add(r);
                            }
                        }

                        if (!cancelMappers.isEmpty() || !cancelReducers.isEmpty())
                            jobMetaPrj.transformAsync(jobId, new CancelJobClosure(cancelMappers, cancelReducers));
                    }

                    break;
                }

                case PHASE_COMPLETE: {
                    if (state != null) {
                        state = activeJobs.remove(jobId);

                        assert state != null;

                        ctx.shuffle().jobFinished(jobId);
                    }

                    GridFutureAdapter<GridHadoopJobId> finishFut = activeFinishFuts.remove(jobId);

                    if (finishFut != null) {
                        if (log.isDebugEnabled())
                            log.debug("Completing job future [locNodeId=" + locNodeId + ", meta=" + meta + ']');

                        finishFut.onDone(jobId, meta.failCause());
                    }

                    break;
                }

                default:
                    assert false;
            }
        }
    }

    /**
     * Creates mapper tasks based on job information.
     *
     * @param mappers Mapper blocks.
     * @param job Job instance.
     * @param meta Job metadata.
     * @return Collection of created task infos or {@code null} if no mapper tasks scheduled for local node.
     */
    private Collection<GridHadoopTaskInfo> mapperTasks(Iterable<GridHadoopInputSplit> mappers,
        GridHadoopJob job, GridHadoopJobMetadata meta) {
        UUID locNodeId = ctx.localNodeId();
        GridHadoopJobId jobId = job.id();

        JobLocalState state = activeJobs.get(jobId);

        Collection<GridHadoopTaskInfo> tasks = null;

        if (mappers != null) {
            if (state == null)
                state = initState(job, meta);

            for (GridHadoopInputSplit split : mappers) {
                if (state.addMapper(split)) {
                    if (log.isDebugEnabled())
                        log.debug("Submitting MAP task for execution [locNodeId=" + locNodeId +
                            ", split=" + split + ']');

                    GridHadoopTaskInfo taskInfo = new GridHadoopTaskInfo(locNodeId,
                        GridHadoopTaskType.MAP, jobId, meta.taskNumber(split), 0, split);

                    if (tasks == null)
                        tasks = new ArrayList<>();

                    tasks.add(taskInfo);
                }
            }
        }

        return tasks;
    }

    /**
     * Creates reducer tasks based on job information.
     *
     * @param reducers Reducers (may be {@code null}).
     * @param job Job instance.
     * @param meta Job metadata.
     * @param tasks Optional collection of tasks to add new tasks to.
     * @return Collection of task infos.
     */
    private Collection<GridHadoopTaskInfo> reducerTasks(int[] reducers, GridHadoopJob job, GridHadoopJobMetadata meta,
        Collection<GridHadoopTaskInfo> tasks) {
        UUID locNodeId = ctx.localNodeId();
        GridHadoopJobId jobId = job.id();

        JobLocalState state = activeJobs.get(jobId);

        if (reducers != null) {
            if (state == null)
                state = initState(job, meta);

            for (int rdc : reducers) {
                if (state.addReducer(rdc)) {
                    if (log.isDebugEnabled())
                        log.debug("Submitting REDUCE task for execution [locNodeId=" + locNodeId +
                            ", rdc=" + rdc + ']');

                    GridHadoopTaskInfo taskInfo = new GridHadoopTaskInfo(locNodeId,
                        GridHadoopTaskType.REDUCE, jobId, rdc, 0, null);

                    if (tasks == null)
                        tasks = new ArrayList<>();

                    tasks.add(taskInfo);
                }
            }
        }

        return tasks;
    }

    /**
     * Initializes local state for given job metadata.
     *
     * @param meta Job metadata.
     * @return Local state.
     */
    private JobLocalState initState(GridHadoopJob job, GridHadoopJobMetadata meta) {
        GridHadoopJobId jobId = meta.jobId();

        JobLocalState state = new JobLocalState(job, meta);

        return F.addIfAbsent(activeJobs, jobId, state);
    }

    /**
     * Gets job instance by job ID.
     *
     * @param jobId Job ID.
     * @return Job.
     */
    @Nullable public GridHadoopJob job(GridHadoopJobId jobId) throws GridException {
        JobLocalState state = activeJobs.get(jobId);

        if (state != null)
            return state.job;

        GridHadoopJobMetadata meta = jobMetaPrj.get(jobId);

        if (meta == null)
            return null;

        return ctx.jobFactory().createJob(jobId, meta.jobInfo());
    }

    /**
     * Event handler protected by busy lock.
     */
    private abstract class EventHandler implements Runnable {
        /** {@inheritDoc} */
        @Override public void run() {
            if (!busyLock.tryReadLock())
                return;

            try {
                body();
            }
            catch (Throwable e) {
                log.error("Unhandled exception while processing event.", e);
            }
            finally {
                busyLock.readUnlock();
            }
        }

        /**
         * Handler body.
         */
        protected abstract void body();
    }

    /**
     *
     */
    private class JobLocalState {
        /** Job info. */
        private GridHadoopJob job;

        /** Execution plan. */
        private GridHadoopJobMetadata meta;

        /** Mappers. */
        private Collection<GridHadoopInputSplit> currMappers = new HashSet<>();

        /** Reducers. */
        private Collection<Integer> currReducers = new HashSet<>();

        /** Number of completed mappers. */
        private AtomicInteger completedMappersCnt = new AtomicInteger();

        /** Cancelled flag. */
        private boolean cancelled;

        /**
         * @param job Job.
         */
        private JobLocalState(GridHadoopJob job, GridHadoopJobMetadata meta) {
            this.job = job;
            this.meta = meta;
        }

        /**
         * @param mapSplit Map split to add.
         * @return {@code True} if mapper was added.
         */
        private boolean addMapper(GridHadoopInputSplit mapSplit) {
            return currMappers.add(mapSplit);
        }

        /**
         * @param rdc Reducer number to add.
         * @return {@code True} if reducer was added.
         */
        private boolean addReducer(int rdc) {
            return currReducers.add(rdc);
        }

        /**
         * Checks whether this split was scheduled for given attempt.
         *
         * @param mapSplit Map split to check.
         * @return {@code True} if mapper was scheduled.
         */
        public boolean mapperScheduled(GridHadoopInputSplit mapSplit) {
            return currMappers.contains(mapSplit);
        }

        /**
         * Checks whether this split was scheduled for given attempt.
         *
         * @param rdc Reducer number to check.
         * @return {@code True} if reducer was scheduled.
         */
        public boolean reducerScheduled(int rdc) {
            return currReducers.contains(rdc);
        }

        /**
         * @param taskInfo Task info.
         * @param status Task status.
         */
        private void onMapFinished(final GridHadoopTaskInfo taskInfo, GridHadoopTaskStatus status) {
            final GridHadoopJobId jobId = taskInfo.jobId();

            boolean lastMapperFinished = completedMappersCnt.incrementAndGet() == currMappers.size();

            if (status.state() == FAILED || status.state() == CRASHED) {
                // Fail the whole job.
                jobMetaPrj.transformAsync(jobId, new RemoveMappersClosure(taskInfo.inputSplit(), status.failCause()));

                return;
            }

            if (job.hasCombiner()) {
                // Create combiner.
                if (lastMapperFinished && !meta.externalExecution()) {
                    GridHadoopTaskInfo info = new GridHadoopTaskInfo(ctx.localNodeId(), COMBINE, jobId,
                        meta.taskNumber(ctx.localNodeId()), taskInfo.attempt(), null);

                    ctx.taskExecutor(meta.externalExecution()).run(job, Collections.singletonList(info));
                }
            }
            else {
                GridInClosure<GridFuture<?>> cacheUpdater = new CIX1<GridFuture<?>>() {
                    @Override public void applyx(GridFuture<?> f) {
                        Throwable err = null;

                        if (f != null) {
                            try {
                                f.get();
                            }
                            catch (GridException e) {
                                err = e;
                            }
                        }

                        jobMetaPrj.transformAsync(jobId, new RemoveMappersClosure(taskInfo.inputSplit(), err));
                    }
                };

                if (lastMapperFinished)
                    ctx.shuffle().flush(jobId).listenAsync(cacheUpdater);
                else
                    cacheUpdater.apply(null);
            }
        }

        /**
         * @param taskInfo Task info.
         * @param status Task status.
         */
        private void onReduceFinished(GridHadoopTaskInfo taskInfo, GridHadoopTaskStatus status) {
            GridHadoopJobId jobId = taskInfo.jobId();
            if (status.state() == FAILED || status.state() == CRASHED)
                // Fail the whole job.
                jobMetaPrj.transformAsync(jobId, new RemoveReducerClosure(taskInfo.taskNumber(), status.failCause()));
            else
                jobMetaPrj.transformAsync(jobId, new RemoveReducerClosure(taskInfo.taskNumber()));
        }

        /**
         * @param taskInfo Task info.
         * @param status Task status.
         */
        private void onCombineFinished(GridHadoopTaskInfo taskInfo, GridHadoopTaskStatus status) {
            final GridHadoopJobId jobId = taskInfo.jobId();

            assert job.hasCombiner();

            if (status.state() == FAILED || status.state() == CRASHED)
                // Fail the whole job.
                jobMetaPrj.transformAsync(jobId, new RemoveMappersClosure(currMappers, status.failCause()));
            else {
                ctx.shuffle().flush(jobId).listenAsync(new CIX1<GridFuture<?>>() {
                    @Override public void applyx(GridFuture<?> f) {
                        Throwable err = null;

                        if (f != null) {
                            try {
                                f.get();
                            }
                            catch (GridException e) {
                                err = e;
                            }
                        }

                        jobMetaPrj.transformAsync(jobId, new RemoveMappersClosure(currMappers, err));
                    }
                });
            }
        }

        /**
         * @return {@code True} if job was cancelled by this (first) call.
         */
        public boolean onCancel() {
            if (!cancelled) {
                cancelled = true;

                return true;
            }

            return false;
        }
    }

    /**
     * Update job phase transform closure.
     */
    private static class UpdatePhaseClosure implements GridClosure<GridHadoopJobMetadata, GridHadoopJobMetadata> {
        /** Phase to update. */
        private GridHadoopJobPhase phase;

        /**
         * @param phase Phase to update.
         */
        private UpdatePhaseClosure(GridHadoopJobPhase phase) {
            this.phase = phase;
        }

        /** {@inheritDoc} */
        @Override public GridHadoopJobMetadata apply(GridHadoopJobMetadata meta) {
            GridHadoopJobMetadata cp = new GridHadoopJobMetadata(meta);

            cp.phase(phase);

            return cp;
        }
    }

    /**
     * Remove mapper transform closure.
     */
    private static class RemoveMappersClosure implements GridClosure<GridHadoopJobMetadata, GridHadoopJobMetadata> {
        /** Mapper split to remove. */
        private Collection<GridHadoopInputSplit> splits;

        /** Error. */
        private Throwable err;

        /**
         * @param split Mapper split to remove.
         */
        private RemoveMappersClosure(GridHadoopInputSplit split) {
            splits = Collections.singletonList(split);
        }

        /**
         * @param split Mapper split to remove.
         */
        private RemoveMappersClosure(GridHadoopInputSplit split, Throwable err) {
            splits = Collections.singletonList(split);
            this.err = err;
        }

        /**
         * @param splits Mapper splits to remove.
         */
        private RemoveMappersClosure(Collection<GridHadoopInputSplit> splits) {
            this.splits = splits;
        }

        /**
         * @param splits Mapper splits to remove.
         */
        private RemoveMappersClosure(Collection<GridHadoopInputSplit> splits, Throwable err) {
            this.splits = splits;
            this.err = err;
        }

        /** {@inheritDoc} */
        @Override public GridHadoopJobMetadata apply(GridHadoopJobMetadata meta) {
            GridHadoopJobMetadata cp = new GridHadoopJobMetadata(meta);

            Collection<GridHadoopInputSplit> splitsCp = new HashSet<>(cp.pendingSplits());

            splitsCp.removeAll(splits);

            cp.pendingSplits(splitsCp);

            cp.failCause(err);

            if (err != null)
                cp.phase(PHASE_CANCELLING);

            if (splitsCp.isEmpty()) {
                if (cp.phase() != PHASE_CANCELLING)
                    cp.phase(PHASE_REDUCE);
            }

            return cp;
        }
    }

    /**
     * Remove reducer transform closure.
     */
    private static class RemoveReducerClosure implements GridClosure<GridHadoopJobMetadata, GridHadoopJobMetadata> {
        /** Mapper split to remove. */
        private int rdc;

        /** Error. */
        private Throwable err;

        /**
         * @param rdc Reducer to remove.
         */
        private RemoveReducerClosure(int rdc) {
            this.rdc = rdc;
        }

        /**
         * @param rdc Reducer to remove.
         */
        private RemoveReducerClosure(int rdc, Throwable err) {
            this.rdc = rdc;
            this.err = err;
        }

        /** {@inheritDoc} */
        @Override public GridHadoopJobMetadata apply(GridHadoopJobMetadata meta) {
            GridHadoopJobMetadata cp = new GridHadoopJobMetadata(meta);

            Collection<Integer> rdcCp = new HashSet<>(cp.pendingReducers());

            rdcCp.remove(rdc);

            cp.pendingReducers(rdcCp);

            if (err != null)
                cp.phase(PHASE_CANCELLING);

            return cp;
        }
    }

    /**
     * Remove reducer transform closure.
     */
    private static class CancelJobClosure implements GridClosure<GridHadoopJobMetadata, GridHadoopJobMetadata> {
        /** Mapper split to remove. */
        private Collection<GridHadoopInputSplit> splits;

        /** Reducers to remove. */
        private Collection<Integer> rdc;

        /** Error. */
        private Throwable err;

        /**
         * @param splits Splits to remove.
         * @param rdc Reducers to remove.
         */
        private CancelJobClosure(Collection<GridHadoopInputSplit> splits, Collection<Integer> rdc) {
            this.splits = splits;
            this.rdc = rdc;
        }

        /**
         * @param err Error.
         * @param splits Splits to remove.
         * @param rdc Reducers to remove.
         */
        private CancelJobClosure(Throwable err, Collection<GridHadoopInputSplit> splits, Collection<Integer> rdc) {
            this.splits = splits;
            this.rdc = rdc;
            this.err = err;
        }

        /** {@inheritDoc} */
        @Override public GridHadoopJobMetadata apply(GridHadoopJobMetadata meta) {
            assert meta.phase() == PHASE_CANCELLING || err != null: "Invalid phase for cancel: " + meta;

            GridHadoopJobMetadata cp = new GridHadoopJobMetadata(meta);

            Collection<Integer> rdcCp = new HashSet<>(cp.pendingReducers());

            rdcCp.removeAll(rdc);

            cp.pendingReducers(rdcCp);

            Collection<GridHadoopInputSplit> splitsCp = new HashSet<>(cp.pendingSplits());

            splitsCp.removeAll(splits);

            cp.pendingSplits(splitsCp);

            cp.phase(PHASE_CANCELLING);

            if (splitsCp.isEmpty() && rdcCp.isEmpty())
                cp.phase(PHASE_COMPLETE);

            return cp;
        }
    }
}
