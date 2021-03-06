/*
 * Copyright (c) 2016 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.infrautils.jobcoordinator.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.GuardedBy;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.infrautils.jobcoordinator.JobCoordinator;
import org.opendaylight.infrautils.jobcoordinator.JobCoordinatorMonitor;
import org.opendaylight.infrautils.jobcoordinator.RollbackCallable;
import org.opendaylight.infrautils.utils.concurrent.LoggingThreadUncaughtExceptionHandler;
import org.opendaylight.infrautils.utils.concurrent.LoggingUncaughtThreadDeathContextRunnable;
import org.opendaylight.infrautils.utils.concurrent.ThreadFactoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO uncomment this @Singleton when Activator is removed, when DataStoreJobCoordinator in genius is removed,
// when dependency from mdsalutil-api to jobcoordinator-impl is removed
public class JobCoordinatorImpl implements JobCoordinator, JobCoordinatorMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(JobCoordinatorImpl.class);

    private static final long RETRY_WAIT_BASE_TIME_MILLIS = 1000;

    private static final int FJP_MAX_CAP = 0x7fff; // max #workers - 1; copy/pasted from ForkJoinPool private

    private final ForkJoinPool fjPool = new ForkJoinPool(
            Math.min(FJP_MAX_CAP, Runtime.getRuntime().availableProcessors()),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            LoggingThreadUncaughtExceptionHandler.toLogger(LOG),
            false);

    private final Map<String, JobQueue> jobQueueMap = new ConcurrentHashMap<>();
    private final ReentrantLock jobQueueMapLock = new ReentrantLock();
    private final Condition jobQueueMapCondition = jobQueueMapLock.newCondition();
    private final JobCoordinatorCounters counters = new JobCoordinatorCounters();

    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(5);

    private final Thread jobQueueHandlerThread;

    @GuardedBy("jobQueueMapLock")
    private boolean isJobAvailable = false;

    private volatile boolean shutdown = false;

    public JobCoordinatorImpl() {
        jobQueueHandlerThread = ThreadFactoryProvider.builder()
            .namePrefix("JobCoordinator-JobQueueHandler")
            .logger(LOG)
            .build().get()
            .newThread(new JobQueueHandler());
        jobQueueHandlerThread.start();
    }

    @PreDestroy
    public void destroy() {
        LOG.info("JobCoordinator shutting down... (tasks still running may be stopped/cancelled/interrupted)");

        jobQueueMapLock.lock();
        try {
            shutdown = true;
            jobQueueMapCondition.signalAll();
        } finally {
            jobQueueMapLock.unlock();
        }

        fjPool.shutdownNow();
        scheduledExecutorService.shutdownNow();

        try {
            jobQueueHandlerThread.join(10000);
        } catch (InterruptedException e) {
            // Shouldn't get interrupted - either way we don't care.
        }

        LOG.info("JobCoordinator now closed for business.");
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker) {
        enqueueJob(key, mainWorker, null, 0);
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker,
            RollbackCallable rollbackWorker) {
        enqueueJob(key, mainWorker, rollbackWorker, 0);
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker, int maxRetries) {
        enqueueJob(key, mainWorker, null, maxRetries);
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker,
            @Nullable RollbackCallable rollbackWorker, int maxRetries) {
        JobEntry jobEntry = new JobEntry(key, mainWorker, rollbackWorker, maxRetries);
        JobQueue jobQueue = jobQueueMap.computeIfAbsent(key, mapKey -> new JobQueue());
        jobQueue.addEntry(jobEntry);

        counters.jobsPending().incrementAndGet();
        counters.jobsIncomplete().incrementAndGet();
        counters.jobsCreated().incrementAndGet();

        signalForNexJob();
    }

    @Override
    public long getClearedTaskCount() {
        return counters.jobsCleared().get();
    }

    @Override
    public long getCreatedTaskCount() {
        return counters.jobsCreated().get();
    }

    @Override
    public long getIncompleteTaskCount() {
        return counters.jobsIncomplete().get();
    }

    @Override
    public long getPendingTaskCount() {
        return counters.jobsPending().get();
    }

    @Override
    public long getFailedJobCount() {
        return counters.jobsFailed().get();
    }

    @Override
    public long getRetriesCount() {
        return counters.jobsRetriesForFailure().get();
    }

    @Override
    public long getExecuteAttempts() {
        return counters.jobExecuteAttempts().get();
    }

    /**
     * Cleanup the submitted job from the job queue.
     **/
    private void clearJob(JobEntry jobEntry) {
        String jobKey = jobEntry.getKey();
        LOG.trace("About to clear jobkey {}", jobKey);
        JobQueue jobQueue = jobQueueMap.get(jobKey);
        if (jobQueue != null) {
            jobQueue.setExecutingEntry(null);
        } else {
            LOG.error("clearJob: jobQueueMap did not contain the key for this entry: {}", jobEntry);
        }
        counters.jobsCleared().incrementAndGet();
        counters.jobsIncomplete().decrementAndGet();
        signalForNexJob();
    }

    private void signalForNexJob() {
        jobQueueMapLock.lock();
        try {
            isJobAvailable = true;
            jobQueueMapCondition.signalAll();
        } finally {
            jobQueueMapLock.unlock();
        }
    }

    private boolean executeTask(Runnable task) {
        try {
            fjPool.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            if (!fjPool.isShutdown()) {
                LOG.error("ForkJoinPool task rejected", e);
            }

            return false;
        }
    }

    private boolean scheduleTask(Runnable task, long delay, TimeUnit unit) {
        try {
            scheduledExecutorService.schedule(task, delay, unit);
            return true;
        } catch (RejectedExecutionException e) {
            if (!scheduledExecutorService.isShutdown()) {
                LOG.error("ScheduledExecutorService task rejected", e);
            }

            return false;
        }
    }

    @VisibleForTesting
    // Ideally this would be package-scoped but the unit test class is in a separate package.
    protected Thread getJobQueueHandlerThread() {
        return jobQueueHandlerThread;
    }

    /**
     * JobCallback class is used as a future callback for main and rollback
     * workers to handle success and failure.
     */
    private class JobCallback implements FutureCallback<List<Void>> {
        private final JobEntry jobEntry;

        JobCallback(JobEntry jobEntry) {
            this.jobEntry = jobEntry;
        }

        /**
         * This implies that all the future instances have returned success. --
         * TODO: Confirm this
         */
        @Override
        public void onSuccess(@Nullable List<Void> voids) {
            LOG.trace("Job {} completed successfully", jobEntry.getKey());
            clearJob(jobEntry);
        }

        /**
         * This method is used to handle failure callbacks. If more retry
         * needed, the retrycount is decremented and mainworker is executed
         * again. After retries completed, rollbackworker is executed. If
         * rollbackworker fails, this is a double-fault. Double fault is logged
         * and ignored.
         */
        @Override
        public void onFailure(Throwable throwable) {
            LOG.warn("Job: {} failed", jobEntry, throwable);
            if (jobEntry.getMainWorker() == null) {
                LOG.error("Job: {} failed with Double-Fault. Bailing Out.", jobEntry);
                clearJob(jobEntry);
                return;
            }

            int retryCount = jobEntry.decrementRetryCountAndGet();
            counters.jobsRetriesForFailure().incrementAndGet();
            if (retryCount > 0) {
                long waitTime = RETRY_WAIT_BASE_TIME_MILLIS / retryCount;
                scheduleTask(() -> {
                    MainTask worker = new MainTask(jobEntry);
                    executeTask(worker);
                }, waitTime, TimeUnit.MILLISECONDS);
                return;
            }
            counters.jobsFailed().incrementAndGet();
            if (jobEntry.getRollbackWorker() != null) {
                jobEntry.setMainWorker(null);
                RollbackTask rollbackTask = new RollbackTask(jobEntry);
                executeTask(rollbackTask);
                return;
            }

            clearJob(jobEntry);
        }
    }

    /**
     * RollbackTask is used to execute the RollbackCallable provided by the
     * application in the eventuality of a failure.
     */
    private class RollbackTask extends LoggingUncaughtThreadDeathContextRunnable {
        private final JobEntry jobEntry;

        RollbackTask(JobEntry jobEntry) {
            super(LOG, jobEntry::toString);
            this.jobEntry = jobEntry;
        }

        @Override
        @SuppressWarnings("checkstyle:IllegalCatch")
        public void runWithUncheckedExceptionLogging() {
            RollbackCallable callable = jobEntry.getRollbackWorker();
            List<ListenableFuture<Void>> futures = null;
            if (callable != null) {
                callable.setFutures(jobEntry.getFutures());

                try {
                    futures = callable.call();
                } catch (Exception e) {
                    LOG.error("Exception when executing jobEntry: {}", jobEntry, e);
                }
            } else {
                LOG.error("Unexpected no (null) rollback worker on job: {}", jobEntry);
            }

            if (futures == null || futures.isEmpty()) {
                clearJob(jobEntry);
                return;
            }

            jobEntry.setFutures(futures);
            ListenableFuture<List<Void>> listenableFuture = Futures.allAsList(futures);
            Futures.addCallback(listenableFuture, new JobCallback(jobEntry), MoreExecutors.directExecutor());
        }
    }

    /**
     * Execute the MainWorker callable.
     */
    private class MainTask extends LoggingUncaughtThreadDeathContextRunnable {
        private static final int LONG_JOBS_THRESHOLD = 1000; // MS
        private final JobEntry jobEntry;

        MainTask(JobEntry jobEntry) {
            super(LOG, jobEntry::toString);
            this.jobEntry = jobEntry;
        }

        @Override
        @SuppressWarnings("checkstyle:illegalcatch")
        public void runWithUncheckedExceptionLogging() {
            List<ListenableFuture<Void>> futures = null;
            long jobStartTimestampNanos = System.nanoTime();
            LOG.trace("Running job {}", jobEntry.getKey());

            try {
                Callable<List<ListenableFuture<Void>>> mainWorker = jobEntry.getMainWorker();
                if (mainWorker != null) {
                    futures = mainWorker.call();
                } else {
                    LOG.error("Unexpected no (null) main worker on job: {}", jobEntry);
                }
                long jobExecutionTimeNanos = System.nanoTime() - jobStartTimestampNanos;
                printJobs(jobEntry.getKey(), TimeUnit.NANOSECONDS.toMillis(jobExecutionTimeNanos));
            } catch (Exception e) {
                counters.jobsFailed().incrementAndGet();
                LOG.error("Exception when executing jobEntry: {}", jobEntry, e);
            }

            if (futures == null || futures.isEmpty()) {
                clearJob(jobEntry);
                return;
            }

            jobEntry.setFutures(futures);
            ListenableFuture<List<Void>> listenableFuture = Futures.allAsList(futures);
            Futures.addCallback(listenableFuture, new JobCallback(jobEntry), MoreExecutors.directExecutor());
        }

        private void printJobs(String key, long jobExecutionTime) {
            if (jobExecutionTime > LONG_JOBS_THRESHOLD) {
                LOG.warn("Job {} took {}ms to complete", jobEntry.getKey(), jobExecutionTime);
                return;
            }
            LOG.trace("Job {} took {}ms to complete", jobEntry.getKey(), jobExecutionTime);
        }
    }

    private class JobQueueHandler implements Runnable {
        @Override
        @SuppressWarnings("checkstyle:illegalcatch")
        public void run() {
            LOG.info("Starting JobQueue Handler Thread");
            while (true) {
                try {
                    for (Map.Entry<String, JobQueue> entry : jobQueueMap.entrySet()) {
                        if (shutdown) {
                            break;
                        }

                        JobQueue jobQueue = entry.getValue();
                        if (jobQueue.getExecutingEntry() != null) {
                            counters.jobExecuteAttempts().incrementAndGet();
                            continue;
                        }
                        JobEntry jobEntry = jobQueue.poll();
                        if (jobEntry == null) {
                            // job queue is empty. so continue with next job queue entry
                            continue;
                        }
                        jobQueue.setExecutingEntry(jobEntry);
                        MainTask worker = new MainTask(jobEntry);
                        LOG.trace("Executing job {}", jobEntry.getKey());

                        if (executeTask(worker)) {
                            counters.jobsPending().decrementAndGet();
                        }
                    }

                    if (!waitForJobIfNeeded()) {
                        break;
                    }
                } catch (Exception e) {
                    LOG.error("Exception while executing the tasks", e);
                }
            }
        }

        private boolean waitForJobIfNeeded() throws InterruptedException {
            jobQueueMapLock.lock();
            try {
                while (!isJobAvailable && !shutdown) {
                    jobQueueMapCondition.await(1, TimeUnit.SECONDS);
                }
                isJobAvailable = false;
                return !shutdown;
            } finally {
                jobQueueMapLock.unlock();
            }
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("incompleteTasks", getIncompleteTaskCount())
                .add("pendingTasks", getPendingTaskCount()).add("failedJobs", getFailedJobCount())
                .add("clearedTasks", getClearedTaskCount()).add("createdTasks", getCreatedTaskCount())
                .add("executeAttempts", getExecuteAttempts()).add("retriesCount", getRetriesCount())
                .add("fjPool", fjPool).add("jobQueueMap", jobQueueMap).add("jobQueueMapLock", jobQueueMapLock)
                .add("scheduledExecutorService", scheduledExecutorService).add("isJobAvailable", isJobAvailable)
                .add("jobQueueMapCondition", jobQueueMapCondition)
                .toString();
    }
}
