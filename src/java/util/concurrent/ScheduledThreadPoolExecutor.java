/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * A {@link ThreadPoolExecutor} that can additionally schedule
 * commands to run after a given delay, or to execute
 * periodically. This class is preferable to {@link java.util.Timer}
 * when multiple worker threads are needed, or when the additional
 * flexibility or capabilities of {@link ThreadPoolExecutor} (which
 * this class extends) are required.
 *
 * ScheduledThreadPoolExecutor是ThreadPoolExecutor的变种版本，可以延时或周期性执行任务
 * STPE比java.util.Timer更具多线程操控能力以及更多的功能特性
 *
 * <p>Delayed tasks execute no sooner than they are enabled, but
 * without any real-time guarantees about when, after they are
 * enabled, they will commence. Tasks scheduled for exactly the same
 * execution time are enabled in first-in-first-out (FIFO) order of
 * submission.
 *
 * 任务是延时执行的，但是实际上何时执行是不确定的（要看工作线程繁忙程度了以及同个时间下的任务密集程度）
 * 任务一旦到达可执行状态，就有可能开始被执行，剩余相同延时的任务则按照任务队列FIFO方式弹出执行
 *
 * <p>When a submitted task is cancelled before it is run, execution
 * is suppressed. By default, such a cancelled task is not
 * automatically removed from the work queue until its delay
 * elapses. While this enables further inspection and monitoring, it
 * may also cause unbounded retention of cancelled tasks. To avoid
 * this, set {@link #setRemoveOnCancelPolicy} to {@code true}, which
 * causes tasks to be immediately removed from the work queue at
 * time of cancellation.
 *
 * 在执行前就被取消的任务是不会被执行的
 * 默认情况下被取消的任务还是会留在任务队列中，只有当工作线程取到他的时候才会发现它已经被取消从而丢弃它
 * 由于是只有延时时间到才能将取消的任务从任务队列中移除，这有可能导致该任务长时间存留在队列中
 * 可以通过设置setRemoveOnCancelPolicy()来让任务在被取消时即从任务队列中移除
 *
 * <p>Successive executions of a task scheduled via
 * {@code scheduleAtFixedRate} or
 * {@code scheduleWithFixedDelay} do not overlap. While different
 * executions may be performed by different threads, the effects of
 * prior executions <a
 * href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * those of subsequent ones.
 *
 * 周期性任务不会存在同一个时刻有两个工作线程在执行同一个任务的情况，但是一个任务却可能被不同工作线程调度
 * 前一个工作线程执行任务时可看到的共享内存，对后续执行执行该任务的工作线程也是可见的
 * 这是工作线程执行任务时读取volatile变量带来的内存可见性影响
 *
 * <p>While this class inherits from {@link ThreadPoolExecutor}, a few
 * of the inherited tuning methods are not useful for it. In
 * particular, because it acts as a fixed-sized pool using
 * {@code corePoolSize} threads and an unbounded queue, adjustments
 * to {@code maximumPoolSize} have no useful effect. Additionally, it
 * is almost never a good idea to set {@code corePoolSize} to zero or
 * use {@code allowCoreThreadTimeOut} because this may leave the pool
 * without threads to handle tasks once they become eligible to run.
 *
 * STPE继承自ThreadPoolExecutor，但由于设计目的不同，父类很多特性和方法在该类中都是不合适的
 * 由于STPE相是一个固定核心线程数配合无界任务队列的设计，那么最大线程maximumPoolSize这个参数其实是没用的
 * 另外，由于是通过核心工作线程扫描任务队列的方式来执行任务，所以将核心线程数数量设置为0或者允许核心线程数全部超时退出是不明智的
 * 这会导致任务队列里的任务即时延时到期了也没有工作线程执行，这也是每次添加任务都要保证工作线程数的原因--ensurePrestart()
 *
 * <p><b>Extension notes:</b> This class overrides the
 * {@link ThreadPoolExecutor#execute(Runnable) execute} and
 * {@link AbstractExecutorService#submit(Runnable) submit}
 * methods to generate internal {@link ScheduledFuture} objects to
 * control per-task delays and scheduling.  To preserve
 * functionality, any further overrides of these methods in
 * subclasses must invoke superclass versions, which effectively
 * disables additional task customization.  However, this class
 * provides alternative protected extension method
 * {@code decorateTask} (one version each for {@code Runnable} and
 * {@code Callable}) that can be used to customize the concrete task
 * types used to execute commands entered via {@code execute},
 * {@code submit}, {@code schedule}, {@code scheduleAtFixedRate},
 * and {@code scheduleWithFixedDelay}.  By default, a
 * {@code ScheduledThreadPoolExecutor} uses a task type extending
 * {@link FutureTask}. However, this may be modified or replaced using
 * subclasses of the form:
 *
 * STPE重写了父类的execute和submit方法，用于将任务都封装为ScheduledFuture来控制任务的调度
 * 为了保持统一使用ScheduledFuture调度的特性，子类重写的时候也要调用STPE的方法也实现统一的任务管理方式
 * STPE提供了一个切面decorateTask用于帮助子类在构建完ScheduledFuture之后做一些适度的调整
 *
 * <pre> {@code
 * public class CustomScheduledExecutor extends ScheduledThreadPoolExecutor {
 *
 *   static class CustomTask<V> implements RunnableScheduledFuture<V> { ... }
 *
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Runnable r, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(r, task);
 *   }
 *
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Callable<V> c, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(c, task);
 *   }
 *   // ... add constructors, etc.
 * }}</pre>
 *
 * @author Doug Lea
 * @since 1.5
 */
public class ScheduledThreadPoolExecutor extends ThreadPoolExecutor implements ScheduledExecutorService {

    /*
     * This class specializes ThreadPoolExecutor implementation by
     *
     * 1. Using a custom task type, ScheduledFutureTask for
     *    tasks, even those that don't require scheduling (i.e.,
     *    those submitted using ExecutorService execute, not
     *    ScheduledExecutorService methods) which are treated as
     *    delayed tasks with a delay of zero.
     *
     * 2. Using a custom queue (DelayedWorkQueue), a variant of
     *    unbounded DelayQueue. The lack of capacity constraint and
     *    the fact that corePoolSize and maximumPoolSize are
     *    effectively identical simplifies some execution mechanics
     *    (see delayedExecute) compared to ThreadPoolExecutor.
     *
     * 3. Supporting optional run-after-shutdown parameters, which
     *    leads to overrides of shutdown methods to remove and cancel
     *    tasks that should NOT be run after shutdown, as well as
     *    different recheck logic when task (re)submission overlaps
     *    with a shutdown.
     *
     * 4. Task decoration methods to allow interception and
     *    instrumentation, which are needed because subclasses cannot
     *    otherwise override submit methods to get this effect. These
     *    don't have any impact on pool control logic though.
     *
     * STPE通过定制ThreadPoolExecutor的4个行为来实现
     *
     * 1. 使用ScheduledFutureTask再次封装Runnable（不论任务是否需要延时执行）
     * 2. 使用一个自定义的延时队列（JDK DelayQueue的变体），简化了延时执行机制，后续的任务只要将任务追加到该队列中
     * 3. 由于延时特性，所以增加了参数来处理在shutdown后是否还需要执行一次性延时任务
     * 4. 提供切面让子类可以适当改变ScheduledFuture的任务特性
     *
     */

    /**
     * False if should cancel/suppress periodic tasks on shutdown.
     */
    private volatile boolean continueExistingPeriodicTasksAfterShutdown;

    /**
     * False if should cancel non-periodic tasks on shutdown.
     */
    private volatile boolean executeExistingDelayedTasksAfterShutdown = true;

    /**
     * True if ScheduledFutureTask.cancel should remove from queue
     */
    private volatile boolean removeOnCancel = false;

    /**
     * Sequence number to break scheduling ties, and in turn to
     * guarantee FIFO order among tied entries.
     */
    private static final AtomicLong sequencer = new AtomicLong();

    /**
     * Returns current nanosecond time.
     */
    final long now() {
        return System.nanoTime();
    }

    private class ScheduledFutureTask<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {

        /** Sequence number to break ties FIFO */
        private final long sequenceNumber;

        /** The time the task is enabled to execute in nanoTime units */
        private long time;

        /**
         * Period in nanoseconds for repeating tasks.  A positive
         * value indicates fixed-rate execution.  A negative value
         * indicates fixed-delay execution.  A value of 0 indicates a
         * non-repeating task.
         */
        private final long period;

        /** The actual task to be re-enqueued by reExecutePeriodic */
        RunnableScheduledFuture<V> outerTask = this;

        /**
         * Index into delay queue, to support faster cancellation.
         */
        int heapIndex;

        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        ScheduledFutureTask(Runnable r, V result, long ns) {
            super(r, result);
            this.time = ns;
            this.period = 0;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * Creates a periodic action with given nano time and period.
         */
        ScheduledFutureTask(Runnable r, V result, long ns, long period) {
            super(r, result);
            this.time = ns;
            this.period = period;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        ScheduledFutureTask(Callable<V> callable, long ns) {
            super(callable);
            this.time = ns;
            this.period = 0;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        public long getDelay(TimeUnit unit) {
            return unit.convert(time - now(), NANOSECONDS);
        }

        public int compareTo(Delayed other) {
            if (other == this) // compare zero if same object
            {
                return 0;
            }
            if (other instanceof ScheduledFutureTask) {
                ScheduledFutureTask<?> x = (ScheduledFutureTask<?>) other;
                long diff = time - x.time;
                if (diff < 0) {
                    return -1;
                } else if (diff > 0) {
                    return 1;
                } else if (sequenceNumber < x.sequenceNumber) {
                    return -1;
                } else {
                    return 1;
                }
            }
            long diff = getDelay(NANOSECONDS) - other.getDelay(NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        /**
         * Returns {@code true} if this is a periodic (not a one-shot) action.
         *
         * @return {@code true} if periodic
         */
        public boolean isPeriodic() {
            return period != 0;
        }

        /**
         * Sets the next time to run for a periodic task.
         */
        private void setNextRunTime() {
            long p = period;
            if (p > 0) {
                time += p;
            } else {
                time = triggerTime(-p);
            }
        }

        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && removeOnCancel && heapIndex >= 0) {
                remove(this);
            }
            return cancelled;
        }

        /**
         * Overrides FutureTask version so as to reset/requeue if periodic.
         */
        public void run() {
            // 任务是否是需要重复执行的
            boolean periodic = isPeriodic();
            if (!canRunInCurrentRunState(periodic)) {
                cancel(false);
            } else if (!periodic) {
                // 一次性执行
                ScheduledFutureTask.super.run();
            } else if (ScheduledFutureTask.super.runAndReset()) {
                // 直接在if条件中执行完了，然后设置下下次执行的时间，分为两种
                // 任务的执行时长计入间隔时间或不计入，计入情况下，任务执行时长超过period则会导致此次执行间隔变长
                setNextRunTime();
                // 再次把该任务追加到任务队列中
                reExecutePeriodic(outerTask);
            }
        }
    }

    /**
     * Returns true if can run a task given current run state
     * and run-after-shutdown parameters.
     *
     * @param periodic
     *         true if this task periodic, false if delayed
     */
    boolean canRunInCurrentRunState(boolean periodic) {
        return isRunningOrShutdown(periodic ? continueExistingPeriodicTasksAfterShutdown : executeExistingDelayedTasksAfterShutdown);
    }

    /**
     * Main execution method for delayed or periodic tasks.  If pool
     * is shut down, rejects the task. Otherwise adds task to queue
     * and starts a thread, if necessary, to run it.  (We cannot
     * prestart the thread to run the task because the task (probably)
     * shouldn't be run yet.)  If the pool is shut down while the task
     * is being added, cancel and remove it if required by state and
     * run-after-shutdown parameters.
     *
     * @param task
     *         the task
     */
    private void delayedExecute(RunnableScheduledFuture<?> task) {
        if (isShutdown()) {
            reject(task);
        } else {
            // 将任务添加到任务队列之后，非常重要的是ensurePrestart()一步，确保线程池中至少有一个工作线程在消费任务
            super.getQueue().add(task);
            if (isShutdown() && !canRunInCurrentRunState(task.isPeriodic()) && remove(task)) {
                task.cancel(false);
            } else {
                ensurePrestart();
            }
        }
    }

    /**
     * Requeues a periodic task unless current run state precludes it.
     * Same idea as delayedExecute except drops task rather than rejecting.
     *
     * @param task
     *         the task
     */
    void reExecutePeriodic(RunnableScheduledFuture<?> task) {
        if (canRunInCurrentRunState(true)) {
            super.getQueue().add(task);
            if (!canRunInCurrentRunState(true) && remove(task)) {
                task.cancel(false);
            } else {
                ensurePrestart();
            }
        }
    }

    /**
     * Cancels and clears the queue of all tasks that should not be run
     * due to shutdown policy.  Invoked within super.shutdown.
     */
    @Override
    void onShutdown() {
        // 线程被全部设置了中断信号后才会调用该方法，此时需要保证线程一直满负荷工作，靠延时队列
        BlockingQueue<Runnable> q = super.getQueue();
        // 如果为true则还未执行的一次性延时任务得执行完才能关闭线程池
        boolean keepDelayed = getExecuteExistingDelayedTasksAfterShutdownPolicy();
        // 如果为true则及时调用了shutdown，周期性任务依旧继续周期执行，线程池无法关闭
        boolean keepPeriodic = getContinueExistingPeriodicTasksAfterShutdownPolicy();
        if (!keepDelayed && !keepPeriodic) {
            for (Object e : q.toArray()) {
                if (e instanceof RunnableScheduledFuture<?>) {
                    ((RunnableScheduledFuture<?>) e).cancel(false);
                }
            }
            q.clear();
        } else {
            // Traverse snapshot to avoid iterator exceptions
            for (Object e : q.toArray()) {
                if (e instanceof RunnableScheduledFuture) {
                    RunnableScheduledFuture<?> t = (RunnableScheduledFuture<?>) e;
                    if ((t.isPeriodic() ? !keepPeriodic : !keepDelayed) || t.isCancelled()) { // also remove if already cancelled
                        if (q.remove(t)) {
                            t.cancel(false);
                        }
                    }
                }
            }
        }
        // 很关键的一点是利用了任务队列不为空（这也是全部工作线程退出的必要条件），加之ensurePrestart()保证工作线程不为0
        tryTerminate();
    }

    /**
     * Modifies or replaces the task used to execute a runnable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * 开发者可以通过重载该方法实现对实际执行任务的监测或改写
     *
     * @param runnable
     *         the submitted Runnable
     * @param task
     *         the task created to execute the runnable
     * @param <V>
     *         the type of the task's result
     *
     * @return a task that can execute the runnable
     *
     * @since 1.6
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(Runnable runnable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Modifies or replaces the task used to execute a callable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param callable
     *         the submitted Callable
     * @param task
     *         the task created to execute the callable
     * @param <V>
     *         the type of the task's result
     *
     * @return a task that can execute the callable
     *
     * @since 1.6
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(Callable<V> callable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Creates a new {@code ScheduledThreadPoolExecutor} with the
     * given core pool size.
     *
     * @param corePoolSize
     *         the number of threads to keep in the pool, even
     *         if they are idle, unless {@code allowCoreThreadTimeOut} is set
     *
     * @throws IllegalArgumentException
     *         if {@code corePoolSize < 0}
     */
    public ScheduledThreadPoolExecutor(int corePoolSize) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS, new DelayedWorkQueue());
    }

    /**
     * Creates a new {@code ScheduledThreadPoolExecutor} with the
     * given initial parameters.
     *
     * @param corePoolSize
     *         the number of threads to keep in the pool, even
     *         if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param threadFactory
     *         the factory to use when the executor
     *         creates a new thread
     *
     * @throws IllegalArgumentException
     *         if {@code corePoolSize < 0}
     * @throws NullPointerException
     *         if {@code threadFactory} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS, new DelayedWorkQueue(), threadFactory);
    }

    /**
     * Creates a new ScheduledThreadPoolExecutor with the given
     * initial parameters.
     *
     * @param corePoolSize
     *         the number of threads to keep in the pool, even
     *         if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param handler
     *         the handler to use when execution is blocked
     *         because the thread bounds and queue capacities are reached
     *
     * @throws IllegalArgumentException
     *         if {@code corePoolSize < 0}
     * @throws NullPointerException
     *         if {@code handler} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize, RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS, new DelayedWorkQueue(), handler);
    }

    /**
     * Creates a new ScheduledThreadPoolExecutor with the given
     * initial parameters.
     *
     * @param corePoolSize
     *         the number of threads to keep in the pool, even
     *         if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param threadFactory
     *         the factory to use when the executor
     *         creates a new thread
     * @param handler
     *         the handler to use when execution is blocked
     *         because the thread bounds and queue capacities are reached
     *
     * @throws IllegalArgumentException
     *         if {@code corePoolSize < 0}
     * @throws NullPointerException
     *         if {@code threadFactory} or
     *         {@code handler} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS, new DelayedWorkQueue(), threadFactory, handler);
    }

    /**
     * Returns the trigger time of a delayed action.
     */
    private long triggerTime(long delay, TimeUnit unit) {
        return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
    }

    /**
     * Returns the trigger time of a delayed action.
     */
    long triggerTime(long delay) {
        return now() + ((delay < (Long.MAX_VALUE >> 1)) ? delay : overflowFree(delay));
    }

    /**
     * Constrains the values of all delays in the queue to be within
     * Long.MAX_VALUE of each other, to avoid overflow in compareTo.
     * This may occur if a task is eligible to be dequeued, but has
     * not yet been, while some other task is added with a delay of
     * Long.MAX_VALUE.
     */
    private long overflowFree(long delay) {
        Delayed head = (Delayed) super.getQueue().peek();
        if (head != null) {
            long headDelay = head.getDelay(NANOSECONDS);
            if (headDelay < 0 && (delay - headDelay < 0)) {
                delay = Long.MAX_VALUE + headDelay;
            }
        }
        return delay;
    }

    /**
     * @throws RejectedExecutionException
     *         {@inheritDoc}
     * @throws NullPointerException
     *         {@inheritDoc}
     */
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }
        RunnableScheduledFuture<?> t = decorateTask(command, new ScheduledFutureTask<Void>(command, null, triggerTime(delay, unit)));
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException
     *         {@inheritDoc}
     * @throws NullPointerException
     *         {@inheritDoc}
     */
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (callable == null || unit == null) {
            throw new NullPointerException();
        }
        RunnableScheduledFuture<V> t = decorateTask(callable, new ScheduledFutureTask<V>(callable, triggerTime(delay, unit)));
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException
     *         {@inheritDoc}
     * @throws NullPointerException
     *         {@inheritDoc}
     * @throws IllegalArgumentException
     *         {@inheritDoc}
     */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }
        if (period <= 0) {
            throw new IllegalArgumentException();
        }
        // ScheduledFutureTask的run主逻辑是实现周期性循环调用的关键
        ScheduledFutureTask<Void> sft = new ScheduledFutureTask<Void>(command, null, triggerTime(initialDelay, unit), unit.toNanos(period));
        // 给开发者提供一个修改实际执行任务的切面
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException
     *         {@inheritDoc}
     * @throws NullPointerException
     *         {@inheritDoc}
     * @throws IllegalArgumentException
     *         {@inheritDoc}
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }
        if (delay <= 0) {
            throw new IllegalArgumentException();
        }
        ScheduledFutureTask<Void> sft = new ScheduledFutureTask<Void>(command, null, triggerTime(initialDelay, unit), unit.toNanos(-delay));
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        delayedExecute(t);
        return t;
    }

    /**
     * Executes {@code command} with zero required delay.
     * This has effect equivalent to
     * {@link #schedule(Runnable, long, TimeUnit) schedule(command, 0, anyUnit)}.
     * Note that inspections of the queue and of the list returned by
     * {@code shutdownNow} will access the zero-delayed
     * {@link ScheduledFuture}, not the {@code command} itself.
     *
     * <p>A consequence of the use of {@code ScheduledFuture} objects is
     * that {@link ThreadPoolExecutor#afterExecute afterExecute} is always
     * called with a null second {@code Throwable} argument, even if the
     * {@code command} terminated abruptly.  Instead, the {@code Throwable}
     * thrown by such a task can be obtained via {@link Future#get}.
     *
     * @throws RejectedExecutionException
     *         at discretion of
     *         {@code RejectedExecutionHandler}, if the task
     *         cannot be accepted for execution because the
     *         executor has been shut down
     * @throws NullPointerException
     *         {@inheritDoc}
     */
    public void execute(Runnable command) {
        schedule(command, 0, NANOSECONDS);
    }

    // Override AbstractExecutorService methods

    /**
     * @throws RejectedExecutionException
     *         {@inheritDoc}
     * @throws NullPointerException
     *         {@inheritDoc}
     */
    public Future<?> submit(Runnable task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException
     *         {@inheritDoc}
     * @throws NullPointerException
     *         {@inheritDoc}
     */
    public <T> Future<T> submit(Runnable task, T result) {
        return schedule(Executors.callable(task, result), 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException
     *         {@inheritDoc}
     * @throws NullPointerException
     *         {@inheritDoc}
     */
    public <T> Future<T> submit(Callable<T> task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * Sets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow} or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @param value
     *         if {@code true}, continue after shutdown, else don't
     *
     * @see #getContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        continueExistingPeriodicTasksAfterShutdown = value;
        if (!value && isShutdown()) {
            onShutdown();
        }
    }

    /**
     * Gets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow} or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @return {@code true} if will continue after shutdown
     *
     * @see #setContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return continueExistingPeriodicTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @param value
     *         if {@code true}, execute after shutdown, else don't
     *
     * @see #getExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        executeExistingDelayedTasksAfterShutdown = value;
        if (!value && isShutdown()) {
            onShutdown();
        }
    }

    /**
     * Gets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @return {@code true} if will execute after shutdown
     *
     * @see #setExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return executeExistingDelayedTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @param value
     *         if {@code true}, remove on cancellation, else don't
     *
     * @see #getRemoveOnCancelPolicy
     * @since 1.7
     */
    public void setRemoveOnCancelPolicy(boolean value) {
        removeOnCancel = value;
    }

    /**
     * Gets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @return {@code true} if cancelled tasks are immediately removed
     * from the queue
     *
     * @see #setRemoveOnCancelPolicy
     * @since 1.7
     */
    public boolean getRemoveOnCancelPolicy() {
        return removeOnCancel;
    }

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     *
     * <p>This method does not wait for previously submitted tasks to
     * complete execution.  Use {@link #awaitTermination awaitTermination}
     * to do that.
     *
     * <p>If the {@code ExecuteExistingDelayedTasksAfterShutdownPolicy}
     * has been set {@code false}, existing delayed tasks whose delays
     * have not yet elapsed are cancelled.  And unless the {@code
     * ContinueExistingPeriodicTasksAfterShutdownPolicy} has been set
     * {@code true}, future executions of existing periodic tasks will
     * be cancelled.
     *
     * @throws SecurityException
     *         {@inheritDoc}
     */
    public void shutdown() {
        super.shutdown();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution.
     *
     * <p>This method does not wait for actively executing tasks to
     * terminate.  Use {@link #awaitTermination awaitTermination} to
     * do that.
     *
     * <p>There are no guarantees beyond best-effort attempts to stop
     * processing actively executing tasks.  This implementation
     * cancels tasks via {@link Thread#interrupt}, so any task that
     * fails to respond to interrupts may never terminate.
     *
     * @return list of tasks that never commenced execution.
     * Each element of this list is a {@link ScheduledFuture},
     * including those tasks submitted using {@code execute},
     * which are for scheduling purposes used as the basis of a
     * zero-delay {@code ScheduledFuture}.
     *
     * @throws SecurityException
     *         {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        return super.shutdownNow();
    }

    /**
     * Returns the task queue used by this executor.  Each element of
     * this queue is a {@link ScheduledFuture}, including those
     * tasks submitted using {@code execute} which are for scheduling
     * purposes used as the basis of a zero-delay
     * {@code ScheduledFuture}.  Iteration over this queue is
     * <em>not</em> guaranteed to traverse tasks in the order in
     * which they will execute.
     *
     * @return the task queue
     */
    public BlockingQueue<Runnable> getQueue() {
        return super.getQueue();
    }

    /**
     * Specialized delay queue. To mesh with TPE declarations, this
     * class must be declared as a BlockingQueue<Runnable> even though
     * it can only hold RunnableScheduledFutures.
     */
    static class DelayedWorkQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {

        /*
         * A DelayedWorkQueue is based on a heap-based data structure
         * like those in DelayQueue and PriorityQueue, except that
         * every ScheduledFutureTask also records its index into the
         * heap array. This eliminates the need to find a task upon
         * cancellation, greatly speeding up removal (down from O(n)
         * to O(log n)), and reducing garbage retention that would
         * otherwise occur by waiting for the element to rise to top
         * before clearing. But because the queue may also hold
         * RunnableScheduledFutures that are not ScheduledFutureTasks,
         * we are not guaranteed to have such indices available, in
         * which case we fall back to linear search. (We expect that
         * most tasks will not be decorated, and that the faster cases
         * will be much more common.)
         *
         * All heap operations must record index changes -- mainly
         * within siftUp and siftDown. Upon removal, a task's
         * heapIndex is set to -1. Note that ScheduledFutureTasks can
         * appear at most once in the queue (this need not be true for
         * other kinds of tasks or work queues), so are uniquely
         * identified by heapIndex.
         */

        private static final int INITIAL_CAPACITY = 16;
        private RunnableScheduledFuture<?>[] queue = new RunnableScheduledFuture<?>[INITIAL_CAPACITY];
        private final ReentrantLock lock = new ReentrantLock();
        private int size = 0;

        /**
         * Thread designated to wait for the task at the head of the
         * queue.  This variant of the Leader-Follower pattern
         * (http://www.cs.wustl.edu/~schmidt/POSA/POSA2/) serves to
         * minimize unnecessary timed waiting.  When a thread becomes
         * the leader, it waits only for the next delay to elapse, but
         * other threads await indefinitely.  The leader thread must
         * signal some other thread before returning from take() or
         * poll(...), unless some other thread becomes leader in the
         * interim.  Whenever the head of the queue is replaced with a
         * task with an earlier expiration time, the leader field is
         * invalidated by being reset to null, and some waiting
         * thread, but not necessarily the current leader, is
         * signalled.  So waiting threads must be prepared to acquire
         * and lose leadership while waiting.
         */
        private Thread leader = null;

        /**
         * Condition signalled when a newer task becomes available at the
         * head of the queue or a new thread may need to become leader.
         */
        private final Condition available = lock.newCondition();

        /**
         * Sets f's heapIndex if it is a ScheduledFutureTask.
         */
        private void setIndex(RunnableScheduledFuture<?> f, int idx) {
            if (f instanceof ScheduledFutureTask) {
                ((ScheduledFutureTask) f).heapIndex = idx;
            }
        }

        /**
         * Sifts element added at bottom up to its heap-ordered spot.
         * Call only when holding lock.
         */
        private void siftUp(int k, RunnableScheduledFuture<?> key) {
            while (k > 0) {
                int parent = (k - 1) >>> 1;
                RunnableScheduledFuture<?> e = queue[parent];
                if (key.compareTo(e) >= 0) {
                    break;
                }
                queue[k] = e;
                setIndex(e, k);
                k = parent;
            }
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * Sifts element added at top down to its heap-ordered spot.
         * Call only when holding lock.
         */
        private void siftDown(int k, RunnableScheduledFuture<?> key) {
            int half = size >>> 1;
            while (k < half) {
                int child = (k << 1) + 1;
                RunnableScheduledFuture<?> c = queue[child];
                int right = child + 1;
                if (right < size && c.compareTo(queue[right]) > 0) {
                    c = queue[child = right];
                }
                if (key.compareTo(c) <= 0) {
                    break;
                }
                queue[k] = c;
                setIndex(c, k);
                k = child;
            }
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * Resizes the heap array.  Call only when holding lock.
         */
        private void grow() {
            int oldCapacity = queue.length;
            int newCapacity = oldCapacity + (oldCapacity >> 1); // grow 50%
            if (newCapacity < 0) // overflow
            {
                newCapacity = Integer.MAX_VALUE;
            }
            queue = Arrays.copyOf(queue, newCapacity);
        }

        /**
         * Finds index of given object, or -1 if absent.
         */
        private int indexOf(Object x) {
            if (x != null) {
                if (x instanceof ScheduledFutureTask) {
                    int i = ((ScheduledFutureTask) x).heapIndex;
                    // Sanity check; x could conceivably be a
                    // ScheduledFutureTask from some other pool.
                    if (i >= 0 && i < size && queue[i] == x) {
                        return i;
                    }
                } else {
                    for (int i = 0; i < size; i++) {
                        if (x.equals(queue[i])) {
                            return i;
                        }
                    }
                }
            }
            return -1;
        }

        public boolean contains(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return indexOf(x) != -1;
            } finally {
                lock.unlock();
            }
        }

        public boolean remove(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = indexOf(x);
                if (i < 0) {
                    return false;
                }

                setIndex(queue[i], -1);
                int s = --size;
                RunnableScheduledFuture<?> replacement = queue[s];
                queue[s] = null;
                if (s != i) {
                    siftDown(i, replacement);
                    if (queue[i] == replacement) {
                        siftUp(i, replacement);
                    }
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        public int size() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return size;
            } finally {
                lock.unlock();
            }
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        public RunnableScheduledFuture<?> peek() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return queue[0];
            } finally {
                lock.unlock();
            }
        }

        public boolean offer(Runnable x) {
            if (x == null) {
                throw new NullPointerException();
            }
            RunnableScheduledFuture<?> e = (RunnableScheduledFuture<?>) x;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = size;
                if (i >= queue.length) {
                    grow();
                }
                size = i + 1;
                if (i == 0) {
                    queue[0] = e;
                    setIndex(e, 0);
                } else {
                    siftUp(i, e);
                }
                if (queue[0] == e) {
                    leader = null;
                    available.signal();
                }
            } finally {
                lock.unlock();
            }
            return true;
        }

        public void put(Runnable e) {
            offer(e);
        }

        public boolean add(Runnable e) {
            return offer(e);
        }

        public boolean offer(Runnable e, long timeout, TimeUnit unit) {
            return offer(e);
        }

        /**
         * Performs common bookkeeping for poll and take: Replaces
         * first element with last and sifts it down.  Call only when
         * holding lock.
         *
         * @param f
         *         the task to remove and return
         */
        private RunnableScheduledFuture<?> finishPoll(RunnableScheduledFuture<?> f) {
            int s = --size;
            RunnableScheduledFuture<?> x = queue[s];
            queue[s] = null;
            if (s != 0) {
                siftDown(0, x);
            }
            setIndex(f, -1);
            return f;
        }

        public RunnableScheduledFuture<?> poll() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first = queue[0];
                if (first == null || first.getDelay(NANOSECONDS) > 0) {
                    return null;
                } else {
                    return finishPoll(first);
                }
            } finally {
                lock.unlock();
            }
        }

        public RunnableScheduledFuture<?> take() throws InterruptedException {
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for (; ; ) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if (first == null) {
                        available.await();
                    } else {
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0) {
                            return finishPoll(first);
                        }
                        first = null; // don't retain ref while waiting
                        if (leader != null) {
                            available.await();
                        } else {
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                available.awaitNanos(delay);
                            } finally {
                                if (leader == thisThread) {
                                    leader = null;
                                }
                            }
                        }
                    }
                }
            } finally {
                if (leader == null && queue[0] != null) {
                    available.signal();
                }
                lock.unlock();
            }
        }

        public RunnableScheduledFuture<?> poll(long timeout, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for (; ; ) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if (first == null) {
                        if (nanos <= 0) {
                            return null;
                        } else {
                            nanos = available.awaitNanos(nanos);
                        }
                    } else {
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0) {
                            return finishPoll(first);
                        }
                        if (nanos <= 0) {
                            return null;
                        }
                        first = null; // don't retain ref while waiting
                        if (nanos < delay || leader != null) {
                            nanos = available.awaitNanos(nanos);
                        } else {
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                long timeLeft = available.awaitNanos(delay);
                                nanos -= delay - timeLeft;
                            } finally {
                                if (leader == thisThread) {
                                    leader = null;
                                }
                            }
                        }
                    }
                }
            } finally {
                if (leader == null && queue[0] != null) {
                    available.signal();
                }
                lock.unlock();
            }
        }

        public void clear() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                for (int i = 0; i < size; i++) {
                    RunnableScheduledFuture<?> t = queue[i];
                    if (t != null) {
                        queue[i] = null;
                        setIndex(t, -1);
                    }
                }
                size = 0;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns first element only if it is expired.
         * Used only by drainTo.  Call only when holding lock.
         */
        private RunnableScheduledFuture<?> peekExpired() {
            // assert lock.isHeldByCurrentThread();
            RunnableScheduledFuture<?> first = queue[0];
            return (first == null || first.getDelay(NANOSECONDS) > 0) ? null : first;
        }

        public int drainTo(Collection<? super Runnable> c) {
            if (c == null) {
                throw new NullPointerException();
            }
            if (c == this) {
                throw new IllegalArgumentException();
            }
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first;
                int n = 0;
                while ((first = peekExpired()) != null) {
                    c.add(first);   // In this order, in case add() throws.
                    finishPoll(first);
                    ++n;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            if (c == null) {
                throw new NullPointerException();
            }
            if (c == this) {
                throw new IllegalArgumentException();
            }
            if (maxElements <= 0) {
                return 0;
            }
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first;
                int n = 0;
                while (n < maxElements && (first = peekExpired()) != null) {
                    c.add(first);   // In this order, in case add() throws.
                    finishPoll(first);
                    ++n;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        public Object[] toArray() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return Arrays.copyOf(queue, size, Object[].class);
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T[] toArray(T[] a) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                if (a.length < size) {
                    return (T[]) Arrays.copyOf(queue, size, a.getClass());
                }
                System.arraycopy(queue, 0, a, 0, size);
                if (a.length > size) {
                    a[size] = null;
                }
                return a;
            } finally {
                lock.unlock();
            }
        }

        public Iterator<Runnable> iterator() {
            return new Itr(Arrays.copyOf(queue, size));
        }

        /**
         * Snapshot iterator that works off copy of underlying q array.
         */
        private class Itr implements Iterator<Runnable> {
            final RunnableScheduledFuture<?>[] array;
            int cursor = 0;     // index of next element to return
            int lastRet = -1;   // index of last element, or -1 if no such

            Itr(RunnableScheduledFuture<?>[] array) {
                this.array = array;
            }

            public boolean hasNext() {
                return cursor < array.length;
            }

            public Runnable next() {
                if (cursor >= array.length) {
                    throw new NoSuchElementException();
                }
                lastRet = cursor;
                return array[cursor++];
            }

            public void remove() {
                if (lastRet < 0) {
                    throw new IllegalStateException();
                }
                DelayedWorkQueue.this.remove(array[lastRet]);
                lastRet = -1;
            }
        }
    }
}
