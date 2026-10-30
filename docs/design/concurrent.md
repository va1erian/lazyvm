# `java.util.concurrent` (S6-04, S6-05): the observable rules

This page is the brief for whoever implements `java.util.concurrent` and
`java.util.concurrent.locks` in `lazyrt`. It holds only what can be seen from
outside: results, exceptions, messages, `toString` forms and orderings. It says
nothing about how a class should be built. Implement from this page and the
[Java SE 8 Javadoc](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/package-summary.html)
alone. The clean-room rule (`CLAUDE.md`, rule 1) is the hard edge here: these
classes are famous, so do not recall their source, and choose your own
structures and names.

Javadoc promises are not repeated. Read the class page first, and treat this
page as what the Javadoc leaves open, as decided by Temurin 8 (8u504).

Every rule names its pin: a test in `tests/java/concurrent/` and, in quotes,
the label of a line of its golden. Change the rule and that line should change.

## The tests

| Test | Area |
|---|---|
| `Locks` | `ReentrantLock`, `Condition` |
| `ReadWriteLocks` | `ReentrantReadWriteLock` and its two views |
| `LockSupports` | `LockSupport` |
| `ThreadPools` | `ThreadPoolExecutor` |
| `ExecutorFactories` | `Executors` |
| `FutureTasks` | `FutureTask` |
| `InvokeAllAny` | `invokeAll`/`invokeAny`, `AbstractExecutorService`, `ExecutorCompletionService` |
| `ScheduledExecutors` | `ScheduledThreadPoolExecutor` |
| `ConcurrentHashMaps` | `ConcurrentHashMap` basics, views, atomic operations |
| `ConcurrentHashMapBulk` | Java 8 bulk operations, `KeySetView` |
| `CopyOnWrites` | `CopyOnWriteArrayList`, `CopyOnWriteArraySet` |
| `BoundedQueues` | `ArrayBlockingQueue`, `LinkedBlockingQueue` (one script, both classes) |
| `BlockingDeques` | `LinkedBlockingDeque` |
| `PriorityAndDelayQueues` | `PriorityBlockingQueue`, `DelayQueue` |
| `HandoffQueues` | `SynchronousQueue`, `LinkedTransferQueue` |
| `ConcurrentLinked` | `ConcurrentLinkedQueue`, `ConcurrentLinkedDeque` |
| `SkipLists` | `ConcurrentSkipListMap`, `ConcurrentSkipListSet` |
| `Latches` | `CountDownLatch`, `CyclicBarrier` |
| `Semaphores` | `Semaphore` |
| `ExchangersAndPhasers` | `Exchanger`, `Phaser` |
| `ThreadLocalRandoms` | `ThreadLocalRandom` (properties, never a drawn value) |
| `TimeUnits` | `TimeUnit` |
| `ForkJoin` | `ForkJoinPool`, `RecursiveTask`, `RecursiveAction` |
| `ForkJoinTasks` | custom `ForkJoinTask`, tags, worker hooks, protected pool calls |
| `CompletableFutures` | S6-05's Check: states, reads, cancel, obtrude |
| `CompletableFutureStages` | one-source dependents, every method against every source state |
| `CompletableFutureCombos` | two-source dependents, `allOf`/`anyOf`, null arguments |
| `CompletableFutureAsync` | async variants, executors, the common pool |
| `CompletableFutureNoCommon` | a common pool below parallelism two |
| `CompletableFutureOrder` | firing order, dependents counts, re-entrancy, deep chains |
| `Contention` | totals under threads: exclusion, permits, no lost or repeated element |
| `LockEdges` | locks: check order, who wakes whom, read holds around `await`, the read limit in a queue |
| `SynchronizerEdges` | latch, barrier, exchanger, semaphore, random: check order, a running action, wake chains, wide ranges |
| `PhaserEdges` | `Phaser`: forcing, late registration, tiers, a blocked or throwing hook |
| `CollectionEdges` | the collections: range bounds, view identity, bulk entries, a running compute, what counts as a copy-on-write, hand-off order |
| `ThreadPoolEdges` | `ThreadPoolExecutor`: hooks that throw, the `terminated` hook, odd factories and queues, late resizing |
| `FutureEdges` | `FutureTask` re-entrancy, `invokeAll`/`invokeAny` check and failure order, `ExecutorCompletionService` delivery, `Executors` |
| `ScheduledEdges` | `ScheduledThreadPoolExecutor`: the queue's view, policies changed late, decorated tasks |
| `ForkJoinEdges` | `invokeAll` order and failures, status corners, `shutdownNow`, a worker dying, `pollTask` and `tryUnfork` |
| `ForkJoinWorkers` | interrupts in `get` and `join` on a worker, compensation, submission order, deep joins |
| `ForkJoinNoWorkers` | a common pool of parallelism 0 |
| `ConcurrentExceptions` | the package's exception constructors |
| `ConcurrentMapDefaults` | `ConcurrentMap`'s default methods on a custom map |

What the tests need besides this package: `AtomicInteger` (S6-03, used by
`ScheduledExecutors`), and threads that report `WAITING` or `TIMED_WAITING`
from `getState()` while blocked in any method here, since the tests poll that
to order their steps. A parked thread is `WAITING`, a thread parked with a
timeout is `TIMED_WAITING` (`LockSupports`: "state parked", "state timed").
A thread blocked in a lock, a queue or a latch is `WAITING`, never `BLOCKED`
(`Locks`: "blocked state").

`ForkJoin`, `CompletableFutureAsync` and `CompletableFutureNoCommon` set
`-Djava.util.concurrent.ForkJoinPool.common.parallelism`, because the common
pool's size otherwise depends on the machine. The launcher already passes `-D`.

## Rules that hold everywhere

- **G1, `toString`.** A class given a bracket form below prints
  `super.toString()` (class name, `@`, hash) then the bracket. A class not given
  one keeps `Object`'s form: `FutureTask` ("toString", "toString done"),
  `CyclicBarrier` (`Latches`: "toString not overridden"). Goldens print the hash
  as `@H`.
- **G2, exceptions carry no message** unless a rule quotes one. This applies to
  every `NullPointerException`, `IllegalArgumentException`,
  `IllegalMonitorStateException`, `TimeoutException`, `InterruptedException`,
  `CancellationException`, `BrokenBarrierException` and
  `NoSuchElementException` this package throws itself (all golden lines ending
  at the class name). Quoted messages are exact.
- **G3, nulls.** A null argument the Javadoc forbids throws
  `NullPointerException` at once, before any blocking and whatever the object's
  state. A null `TimeUnit` is checked even when the answer is already known:
  `FutureTask.get(1, null)` on a done task ("get null unit done"),
  `CompletableFuture.get(1, null)` (`CompletableFutures`: "null unit done").
- **G4, timeouts.** A zero or negative timeout never blocks, and reports failure
  the way any expired timeout does ("zero get", "zero poll empty", "timed zero",
  "zero timeout", "await zero").
- **G5, interrupts.** A method declaring `InterruptedException` that finds the
  flag set when it would otherwise wait throws at once, and the flag is clear
  when it throws (every "flag=false" line). Whether it also throws when it would
  *not* have to wait differs per class, and is listed here in full.
  - Throw even then: `CountDownLatch.await`, `Semaphore.acquire`,
    `lockInterruptibly`, `put`, `take`, timed `offer` and timed `poll` of
    `ArrayBlockingQueue` and `LinkedBlockingQueue`, `take` and timed `poll` of
    `PriorityBlockingQueue` and `take` of `DelayQueue`
    (`PriorityAndDelayQueues`: "take flagged, element ready", "timed poll flagged,
    element ready"), and `ForkJoinTask.get` on a done task (`ForkJoin`: "get
    flagged, done", "timed get flagged, done").
  - Do the work and keep the flag: `take` and `put` of `LinkedBlockingDeque`;
    `take` and timed `poll` of `LinkedTransferQueue` with an element ready
    (`HandoffQueues`: "take flagged, element ready", "flag kept by take", "timed
    poll flagged, element ready", "flag kept by poll"); `put` and timed `offer` of
    `PriorityBlockingQueue` and `DelayQueue`, which never wait
    (`PriorityAndDelayQueues`: "put flagged", "timed offer flagged", "flag kept by
    put and offer"); `FutureTask.get` and `CompletableFuture.get` on a done future.
  - Untimed non-blocking calls (`poll()`, `offer(e)`, `tryLock()`, `tryAcquire()`)
    ignore the flag and leave it set ("offer flagged", "poll flagged", "tryLock
    keeps flag"). Uninterruptible waits (`lock()`, `awaitUninterruptibly`,
    `acquireUninterruptibly`, `join`, `Phaser.awaitAdvance`) keep waiting and
    return with the flag set.
- **G6, iterators of the concurrent collections** never throw
  `ConcurrentModificationException` ("no CME"). `next()` past the end throws
  `NoSuchElementException`. Where `remove()` is supported, calling it before
  `next()` or twice throws `IllegalStateException` ("remove before next", "remove
  twice", "double remove").
- **G7, iteration order.** Fixed only where a rule says so. Tests never print
  the iteration order or a multi-entry `toString` of `ConcurrentHashMap`, nor the
  iterator or `toString` order of `PriorityBlockingQueue` or `DelayQueue`.
- **G8, `drainTo`** (every blocking queue): a null target throws
  `NullPointerException`, the queue itself as target throws
  `IllegalArgumentException` (both forms), `maxElements <= 0` returns 0 and moves
  nothing, elements move in the queue's own take order ("drain null", "drain
  self", "drain self max", "drain none", "drain negative", "drain 2"). If the
  target throws, the exception reaches the caller, and what it had accepted is
  gone from the queue. `ArrayBlockingQueue`, `LinkedBlockingQueue`,
  `LinkedBlockingDeque`, `PriorityBlockingQueue` and `DelayQueue` keep the element
  it refused and those after it. `LinkedTransferQueue` loses the refused element,
  keeping only those after it. Each class has a line ("drain refused midway",
  `BoundedQueues` also "drain into failing"). `SynchronousQueue` with nobody waiting
  moves nothing ("drain nothing to a refusing sink").

## Locks

### `ReentrantLock` and its `Condition` (`Locks`)

- `toString` bracket is `[Unlocked]` or `[Locked by thread <name>]` ("new
  toString", "locked toString"), on a subclass too.
- `unlock` without holding: `IllegalMonitorStateException` ("unlock free", "other
  unlock"). The same for every `Condition` method without the lock, and for
  `hasWaiters`, `getWaitQueueLength`, `getWaitingThreads` ("await unowned" through
  "waitingThreads unowned").
- `hasWaiters`, `getWaitQueueLength`, `getWaitingThreads` with a `Condition` of
  another lock: `IllegalArgumentException: Not owner` ("hasWaiters foreign",
  "waitQueueLength foreign"). A null one: `NullPointerException`, whatever the
  ownership ("hasWaiters null").
- A fair lock grants in arrival order, and a `Condition` on it wakes in `await`
  order ("fair order", "signal FIFO"). Non-fair order is not pinned.
- `signal` moves one waiter to the lock's queue immediately: the waiter count
  drops and the queue length rises while the signaller still holds the lock
  ("waiters after signal", "queued after signal"). Each waiter returns with its
  full hold count restored ("first signalled": "holds 2").
- `lockInterruptibly` and the timed `tryLock` throw when flagged, even on a free
  lock ("lockInterruptibly flagged", "timed tryLock flagged"). An interrupted
  queued `lockInterruptibly` leaves the queue ("left queue"). A queued `lock()`
  stays queued when interrupted and returns with the flag set ("uninterruptible
  still queued", "lock after interrupt").
- Interrupt before signal: `await` throws, holding the lock, flag clear
  ("interrupt before signal"). Interrupt after signal: `await` returns normally
  with the flag set ("interrupt after signal"). All `await` forms throw when
  flagged on entry ("await flagged", "awaitNanos flagged"), holding the lock
  afterwards ("held").
- `awaitNanos` returns a value `<= 0` when out of time; `await(time, unit)` and
  `awaitUntil` return false. None needs a waker when the time has passed
  ("awaitNanos negative", "await timed out", "awaitUntil past"). A null `Date` or
  unit throws ("awaitUntil null", "await null unit").
- Order of the checks on a `Condition` wait: first a null argument, then the flag, then
  ownership. So a flagged thread that does not hold the lock gets
  `InterruptedException`, flag cleared, from `await`, `awaitNanos`, the timed `await` and
  `awaitUntil`; only `awaitUninterruptibly`, which ignores the flag, reports
  `IllegalMonitorStateException` (`LockEdges`: "await unowned flagged", "awaitNanos unowned
  flagged", "await time unowned flagged", "awaitUntil unowned flagged", "awaitUninterruptibly
  unowned flagged", "awaitUntil null flagged").
- A `Condition` that is not this package's own, passed to `hasWaiters` and the like, gives a
  different text from a foreign lock's: `IllegalArgumentException: not owner`, lower case. A foreign
  lock's condition is reported before ownership is looked at (`LockEdges`: "hasWaiters custom
  condition", "hasWaiters foreign unowned").
- A thread that already holds the lock re-enters past any queue, fair or not, timed or not
  (`LockEdges`: "fair timed reentry behind queue", "fair read reentry", "writer reads past its queue").

### `ReentrantReadWriteLock` (`ReadWriteLocks`)

- `readLock()` and `writeLock()` return the same object each time ("same read
  view", "same write view").
- Brackets: the lock prints `[Write locks = W, Read locks = R]`, where W is the
  write hold count and R counts read holds of all threads ("new", "read toString",
  "write toString", "downgrade toString"). The read view prints `[Read locks = R]`
  ("read new", "read view"), the write view `[Unlocked]` or `[Locked by thread
  <name>]` ("write new", "write view").
- A writer may take the read lock, then release the write lock ("downgrade
  toString", "after downgrade"). A reader cannot take the write lock: `tryLock` is
  false, timed `tryLock` times out ("upgrade tryLock", "upgrade timed", "write
  tryLock under read"). Read holds are per thread ("readHold other", "read unlock
  other").
- Read `unlock` without a hold: `IllegalMonitorStateException: attempt to unlock
  read lock, not locked by current thread`. Write `unlock` without a hold: the
  same class, no message ("read unlock free", "write unlock free").
- `readLock().newCondition()`: `UnsupportedOperationException` ("read newCondition").
- Limits: exactly 65535 holds of each. The next acquisition, `tryLock` included,
  throws `Error: Maximum lock count exceeded`, leaving the count at 65535 ("read
  limit", "read tryLock at limit", "readLockCount", "write limit", "write tryLock
  at limit", "writeHold").
- Fair mode: queue order is arrival order across readers and writers ("fair
  order"). With a writer queued, a timed read `tryLock(0, ...)` fails while the
  plain `tryLock()` barges and succeeds ("fair timed read behind writer", "barging
  read tryLock").
- `toString` does not reflect the queue ("toString queued"). `getOwner` is the
  writer only, null under read holds ("owner", "owner under read"). The protected
  queue queries report writers and readers apart ("queued writers", "queued readers").
- Write-lock `Condition` misuse follows `Locks`, including `Not owner` ("hasWaiters
  foreign"). `await` restores all write holds ("condition return").
- `await` by a thread holding both the write lock and read holds gives up the whole lock, the read
  holds as well: during the wait another thread can take the write lock, and sees a read count of
  0. When the waiter returns, its write holds and its read holds are all back (`LockEdges`: "await with
  both holds", "seen during that wait", "both holds back").
- The read limit also binds a reader that has been queueing. If the count is at 65535 when its
  turn comes, it gets `Error: Maximum lock count exceeded` from `lock`, `lockInterruptibly` or the timed
  `tryLock`, leaves the queue, and the next in line is woken and judged the same way (`LockEdges`:
  "queued lock at the limit", "queued interruptibly at the limit", "queued timed at the limit", "after them").

### `LockSupport` (`LockSupports`)

- One permit, which does not stack ("permit consumed by park", "permits do not
  stack"). `unpark(null)` and `unpark` of an unstarted or dead thread are harmless
  ("unpark null", "unpark unstarted", "unpark dead").
- `parkNanos(<=0)` and `parkUntil(past)` return at once ("non-positive return at once").
- The flag being set makes each park return without clearing it ("flagged parks
  return"). An interrupt wakes a park and stays set ("interrupt wakes park, keeps flag").
- `getBlocker(null)` throws `NullPointerException`; the blocker is visible while
  parked in each of the blocker forms and null after ("getBlocker null", "blocker
  while parked", "blocker timed", "blocker until", "blocker after", "null blocker").

## Executors

### `ThreadPoolExecutor` (`ThreadPools`)

- Bracket: `[<state>, pool size = P, active threads = A, queued tasks = Q,
  completed tasks = C]`. State is `Running`, `Shutting down` or `Terminated`
  ("toString", "toString shutting", "terminated").
- Task admission: below core a new worker takes the task even if others idle; at
  core the queue takes it; if the queue refuses, a new worker up to the maximum;
  else rejection ("one core", "queued", "overflow worker").
- Rejection message: `Task <task.toString()> rejected from <executor.toString()>`
  ("rejected", "after shutdown reject"). `submit` rejects with the wrapping
  `FutureTask` in the message ("after shutdown submit").
- Constructor: bad sizes or a negative keep-alive are `IllegalArgumentException`,
  null arguments `NullPointerException` ("core negative" through "null both").
  `setCorePoolSize` accepts a value above the maximum ("setCore above max") and
  rejects a negative. `setMaximumPoolSize` rejects zero and values below core.
- `allowCoreThreadTimeOut(true)` with zero keep-alive, and `setKeepAliveTime(0, ...)`
  while it is on: `IllegalArgumentException: Core threads must have nonzero keep
  alive times` ("allowCore zero keepalive", "setKeepAlive zero with allowCore").
- Policies. `CallerRuns` runs on the caller; `Discard` drops; `DiscardOldest` drops
  the queue's head and retries; `Abort` throws, also after shutdown; the others do
  nothing after shutdown ("CallerRunsPolicy ..." and the like). A null task:
  `Discard` ignores it, `CallerRuns` throws ("policy null task", "caller null task").
- `shutdown` lets running and queued tasks finish and is idempotent ("queued ran
  after shutdown"). `isTerminating` is true between shutdown and termination, false
  afterwards ("flags shutdown", "flags terminated").
- `shutdownNow` returns the queue in order, leaves it empty, interrupts running
  tasks ("shutdownNow", "queue empty", "interrupted worker"), and returns an empty
  list on a terminated pool ("shutdownNow terminated").
- An empty pool terminates during `shutdown` ("no workers terminate at once").
  `awaitTermination` on a terminated pool returns true without checking the flag
  ("await flagged"), and on a running pool throws when flagged ("await flagged running").
- Hooks: `beforeExecute(thread, task)`, the task, `afterExecute(task, throwable)`,
  and at the end `terminated()`. A throwing task passed to `execute` reaches
  `afterExecute` and counts as completed, then its worker dies, the thread's
  uncaught handler sees it, and a new worker serves the rest of the queue ("log ..."
  lines, "uncaught", "threads made", "completed counts failure"). A failure inside
  a `submit` task is captured: `afterExecute` gets null.
- An interrupt a task leaves on its worker does not reach the next task ("interrupt
  not carried to next task").
- A factory returning null: `execute` returns normally with the task queued and no
  worker ("null thread execute"), `prestartCoreThread` is false ("null thread prestart").
- Worker counts: "prestart" lines; idle core workers survive past keep-alive unless
  `allowCoreThreadTimeOut` ("no core-thread timeout", "core timed out"); lowering
  sizes retires idle workers ("shrunk", "shrunk to none"); a zero core pool still
  runs queued work ("zero core still runs"); `getLargestPoolSize` keeps its peak
  ("largest kept"). `purge` removes cancelled queued futures, which otherwise stay
  ("queue before purge", "queue after purge").
- Hooks that throw (`ThreadPoolEdges`). A throwing `beforeExecute` means the task does not run and `afterExecute` is not
  called, yet the task counts as completed and the worker dies ("beforeThrows ..."). `afterExecute` sees the task's failure as it
  was; if it throws too, the uncaught handler sees its exception instead ("bothThrow ..."). A checked exception hidden from the
  compiler reaches `afterExecute` as is and the uncaught handler as `java.lang.Error` over it ("sneaky uncaught"). During both hooks the
  worker is active and the task not yet counted ("... log").
- A new worker counts as active from the moment it is listed until it first looks for work, so a thread just started for a task shows
  in the rejection message ("core above max reject"), and one from a factory that ignored its `Runnable` stays active for good ("factory
  ignoring runnable"). An interrupt meant for idle workers does not reach it.
- `terminated()` runs once, on the thread that ended the pool, the caller of `shutdown` if there are no workers ("hook log (no workers)",
  "hook once"). Inside it the pool is terminating, not terminated, and prints `Shutting down`. A throw from it reaches that thread, and the
  pool still ends ("hook throws in shutdown", "after hook throw"). **While it runs, other threads wait** in `getPoolSize`, `getActiveCount`,
  `getLargestPoolSize`, `getCompletedTaskCount`, `getTaskCount`, `toString`, `awaitTermination`, `shutdown`, `shutdownNow`, and so in the
  message of a rejection, but not in the flags, `prestartCoreThread` or `setCorePoolSize` ("during hook ..."). A leftover interrupt from a task
  or from `shutdownNow` is still set in the hook ("hook flag ...").
- The factory: `getPoolSize` inside it does not count the thread being made ("size inside factory"). What it throws reaches the caller of
  `execute` or `prestart*`, with the task not queued ("factory throws ..."). A thread it already started: `IllegalThreadStateException`
  ("factory started thread"). A pool whose factory gives null and has queued work stays `Shutting down` after `shutdown`, until `shutdownNow`
  returns the work ("null thread shutdown", "null thread shutdownNow").
- The queue: only its public methods are used. `execute` lets a throw from `offer` through ("offer throws"); `offer` refusing means a new
  worker up to the maximum, then rejection ("refusing ..."); a `shutdown` during `offer` means the task comes back out and is rejected ("shutdown
  during offer"). `shutdownNow` gets what `drainTo` left by `toArray` and `remove` ("no drain"). Elements added by hand wait for a worker ("hand
  added", "prestart serves it").
- Sizes: raising the core starts workers only for what is queued ("raise with queued", "raise beyond queue"). A core above the maximum holds
  while busy, and falls to the maximum when idle ("core above max ..."). A worker killed by its task is replaced even with no work, while the pool is
  running ("abrupt replaced ..."), but after `shutdown` only if the queue holds something ("abrupt at shutdown ...").

### `Executors` (`ExecutorFactories`)

- Pools are numbered per VM from 1, each thread per pool from 1: `pool-1-thread-1`,
  then `pool-2-thread-1` ("first pool thread", "second pool thread"). Nothing in
  LazyVM's start-up may consume a pool number. Threads are non-daemon, priority 5,
  in the creating thread's group, whatever the creator's daemon flag and priority
  ("priority resets", "daemon parent not inherited"). A later factory restarts its
  thread count ("second factory restarts threads").
- Exact configurations, including queue and handler classes, are in "fixed",
  "cached" and "scheduled sizes". `newSingleThreadExecutor`,
  `newSingleThreadScheduledExecutor` and the `unconfigurable*` wrappers are not
  `ThreadPoolExecutor`s ("single", "single scheduled", "unconfigurable hides"), and
  pass `shutdown` through ("unconfigurable passes shutdown").
- `newWorkStealingPool(n)` is a `ForkJoinPool` in async mode with daemon workers;
  the no-arg form uses `availableProcessors()` ("stealing type", "stealing async",
  "stealing daemon", "stealing default parallelism").
- `privilegedCallable*` and `privilegedThreadFactory` behave as their plain versions
  (LazyVM has no security manager) and reject nulls ("privilegedCallable" lines,
  "privilegedThreadFactory").
- `callable(Runnable)` yields null, `callable(Runnable, v)` yields v, a null Runnable
  throws ("callable", "callable value", "callable null").

### `FutureTask` (`FutureTasks`)

- `run` calls the callable once; a second `run` does nothing ("runs once").
- `get` on a failed task: `ExecutionException` whose message is the cause's
  `toString()`, with any `Throwable` captured, `Error` too ("failed get", "message",
  "error captured").
- `cancel` is true only when it moves a not-done task to cancelled, so a second
  `cancel` and a `cancel` after completion are false ("cancel new", "cancel again",
  "cancel after done"). `isDone` and `isCancelled` are true from that moment, though
  the callable may still be running ("flags before task ends"), and its result is
  dropped ("get ignores its result"). `cancel(true)` interrupts the runner ("task
  saw"), `cancel(false)` does not ("task finished anyway"). A cancelled task never
  starts ("run after cancel skipped").
- `get` on a done task returns without looking at the flag, leaving it set ("get
  flagged done", "flag after done get"). Pending and flagged: throws ("get flagged
  pending"). An interrupted waiter does not affect the task ("task unaffected").
- `done()` runs once on any completion, with state already readable ("done hook
  once", "cancel fires done"). `set` and `setException` are ignored after completion,
  and win when called first ("set ignored", "set wins", "set then run").
- `runAndReset` returns true and stays pending on success, firing no `done()`
  ("runAndReset", "still pending", "reset no done"); false once done ("reset after
  done"); false and failed if the callable throws ("runAndReset failing", "failing get").

### `invokeAll`, `invokeAny`, `AbstractExecutorService`, `ExecutorCompletionService` (`InvokeAllAny`)

- `invokeAll`: results in collection order, all done; failures stay inside their
  futures ("all order"). With a timeout, unfinished tasks are cancelled and every
  future is done ("all timed out cancels", "all timed zero"). A null element throws
  ("all null element").
- `invokeAny`: the value of a task that succeeded even if others fail ("any failing
  then ok"); all failed: `ExecutionException` with the cause ("any one failed");
  empty: `IllegalArgumentException` ("any empty"); timeout: `TimeoutException` ("any
  timeout"); the losers are interrupted ("any interrupted the loser"). On a
  same-thread executor it stops after the first success (the end of "log").
- `AbstractExecutorService` routes `submit`, `invokeAll` and `invokeAny` through
  `newTaskFor` then `execute` ("log"). `submit(Runnable)` yields null,
  `submit(Runnable, r)` yields r.
- `ExecutorCompletionService`: `poll` null when empty, `take` in completion order,
  the same future `submit` returned ("take third same future"), cancelled tasks
  delivered too ("cancelled is delivered"), a given queue is used ("own queue used"),
  an executor's rejection propagates ("completion service reject").
- Order of checks and failures (`FutureEdges`). Untimed `invokeAll` executes each task as it makes it, so a null runs those before it ("all null
  middle log"); the timed form makes all first, so it runs none ("all timed null middle log"), and it executes a task before looking at the clock, so
  a zero timeout still hands the first task over ("all timed zero executes"). `invokeAll` looks at the flag only if it has to wait ("all flagged direct",
  "all timed flagged"). A rejection cancels what ran and reaches the caller ("all reject second", "any reject second"). `invokeAny` with every task failed
  reports the last ("any all failed which"), and a null is only found when reached ("any null later log").
- `ExecutorCompletionService` hands the executor a wrapper, which queues the task when *it* runs: a task cancelled while queued is not delivered until
  the executor gets to it ("not delivered until its turn"). A full queue's `IllegalStateException` comes out of the executor's run ("queue full").
- `FutureTask`: `run` goes through the virtual `set` and `setException` ("run calls set virtually"), `cancel` through neither ("cancel skips set"). A `done` that
  throws reaches the caller, with the state already set ("done throws ..."). `get(0, ...)` on a pending task with the flag set throws
  `InterruptedException`, not `TimeoutException` ("timed zero flagged pending"). A null unit comes before the flag ("null unit flagged pending").

### `ScheduledThreadPoolExecutor` (`ScheduledExecutors`)

- Bracket as `ThreadPoolExecutor`. Maximum is `Integer.MAX_VALUE`, keep-alive 0
  ("toString", "max", "keepAlive").
- Period zero or below: `IllegalArgumentException` ("fixedRate zero", "fixedDelay negative").
- Default policies: delayed tasks run after shutdown, periodic ones do not, no
  remove-on-cancel ("policy ..." lines). `shutdown` cancels periodic tasks at once
  ("default: periodic cancelled on shutdown") and waits for delayed ones ("default:
  delayed ran"). With the delayed policy off, they are cancelled and the pool ends
  ("drop: cancelled"). With the periodic policy on, they continue, new work is
  rejected, and `shutdownNow` ends it ("keep: ...").
- `shutdownNow` returns queued tasks, the very futures `schedule` returned, neither
  done nor cancelled ("shutdownNow same future", "shutdownNow leaves it").
- Order: by due time, then submission. A negative delay counts as zero, so -1 s queued before
  -5 s still runs first; `execute` and `submit` also act as delay zero ("delay order").
  A rejection follows the `ThreadPoolExecutor` message; the task's class in it is not
  API, and goldens hide it ("after shutdown").
- `Long.MAX_VALUE` days does not overflow into the past, and compares after nearer
  tasks ("huge delay positive", "huge after near").
- Returned futures are `RunnableScheduledFuture`s: `isPeriodic` ("periodic flag");
  `getDelay`, `compareTo` ("getDelay", "compare", "compare self"). `getQueue()` shows
  the future itself ("queue elem is future"); cancelled tasks stay there until `purge`
  or the remove policy ("cancelled stays queued", "purged", "removed on cancel").
- A periodic task's `get` waits while it repeats ("timed get repeats"). A failing run
  ends it: `get` throws `ExecutionException`, done, not cancelled, no further runs
  ("failure stops", "failure run count", "failure flags").
- `decorateTask`: called with the Runnable form for `schedule(Runnable)`,
  `submit(Runnable)`, `execute` and periodic tasks; the Callable form for
  `schedule(Callable)` and `submit(Callable)` ("decorate calls").
- The queue's view (`ScheduledEdges`): `poll` and `drainTo` give only what is due, `peek` the soonest anyway ("poll not due", "peek not due", "drain not due").
  A plain `Runnable` added by hand is a `ClassCastException` ("queue add plain"), whose message is not pinned. `shutdownNow` returns what is due first, in order,
  then the rest ("shutdownNow order").
- A cancelled task still queued goes at `shutdown`, whatever the policy, so it holds nothing up ("cancelled removed at shutdown"); one cancelled after `shutdown` stays
  until `purge` or the remove policy ("cancelled stays", "purge ends", "remove policy ends"). Turning a policy off after `shutdown` applies it to what is queued ("policy off after
  shutdown", "periodic off after shutdown").
- **A periodic task that is running at `shutdown` or `shutdownNow` just stops:** its next run is not queued and it is left neither done nor cancelled, so a `get` on it never
  returns ("running periodic end", "shutdownNow running"). This is what the oracle does.
- Decorated tasks: reruns of a periodic task go through the decorated object ("periodic reruns go through the decorated one"); the remove policy removes only the undecorated
  task, so a decorated one stays until `purge` ("remove policy with decorated", "purge decorated"). A discarding handler leaves the returned future pending ("discarded future"). `beforeExecute` and
  `afterExecute` see the future, with null for a failure inside it ("hooks"). A huge delay reads about 106751 days, the most a `long` of nanoseconds holds ("huge getDelay days").

## Concurrent maps

### `ConcurrentHashMap` (`ConcurrentHashMaps`, `ConcurrentHashMapBulk`)

- Null keys and values always throw, reads included, with these exceptions:
  `remove(k, null)` is false, `getOrDefault(k, null)` may return null,
  value-collection `remove(null)` is false, entry-set `contains(null)` is false
  ("remove null value pair", "getOrDefault null default", "value remove null",
  "entry contains null"). Key-set `contains(null)` and `remove(null)`, and
  value-collection `contains(null)`, throw ("key contains null", "key remove null",
  "value contains null"). A null value in a source map or from `replaceAll` throws
  ("source null value", "replaceAll to null", "putAll null value").
- Constructors: negative capacity, non-positive load factor and non-positive
  concurrency level are `IllegalArgumentException`; capacity 0 is fine ("capacity
  negative", "capacity zero", "factor zero", "concurrency zero").
- `compute`, `computeIfAbsent`, `computeIfPresent`, `merge`: a null result removes or
  leaves absent; an exception from the function reaches the caller and leaves the
  mapping unchanged ("compute ..." lines, "unchanged"). A function that recursively
  updates the same map is undefined: no test touches it, and any failure mode other
  than corruption is acceptable.
- Key view and value view reject `add` and `addAll` with
  `UnsupportedOperationException`. The entry set's `add(e)` writes `e`'s value and
  answers true only when the key was new, so it is false for an existing key even
  when the value changes ("entry add", "entry add same", "entry add changes", "after
  entry adds").
- Iterator entries write through on `setValue`, return the old value, reject null
  ("entry setValue returns", "writes through", "setValue null"). They have
  `Map.Entry`'s `equals`, `hashCode` and `key=value` text ("entry equals", "entry
  hash", "entry toString").
- `keys()` and `elements()` are `Enumeration`s, `NoSuchElementException` at the end
  ("enum next empty"). `contains(v)` means `containsValue(v)`. `mappingCount()`
  equals `size()` here ("grown"). A single-entry map prints `{k=v}`, and its views
  `[k]`, `[1]`, `[k=1]` ("single toString", "keys toString"); `equals` and `hashCode`
  agree with `HashMap` ("equals hashmap", "hash").
- Under threads, `merge` and `compute` totals are exact ("merge total", "compute total").
- Bulk methods give the same answer on either side of the threshold, including
  thresholds 0 and negative ("seq"/"par" line pairs, "threshold zero", "threshold
  negative"). A transformer's null means skip. `search` returns a non-null result,
  null if none; `reduce` of nothing is null ("search none", "reduce all null",
  "empty reduce"). A primitive `reduce*To*` folds the basis exactly once on a
  sequential run ("reduceToInt seq" is 1210 from basis 1000). Tests pass an identity
  basis to parallel runs, where it may be folded more than once. Null functions throw
  `NullPointerException` ("forEach null", "search null", "reduce null"). A function's
  exception reaches the caller with its class kept; its message form in parallel is
  not pinned ("forEach throws seq", "forEach throws par"). The common pool stays open
  ("common pool intact").
- `newKeySet()` and `newKeySet(int)` return a `KeySetView<K, Boolean>` mapped to
  `Boolean.TRUE`; a negative capacity throws ("view mapped true", "newKeySet bad cap").
  `keySet(v)` returns a view whose `add` inserts `v`; `keySet(null)` throws; the plain
  `keySet()` view has a null `getMappedValue()` and its `add` throws ("keySet add puts
  default", "plain add", "keySet null default").
- `keySet()`, `values()` and `entrySet()` give one object each per map; `keySet(v)` a new one each
  time (`CollectionEdges`: "hash views are one each", "keySet(v) is new"). An entry a bulk method
  passes or returns is read-only, unlike a view iterator's, which writes through even if its key has
  since left ("bulk entry is read-only", "reduced entry is read-only", "view entry of a removed key
  writes it back").
- An iterator goes on seeing the table it started on while the map grows: no key twice, every old
  key once, and a key removed since is not handed out, beyond the one it already held ("growing map:
  no repeat, all old keys seen", "removed after growth: at most the one it held"). Without growth, it
  still hands out keys removed further along the bin it is already in, even after `clear`, so colliding keys
  show more than other keys do ("removed further along one bin", "cleared under an iterator").
- A running `compute` shuts out writers of its own key, as `BLOCKED` threads, but not readers, nor
  writers or computations of keys in other bins ("writer of that key", "read during compute", "other
  key during compute", "other compute during compute"). `computeIfAbsent` counts as a writer even when its key is
  present, and answers what the compute produced ("computeIfAbsent of a present key waits for its compute", "and
  answers what it computed"). Nothing here looks at the flag ("flag kept by
  compute").

### `ConcurrentSkipListMap`, `ConcurrentSkipListSet` (`SkipLists`)

- Sorted iteration and `toString` ("sorted"). Nulls throw, except `remove(k, null)`
  is false ("remove pair null value"). A mismatched key: `ClassCastException` with the
  VM's cast message, the argument being the receiver of `compareTo` ("mixed key",
  "mixed get"). The first key into an empty map is **not pinned**: see "What is not
  pinned".
- `firstKey`, `lastKey`, `first`, `last` on empty: `NoSuchElementException`
  ("firstKey empty", "first empty"); `firstEntry`, `pollFirstEntry`, navigation
  misses: null ("firstEntry empty", "pollFirst empty", "lower").
- Returned entries and iterator entries are snapshots: `setValue` throws
  `UnsupportedOperationException` and later writes do not show ("entry immutable",
  "snapshot entry", "iterator entry setValue").
- Ranges: `subMap(from > to)` and `subSet`: `IllegalArgumentException: inconsistent
  range` ("bad range", "subSet bad"). Writes outside a range:
  `IllegalArgumentException: key out of range`; reads and `remove` there are absent
  ("sub out of range ..."). A sub-range wider than its parent throws `key out of
  range` ("sub of sub beyond", "sub head beyond"). Descending views invert everything
  ("descending ..." lines), and `descendingMap().comparator()` is non-null even with
  natural order ("descending comparator null source").
- A new bound of a sub-range is checked against the old bound on its own side only, in the map's
  order: past it is `key out of range`, low bound first. A bound past the far end is then an
  `inconsistent range`, unless it only touches it, which is an empty map (`CollectionEdges`: "head below
  the low end", "tail above the high end", "sub above both ends", "head on an excluded low end",
  "sub reversed inside"). It may sit on an excluded old bound only if it excludes it too ("head
  inclusive on an excluded high end", "tail exclusive on an excluded low end"). Navigating from
  outside a range finds its nearest end ("range nav outside", "descending nav"). A conditional write
  outside a range either is a miss or throws, as with `put`: `replace` throws, `remove(k, v)` is false,
  `computeIfAbsent` to null is null, `merge` throws ("range write outside", "range conditional
  outside", "range computes outside", "range merge outside").
- `keySet`, `navigableKeySet`, `values`, `entrySet` and `descendingMap` give one object each per view;
  `descendingMap().descendingMap()` is not the map itself ("skip views are one each", "double
  descending is not the map").
- `keySet`, `values`, `entrySet` are ordered and write through on removal; none
  supports `add` ("key add", "value add", "entry add"). `keySet()` is a `NavigableSet`
  ("key first" through "key descendingSet").
- A `SortedMap` or `SortedSet` source's comparator is kept ("from sorted keeps
  comparator"). `clone` is independent ("clone independent").

### `ConcurrentMap` defaults (`ConcurrentMapDefaults`)

- On a map supplying only the abstract methods, the default `getOrDefault`, `forEach`, `replaceAll`, `computeIfAbsent`,
  `computeIfPresent`, `compute` and `merge` give the Javadoc's results, with null functions and a null `merge` value rejected.
- They use the map's conditional writes, retrying one that lost: the function runs again on the newer value ("merge retries a lost
  replace", "compute retries a lost replace", "computeIfPresent retries", "replaceAll retries"). `forEach` and `replaceAll` skip an
  entry whose `getKey` or `getValue` throws `IllegalStateException` ("forEach skips a vanished entry", "replaceAll skips a vanished
  entry"). `replaceAll` itself does not reject a null result: that is left to the map's `replace` ("replaceAll to null is the map's to
  judge").

## `CopyOnWriteArrayList`, `CopyOnWriteArraySet` (`CopyOnWrites`)

- Nulls are ordinary elements ("add null", "from array"). Array and collection sources
  are copied ("array copied").
- Index errors, exact text: `get`, `set`, `remove(int)` throw
  `ArrayIndexOutOfBoundsException: <index>` ("get far", "get neg", "set far", "remove
  at far"); `add(int)` and `addAll(int)` throw `IndexOutOfBoundsException: Index: <i>,
  Size: <n>` ("add at far", "addAll at far"); `listIterator(i)` throws
  `IndexOutOfBoundsException: Index: <i>` ("listIterator far"). `indexOf(e, i)` with
  negative `i` and `lastIndexOf(e, i)` with `i >= size` throw
  `ArrayIndexOutOfBoundsException: <i>` ("indexOf neg", "lastIndexOf far"); the other
  way round they return -1 ("indexOf far", "lastIndexOf neg").
- Iterators and list iterators are snapshots: later writes do not show, and each of
  `remove`, `set`, `add` throws `UnsupportedOperationException` ("snapshot", "iterator
  remove", "li set", "li add", "li remove", "mutate during foreach"). `sort(null)` is
  natural order ("sort null natural"). Null function arguments throw ("forEach null",
  "removeIf null", "replaceAll null").
- `addIfAbsent` is true when added; `addAllAbsent` returns the number added and adds
  repeated new elements once ("addAllAbsent", "after absent").
- `subList` is a live view while the list changes only through it. Index errors:
  `IndexOutOfBoundsException: Index: <i>,Size: <n>` (no space) for `get`, `set`,
  `remove(int)` ("sub get far", "sub set far", "sub remove far"); `add(int)` has no
  message ("sub add far"); `listIterator(i)`: `Index: <i>, Size: <n>` ("sub listIterator
  far"). Bad ranges: `IndexOutOfBoundsException` with no message ("bad from", "bad to",
  "bad order", "nested bad"). A write to the parent after creation makes each sub-list
  operation throw `ConcurrentModificationException` ("sub after outer change", "sub
  toString after outer change", "sub iterator after outer change"). Sub-list iterators
  are unmodifiable like the parent's ("sub iterator remove", "sub listIterator set").
- What counts as a write to the parent, for its sub-lists: `set` of the very same element does not,
  nor does a remove or add that changes nothing; a `sort` does even if nothing moves, and so does a
  `clear` of an empty list (`CollectionEdges`: "set of the same element is no write", "failed removes
  are no write", "failed adds are no write", "same-order sort is a write", "clear of nothing is a
  write"). On a stale sub-list, `get`, `set` and `remove(int)` check the index before staleness, the other
  operations staleness first, and `addAll` of nothing just answers false ("sub get far, stale: index first",
  "sub add far, stale: staleness first", "sub addAll of nothing, stale", "sub addAll at of nothing, stale").
- A source array of a narrower type does not narrow the list ("array source narrower than the list",
  "toArray is plain").
- `CopyOnWriteArraySet` keeps insertion order ("insertion order", "from collection
  order", "forEach", "toArray"), allows null, is unmodifiable through its iterator
  ("iterator remove"), and `equals` is set equality regardless of order, never equal to
  a list ("equals order free", "equals list").

## Queues

### `ArrayBlockingQueue`, `LinkedBlockingQueue` (`BoundedQueues`)

One script, run on both; rules hold for both unless noted.

- `add` when full: `IllegalStateException: Queue full`. `addAll` adds one at a time, so
  a partial insert stays; the queue itself as source throws `IllegalArgumentException`
  ("add full", "addAll null elem", "after partial addAll", "addAll self", "addAll full").
- `contains(null)` and `remove(null)` are false ("contains null", "remove obj null").
- Interrupts: `put`, `take`, timed `offer` and timed `poll` throw when flagged, even
  with room or an element ready ("take flagged, available", "put flagged, free", "timed
  offer flagged", "timed poll flagged"). An interrupted `put` leaves nothing behind
  ("not inserted").
- Iterators run in FIFO order and support `remove` ("iterate", "iterator removed").
  After later removals by others an iterator may still hand out what it already held
  ("hasNext after concurrent removal" allows zero or one more).
- Constructor capacity below 1, and a too-large or null-holding collection, throw
  ("array bad capacity", "array init too many", "array init null element").
  `LinkedBlockingQueue` defaults to `Integer.MAX_VALUE` capacity ("linked default
  capacity", "linked init capacity").
- A fair `ArrayBlockingQueue` serves waiting takers FIFO ("fair takers FIFO"), and waiting putters
  too (`CollectionEdges`: "fair putters FIFO").

### `LinkedBlockingDeque` (`BlockingDeques`)

- `addFirst`, `addLast`, `add`, `push` when full: `IllegalStateException: Deque full`
  ("addFirst full", "addLast full", "add full", "push full"). The text differs from the
  queues'.
- Unlike the two queues above, `takeFirst` with an element ready and `putFirst` with
  room do not look at the flag, which stays set ("takeFirst flagged, element ready",
  "putFirst flagged, room", "flag kept by uninterruptible put"). Timed forms throw when
  flagged ("timed offerFirst flagged", `CollectionEdges`: "deque timed poll flagged, element ready"); so does a `take` that must wait ("interrupted").
- `removeFirstOccurrence`, `removeLastOccurrence`, `remove(Object)`, `contains` with
  null: false ("first null", "last null", "remove null", "contains null").
- `descendingIterator` is reverse order and supports `remove` ("descending", "removed tail").

### `PriorityBlockingQueue`, `DelayQueue` (`PriorityAndDelayQueues`)

- `PriorityBlockingQueue`: capacity below 1 throws ("capacity zero", "capacity zero
  cmp"), grows on demand ("capacity one grows"), `remainingCapacity` is
  `Integer.MAX_VALUE` ("remaining"). `SortedSet` and `PriorityBlockingQueue` sources
  keep their comparator, a plain collection has none ("sorted source comparator kept",
  "queue source comparator kept", "plain source no comparator").
- A first non-`Comparable` element is rejected with `ClassCastException` and the queue
  stays empty ("first non-comparable", "after failure"). A mismatch throws with the new
  element as `compareTo`'s receiver ("second mismatch").
- Iterators are snapshots ("iterator is a snapshot") and support `remove` ("iterator
  remove"), which takes out that very element, not an equal one (`CollectionEdges`: "iterator removes
  that very element"). `take`, `poll` and `drainTo` follow priority order ("take order", "drain
  order"). Ties are unspecified.
- `DelayQueue`: `peek` and `element` show the head though unexpired ("peek shows
  unexpired head"), `size` counts it ("size counts unexpired"); `poll`, `remove()` and
  `drainTo` take only expired elements ("poll unexpired", "remove()", "drain unexpired",
  "drain expired only"). `take` waits for the delay ("take waits"), as `TIMED_WAITING`
  when a head exists and as `WAITING` when empty ("taker sleeps until head due", "taker
  waits on empty"). A new earlier head wakes it ("earlier head wakes taker").
- A source that is a `java.util.PriorityQueue` is treated as a plain collection: unlike a sorted set
  or another `PriorityBlockingQueue`, its comparator is dropped, and its elements must be naturally
  ordered (`CollectionEdges`: "blocking queue source keeps its comparator", "java.util.PriorityQueue
  source: comparator dropped"). One non-`Comparable` element in a source is accepted, since nothing is
  compared ("one non-comparable source"). A comparator that throws leaves the queue as it was ("comparator
  that throws, second insert"). The array's layout follows `java.util.PriorityQueue` ("heap layout").

### `SynchronousQueue`, `LinkedTransferQueue` (`HandoffQueues`)

- `SynchronousQueue` looks empty always: `toString` `[]`, `size` 0, `peek` null,
  `remainingCapacity` 0, iterator empty (`next` throws `NoSuchElementException`, `remove`
  `IllegalStateException`), even with a producer waiting ("size while putter waits",
  "peek while putter waits"). `toArray(a)` sets `a[0]` to null if `a` has any length
  ("toArray typed sets null"). `containsAll(empty)` is true, otherwise false
  ("containsAll", "containsAll empty").
- `offer` succeeds only with a waiting taker, `poll` only with a waiting producer ("offer
  alone", "offer to taker", "poll from putter"). `add` alone: `IllegalStateException:
  Queue full`. Fair mode serves takers FIFO ("fair FIFO"), non-fair mode the newest first (`CollectionEdges`:
  "non-fair: the newest taker first"). `offer` and `poll` ignore the flag ("offer flagged", "poll flagged").
  `put`, `take` and the timed forms look at it only when nobody is there to meet: with a partner waiting they
  meet, and keep the flag ("put flagged, a taker waits", "timed poll flagged, a giver waits").
- `LinkedTransferQueue`: `tryTransfer` is false and leaves nothing without a waiting
  consumer ("tryTransfer none", "left no trace"). A blocked `transfer` element is visible
  to `size`, `peek`, `contains` and `poll`, which ends it ("pending visible", "contains
  pending", "poll takes pending"). An interrupted transfer withdraws its element
  ("interrupted transfer", "element withdrawn"), as does a timed-out one ("timed
  tryTransfer withdrawn"). Waiting consumers do not count in `size` or `peek` ("size
  ignores consumers"), `getWaitingConsumerCount` counts them, and an interrupted one
  leaves ("consumer gone"). `put` and `offer` never block ("put never blocks"). FIFO
  iteration and `toString` ("fifo", "toArray").
- `LinkedTransferQueue`: a timed `offer` never reads its time unit, but `tryTransfer` does, first, so a null
  unit leaves nothing behind ("timed offer never reads its unit", "tryTransfer null unit leaves nothing").
  Each waiting consumer takes one hand-over ("one hand-over per consumer").

### `ConcurrentLinkedQueue`, `ConcurrentLinkedDeque` (`ConcurrentLinked`)

- Null elements throw on insertion, also from a collection. `contains(null)` is false on
  both. `remove(null)` is false on the queue but throws on the deque, as do
  `removeFirstOccurrence(null)` and `removeLastOccurrence(null)` ("contains null", "remove
  null", "first null", "last null").
- `addAll(self)`: `IllegalArgumentException`. A null inside the collection:
  `NullPointerException` and nothing is added ("addAll self", "addAll null element", "addAll
  atomic"). This is all-or-nothing, unlike the blocking queues.
- Iterators see elements added after they were made ("sees later add"). They and the
  descending iterator support `remove` ("iterator remove", "descending remove").
- Thread totals are exact, no element lost or repeated ("polled total", "deque size",
  "deque total").

## Synchronizers

### `CountDownLatch`, `CyclicBarrier` (`Latches`)

- `new CountDownLatch(-1)`: `IllegalArgumentException: count < 0`. Bracket `[Count = N]`.
  `countDown` at zero stays zero ("floors at zero"). `await` throws when flagged even if
  open ("await flagged, open").
- `CyclicBarrier`: parties below 1 throw ("parties zero"). `await` returns the arrival
  index, `parties - 1` first, 0 last ("log", "last index"); the action runs on the last
  arriver ("log"). A null action is allowed ("null action").
- Timeout breaks the barrier: the timer gets `TimeoutException`, others
  `BrokenBarrierException`, and later `await`s throw that at once ("timeout", "broken after
  timeout", "await broken"). `reset` repairs it, breaking current waiters ("reset breaks
  waiter"). An interrupted waiter gets `InterruptedException`, others
  `BrokenBarrierException` ("interrupted waiter", "other waiter"); `await` when flagged
  breaks it too ("broken after flagged").
- An action's exception propagates to the last arriver and breaks the barrier for the
  others ("action throws", "action broke it", "last runs action", "first sees broken").
- A barrier found broken reports that before looking at the flag, which stays set
  (`SynchronizerEdges`: "broken and flagged"). A timeout of zero with the flag set is
  `InterruptedException` and breaks the barrier ("barrier zero flagged", "broken by that"); a null unit
  throws first and breaks nothing ("barrier null unit flagged", "not broken by a null unit").
- While the action runs, `getNumberWaiting`, `isBroken`, `reset` and further `await`s wait for it, in
  `WAITING` state, deaf to interrupts until then and with no regard to a timeout (`getParties` does
  not wait). An interrupt during that wait is honoured on arrival: `InterruptedException`, barrier broken
  (`SynchronizerEdges`: "parties during action", "getNumberWaiting waited", "reset waited",
  "interrupted while the action runs: still", "late interrupted arrival").

### `Semaphore` (`Semaphores`)

- Bracket `[Permits = N]`, negative counts allowed ("negative ok"). Negative permit
  arguments throw `IllegalArgumentException` everywhere ("acquire -1" through "reduce -1").
  A negative `drainPermits` returns the negative count and sets zero ("negative drain",
  "drained negative"). A null unit throws ("timed null unit").
- Overflow: `Error: Maximum permit count exceeded`. Underflow from `reducePermits`: `Error:
  Permit count underflow` ("release overflow", "reduce underflow").
- `acquire` and timed `tryAcquire` throw when flagged even with permits free ("acquire
  flagged, permits free", "timed flagged, permits free"), plain `tryAcquire` ignores it
  ("tryAcquire flagged").
- Fair mode: strict arrival order, and a head needing more permits than available holds up
  those behind ("head served", "head needs 2: those behind it wait", "fair order"). Timed
  `tryAcquire(0, ...)` respects the queue; plain `tryAcquire()` barges ("fair timed respects
  the queue", "fair tryAcquire barges").

### `Exchanger`, `Phaser` (`ExchangersAndPhasers`)

- `Exchanger`: null values pass both ways ("null both ways"), timeout zero throws
  `TimeoutException` when alone ("zero timeout"), and interrupts follow G5 ("flagged",
  "interrupted"). An interrupted `exchange` leaves the exchanger usable ("usable after
  interrupt"). The flag is looked at on entry, whoever is waiting and whatever the timeout, and a partner
  already waiting stays there (`SynchronizerEdges`: "exchange zero flagged", "exchange flagged, partner
  waiting", "partner still there").
- `Phaser` bracket: `[phase = P parties = N arrived = A]`. Constructor: negative parties or
  above 65535: `IllegalArgumentException: Illegal number of parties` ("negative parties",
  "too many parties").
- Arrival without a registered party: `IllegalStateException: Attempted arrival of
  unregistered party for <phaser.toString()>`, for each of `arrive`, `arriveAndDeregister`,
  `arriveAndAwaitAdvance` ("arrive unregistered", "deregister unregistered", "await-arrive
  unregistered"). Too many: `IllegalStateException: Attempt to register more than 65535
  parties for <phaser.toString()>` ("bulk too many", "register at limit"). `bulkRegister(-1)`:
  `IllegalArgumentException` ("bulk negative").
- `arrive` and `register` return the phase at arrival ("arrive", "arrive advances").
  `awaitAdvance(p)` returns at once with the current phase unless `p` equals it; a negative
  `p` is returned unchanged ("await past phase", "await far phase", "await negative"). It
  ignores interrupts ("awaitAdvance ignores interrupt"). `awaitAdvanceInterruptibly` when
  flagged throws if it would wait, not otherwise ("interruptibly flagged", "interruptibly
  flagged past", "flag kept").
- Deregistering the last party terminates: the phase becomes `Integer.MIN_VALUE + (old phase
  + 1)`, printed as -2147483646 from phase 1 ("zero parties terminates"). Parties keep their
  count across termination by hook or force ("parties kept", "force keeps parties").
- `onAdvance(phase, parties)` runs once per advance in the arriving thread, with the phase
  before the advance; returning true terminates; an exception reaches the arriving thread
  ("hook log", "terminated by hook", "hook keeps alive", "hook throws"). `forceTermination`
  releases waiters with a negative result ("force releases").
- Tiers: a child with parties adds one to its parent; a child with none adds nothing until its
  first ("root parties", "leaf no party yet", "leaf registers up"). All phases advance together
  when the root does ("all advanced"); an emptied child leaves its parent without terminating it
  ("emptied child"); termination reaches every child ("terminate from root"). A child of a
  terminated parent is terminated ("register under terminated parent").
- `forceTermination` only sets the sign bit, so the phase number and that phase's arrivals still
  show, unlike a termination by advance, where arrivals read 0 (`PhaserEdges`: "forced with an arrival",
  "forced at phase 2", "last deregister calls hook").
- A hook that throws leaves the phase unadvanced and fully arrived, for good: arrivals are refused as
  unregistered, the hook is not called again, `register` and `awaitAdvance` wait until a
  `forceTermination` releases them (`PhaserEdges`: "after the throw", "arrive after the throw", "hook not
  called again", "register released by force", "awaitAdvance released by force").
- A hook runs with nothing held. While it runs, the getters and `toString` answer at once and show the
  phase fully arrived; every arrival, on the root or an arrived child, is refused as unregistered at once;
  `bulkRegister(0)`, a wait for another phase and a timed wait's timeout are all unaffected; `register`,
  empty child or not, and `awaitAdvance` of this phase wait for the advance, as `WAITING`
  (`PhaserEdges`: every "during hook" and "after hook" line, "child arrive during hook", "child register after
  hook", "register during empty hook"). `forceTermination` works during a hook and wins over what the
  hook returns (`PhaserEdges`: "force during hook", "hook ends after force", "hook forces", "hook forces and ends").
- `register` on a child whose parties have all arrived, with the root still waiting, waits for the
  advance (`PhaserEdges`: "register on an arrived child waited").

## `ThreadLocalRandom`, `TimeUnit`

- `ThreadLocalRandom` (`ThreadLocalRandoms`): `current()` is the same object per thread ("same
  instance"). `setSeed` throws `UnsupportedOperationException` ("setSeed"). Exact messages: `bound
  must be positive` for `nextInt(n)`, `nextLong(n)`, `nextDouble(d)`, NaN included ("nextInt 0",
  "nextDouble NaN"); `bound must be greater than origin` for the range forms, NaN included ("nextInt
  range bad", "nextDouble range NaN"). All range and bound promises hold over 2000 draws, including
  ranges wider than the type's positive half ("nextInt wide range", "nextLong wide range"). Tests never
  print a drawn number; any decent generator will do. The stream-returning members are deferred: see the
  checklist.
- `TimeUnit` (`TimeUnits`): the table lines are the whole conversion matrix of each unit against each value
  in `0, ±1, ±999, 1001, 86400, 90061001, Long.MIN_VALUE, Long.MAX_VALUE`, and values around `Long.MAX_VALUE
  / 1000`. Division truncates toward zero, and overflow saturates to `Long.MAX_VALUE` or `Long.MIN_VALUE`.
  `sleep(<=0)` returns at once, even when flagged ("sleep zero flagged"); `sleep(1)` when flagged throws
  `InterruptedException: sleep interrupted` ("sleep flagged"). `timedWait` and `timedJoin` with `timeout <=
  0` do nothing, null included ("timedWait null zero", "timedJoin null zero"); with a positive timeout they
  use `Object.wait` and `Thread.join` rules, so no monitor gives `IllegalMonitorStateException` and null gives
  `NullPointerException` ("timedWait no monitor", "timedWait null", "timedJoin null").

## Fork/join

### `ForkJoinPool` (`ForkJoin`, `ForkJoinTasks`)

- Bracket, as on a fresh pool: `[Running, parallelism = 2, size = 0, active = 0, running = 0, steals = 0, tasks
  = 0, submissions = 0]` ("fresh toString"). The word becomes `Terminated` ("terminated toString"). Later counts
  are not pinned.
- Workers are created on demand: a new pool of parallelism 32767 costs nothing ("parallelism limit"). Parallelism
  outside 1 to 32767: `IllegalArgumentException` ("parallelism 0", "parallelism over limit"). A null factory throws
  ("null factory").
- The common pool's parallelism comes from the system property (`ForkJoin`: "common parallelism from property").
  `shutdown` and `shutdownNow` do nothing to it, `shutdownNow` returns an empty list, `awaitTermination` gives false
  ("common ignores shutdown", "common shutdownNow", "common never terminates").
- Worker thread names: `ForkJoinPool-<n>-worker-<m>` and `ForkJoinPool.commonPool-worker-<m>` (digits normalised in
  goldens), daemon, `instanceof ForkJoinWorkerThread`, with `getPool` and `getPoolIndex >= 0` ("worker identity",
  "common worker identity"). A custom factory's worker and name are used ("custom factory"). A factory returning null
  leaves no thread; one that throws propagates to `execute` ("null worker: no thread", "thrown by factory").
- After `shutdown`, `submit`, `execute` and `invoke` throw `RejectedExecutionException`; only the class is pinned
  ("submit after shutdown", "execute after shutdown", "invoke after shutdown").
- `execute(Runnable)`'s failure goes to the pool's `UncaughtExceptionHandler` and the pool keeps working ("handler
  sees execute failure", "pool keeps working"). `getUncaughtExceptionHandler` defaults to null ("handler default").
- Hooks: `onStart`, the tasks, `onTermination(null)` ("hooks in order").
- Protected queue operations: with its one worker blocked, three submissions queue up; `pollSubmission` returns one,
  `drainTasksTo` moves the rest without running them ("queued submissions" through "drained stay pending").
- `submit`, `execute` and `invoke` from a worker go to the submissions, not its local queue ("submit from worker queued", "execute from worker queued"). Submissions run in
  order on one worker, async or not ("submission order", "async submission order"). A `Runnable` that is also a `ForkJoinTask` goes in as the task ("execute runnable task",
  "submit runnable same").
- `shutdownNow` cancels what is queued, **what is running and what a worker is blocked on**, and interrupts the workers ("queued cancelled", "running saw", "stop with blocked join", "joiner and awaited
  cancelled"). A task a worker forks after that still goes in its queue, and its join runs it ("fork after stop"). After a plain `shutdown` a task can still fork, but `submit` from inside is rejected ("inside after shutdown").
  A task's leftover interrupt does reach the next task if one is queued, unlike in `ThreadPoolExecutor`, but not after the worker went idle ("interrupt carried to a queued next task", "no interrupt once idle"). While busy after `shutdown` the word is
  `Shutting down` ("word while busy"). A pool that cannot make a worker keeps its queue after `shutdown` until `shutdownNow` ("null factory ..."). `isQuiescent` looks at workers only: a
  queued task with no worker is quiescent ("null factory queued"). What a factory throws leaves the task queued ("throwing factory queue").
- A worker dies when an `execute(Runnable)` task fails or `onStart` throws: `onTermination` gets the exception, then the thread's uncaught handler, which is the pool's when it has one, and a
  new worker follows ("hook log after failure", "threads made", "onStart throws"). Only that form escapes: a `submit` or `execute(ForkJoinTask)` failure stays in the task ("submit failure no
  handler", "execute(task) failure no handler").
- The common pool's property (`ForkJoinNoWorkers`): 0 reports parallelism 1 but makes no workers, so a task only runs when an outside thread awaits it, also with a timeout ("common parallelism", "fork
  join", "timed get", "worker thread name"). A negative or malformed value means the default, a value over the limit means the limit. Those last two were checked by hand on both VMs, as a golden there would depend on the machine.

### Tasks (`ForkJoin`, `ForkJoinTasks`)

- `fork` from outside a pool goes to the common pool. `invoke` from any thread starts the task right there ("common
  invoke", "direct invoke, same thread"). `invokeAll` waits for all and returns the given collection ("static invokeAll
  list same"); a null array, element or collection throws ("invokeAll null array", "invokeAll null element", "invokeAll
  null collection").
- A failure seen by the thread that ran it keeps the exception object ("direct invoke, same thread", "direct
  getException"). `get` wraps in `ExecutionException` ("direct get"). When it failed on another thread, only the class
  is
  pinned, because the exception the waiting thread sees may be a different object of the same class ("invoke failure", "submit join failure", "getException
  type").
- `completeExceptionally(unchecked)` rethrows it as is; a checked one, or the adapter's checked failure, is wrapped in
  `RuntimeException` whose message is the cause's `toString()` ("checked via join", "adapted checked wraps", "checked
  via get"). `completeExceptionally(null)` gives a bare `RuntimeException` ("after null").
- `cancel` is true also when already cancelled, false on a normally done task ("cancel again", "cancel done"). A
  cancelled task has a `CancellationException` from `getException()`, `isCompletedAbnormally` true, and `join`, `get`,
  `invoke` throw it ("cancel flags", "cancel getException", "cancel join", "cancel get", "cancel invoke").
- `reinitialize` clears completion ("reinitialize then invoke", "reinitialized"). `complete(v)` overrides `exec`'s result
  and calls `setRawResult` ("complete", "complete calls set"). `quietlyComplete` skips `setRawResult` ("quietlyComplete
  log"). A throw from `setRawResult` fails the task ("setRawResult exception"). A task whose `exec` returns false stays
  pending until completed ("exec false: runs and waits"). `exec` runs once ("no second exec").
- Tags: default 0, `setForkJoinTaskTag` returns the old one, `compareAndSetForkJoinTaskTag` as named, independent of
  completion, negative shorts kept ("tag ..." lines, "cas wrong", "cas right", "flags intact").
- Local queue, on a one-worker pool: LIFO by default, FIFO in async mode ("lifo order", "async order").
  `getQueuedTaskCount`, `peekNextLocalTask`, `pollNextLocalTask` and `tryUnfork` act on that order ("lifo local ops",
  "async local ops"). Outside a pool: `inForkJoinPool` false, `getPool` null, `getQueuedTaskCount` 0 ("outside ...").
- `invokeAll(t1, t2)` forks `t2`, runs `t1` here, then joins `t2`. If `t1` fails, the exception comes out at once and `t2` stays queued, not cancelled ("two fail at 0", "both fail"). A null `t1` is
  found after `t2` was forked ("null first of two"), a null `t2` before anything ("null second of two"). The array and collection forms fork from the last to the second, run the first here, join in order,
  and on the first failure cancel the rest ("three fail at ..."). A null there counts as a failure, found as the forking passes it: the first task still runs if the null is not first ("null in
  array", "null first in array", "null in list").
- A task's own throw is kept as it is, `Error` or hidden checked exception too ("sneaky ex", "error ex"), and so is a `CancellationException`, which does not make the task cancelled ("task throws CE flags",
  "complete with CE"). A runnable adapter does not wrap a hidden checked exception, a callable one does ("adapted runnable sneaky", "adapted checked wraps"). `complete` after completion still calls `setRawResult`, leaving the
  state alone ("complete after done", "complete after cancel"). `reinitialize` also clears the tag ("reinit tag", "reinit after fail").
- **Interrupts on a worker** (`ForkJoinWorkers`) differ from outside. `get` never looks at the flag there and behaves as a `join` that wraps failures: it runs the task, and the flag stays set ("get flagged queued keeps
  flag", "get flagged done"). The timed `get` does check the flag first ("timed get flagged queued", "timed get flagged done"). Blocked in either, a worker ignores an interrupt and the flag is lost ("done after interrupt", "pending get
  interrupted ..."). Outside a pool `join` waits on and sets the flag again after ("join keeps waiting past interrupt", "join flag set after").
- A worker blocked in `join`, `get` or a `ManagedBlocker` lets a queued task run even at parallelism 1 ("managed compensates", "join compensates"); blocked in a plain latch it does not ("unmanaged no compensation"). Plain tasks never run above the
  parallelism ("runs to parallelism", "never above parallelism").
- A chain of forks and joins 800 deep works ("deep 800"), though each level costs stack frames and a thread has at most 4094: a joiner stacks only so many tasks on itself, then blocks and lets another worker take over on a new stack.
- `ManagedBlocker`: `isReleasable` true skips `block`; `block` repeats until true ("outside pool blocker", "releasable
  skips block", "inside pool blocker").

## `CompletableFuture`, `CompletionStage`

**Owner rule (LazyVM design, not oracle behaviour).** `complete`, `completeExceptionally`, `cancel`, `obtrudeValue` and
`obtrudeException` must never call one another virtually. Each completes through private helpers, because
`lazy.internal.LoopFuture` (S6-06) overrides them ([`async.md`](../async.md)); if `cancel` called
`this.completeExceptionally(...)`, the override would swallow the settlement. S6-06's tests pin this, not S6-05's.

### States and reads (`CompletableFutures`)

- Brackets: `[Not completed]`, `[Not completed, N dependents]`, `[Completed normally]`, `[Completed exceptionally]` ("new
  toString", "done toString"; "one toString" in `CompletableFutureOrder`). A cancelled future prints `[Completed
  exceptionally]` ("cancelled toString").
- The read table, for a future completed with exception `X` (line "read" for each, under "fail with ..."):

| `X` | `join`, `getNow` throw | `get` throws | a callback on this future sees |
|---|---|---|---|
| ordinary, or `Error` | `CompletionException(X)` | `ExecutionException(X)` | `X` itself |
| `CancellationException` | `X` | `X` | `X` |
| `CompletionException` with cause `C` | `X` itself, not re-wrapped ("identity CE not rewrapped") | `ExecutionException(C)` | `X` |
| `CompletionException` with no cause | `X` | `ExecutionException(X)` | `X` |
| `ExecutionException` | `CompletionException(X)` | `ExecutionException(X)` | `X` |

  `CompletionException(X)` here means message `X.toString()` and cause `X` ("identity join cause"). So does
  `ExecutionException(X)`.
- `isCompletedExceptionally` is true for any failure. `isCancelled` is true exactly when the outcome is a
  `CancellationException`, however it came, by `completeExceptionally` or `obtrudeException` too ("fail with
  CancellationException", "obtrude cancellation"). A dependent of a cancelled source is failed, not cancelled ("dep
  cancelled flag").
- `complete` and `completeExceptionally` return false when already settled. `cancel` is true when it cancels or it was
  already cancelled, false when otherwise settled ("cancel again", "cancel failed", "cancel when failed with CE"). The
  `mayInterrupt` argument changes nothing ("cancel(true)", "cancel(false)"). `completeExceptionally(null)` and
  `obtrudeException(null)` throw and change nothing ("fail null", "fail null leaves", "obtrude null exception", "left").
- Cancelling a dependent leaves its source alone and survives the source settling later ("cancelling child leaves parent",
  "child stays cancelled"); cancelling a source settles its dependents as failed ("dependent of cancelled").
- `obtrude*` overwrite any state, and fire dependents the first time the future settles, not after ("obtrude pending",
  "obtrude fires dependents", "no refire"). Dependents already fired keep what they saw ("dep keeps old"). `obtrudeValue`
  removes cancellation ("obtrude over cancel"), after which `complete` is false ("complete after obtrude").
- Interrupts: `get` throws when flagged only if it would wait; a done future returns and the flag stays ("get flagged
  pending", "get flagged done", "flag kept"). `join` ignores interrupts and returns with the flag set ("join ignores
  interrupt", "join return"). Blocked `get` and `join` threads are released when the future settles, each with its own
  exception form ("get waiter", "join waiter", "timed waiter on cancel").
- Dependents created from a subclass are plain `CompletableFuture`s: S6-06 relies on this ("sub dependent class", "sub
  handle class", "sub exceptionally class"). `toCompletableFuture` returns `this` ("toCompletableFuture").

### One-source dependents (`CompletableFutureStages`)

The golden is the matrix of `thenApply`, `thenAccept`, `thenRun`, `thenCompose`, `whenComplete`, `handle`, `exceptionally`,
each against each source state, with functions that return and that throw. The rules behind it:

- A source failure skips `thenApply`, `thenAccept`, `thenRun`, `thenCompose`. The dependent fails with
  `CompletionException(X)`; an `X` that is already a `CompletionException` is kept as is ("thenApply / done fail",
  "thenApply / done CE"). A callback therefore sees the raw `X` only on the failed source itself, and a
  `CompletionException` on any stage further down ("exceptionally sees wrapped downstream", "failure skips stages, stays
  wrapped").
- `whenComplete` and `handle` always run, given `(value, null)` or `(null, raw outcome)` ("whenComplete / done fail",
  "handle / done fail", "handle / done CE", "handle / done cancelled"). `exceptionally` runs only on failure, with the same
  raw outcome, and otherwise passes the value on, null too ("exceptionally / done ok", "exceptionally / done fail").
- A function that throws `E` fails the dependent with `CompletionException(E)`; a thrown `CompletionException` is kept, the
  very object; a thrown `CancellationException` or `Error` is wrapped like the rest ("throwing thenApply / done ok", "thrown
  CE kept, not rewrapped", "thrown cancellation is wrapped", "thrown error captured").
- `whenComplete`: on a good source, a throwing action fails the dependent; on a failed source, the source's failure wins and
  the action's is dropped, without `addSuppressed` ("throwing whenComplete / done ok", "throwing whenComplete / done fail",
  "whenComplete no suppression"). The result otherwise repeats the source's, in a new future ("whenComplete keeps value",
  "whenComplete is new").
- `thenCompose`: a null from the function, or a null from the stage's `toCompletableFuture()`, fails the dependent with
  `CompletionException(NullPointerException)` ("compose null stage", "compose toCompletableFuture null"). The stage is read
  through `toCompletableFuture()` ("compose follows toCompletableFuture"). The result is a new future, and the inner outcome
  is treated as a source's ("compose failed", "compose CE", "compose cancelled", "compose pending", "compose pending fails").
- Callback thread: the caller if the source was already done ("on main"), else the thread that settled the source ("on
  settler").
- Settling a dependent directly wins over its source, and its function does not run ("complete a dependent early").

### Two-source dependents, `allOf`, `anyOf` (`CompletableFutureCombos`)

Each line of the first part is one method, one pair of outcomes and one settling order: `before` (both settled before
attaching), `a,b`, `b,a`; a throwing function is also covered.

- `thenCombine`, `thenAcceptBoth`, `runAfterBoth` wait for **both** sources, even after one failed. On a failure the
  receiver's outcome wins over the argument's, whatever the settling order, and the function does not run ("thenCombine
  fail1,fail2 b,a"). Failures are wrapped as for one source.
- `applyToEither`, `acceptEither`, `runAfterEither` settle at the **first** source to settle, good or bad, and ignore the
  other ("applyToEither fail1,ok2 a,b", "applyToEither fail1,ok2 b,a"). If both were settled before attaching, the receiver
  wins ("applyToEither ok1,fail2 before").
- The same future as both sources works ("combine with itself", "either with itself").
- `allOf()` is done at once with null; `anyOf()` never completes ("allOf empty", "anyOf empty"). A null array or element:
  `NullPointerException` ("allOf null element"). A single source gives a new future ("allOf one", "anyOf one").
- `allOf` settles when all are settled, value null, even after a failure ("order ...", "allOf waits behind failure"). With
  several failures it reports the lowest-index one, whatever the settling order ("allOf reports the lowest index that
  failed"). The failure is wrapped as a dependent's ("allOf done fail1,ok2,ok1").
- `anyOf` takes the outcome of the first to settle; if some were settled before the call, the lowest index among them ("anyOf
  done ..."). A failure is wrapped as a dependent's. Cancelling `allOf`'s result leaves sources alone ("cancel allOf leaves
  sources").
- Null arguments: every method throws `NullPointerException` at the call, on a pending and a done receiver alike. A source
  whose `toCompletableFuture()` returns null also throws at the call for the two-source methods ("pending ...", "done ..."
  lines, "other toCompletableFuture null", "either toCompletableFuture null").

### Async variants (`CompletableFutureAsync`, `CompletableFutureNoCommon`)

- `*Async(f, executor)` calls `executor.execute` once per dependent, and `f` runs on whatever thread that uses ("named ..."
  lines, "one executor call per dependent"). Tasks handed over implement `CompletableFuture.AsynchronousCompletionTask`
  ("marker"), an interface with no methods ("marker empty"). A direct executor runs them in the caller ("direct executor,
  thread", "direct executor done at return").
- `*Async(f)` runs on the common pool if its parallelism is at least 2 ("default ..." lines, "supply on common", "common
  thread"). Below 2, each task gets a new non-daemon plain thread, not a pool worker ("supplyAsync thread", "thenApplyAsync
  thread", "a new thread each time": `CompletableFutureNoCommon`). Common-pool workers are daemon threads, so `main` must
  `join`.
- A rejecting executor: `supplyAsync` and `runAsync` throw to the caller ("rejecting supplyAsync", "rejecting runAsync"). A
  dependent attached to a done source fails with `CompletionException(rejection)`, and `complete` on a pending source returns
  true with the rejection only in the dependent ("rejecting dependent on done", "rejecting dependent on pending").
- A failed source skips the executor for dependents that skip their function, and uses it for `handle` and `whenComplete`
  ("failed source skips executor" prints 2 of 3).
- A failure inside `supplyAsync`: `get` gives `ExecutionException(E)`, `join` gives `CompletionException(E)`; a
  `CompletionException` thrown there is treated as in the read table ("supply fails get", "supply fails join", "supply throws
  CE").
- Sync dependents run where the source finished ("sync dependent runs where source finished", "runs on completing thread",
  "runs on cancelling thread"), or in the caller if already done ("attached after done runs in caller").
- `cancel(true)` on a running `supplyAsync` settles the future at once and does **not** interrupt the task; its later result
  is dropped ("cancel while running", "running task was not interrupted", "still cancelled").

### Order, counts, depth (`CompletableFutureOrder`)

- **First level.** Firing starts at a stage settled by a call (`complete`, `completeExceptionally`, `cancel`, `obtrudeValue`,
  `obtrudeException`, a dependent settled directly included) or by a task an executor ran (`supplyAsync`, `runAsync`, any
  `*Async` dependent, a direct executor included). That stage's own dependents fire last attached first, all kinds together
  ("flat", "mixed kinds", "on failure", "settled by cancel", "settled by completeExceptionally", "settled by obtrudeValue",
  "settled by obtrudeException", "dependent settled by a direct complete", "supplyAsync, several thenAccept", "dependent run by an
  executor", "dependent run by a direct executor"). The Javadoc promises no order; these lines match the oracle's.
- **Deeper levels.** A stage settled as part of another's firing, such as a sync dependent, including one below `thenCombine`,
  `allOf`, `anyOf`, `applyToEither` or a pending `thenCompose`, fires its own dependents first attached first ("below thenApply",
  "below thenCombine", "below allOf", "below anyOf", "below applyToEither", "below a pending compose", "below a failed thenApply").
- Each stage fires before its dependents, and its whole subtree fires as one run before the next sibling ("whole tree", "chain and
  sibling").
- Re-entrancy: a dependent attached during firing to the settled source runs immediately in place, then the remaining ones
  continue ("attach during firing"). A dependent that settles another future runs that future's dependents before `complete`
  returns to it ("complete another inside"). `complete` from inside its own dependent is false, `getNow` there sees the value
  ("complete self inside", "getNow inside"). A throwing dependent does not stop the others or reach `complete`'s caller
  ("dependent throws, others run").
- `getNumberOfDependents`: each attached stage counts 1, on each source for two-source methods ("one", "three", "both counts
  each", "either counts each", "compose counts", "handlers count"); grandchildren do not ("grandchild not counted"); settling
  drops to 0, and later attachments run at once and do not count ("after completion", "attached after done"). A two-source
  dependent still counts on its unsettled source after the other settled ("both after first"). Whether a cancelled dependent
  still counts is not pinned ("cancelled dependent" only asserts `>= 0`).
- Long chains of stages linked by the API do not exhaust the stack: 20000 chained `thenApply` and `thenCompose`, and a fan of 5000
  dependents, settle on the default thread stack ("deep chain", "deep compose", "wide fan"). A failure passing through 20000 stages
  stays a single `CompletionException` over `X` ("deep failure"). One native or Java frame per stage will not pass this, since
  LazyVM's frame limit is 4094. This does not cover user code: a chain in which each dependent's function calls `complete` on the
  next future is ordinary recursion, overflows on the oracle too, and is not tested.

## Decisions checked against the oracle

Two questions the Javadoc leaves open, where a simpler answer looked acceptable and was checked before
being kept. Neither was: both are now what the oracle does.

- **A queued reader at the 65535 limit.** The first idea was to leave it waiting until the count fell. The
  oracle throws at once, so a queued reader fails like a newcomer ("queued lock at the limit").
- **A `Phaser` hook under the phaser's monitor.** The first idea was to run it holding the monitor, as
  calls from inside a hook are unspecified. But calls from *other* threads are observable: under a monitor
  an arrival during a blocked hook would wait and then succeed, where the oracle refuses it at once, and a
  `forceTermination` would have to wait for the hook. So it runs with nothing held ("during hook: arrive",
  "force during hook"). The same check showed that a `CyclicBarrier` action is the opposite case: it does keep
  the others out, getters included ("getNumberWaiting waited").

## What is not pinned

- **A non-fair newcomer behind an old queue head** (`Turnstile`: it may overtake a head younger than one VM
  lock slice, 5 ms, and queues after that). This is how LazyVM keeps a thread looping on a lock from
  starving the others, and spares a thread switch per critical section, but it is not an oracle rule, and
  its only visible effect is how evenly turns are shared. Pinning that takes a ratio with a margin, which would
  be flaky, so no test holds it. `tests/java/thread/Fairness` holds the same rule for monitors only.
- **Nondeterministic on the oracle**: the first key into an empty `ConcurrentSkipListMap` or `ConcurrentSkipListSet` when it is
  not `Comparable`. Most runs accept it, and some throw `ClassCastException`. The tests do not touch it, and either answer is
  fine for LazyVM.
- **Dependent on processor count**: the common pool's default size, `getPoolSize`, steal counts, the number of times a
  non-identity basis is folded in a parallel `reduce*To*`, and which thread a parallel bulk function runs on. A task submitted
  to the common pool may run in the waiting thread (`ForkJoin` uses a latch to avoid depending on that).
- **Timing**: anything counted while workers are active: `completed tasks` in a rejection message from a pool still busy (the
  tests print only "rejected" there), the exact number of periodic runs ("no more runs" allows one in flight), and whether a
  periodic task is done after `shutdownNow` when it was running at that moment.
- **Failures crossing threads**: in `ForkJoinTask`, a failure on another thread may surface as a different object of the same
  class, whose message may be the original's `toString()`. Tests print only the class. LazyVM rethrows the same object.
- **Artefacts of the oracle's fork/join**: `getQueuedTaskCount` right after a join took an old task from the local queue (the oracle leaves a filler in its place and counts it), `getSurplusQueuedTaskCount`'s value, how many
  spare workers exist after a compensation, and how soon `awaitQuiescence` returns right after a submission from outside (the oracle may answer before its first worker is up; here it waits for the work). LazyVM's worker ends after
  2 s idle.
- **What a dying worker had forked**: on the oracle it is usually lost, and sometimes runs. Here it moves to the submissions and runs, as nothing is gained by losing it.
- **Private class names**: the message of a `ClassCastException` from `getQueue().add(...)` on a scheduled pool, the wrapper's class in a rejection from `invokeAny` or a completion service, and the classes behind `Executors`,
  including the common pool's factory.
- **An active worker with no task**: a new worker with no first task reads as active until it starts looking for work. Tests wait for `getActiveCount() == 0`.
- **Message of an `ArrayStoreException`** from `toArray(T[])` with a wrong type, which differs between classes on the oracle.
  Tests print only the class.
- **`invokeAny` with a null behind a quick task**: the first task may finish before the null is reached. Only a null in first place
  is tested.
- **Order**: `ConcurrentHashMap` iteration, `PriorityBlockingQueue` and `DelayQueue` iterators and ties, non-fair lock order, and
  non-fair queue hand-off order other than `SynchronousQueue`'s.
- **Unstable on the oracle**: what a blocked `transfer` does when its element is taken out by `remove(Object)` or by
  an iterator. From run to run it returns, or throws `InterruptedException`, or a timed `tryTransfer` answers false. Here
  it returns, and answers true, as for any element that left. `clear` and `drainTo` were stable: it returns.
- **Allowed by weak consistency, and different**: on the oracle, an `ArrayBlockingQueue` iterator stops seeing new
  elements once elements it had not reached are taken (a, b; `next`; `poll`; `add` c: it hands out only b). Here it goes on
  following the queue, like the other FIFO queues on the oracle (b, then c).
- **Different on purpose**: a function given to `ConcurrentHashMap.computeIfAbsent` that updates *other* keys of the same
  map can hang the oracle, depending on bins. Here it works.
- **Not tested at all**: serialization of these classes; `ConcurrentHashMap` recursive updates; `ThreadPoolExecutor.finalize`;
  the `Spliterator` and stream members listed as deferred below.
- **Out of scope for S6-04**: `AbstractQueuedSynchronizer`, `AbstractQueuedLongSynchronizer`, `AbstractOwnableSynchronizer`,
  `StampedLock`, `CountedCompleter`. They are public SE 8 types, but the plan does not name them. Build none of them for their
  own sake, and do not expose a helper of your own under a `java.*` public name (`CLAUDE.md`, rule 2).

## Contention and exceptions

- `Contention` prints totals only, from 3 threads of 2000 operations each (300 for `CopyOnWriteArrayList`). Its counters are plain
  `int`s under the lock under test, so a lock that fails to exclude loses counts: `ReentrantLock` and the write lock ("ReentrantLock
  total", "write lock total", "readers saw a half write"). `Semaphore` never exceeds its permits and ends with them all
  ("Semaphore over limit", "Semaphore permits"). `ArrayBlockingQueue`, `LinkedBlockingQueue`, `LinkedBlockingDeque` and
  `PriorityBlockingQueue` under 3 producers and 3 consumers lose and repeat nothing ("... sum", "... repeats", "... left"), and on the
  FIFO three each consumer sees each producer's elements in order ("... producer order broken"). `ConcurrentSkipListMap`: one winner per
  `putIfAbsent` and `remove`, exact `merge` totals, sorted after ("skip list ..."). `CopyOnWriteArrayList`: one winner per
  `addIfAbsent`, no lost `add` ("addIfAbsent ...", "add lost none").
- `ConcurrentExceptions`: `(String, Throwable)` keeps both; `(Throwable)` takes the cause's `toString()` as message, and a null cause gives
  a null message ("... cause", "... null cause"). `CancellationException` is an `IllegalStateException`; `CompletionException` and
  `RejectedExecutionException` are unchecked; `ExecutionException`, `TimeoutException` and `BrokenBarrierException` are checked.

## Harness notes

- `tools/testlib.py` compiles tests against `lazyrt`, so until these classes exist `tools/golden.py` cannot compile them. These
  goldens were blessed with the same oracle flags and transcript function, compiled with the oracle's own `javac` against its own
  API. Once the classes exist, `python tools/golden.py --bless tests/java/concurrent` must leave every `.out` byte-identical. If
  one changes, either the class's public API differs from SE 8 or the test is nondeterministic: find which. Do not edit a golden
  by hand.
- `tools/test.py` compiles the whole selection in one `javac` run, so while any class named here is missing, an unfiltered run
  stops at compilation, for the whole suite, not only these tests. This page's branch is therefore not mergeable alone. `python
  tools/test.py --filter <something else>` still works.
- Each test ran within about 2 s on the oracle. `ScheduledExecutors` is the longest by design, because its delays are real. Its one time
  margin is in "delay order", where two consecutive `schedule` calls must be under 700 ms apart; everything else there, like the
  "default: ..." lines, is ordered by latches or polling.
- Goldens were checked for stability: repeated runs at 1, 2, 16 and all processors, under `-Xint`, with six runs at once under 24 busy
  processes, and on a clock-starved core at about 41 times `-Xint`, the speed CI is expected to give LazyVM, where each test finished
  within about 17 s, against a 60 s limit. Starvation exposes real races: this is how "any null element" was found to depend on which task finished
  first, so that test now puts the null first.

## API checklist

Every `public` and `protected` member the SE 8 Javadoc declares in scope, one line each, signatures extracted from the Javadoc
pages. Add nothing else (`CLAUDE.md`, rule 2): helpers are package-private or live in `lazy.internal.*`. Inherited members are
not repeated. Members from `Object`, `Collection` and the like that a class overrides are listed because the Javadoc lists them.

**Already in `lazyrt`:** `java.util.concurrent.Callable` and `java.util.concurrent.TimeUnit`. `TimeUnit` is only partial, so
`TimeUnits` does not compile against it yet. `java.util.ConcurrentModificationException` also exists, outside this package.
Nothing else in these packages exists.

**Deferred, do not add yet** (marked `deferred` below), because their parameter or return types do not exist in `lazyrt`: all
`spliterator()` methods (no `java.util.Spliterator`), the `ints`, `longs` and `doubles` streams of `ThreadLocalRandom` (no
`java.util.stream`), and `Executors.callable(PrivilegedAction)` and `callable(PrivilegedExceptionAction)` (no
`java.security.Privileged*`). Add them with the types they need.

### `java.util.concurrent.AbstractExecutorService`

`public abstract class AbstractExecutorService extends Object implements ExecutorService`

- [ ] `public AbstractExecutorService()`
- [ ] `protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value)`
- [ ] `protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable)`
- [ ] `public Future<?> submit(Runnable task)`
- [ ] `public <T> Future<T> submit(Runnable task, T result)`
- [ ] `public <T> Future<T> submit(Callable<T> task)`
- [ ] `public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException`
- [ ] `public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException`
- [ ] `public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException`
- [ ] `public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException`

### `java.util.concurrent.ArrayBlockingQueue`

`public class ArrayBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, Serializable`

- [ ] `public ArrayBlockingQueue(int capacity)`
- [ ] `public ArrayBlockingQueue(int capacity, boolean fair)`
- [ ] `public ArrayBlockingQueue(int capacity, boolean fair, Collection<? extends E> c)`
- [ ] `public boolean add(E e)`
- [ ] `public boolean offer(E e)`
- [ ] `public void put(E e) throws InterruptedException`
- [ ] `public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E poll()`
- [ ] `public E take() throws InterruptedException`
- [ ] `public E poll(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E peek()`
- [ ] `public int size()`
- [ ] `public int remainingCapacity()`
- [ ] `public boolean remove(Object o)`
- [ ] `public boolean contains(Object o)`
- [ ] `public Object[] toArray()`
- [ ] `public <T> T[] toArray(T[] a)`
- [ ] `public String toString()`
- [ ] `public void clear()`
- [ ] `public int drainTo(Collection<? super E> c)`
- [ ] `public int drainTo(Collection<? super E> c, int maxElements)`
- [ ] `public Iterator<E> iterator()`
- [ ] `public Spliterator<E> spliterator()` (deferred)

### `java.util.concurrent.BlockingDeque`

`public interface BlockingDeque<E> extends BlockingQueue<E>, Deque<E>`

- [ ] `void addFirst(E e)`
- [ ] `void addLast(E e)`
- [ ] `boolean offerFirst(E e)`
- [ ] `boolean offerLast(E e)`
- [ ] `void putFirst(E e) throws InterruptedException`
- [ ] `void putLast(E e) throws InterruptedException`
- [ ] `boolean offerFirst(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `boolean offerLast(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `E takeFirst() throws InterruptedException`
- [ ] `E takeLast() throws InterruptedException`
- [ ] `E pollFirst(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `E pollLast(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `boolean removeFirstOccurrence(Object o)`
- [ ] `boolean removeLastOccurrence(Object o)`
- [ ] `boolean add(E e)`
- [ ] `boolean offer(E e)`
- [ ] `void put(E e) throws InterruptedException`
- [ ] `boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `E remove()`
- [ ] `E poll()`
- [ ] `E take() throws InterruptedException`
- [ ] `E poll(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `E element()`
- [ ] `E peek()`
- [ ] `boolean remove(Object o)`
- [ ] `boolean contains(Object o)`
- [ ] `int size()`
- [ ] `Iterator<E> iterator()`
- [ ] `void push(E e)`

### `java.util.concurrent.BlockingQueue`

`public interface BlockingQueue<E> extends Queue<E>`

- [ ] `boolean add(E e)`
- [ ] `boolean offer(E e)`
- [ ] `void put(E e) throws InterruptedException`
- [ ] `boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `E take() throws InterruptedException`
- [ ] `E poll(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `int remainingCapacity()`
- [ ] `boolean remove(Object o)`
- [ ] `boolean contains(Object o)`
- [ ] `int drainTo(Collection<? super E> c)`
- [ ] `int drainTo(Collection<? super E> c, int maxElements)`

### `java.util.concurrent.BrokenBarrierException`

`public class BrokenBarrierException extends Exception`

- [ ] `public BrokenBarrierException()`
- [ ] `public BrokenBarrierException(String message)`

### `java.util.concurrent.Callable`

`@FunctionalInterface public interface Callable<V>`

- [ ] `V call() throws Exception`

### `java.util.concurrent.CancellationException`

`public class CancellationException extends IllegalStateException`

- [ ] `public CancellationException()`
- [ ] `public CancellationException(String message)`

### `java.util.concurrent.CompletableFuture`

`public class CompletableFuture<T> extends Object implements Future<T>, CompletionStage<T>`

- [ ] `public CompletableFuture()`
- [ ] `public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier)`
- [ ] `public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor)`
- [ ] `public static CompletableFuture<Void> runAsync(Runnable runnable)`
- [ ] `public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor)`
- [ ] `public static <U> CompletableFuture<U> completedFuture(U value)`
- [ ] `public boolean isDone()`
- [ ] `public T get() throws InterruptedException, ExecutionException`
- [ ] `public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException`
- [ ] `public T join()`
- [ ] `public T getNow(T valueIfAbsent)`
- [ ] `public boolean complete(T value)`
- [ ] `public boolean completeExceptionally(Throwable ex)`
- [ ] `public <U> CompletableFuture<U> thenApply(Function<? super T,? extends U> fn)`
- [ ] `public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn)`
- [ ] `public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn, Executor executor)`
- [ ] `public CompletableFuture<Void> thenAccept(Consumer<? super T> action)`
- [ ] `public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action)`
- [ ] `public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor)`
- [ ] `public CompletableFuture<Void> thenRun(Runnable action)`
- [ ] `public CompletableFuture<Void> thenRunAsync(Runnable action)`
- [ ] `public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor)`
- [ ] `public <U,V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn)`
- [ ] `public <U,V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn)`
- [ ] `public <U,V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn, Executor executor)`
- [ ] `public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super T,? super U> action)`
- [ ] `public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T,? super U> action)`
- [ ] `public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T,? super U> action, Executor executor)`
- [ ] `public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action)`
- [ ] `public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action)`
- [ ] `public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor)`
- [ ] `public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other, Function<? super T,U> fn)`
- [ ] `public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T,U> fn)`
- [ ] `public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T,U> fn, Executor executor)`
- [ ] `public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action)`
- [ ] `public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action)`
- [ ] `public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor)`
- [ ] `public CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action)`
- [ ] `public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action)`
- [ ] `public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor)`
- [ ] `public <U> CompletableFuture<U> thenCompose(Function<? super T,? extends CompletionStage<U>> fn)`
- [ ] `public <U> CompletableFuture<U> thenComposeAsync(Function<? super T,? extends CompletionStage<U>> fn)`
- [ ] `public <U> CompletableFuture<U> thenComposeAsync(Function<? super T,? extends CompletionStage<U>> fn, Executor executor)`
- [ ] `public CompletableFuture<T> whenComplete(BiConsumer<? super T,? super Throwable> action)`
- [ ] `public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T,? super Throwable> action)`
- [ ] `public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T,? super Throwable> action, Executor executor)`
- [ ] `public <U> CompletableFuture<U> handle(BiFunction<? super T,Throwable,? extends U> fn)`
- [ ] `public <U> CompletableFuture<U> handleAsync(BiFunction<? super T,Throwable,? extends U> fn)`
- [ ] `public <U> CompletableFuture<U> handleAsync(BiFunction<? super T,Throwable,? extends U> fn, Executor executor)`
- [ ] `public CompletableFuture<T> toCompletableFuture()`
- [ ] `public CompletableFuture<T> exceptionally(Function<Throwable,? extends T> fn)`
- [ ] `public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs)`
- [ ] `public static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs)`
- [ ] `public boolean cancel(boolean mayInterruptIfRunning)`
- [ ] `public boolean isCancelled()`
- [ ] `public boolean isCompletedExceptionally()`
- [ ] `public void obtrudeValue(T value)`
- [ ] `public void obtrudeException(Throwable ex)`
- [ ] `public int getNumberOfDependents()`
- [ ] `public String toString()`

### `java.util.concurrent.CompletableFuture.AsynchronousCompletionTask`

`public static interface CompletableFuture.AsynchronousCompletionTask`

(no members of its own)

### `java.util.concurrent.CompletionException`

`public class CompletionException extends RuntimeException`

- [ ] `protected CompletionException()`
- [ ] `protected CompletionException(String message)`
- [ ] `public CompletionException(String message, Throwable cause)`
- [ ] `public CompletionException(Throwable cause)`

### `java.util.concurrent.CompletionService`

`public interface CompletionService<V>`

- [ ] `Future<V> submit(Callable<V> task)`
- [ ] `Future<V> submit(Runnable task, V result)`
- [ ] `Future<V> take() throws InterruptedException`
- [ ] `Future<V> poll()`
- [ ] `Future<V> poll(long timeout, TimeUnit unit) throws InterruptedException`

### `java.util.concurrent.CompletionStage`

`public interface CompletionStage<T>`

- [ ] `<U> CompletionStage<U> thenApply(Function<? super T,? extends U> fn)`
- [ ] `<U> CompletionStage<U> thenApplyAsync(Function<? super T,? extends U> fn)`
- [ ] `<U> CompletionStage<U> thenApplyAsync(Function<? super T,? extends U> fn, Executor executor)`
- [ ] `CompletionStage<Void> thenAccept(Consumer<? super T> action)`
- [ ] `CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action)`
- [ ] `CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor)`
- [ ] `CompletionStage<Void> thenRun(Runnable action)`
- [ ] `CompletionStage<Void> thenRunAsync(Runnable action)`
- [ ] `CompletionStage<Void> thenRunAsync(Runnable action, Executor executor)`
- [ ] `<U,V> CompletionStage<V> thenCombine(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn)`
- [ ] `<U,V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn)`
- [ ] `<U,V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn, Executor executor)`
- [ ] `<U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super T,? super U> action)`
- [ ] `<U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T,? super U> action)`
- [ ] `<U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T,? super U> action, Executor executor)`
- [ ] `CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action)`
- [ ] `CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action)`
- [ ] `CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor)`
- [ ] `<U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other, Function<? super T,U> fn)`
- [ ] `<U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T,U> fn)`
- [ ] `<U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T,U> fn, Executor executor)`
- [ ] `CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action)`
- [ ] `CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action)`
- [ ] `CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor)`
- [ ] `CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action)`
- [ ] `CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action)`
- [ ] `CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor)`
- [ ] `<U> CompletionStage<U> thenCompose(Function<? super T,? extends CompletionStage<U>> fn)`
- [ ] `<U> CompletionStage<U> thenComposeAsync(Function<? super T,? extends CompletionStage<U>> fn)`
- [ ] `<U> CompletionStage<U> thenComposeAsync(Function<? super T,? extends CompletionStage<U>> fn, Executor executor)`
- [ ] `CompletionStage<T> exceptionally(Function<Throwable,? extends T> fn)`
- [ ] `CompletionStage<T> whenComplete(BiConsumer<? super T,? super Throwable> action)`
- [ ] `CompletionStage<T> whenCompleteAsync(BiConsumer<? super T,? super Throwable> action)`
- [ ] `CompletionStage<T> whenCompleteAsync(BiConsumer<? super T,? super Throwable> action, Executor executor)`
- [ ] `<U> CompletionStage<U> handle(BiFunction<? super T,Throwable,? extends U> fn)`
- [ ] `<U> CompletionStage<U> handleAsync(BiFunction<? super T,Throwable,? extends U> fn)`
- [ ] `<U> CompletionStage<U> handleAsync(BiFunction<? super T,Throwable,? extends U> fn, Executor executor)`
- [ ] `CompletableFuture<T> toCompletableFuture()`

### `java.util.concurrent.ConcurrentHashMap`

`public class ConcurrentHashMap<K,V> extends AbstractMap<K,V> implements ConcurrentMap<K,V>, Serializable`

- [ ] `public ConcurrentHashMap()`
- [ ] `public ConcurrentHashMap(int initialCapacity)`
- [ ] `public ConcurrentHashMap(Map<? extends K,? extends V> m)`
- [ ] `public ConcurrentHashMap(int initialCapacity, float loadFactor)`
- [ ] `public ConcurrentHashMap(int initialCapacity, float loadFactor, int concurrencyLevel)`
- [ ] `public int size()`
- [ ] `public boolean isEmpty()`
- [ ] `public V get(Object key)`
- [ ] `public boolean containsKey(Object key)`
- [ ] `public boolean containsValue(Object value)`
- [ ] `public V put(K key, V value)`
- [ ] `public void putAll(Map<? extends K,? extends V> m)`
- [ ] `public V remove(Object key)`
- [ ] `public void clear()`
- [ ] `public ConcurrentHashMap.KeySetView<K,V> keySet()`
- [ ] `public Collection<V> values()`
- [ ] `public Set<Map.Entry<K,V>> entrySet()`
- [ ] `public int hashCode()`
- [ ] `public String toString()`
- [ ] `public boolean equals(Object o)`
- [ ] `public V putIfAbsent(K key, V value)`
- [ ] `public boolean remove(Object key, Object value)`
- [ ] `public boolean replace(K key, V oldValue, V newValue)`
- [ ] `public V replace(K key, V value)`
- [ ] `public V getOrDefault(Object key, V defaultValue)`
- [ ] `public void forEach(BiConsumer<? super K,? super V> action)`
- [ ] `public void replaceAll(BiFunction<? super K,? super V,? extends V> function)`
- [ ] `public V computeIfAbsent(K key, Function<? super K,? extends V> mappingFunction)`
- [ ] `public V computeIfPresent(K key, BiFunction<? super K,? super V,? extends V> remappingFunction)`
- [ ] `public V compute(K key, BiFunction<? super K,? super V,? extends V> remappingFunction)`
- [ ] `public V merge(K key, V value, BiFunction<? super V,? super V,? extends V> remappingFunction)`
- [ ] `public boolean contains(Object value)`
- [ ] `public Enumeration<K> keys()`
- [ ] `public Enumeration<V> elements()`
- [ ] `public long mappingCount()`
- [ ] `public static <K> ConcurrentHashMap.KeySetView<K,Boolean> newKeySet()`
- [ ] `public static <K> ConcurrentHashMap.KeySetView<K,Boolean> newKeySet(int initialCapacity)`
- [ ] `public ConcurrentHashMap.KeySetView<K,V> keySet(V mappedValue)`
- [ ] `public void forEach(long parallelismThreshold, BiConsumer<? super K,? super V> action)`
- [ ] `public <U> void forEach(long parallelismThreshold, BiFunction<? super K,? super V,? extends U> transformer, Consumer<? super U> action)`
- [ ] `public <U> U search(long parallelismThreshold, BiFunction<? super K,? super V,? extends U> searchFunction)`
- [ ] `public <U> U reduce(long parallelismThreshold, BiFunction<? super K,? super V,? extends U> transformer, BiFunction<? super U,? super U,? extends U> reducer)`
- [ ] `public double reduceToDouble(long parallelismThreshold, ToDoubleBiFunction<? super K,? super V> transformer, double basis, DoubleBinaryOperator reducer)`
- [ ] `public long reduceToLong(long parallelismThreshold, ToLongBiFunction<? super K,? super V> transformer, long basis, LongBinaryOperator reducer)`
- [ ] `public int reduceToInt(long parallelismThreshold, ToIntBiFunction<? super K,? super V> transformer, int basis, IntBinaryOperator reducer)`
- [ ] `public void forEachKey(long parallelismThreshold, Consumer<? super K> action)`
- [ ] `public <U> void forEachKey(long parallelismThreshold, Function<? super K,? extends U> transformer, Consumer<? super U> action)`
- [ ] `public <U> U searchKeys(long parallelismThreshold, Function<? super K,? extends U> searchFunction)`
- [ ] `public K reduceKeys(long parallelismThreshold, BiFunction<? super K,? super K,? extends K> reducer)`
- [ ] `public <U> U reduceKeys(long parallelismThreshold, Function<? super K,? extends U> transformer, BiFunction<? super U,? super U,? extends U> reducer)`
- [ ] `public double reduceKeysToDouble(long parallelismThreshold, ToDoubleFunction<? super K> transformer, double basis, DoubleBinaryOperator reducer)`
- [ ] `public long reduceKeysToLong(long parallelismThreshold, ToLongFunction<? super K> transformer, long basis, LongBinaryOperator reducer)`
- [ ] `public int reduceKeysToInt(long parallelismThreshold, ToIntFunction<? super K> transformer, int basis, IntBinaryOperator reducer)`
- [ ] `public void forEachValue(long parallelismThreshold, Consumer<? super V> action)`
- [ ] `public <U> void forEachValue(long parallelismThreshold, Function<? super V,? extends U> transformer, Consumer<? super U> action)`
- [ ] `public <U> U searchValues(long parallelismThreshold, Function<? super V,? extends U> searchFunction)`
- [ ] `public V reduceValues(long parallelismThreshold, BiFunction<? super V,? super V,? extends V> reducer)`
- [ ] `public <U> U reduceValues(long parallelismThreshold, Function<? super V,? extends U> transformer, BiFunction<? super U,? super U,? extends U> reducer)`
- [ ] `public double reduceValuesToDouble(long parallelismThreshold, ToDoubleFunction<? super V> transformer, double basis, DoubleBinaryOperator reducer)`
- [ ] `public long reduceValuesToLong(long parallelismThreshold, ToLongFunction<? super V> transformer, long basis, LongBinaryOperator reducer)`
- [ ] `public int reduceValuesToInt(long parallelismThreshold, ToIntFunction<? super V> transformer, int basis, IntBinaryOperator reducer)`
- [ ] `public void forEachEntry(long parallelismThreshold, Consumer<? super Map.Entry<K,V>> action)`
- [ ] `public <U> void forEachEntry(long parallelismThreshold, Function<Map.Entry<K,V>,? extends U> transformer, Consumer<? super U> action)`
- [ ] `public <U> U searchEntries(long parallelismThreshold, Function<Map.Entry<K,V>,? extends U> searchFunction)`
- [ ] `public Map.Entry<K,V> reduceEntries(long parallelismThreshold, BiFunction<Map.Entry<K,V>,Map.Entry<K,V>,? extends Map.Entry<K,V>> reducer)`
- [ ] `public <U> U reduceEntries(long parallelismThreshold, Function<Map.Entry<K,V>,? extends U> transformer, BiFunction<? super U,? super U,? extends U> reducer)`
- [ ] `public double reduceEntriesToDouble(long parallelismThreshold, ToDoubleFunction<Map.Entry<K,V>> transformer, double basis, DoubleBinaryOperator reducer)`
- [ ] `public long reduceEntriesToLong(long parallelismThreshold, ToLongFunction<Map.Entry<K,V>> transformer, long basis, LongBinaryOperator reducer)`
- [ ] `public int reduceEntriesToInt(long parallelismThreshold, ToIntFunction<Map.Entry<K,V>> transformer, int basis, IntBinaryOperator reducer)`

### `java.util.concurrent.ConcurrentHashMap.KeySetView`

`public static class ConcurrentHashMap.KeySetView<K,V> extends Object implements Set<K>, Serializable`

- [ ] `public V getMappedValue()`
- [ ] `public boolean contains(Object o)`
- [ ] `public boolean remove(Object o)`
- [ ] `public Iterator<K> iterator()`
- [ ] `public boolean add(K e)`
- [ ] `public boolean addAll(Collection<? extends K> c)`
- [ ] `public int hashCode()`
- [ ] `public boolean equals(Object o)`
- [ ] `public Spliterator<K> spliterator()` (deferred)
- [ ] `public void forEach(Consumer<? super K> action)`
- [ ] `public ConcurrentHashMap<K,V> getMap()`
- [ ] `public final void clear()`
- [ ] `public final int size()`
- [ ] `public final boolean isEmpty()`
- [ ] `public final Object[] toArray()`
- [ ] `public final <T> T[] toArray(T[] a)`
- [ ] `public final String toString()`
- [ ] `public final boolean containsAll(Collection<?> c)`
- [ ] `public final boolean removeAll(Collection<?> c)`
- [ ] `public final boolean retainAll(Collection<?> c)`

### `java.util.concurrent.ConcurrentLinkedDeque`

`public class ConcurrentLinkedDeque<E> extends AbstractCollection<E> implements Deque<E>, Serializable`

- [ ] `public ConcurrentLinkedDeque()`
- [ ] `public ConcurrentLinkedDeque(Collection<? extends E> c)`
- [ ] `public void addFirst(E e)`
- [ ] `public void addLast(E e)`
- [ ] `public boolean offerFirst(E e)`
- [ ] `public boolean offerLast(E e)`
- [ ] `public E peekFirst()`
- [ ] `public E peekLast()`
- [ ] `public E getFirst()`
- [ ] `public E getLast()`
- [ ] `public E pollFirst()`
- [ ] `public E pollLast()`
- [ ] `public E removeFirst()`
- [ ] `public E removeLast()`
- [ ] `public boolean offer(E e)`
- [ ] `public boolean add(E e)`
- [ ] `public E poll()`
- [ ] `public E peek()`
- [ ] `public E remove()`
- [ ] `public E pop()`
- [ ] `public E element()`
- [ ] `public void push(E e)`
- [ ] `public boolean removeFirstOccurrence(Object o)`
- [ ] `public boolean removeLastOccurrence(Object o)`
- [ ] `public boolean contains(Object o)`
- [ ] `public boolean isEmpty()`
- [ ] `public int size()`
- [ ] `public boolean remove(Object o)`
- [ ] `public boolean addAll(Collection<? extends E> c)`
- [ ] `public void clear()`
- [ ] `public Object[] toArray()`
- [ ] `public <T> T[] toArray(T[] a)`
- [ ] `public Iterator<E> iterator()`
- [ ] `public Iterator<E> descendingIterator()`
- [ ] `public Spliterator<E> spliterator()` (deferred)

### `java.util.concurrent.ConcurrentLinkedQueue`

`public class ConcurrentLinkedQueue<E> extends AbstractQueue<E> implements Queue<E>, Serializable`

- [ ] `public ConcurrentLinkedQueue()`
- [ ] `public ConcurrentLinkedQueue(Collection<? extends E> c)`
- [ ] `public boolean add(E e)`
- [ ] `public boolean offer(E e)`
- [ ] `public E poll()`
- [ ] `public E peek()`
- [ ] `public boolean isEmpty()`
- [ ] `public int size()`
- [ ] `public boolean contains(Object o)`
- [ ] `public boolean remove(Object o)`
- [ ] `public boolean addAll(Collection<? extends E> c)`
- [ ] `public Object[] toArray()`
- [ ] `public <T> T[] toArray(T[] a)`
- [ ] `public Iterator<E> iterator()`
- [ ] `public Spliterator<E> spliterator()` (deferred)

### `java.util.concurrent.ConcurrentMap`

`public interface ConcurrentMap<K,V> extends Map<K,V>`

- [ ] `default V getOrDefault(Object key, V defaultValue)`
- [ ] `default void forEach(BiConsumer<? super K,? super V> action)`
- [ ] `V putIfAbsent(K key, V value)`
- [ ] `boolean remove(Object key, Object value)`
- [ ] `boolean replace(K key, V oldValue, V newValue)`
- [ ] `V replace(K key, V value)`
- [ ] `default void replaceAll(BiFunction<? super K,? super V,? extends V> function)`
- [ ] `default V computeIfAbsent(K key, Function<? super K,? extends V> mappingFunction)`
- [ ] `default V computeIfPresent(K key, BiFunction<? super K,? super V,? extends V> remappingFunction)`
- [ ] `default V compute(K key, BiFunction<? super K,? super V,? extends V> remappingFunction)`
- [ ] `default V merge(K key, V value, BiFunction<? super V,? super V,? extends V> remappingFunction)`

### `java.util.concurrent.ConcurrentNavigableMap`

`public interface ConcurrentNavigableMap<K,V> extends ConcurrentMap<K,V>, NavigableMap<K,V>`

- [ ] `ConcurrentNavigableMap<K,V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive)`
- [ ] `ConcurrentNavigableMap<K,V> headMap(K toKey, boolean inclusive)`
- [ ] `ConcurrentNavigableMap<K,V> tailMap(K fromKey, boolean inclusive)`
- [ ] `ConcurrentNavigableMap<K,V> subMap(K fromKey, K toKey)`
- [ ] `ConcurrentNavigableMap<K,V> headMap(K toKey)`
- [ ] `ConcurrentNavigableMap<K,V> tailMap(K fromKey)`
- [ ] `ConcurrentNavigableMap<K,V> descendingMap()`
- [ ] `NavigableSet<K> navigableKeySet()`
- [ ] `NavigableSet<K> keySet()`
- [ ] `NavigableSet<K> descendingKeySet()`

### `java.util.concurrent.ConcurrentSkipListMap`

`public class ConcurrentSkipListMap<K,V> extends AbstractMap<K,V> implements ConcurrentNavigableMap<K,V>, Cloneable, Serializable`

- [ ] `public ConcurrentSkipListMap()`
- [ ] `public ConcurrentSkipListMap(Comparator<? super K> comparator)`
- [ ] `public ConcurrentSkipListMap(Map<? extends K,? extends V> m)`
- [ ] `public ConcurrentSkipListMap(SortedMap<K,? extends V> m)`
- [ ] `public ConcurrentSkipListMap<K,V> clone()`
- [ ] `public boolean containsKey(Object key)`
- [ ] `public V get(Object key)`
- [ ] `public V getOrDefault(Object key, V defaultValue)`
- [ ] `public V put(K key, V value)`
- [ ] `public V remove(Object key)`
- [ ] `public boolean containsValue(Object value)`
- [ ] `public int size()`
- [ ] `public boolean isEmpty()`
- [ ] `public void clear()`
- [ ] `public V computeIfAbsent(K key, Function<? super K,? extends V> mappingFunction)`
- [ ] `public V computeIfPresent(K key, BiFunction<? super K,? super V,? extends V> remappingFunction)`
- [ ] `public V compute(K key, BiFunction<? super K,? super V,? extends V> remappingFunction)`
- [ ] `public V merge(K key, V value, BiFunction<? super V,? super V,? extends V> remappingFunction)`
- [ ] `public NavigableSet<K> keySet()`
- [ ] `public NavigableSet<K> navigableKeySet()`
- [ ] `public Collection<V> values()`
- [ ] `public Set<Map.Entry<K,V>> entrySet()`
- [ ] `public ConcurrentNavigableMap<K,V> descendingMap()`
- [ ] `public NavigableSet<K> descendingKeySet()`
- [ ] `public boolean equals(Object o)`
- [ ] `public V putIfAbsent(K key, V value)`
- [ ] `public boolean remove(Object key, Object value)`
- [ ] `public boolean replace(K key, V oldValue, V newValue)`
- [ ] `public V replace(K key, V value)`
- [ ] `public Comparator<? super K> comparator()`
- [ ] `public K firstKey()`
- [ ] `public K lastKey()`
- [ ] `public ConcurrentNavigableMap<K,V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive)`
- [ ] `public ConcurrentNavigableMap<K,V> headMap(K toKey, boolean inclusive)`
- [ ] `public ConcurrentNavigableMap<K,V> tailMap(K fromKey, boolean inclusive)`
- [ ] `public ConcurrentNavigableMap<K,V> subMap(K fromKey, K toKey)`
- [ ] `public ConcurrentNavigableMap<K,V> headMap(K toKey)`
- [ ] `public ConcurrentNavigableMap<K,V> tailMap(K fromKey)`
- [ ] `public Map.Entry<K,V> lowerEntry(K key)`
- [ ] `public K lowerKey(K key)`
- [ ] `public Map.Entry<K,V> floorEntry(K key)`
- [ ] `public K floorKey(K key)`
- [ ] `public Map.Entry<K,V> ceilingEntry(K key)`
- [ ] `public K ceilingKey(K key)`
- [ ] `public Map.Entry<K,V> higherEntry(K key)`
- [ ] `public K higherKey(K key)`
- [ ] `public Map.Entry<K,V> firstEntry()`
- [ ] `public Map.Entry<K,V> lastEntry()`
- [ ] `public Map.Entry<K,V> pollFirstEntry()`
- [ ] `public Map.Entry<K,V> pollLastEntry()`
- [ ] `public void forEach(BiConsumer<? super K,? super V> action)`
- [ ] `public void replaceAll(BiFunction<? super K,? super V,? extends V> function)`

### `java.util.concurrent.ConcurrentSkipListSet`

`public class ConcurrentSkipListSet<E> extends AbstractSet<E> implements NavigableSet<E>, Cloneable, Serializable`

- [ ] `public ConcurrentSkipListSet()`
- [ ] `public ConcurrentSkipListSet(Comparator<? super E> comparator)`
- [ ] `public ConcurrentSkipListSet(Collection<? extends E> c)`
- [ ] `public ConcurrentSkipListSet(SortedSet<E> s)`
- [ ] `public ConcurrentSkipListSet<E> clone()`
- [ ] `public int size()`
- [ ] `public boolean isEmpty()`
- [ ] `public boolean contains(Object o)`
- [ ] `public boolean add(E e)`
- [ ] `public boolean remove(Object o)`
- [ ] `public void clear()`
- [ ] `public Iterator<E> iterator()`
- [ ] `public Iterator<E> descendingIterator()`
- [ ] `public boolean equals(Object o)`
- [ ] `public boolean removeAll(Collection<?> c)`
- [ ] `public E lower(E e)`
- [ ] `public E floor(E e)`
- [ ] `public E ceiling(E e)`
- [ ] `public E higher(E e)`
- [ ] `public E pollFirst()`
- [ ] `public E pollLast()`
- [ ] `public Comparator<? super E> comparator()`
- [ ] `public E first()`
- [ ] `public E last()`
- [ ] `public NavigableSet<E> subSet(E fromElement, boolean fromInclusive, E toElement, boolean toInclusive)`
- [ ] `public NavigableSet<E> headSet(E toElement, boolean inclusive)`
- [ ] `public NavigableSet<E> tailSet(E fromElement, boolean inclusive)`
- [ ] `public NavigableSet<E> subSet(E fromElement, E toElement)`
- [ ] `public NavigableSet<E> headSet(E toElement)`
- [ ] `public NavigableSet<E> tailSet(E fromElement)`
- [ ] `public NavigableSet<E> descendingSet()`
- [ ] `public Spliterator<E> spliterator()` (deferred)

### `java.util.concurrent.CopyOnWriteArrayList`

`public class CopyOnWriteArrayList<E> extends Object implements List<E>, RandomAccess, Cloneable, Serializable`

- [ ] `public CopyOnWriteArrayList()`
- [ ] `public CopyOnWriteArrayList(Collection<? extends E> c)`
- [ ] `public CopyOnWriteArrayList(E[] toCopyIn)`
- [ ] `public int size()`
- [ ] `public boolean isEmpty()`
- [ ] `public boolean contains(Object o)`
- [ ] `public int indexOf(Object o)`
- [ ] `public int indexOf(E e, int index)`
- [ ] `public int lastIndexOf(Object o)`
- [ ] `public int lastIndexOf(E e, int index)`
- [ ] `public Object clone()`
- [ ] `public Object[] toArray()`
- [ ] `public <T> T[] toArray(T[] a)`
- [ ] `public E get(int index)`
- [ ] `public E set(int index, E element)`
- [ ] `public boolean add(E e)`
- [ ] `public void add(int index, E element)`
- [ ] `public E remove(int index)`
- [ ] `public boolean remove(Object o)`
- [ ] `public boolean addIfAbsent(E e)`
- [ ] `public boolean containsAll(Collection<?> c)`
- [ ] `public boolean removeAll(Collection<?> c)`
- [ ] `public boolean retainAll(Collection<?> c)`
- [ ] `public int addAllAbsent(Collection<? extends E> c)`
- [ ] `public void clear()`
- [ ] `public boolean addAll(Collection<? extends E> c)`
- [ ] `public boolean addAll(int index, Collection<? extends E> c)`
- [ ] `public void forEach(Consumer<? super E> action)`
- [ ] `public boolean removeIf(Predicate<? super E> filter)`
- [ ] `public void replaceAll(UnaryOperator<E> operator)`
- [ ] `public void sort(Comparator<? super E> c)`
- [ ] `public String toString()`
- [ ] `public boolean equals(Object o)`
- [ ] `public int hashCode()`
- [ ] `public Iterator<E> iterator()`
- [ ] `public ListIterator<E> listIterator()`
- [ ] `public ListIterator<E> listIterator(int index)`
- [ ] `public Spliterator<E> spliterator()` (deferred)
- [ ] `public List<E> subList(int fromIndex, int toIndex)`

### `java.util.concurrent.CopyOnWriteArraySet`

`public class CopyOnWriteArraySet<E> extends AbstractSet<E> implements Serializable`

- [ ] `public CopyOnWriteArraySet()`
- [ ] `public CopyOnWriteArraySet(Collection<? extends E> c)`
- [ ] `public int size()`
- [ ] `public boolean isEmpty()`
- [ ] `public boolean contains(Object o)`
- [ ] `public Object[] toArray()`
- [ ] `public <T> T[] toArray(T[] a)`
- [ ] `public void clear()`
- [ ] `public boolean remove(Object o)`
- [ ] `public boolean add(E e)`
- [ ] `public boolean containsAll(Collection<?> c)`
- [ ] `public boolean addAll(Collection<? extends E> c)`
- [ ] `public boolean removeAll(Collection<?> c)`
- [ ] `public boolean retainAll(Collection<?> c)`
- [ ] `public Iterator<E> iterator()`
- [ ] `public boolean equals(Object o)`
- [ ] `public boolean removeIf(Predicate<? super E> filter)`
- [ ] `public void forEach(Consumer<? super E> action)`
- [ ] `public Spliterator<E> spliterator()` (deferred)

### `java.util.concurrent.CountDownLatch`

`public class CountDownLatch extends Object`

- [ ] `public CountDownLatch(int count)`
- [ ] `public void await() throws InterruptedException`
- [ ] `public boolean await(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public void countDown()`
- [ ] `public long getCount()`
- [ ] `public String toString()`

### `java.util.concurrent.CyclicBarrier`

`public class CyclicBarrier extends Object`

- [ ] `public CyclicBarrier(int parties, Runnable barrierAction)`
- [ ] `public CyclicBarrier(int parties)`
- [ ] `public int getParties()`
- [ ] `public int await() throws InterruptedException, BrokenBarrierException`
- [ ] `public int await(long timeout, TimeUnit unit) throws InterruptedException, BrokenBarrierException, TimeoutException`
- [ ] `public boolean isBroken()`
- [ ] `public void reset()`
- [ ] `public int getNumberWaiting()`

### `java.util.concurrent.DelayQueue`

`public class DelayQueue<E extends Delayed> extends AbstractQueue<E> implements BlockingQueue<E>`

- [ ] `public DelayQueue()`
- [ ] `public DelayQueue(Collection<? extends E> c)`
- [ ] `public boolean add(E e)`
- [ ] `public boolean offer(E e)`
- [ ] `public void put(E e)`
- [ ] `public boolean offer(E e, long timeout, TimeUnit unit)`
- [ ] `public E poll()`
- [ ] `public E take() throws InterruptedException`
- [ ] `public E poll(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E peek()`
- [ ] `public int size()`
- [ ] `public int drainTo(Collection<? super E> c)`
- [ ] `public int drainTo(Collection<? super E> c, int maxElements)`
- [ ] `public void clear()`
- [ ] `public int remainingCapacity()`
- [ ] `public Object[] toArray()`
- [ ] `public <T> T[] toArray(T[] a)`
- [ ] `public boolean remove(Object o)`
- [ ] `public Iterator<E> iterator()`

### `java.util.concurrent.Delayed`

`public interface Delayed extends Comparable<Delayed>`

- [ ] `long getDelay(TimeUnit unit)`

### `java.util.concurrent.Exchanger`

`public class Exchanger<V> extends Object`

- [ ] `public Exchanger()`
- [ ] `public V exchange(V x) throws InterruptedException`
- [ ] `public V exchange(V x, long timeout, TimeUnit unit) throws InterruptedException, TimeoutException`

### `java.util.concurrent.ExecutionException`

`public class ExecutionException extends Exception`

- [ ] `protected ExecutionException()`
- [ ] `protected ExecutionException(String message)`
- [ ] `public ExecutionException(String message, Throwable cause)`
- [ ] `public ExecutionException(Throwable cause)`

### `java.util.concurrent.Executor`

`public interface Executor`

- [ ] `void execute(Runnable command)`

### `java.util.concurrent.ExecutorCompletionService`

`public class ExecutorCompletionService<V> extends Object implements CompletionService<V>`

- [ ] `public ExecutorCompletionService(Executor executor)`
- [ ] `public ExecutorCompletionService(Executor executor, BlockingQueue<Future<V>> completionQueue)`
- [ ] `public Future<V> submit(Callable<V> task)`
- [ ] `public Future<V> submit(Runnable task, V result)`
- [ ] `public Future<V> take() throws InterruptedException`
- [ ] `public Future<V> poll()`
- [ ] `public Future<V> poll(long timeout, TimeUnit unit) throws InterruptedException`

### `java.util.concurrent.ExecutorService`

`public interface ExecutorService extends Executor`

- [ ] `void shutdown()`
- [ ] `List<Runnable> shutdownNow()`
- [ ] `boolean isShutdown()`
- [ ] `boolean isTerminated()`
- [ ] `boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `<T> Future<T> submit(Callable<T> task)`
- [ ] `<T> Future<T> submit(Runnable task, T result)`
- [ ] `Future<?> submit(Runnable task)`
- [ ] `<T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException`
- [ ] `<T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `<T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException`
- [ ] `<T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException`

### `java.util.concurrent.Executors`

`public class Executors extends Object`

- [ ] `public static ExecutorService newFixedThreadPool(int nThreads)`
- [ ] `public static ExecutorService newWorkStealingPool(int parallelism)`
- [ ] `public static ExecutorService newWorkStealingPool()`
- [ ] `public static ExecutorService newFixedThreadPool(int nThreads, ThreadFactory threadFactory)`
- [ ] `public static ExecutorService newSingleThreadExecutor()`
- [ ] `public static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory)`
- [ ] `public static ExecutorService newCachedThreadPool()`
- [ ] `public static ExecutorService newCachedThreadPool(ThreadFactory threadFactory)`
- [ ] `public static ScheduledExecutorService newSingleThreadScheduledExecutor()`
- [ ] `public static ScheduledExecutorService newSingleThreadScheduledExecutor(ThreadFactory threadFactory)`
- [ ] `public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize)`
- [ ] `public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize, ThreadFactory threadFactory)`
- [ ] `public static ExecutorService unconfigurableExecutorService(ExecutorService executor)`
- [ ] `public static ScheduledExecutorService unconfigurableScheduledExecutorService(ScheduledExecutorService executor)`
- [ ] `public static ThreadFactory defaultThreadFactory()`
- [ ] `public static ThreadFactory privilegedThreadFactory()`
- [ ] `public static <T> Callable<T> callable(Runnable task, T result)`
- [ ] `public static Callable<Object> callable(Runnable task)`
- [ ] `public static Callable<Object> callable(PrivilegedAction<?> action)` (deferred)
- [ ] `public static Callable<Object> callable(PrivilegedExceptionAction<?> action)` (deferred)
- [ ] `public static <T> Callable<T> privilegedCallable(Callable<T> callable)`
- [ ] `public static <T> Callable<T> privilegedCallableUsingCurrentClassLoader(Callable<T> callable)`

### `java.util.concurrent.ForkJoinPool`

`public class ForkJoinPool extends AbstractExecutorService`

- [ ] `public static final ForkJoinPool.ForkJoinWorkerThreadFactory defaultForkJoinWorkerThreadFactory`
- [ ] `public ForkJoinPool()`
- [ ] `public ForkJoinPool(int parallelism)`
- [ ] `public ForkJoinPool(int parallelism, ForkJoinPool.ForkJoinWorkerThreadFactory factory, Thread.UncaughtExceptionHandler handler, boolean asyncMode)`
- [ ] `public static ForkJoinPool commonPool()`
- [ ] `public <T> T invoke(ForkJoinTask<T> task)`
- [ ] `public void execute(ForkJoinTask<?> task)`
- [ ] `public void execute(Runnable task)`
- [ ] `public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task)`
- [ ] `public <T> ForkJoinTask<T> submit(Callable<T> task)`
- [ ] `public <T> ForkJoinTask<T> submit(Runnable task, T result)`
- [ ] `public ForkJoinTask<?> submit(Runnable task)`
- [ ] `public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)`
- [ ] `public ForkJoinPool.ForkJoinWorkerThreadFactory getFactory()`
- [ ] `public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler()`
- [ ] `public int getParallelism()`
- [ ] `public static int getCommonPoolParallelism()`
- [ ] `public int getPoolSize()`
- [ ] `public boolean getAsyncMode()`
- [ ] `public int getRunningThreadCount()`
- [ ] `public int getActiveThreadCount()`
- [ ] `public boolean isQuiescent()`
- [ ] `public long getStealCount()`
- [ ] `public long getQueuedTaskCount()`
- [ ] `public int getQueuedSubmissionCount()`
- [ ] `public boolean hasQueuedSubmissions()`
- [ ] `protected ForkJoinTask<?> pollSubmission()`
- [ ] `protected int drainTasksTo(Collection<? super ForkJoinTask<?>> c)`
- [ ] `public String toString()`
- [ ] `public void shutdown()`
- [ ] `public List<Runnable> shutdownNow()`
- [ ] `public boolean isTerminated()`
- [ ] `public boolean isTerminating()`
- [ ] `public boolean isShutdown()`
- [ ] `public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public boolean awaitQuiescence(long timeout, TimeUnit unit)`
- [ ] `public static void managedBlock(ForkJoinPool.ManagedBlocker blocker) throws InterruptedException`
- [ ] `protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value)`
- [ ] `protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable)`

### `java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory`

`public static interface ForkJoinPool.ForkJoinWorkerThreadFactory`

- [ ] `ForkJoinWorkerThread newThread(ForkJoinPool pool)`

### `java.util.concurrent.ForkJoinPool.ManagedBlocker`

`public static interface ForkJoinPool.ManagedBlocker`

- [ ] `boolean block() throws InterruptedException`
- [ ] `boolean isReleasable()`

### `java.util.concurrent.ForkJoinTask`

`public abstract class ForkJoinTask<V> extends Object implements Future<V>, Serializable`

- [ ] `public ForkJoinTask()`
- [ ] `public final ForkJoinTask<V> fork()`
- [ ] `public final V join()`
- [ ] `public final V invoke()`
- [ ] `public static void invokeAll(ForkJoinTask<?> t1, ForkJoinTask<?> t2)`
- [ ] `public static void invokeAll(ForkJoinTask<?>... tasks)`
- [ ] `public static <T extends ForkJoinTask<?>> Collection<T> invokeAll(Collection<T> tasks)`
- [ ] `public boolean cancel(boolean mayInterruptIfRunning)`
- [ ] `public final boolean isDone()`
- [ ] `public final boolean isCancelled()`
- [ ] `public final boolean isCompletedAbnormally()`
- [ ] `public final boolean isCompletedNormally()`
- [ ] `public final Throwable getException()`
- [ ] `public void completeExceptionally(Throwable ex)`
- [ ] `public void complete(V value)`
- [ ] `public final void quietlyComplete()`
- [ ] `public final V get() throws InterruptedException, ExecutionException`
- [ ] `public final V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException`
- [ ] `public final void quietlyJoin()`
- [ ] `public final void quietlyInvoke()`
- [ ] `public static void helpQuiesce()`
- [ ] `public void reinitialize()`
- [ ] `public static ForkJoinPool getPool()`
- [ ] `public static boolean inForkJoinPool()`
- [ ] `public boolean tryUnfork()`
- [ ] `public static int getQueuedTaskCount()`
- [ ] `public static int getSurplusQueuedTaskCount()`
- [ ] `public abstract V getRawResult()`
- [ ] `protected abstract void setRawResult(V value)`
- [ ] `protected abstract boolean exec()`
- [ ] `protected static ForkJoinTask<?> peekNextLocalTask()`
- [ ] `protected static ForkJoinTask<?> pollNextLocalTask()`
- [ ] `protected static ForkJoinTask<?> pollTask()`
- [ ] `public final short getForkJoinTaskTag()`
- [ ] `public final short setForkJoinTaskTag(short tag)`
- [ ] `public final boolean compareAndSetForkJoinTaskTag(short e, short tag)`
- [ ] `public static ForkJoinTask<?> adapt(Runnable runnable)`
- [ ] `public static <T> ForkJoinTask<T> adapt(Runnable runnable, T result)`
- [ ] `public static <T> ForkJoinTask<T> adapt(Callable<? extends T> callable)`

### `java.util.concurrent.ForkJoinWorkerThread`

`public class ForkJoinWorkerThread extends Thread`

- [ ] `protected ForkJoinWorkerThread(ForkJoinPool pool)`
- [ ] `public ForkJoinPool getPool()`
- [ ] `public int getPoolIndex()`
- [ ] `protected void onStart()`
- [ ] `protected void onTermination(Throwable exception)`
- [ ] `public void run()`

### `java.util.concurrent.Future`

`public interface Future<V>`

- [ ] `boolean cancel(boolean mayInterruptIfRunning)`
- [ ] `boolean isCancelled()`
- [ ] `boolean isDone()`
- [ ] `V get() throws InterruptedException, ExecutionException`
- [ ] `V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException`

### `java.util.concurrent.FutureTask`

`public class FutureTask<V> extends Object implements RunnableFuture<V>`

- [ ] `public FutureTask(Callable<V> callable)`
- [ ] `public FutureTask(Runnable runnable, V result)`
- [ ] `public boolean isCancelled()`
- [ ] `public boolean isDone()`
- [ ] `public boolean cancel(boolean mayInterruptIfRunning)`
- [ ] `public V get() throws InterruptedException, ExecutionException`
- [ ] `public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException`
- [ ] `protected void done()`
- [ ] `protected void set(V v)`
- [ ] `protected void setException(Throwable t)`
- [ ] `public void run()`
- [ ] `protected boolean runAndReset()`

### `java.util.concurrent.LinkedBlockingDeque`

`public class LinkedBlockingDeque<E> extends AbstractQueue<E> implements BlockingDeque<E>, Serializable`

- [ ] `public LinkedBlockingDeque()`
- [ ] `public LinkedBlockingDeque(int capacity)`
- [ ] `public LinkedBlockingDeque(Collection<? extends E> c)`
- [ ] `public void addFirst(E e)`
- [ ] `public void addLast(E e)`
- [ ] `public boolean offerFirst(E e)`
- [ ] `public boolean offerLast(E e)`
- [ ] `public void putFirst(E e) throws InterruptedException`
- [ ] `public void putLast(E e) throws InterruptedException`
- [ ] `public boolean offerFirst(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public boolean offerLast(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E removeFirst()`
- [ ] `public E removeLast()`
- [ ] `public E pollFirst()`
- [ ] `public E pollLast()`
- [ ] `public E takeFirst() throws InterruptedException`
- [ ] `public E takeLast() throws InterruptedException`
- [ ] `public E pollFirst(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E pollLast(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E getFirst()`
- [ ] `public E getLast()`
- [ ] `public E peekFirst()`
- [ ] `public E peekLast()`
- [ ] `public boolean removeFirstOccurrence(Object o)`
- [ ] `public boolean removeLastOccurrence(Object o)`
- [ ] `public boolean add(E e)`
- [ ] `public boolean offer(E e)`
- [ ] `public void put(E e) throws InterruptedException`
- [ ] `public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E remove()`
- [ ] `public E poll()`
- [ ] `public E take() throws InterruptedException`
- [ ] `public E poll(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E element()`
- [ ] `public E peek()`
- [ ] `public int remainingCapacity()`
- [ ] `public int drainTo(Collection<? super E> c)`
- [ ] `public int drainTo(Collection<? super E> c, int maxElements)`
- [ ] `public void push(E e)`
- [ ] `public E pop()`
- [ ] `public boolean remove(Object o)`
- [ ] `public int size()`
- [ ] `public boolean contains(Object o)`
- [ ] `public Object[] toArray()`
- [ ] `public <T> T[] toArray(T[] a)`
- [ ] `public String toString()`
- [ ] `public void clear()`
- [ ] `public Iterator<E> iterator()`
- [ ] `public Iterator<E> descendingIterator()`
- [ ] `public Spliterator<E> spliterator()` (deferred)

### `java.util.concurrent.LinkedBlockingQueue`

`public class LinkedBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, Serializable`

- [ ] `public LinkedBlockingQueue()`
- [ ] `public LinkedBlockingQueue(int capacity)`
- [ ] `public LinkedBlockingQueue(Collection<? extends E> c)`
- [ ] `public int size()`
- [ ] `public int remainingCapacity()`
- [ ] `public void put(E e) throws InterruptedException`
- [ ] `public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public boolean offer(E e)`
- [ ] `public E take() throws InterruptedException`
- [ ] `public E poll(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E poll()`
- [ ] `public E peek()`
- [ ] `public boolean remove(Object o)`
- [ ] `public boolean contains(Object o)`
- [ ] `public Object[] toArray()`
- [ ] `public <T> T[] toArray(T[] a)`
- [ ] `public String toString()`
- [ ] `public void clear()`
- [ ] `public int drainTo(Collection<? super E> c)`
- [ ] `public int drainTo(Collection<? super E> c, int maxElements)`
- [ ] `public Iterator<E> iterator()`
- [ ] `public Spliterator<E> spliterator()` (deferred)

### `java.util.concurrent.LinkedTransferQueue`

`public class LinkedTransferQueue<E> extends AbstractQueue<E> implements TransferQueue<E>, Serializable`

- [ ] `public LinkedTransferQueue()`
- [ ] `public LinkedTransferQueue(Collection<? extends E> c)`
- [ ] `public Spliterator<E> spliterator()` (deferred)
- [ ] `public void put(E e)`
- [ ] `public boolean offer(E e, long timeout, TimeUnit unit)`
- [ ] `public boolean offer(E e)`
- [ ] `public boolean add(E e)`
- [ ] `public boolean tryTransfer(E e)`
- [ ] `public void transfer(E e) throws InterruptedException`
- [ ] `public boolean tryTransfer(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E take() throws InterruptedException`
- [ ] `public E poll(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E poll()`
- [ ] `public int drainTo(Collection<? super E> c)`
- [ ] `public int drainTo(Collection<? super E> c, int maxElements)`
- [ ] `public Iterator<E> iterator()`
- [ ] `public E peek()`
- [ ] `public boolean isEmpty()`
- [ ] `public boolean hasWaitingConsumer()`
- [ ] `public int size()`
- [ ] `public int getWaitingConsumerCount()`
- [ ] `public boolean remove(Object o)`
- [ ] `public boolean contains(Object o)`
- [ ] `public int remainingCapacity()`

### `java.util.concurrent.Phaser`

`public class Phaser extends Object`

- [ ] `public Phaser()`
- [ ] `public Phaser(int parties)`
- [ ] `public Phaser(Phaser parent)`
- [ ] `public Phaser(Phaser parent, int parties)`
- [ ] `public int register()`
- [ ] `public int bulkRegister(int parties)`
- [ ] `public int arrive()`
- [ ] `public int arriveAndDeregister()`
- [ ] `public int arriveAndAwaitAdvance()`
- [ ] `public int awaitAdvance(int phase)`
- [ ] `public int awaitAdvanceInterruptibly(int phase) throws InterruptedException`
- [ ] `public int awaitAdvanceInterruptibly(int phase, long timeout, TimeUnit unit) throws InterruptedException, TimeoutException`
- [ ] `public void forceTermination()`
- [ ] `public final int getPhase()`
- [ ] `public int getRegisteredParties()`
- [ ] `public int getArrivedParties()`
- [ ] `public int getUnarrivedParties()`
- [ ] `public Phaser getParent()`
- [ ] `public Phaser getRoot()`
- [ ] `public boolean isTerminated()`
- [ ] `protected boolean onAdvance(int phase, int registeredParties)`
- [ ] `public String toString()`

### `java.util.concurrent.PriorityBlockingQueue`

`public class PriorityBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, Serializable`

- [ ] `public PriorityBlockingQueue()`
- [ ] `public PriorityBlockingQueue(int initialCapacity)`
- [ ] `public PriorityBlockingQueue(int initialCapacity, Comparator<? super E> comparator)`
- [ ] `public PriorityBlockingQueue(Collection<? extends E> c)`
- [ ] `public boolean add(E e)`
- [ ] `public boolean offer(E e)`
- [ ] `public void put(E e)`
- [ ] `public boolean offer(E e, long timeout, TimeUnit unit)`
- [ ] `public E poll()`
- [ ] `public E take() throws InterruptedException`
- [ ] `public E poll(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E peek()`
- [ ] `public Comparator<? super E> comparator()`
- [ ] `public int size()`
- [ ] `public int remainingCapacity()`
- [ ] `public boolean remove(Object o)`
- [ ] `public boolean contains(Object o)`
- [ ] `public Object[] toArray()`
- [ ] `public String toString()`
- [ ] `public int drainTo(Collection<? super E> c)`
- [ ] `public int drainTo(Collection<? super E> c, int maxElements)`
- [ ] `public void clear()`
- [ ] `public <T> T[] toArray(T[] a)`
- [ ] `public Iterator<E> iterator()`
- [ ] `public Spliterator<E> spliterator()` (deferred)

### `java.util.concurrent.RecursiveAction`

`public abstract class RecursiveAction extends ForkJoinTask<Void>`

- [ ] `public RecursiveAction()`
- [ ] `protected abstract void compute()`
- [ ] `public final Void getRawResult()`
- [ ] `protected final void setRawResult(Void mustBeNull)`
- [ ] `protected final boolean exec()`

### `java.util.concurrent.RecursiveTask`

`public abstract class RecursiveTask<V> extends ForkJoinTask<V>`

- [ ] `public RecursiveTask()`
- [ ] `protected abstract V compute()`
- [ ] `public final V getRawResult()`
- [ ] `protected final void setRawResult(V value)`
- [ ] `protected final boolean exec()`

### `java.util.concurrent.RejectedExecutionException`

`public class RejectedExecutionException extends RuntimeException`

- [ ] `public RejectedExecutionException()`
- [ ] `public RejectedExecutionException(String message)`
- [ ] `public RejectedExecutionException(String message, Throwable cause)`
- [ ] `public RejectedExecutionException(Throwable cause)`

### `java.util.concurrent.RejectedExecutionHandler`

`public interface RejectedExecutionHandler`

- [ ] `void rejectedExecution(Runnable r, ThreadPoolExecutor executor)`

### `java.util.concurrent.RunnableFuture`

`public interface RunnableFuture<V> extends Runnable, Future<V>`

- [ ] `void run()`

### `java.util.concurrent.RunnableScheduledFuture`

`public interface RunnableScheduledFuture<V> extends RunnableFuture<V>, ScheduledFuture<V>`

- [ ] `boolean isPeriodic()`

### `java.util.concurrent.ScheduledExecutorService`

`public interface ScheduledExecutorService extends ExecutorService`

- [ ] `ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)`
- [ ] `<V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit)`
- [ ] `ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)`
- [ ] `ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)`

### `java.util.concurrent.ScheduledFuture`

`public interface ScheduledFuture<V> extends Delayed, Future<V>`

(no members of its own)

### `java.util.concurrent.ScheduledThreadPoolExecutor`

`public class ScheduledThreadPoolExecutor extends ThreadPoolExecutor implements ScheduledExecutorService`

- [ ] `public ScheduledThreadPoolExecutor(int corePoolSize)`
- [ ] `public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory)`
- [ ] `public ScheduledThreadPoolExecutor(int corePoolSize, RejectedExecutionHandler handler)`
- [ ] `public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory, RejectedExecutionHandler handler)`
- [ ] `protected <V> RunnableScheduledFuture<V> decorateTask(Runnable runnable, RunnableScheduledFuture<V> task)`
- [ ] `protected <V> RunnableScheduledFuture<V> decorateTask(Callable<V> callable, RunnableScheduledFuture<V> task)`
- [ ] `public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)`
- [ ] `public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit)`
- [ ] `public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)`
- [ ] `public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)`
- [ ] `public void execute(Runnable command)`
- [ ] `public Future<?> submit(Runnable task)`
- [ ] `public <T> Future<T> submit(Runnable task, T result)`
- [ ] `public <T> Future<T> submit(Callable<T> task)`
- [ ] `public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value)`
- [ ] `public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy()`
- [ ] `public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value)`
- [ ] `public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy()`
- [ ] `public void setRemoveOnCancelPolicy(boolean value)`
- [ ] `public boolean getRemoveOnCancelPolicy()`
- [ ] `public void shutdown()`
- [ ] `public List<Runnable> shutdownNow()`
- [ ] `public BlockingQueue<Runnable> getQueue()`

### `java.util.concurrent.Semaphore`

`public class Semaphore extends Object implements Serializable`

- [ ] `public Semaphore(int permits)`
- [ ] `public Semaphore(int permits, boolean fair)`
- [ ] `public void acquire() throws InterruptedException`
- [ ] `public void acquireUninterruptibly()`
- [ ] `public boolean tryAcquire()`
- [ ] `public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public void release()`
- [ ] `public void acquire(int permits) throws InterruptedException`
- [ ] `public void acquireUninterruptibly(int permits)`
- [ ] `public boolean tryAcquire(int permits)`
- [ ] `public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public void release(int permits)`
- [ ] `public int availablePermits()`
- [ ] `public int drainPermits()`
- [ ] `protected void reducePermits(int reduction)`
- [ ] `public boolean isFair()`
- [ ] `public final boolean hasQueuedThreads()`
- [ ] `public final int getQueueLength()`
- [ ] `protected Collection<Thread> getQueuedThreads()`
- [ ] `public String toString()`

### `java.util.concurrent.SynchronousQueue`

`public class SynchronousQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, Serializable`

- [ ] `public SynchronousQueue()`
- [ ] `public SynchronousQueue(boolean fair)`
- [ ] `public void put(E e) throws InterruptedException`
- [ ] `public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public boolean offer(E e)`
- [ ] `public E take() throws InterruptedException`
- [ ] `public E poll(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public E poll()`
- [ ] `public boolean isEmpty()`
- [ ] `public int size()`
- [ ] `public int remainingCapacity()`
- [ ] `public void clear()`
- [ ] `public boolean contains(Object o)`
- [ ] `public boolean remove(Object o)`
- [ ] `public boolean containsAll(Collection<?> c)`
- [ ] `public boolean removeAll(Collection<?> c)`
- [ ] `public boolean retainAll(Collection<?> c)`
- [ ] `public E peek()`
- [ ] `public Iterator<E> iterator()`
- [ ] `public Spliterator<E> spliterator()` (deferred)
- [ ] `public Object[] toArray()`
- [ ] `public <T> T[] toArray(T[] a)`
- [ ] `public int drainTo(Collection<? super E> c)`
- [ ] `public int drainTo(Collection<? super E> c, int maxElements)`

### `java.util.concurrent.ThreadFactory`

`public interface ThreadFactory`

- [ ] `Thread newThread(Runnable r)`

### `java.util.concurrent.ThreadLocalRandom`

`public class ThreadLocalRandom extends Random`

- [ ] `public static ThreadLocalRandom current()`
- [ ] `public void setSeed(long seed)`
- [ ] `protected int next(int bits)`
- [ ] `public int nextInt()`
- [ ] `public int nextInt(int bound)`
- [ ] `public int nextInt(int origin, int bound)`
- [ ] `public long nextLong()`
- [ ] `public long nextLong(long bound)`
- [ ] `public long nextLong(long origin, long bound)`
- [ ] `public double nextDouble()`
- [ ] `public double nextDouble(double bound)`
- [ ] `public double nextDouble(double origin, double bound)`
- [ ] `public boolean nextBoolean()`
- [ ] `public float nextFloat()`
- [ ] `public double nextGaussian()`
- [ ] `public IntStream ints(long streamSize)` (deferred)
- [ ] `public IntStream ints()` (deferred)
- [ ] `public IntStream ints(long streamSize, int randomNumberOrigin, int randomNumberBound)` (deferred)
- [ ] `public IntStream ints(int randomNumberOrigin, int randomNumberBound)` (deferred)
- [ ] `public LongStream longs(long streamSize)` (deferred)
- [ ] `public LongStream longs()` (deferred)
- [ ] `public LongStream longs(long streamSize, long randomNumberOrigin, long randomNumberBound)` (deferred)
- [ ] `public LongStream longs(long randomNumberOrigin, long randomNumberBound)` (deferred)
- [ ] `public DoubleStream doubles(long streamSize)` (deferred)
- [ ] `public DoubleStream doubles()` (deferred)
- [ ] `public DoubleStream doubles(long streamSize, double randomNumberOrigin, double randomNumberBound)` (deferred)
- [ ] `public DoubleStream doubles(double randomNumberOrigin, double randomNumberBound)` (deferred)

### `java.util.concurrent.ThreadPoolExecutor`

`public class ThreadPoolExecutor extends AbstractExecutorService`

- [ ] `public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue)`
- [ ] `public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory)`
- [ ] `public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler)`
- [ ] `public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory, RejectedExecutionHandler handler)`
- [ ] `public void execute(Runnable command)`
- [ ] `public void shutdown()`
- [ ] `public List<Runnable> shutdownNow()`
- [ ] `public boolean isShutdown()`
- [ ] `public boolean isTerminating()`
- [ ] `public boolean isTerminated()`
- [ ] `public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `protected void finalize()`
- [ ] `public void setThreadFactory(ThreadFactory threadFactory)`
- [ ] `public ThreadFactory getThreadFactory()`
- [ ] `public void setRejectedExecutionHandler(RejectedExecutionHandler handler)`
- [ ] `public RejectedExecutionHandler getRejectedExecutionHandler()`
- [ ] `public void setCorePoolSize(int corePoolSize)`
- [ ] `public int getCorePoolSize()`
- [ ] `public boolean prestartCoreThread()`
- [ ] `public int prestartAllCoreThreads()`
- [ ] `public boolean allowsCoreThreadTimeOut()`
- [ ] `public void allowCoreThreadTimeOut(boolean value)`
- [ ] `public void setMaximumPoolSize(int maximumPoolSize)`
- [ ] `public int getMaximumPoolSize()`
- [ ] `public void setKeepAliveTime(long time, TimeUnit unit)`
- [ ] `public long getKeepAliveTime(TimeUnit unit)`
- [ ] `public BlockingQueue<Runnable> getQueue()`
- [ ] `public boolean remove(Runnable task)`
- [ ] `public void purge()`
- [ ] `public int getPoolSize()`
- [ ] `public int getActiveCount()`
- [ ] `public int getLargestPoolSize()`
- [ ] `public long getTaskCount()`
- [ ] `public long getCompletedTaskCount()`
- [ ] `public String toString()`
- [ ] `protected void beforeExecute(Thread t, Runnable r)`
- [ ] `protected void afterExecute(Runnable r, Throwable t)`
- [ ] `protected void terminated()`

### `java.util.concurrent.ThreadPoolExecutor.AbortPolicy`

`public static class ThreadPoolExecutor.AbortPolicy extends Object implements RejectedExecutionHandler`

- [ ] `public AbortPolicy()`
- [ ] `public void rejectedExecution(Runnable r, ThreadPoolExecutor e)`

### `java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy`

`public static class ThreadPoolExecutor.CallerRunsPolicy extends Object implements RejectedExecutionHandler`

- [ ] `public CallerRunsPolicy()`
- [ ] `public void rejectedExecution(Runnable r, ThreadPoolExecutor e)`

### `java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy`

`public static class ThreadPoolExecutor.DiscardOldestPolicy extends Object implements RejectedExecutionHandler`

- [ ] `public DiscardOldestPolicy()`
- [ ] `public void rejectedExecution(Runnable r, ThreadPoolExecutor e)`

### `java.util.concurrent.ThreadPoolExecutor.DiscardPolicy`

`public static class ThreadPoolExecutor.DiscardPolicy extends Object implements RejectedExecutionHandler`

- [ ] `public DiscardPolicy()`
- [ ] `public void rejectedExecution(Runnable r, ThreadPoolExecutor e)`

### `java.util.concurrent.TimeUnit`

`public enum TimeUnit extends Enum<TimeUnit>`

- [ ] `public static final TimeUnit NANOSECONDS`
- [ ] `public static final TimeUnit MICROSECONDS`
- [ ] `public static final TimeUnit MILLISECONDS`
- [ ] `public static final TimeUnit SECONDS`
- [ ] `public static final TimeUnit MINUTES`
- [ ] `public static final TimeUnit HOURS`
- [ ] `public static final TimeUnit DAYS`
- [ ] `public static TimeUnit[] values()`
- [ ] `public static TimeUnit valueOf(String name)`
- [ ] `public long convert(long sourceDuration, TimeUnit sourceUnit)`
- [ ] `public long toNanos(long duration)`
- [ ] `public long toMicros(long duration)`
- [ ] `public long toMillis(long duration)`
- [ ] `public long toSeconds(long duration)`
- [ ] `public long toMinutes(long duration)`
- [ ] `public long toHours(long duration)`
- [ ] `public long toDays(long duration)`
- [ ] `public void timedWait(Object obj, long timeout) throws InterruptedException`
- [ ] `public void timedJoin(Thread thread, long timeout) throws InterruptedException`
- [ ] `public void sleep(long timeout) throws InterruptedException`

### `java.util.concurrent.TimeoutException`

`public class TimeoutException extends Exception`

- [ ] `public TimeoutException()`
- [ ] `public TimeoutException(String message)`

### `java.util.concurrent.TransferQueue`

`public interface TransferQueue<E> extends BlockingQueue<E>`

- [ ] `boolean tryTransfer(E e)`
- [ ] `void transfer(E e) throws InterruptedException`
- [ ] `boolean tryTransfer(E e, long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `boolean hasWaitingConsumer()`
- [ ] `int getWaitingConsumerCount()`

### `java.util.concurrent.locks.Condition`

`public interface Condition`

- [ ] `void await() throws InterruptedException`
- [ ] `void awaitUninterruptibly()`
- [ ] `long awaitNanos(long nanosTimeout) throws InterruptedException`
- [ ] `boolean await(long time, TimeUnit unit) throws InterruptedException`
- [ ] `boolean awaitUntil(Date deadline) throws InterruptedException`
- [ ] `void signal()`
- [ ] `void signalAll()`

### `java.util.concurrent.locks.Lock`

`public interface Lock`

- [ ] `void lock()`
- [ ] `void lockInterruptibly() throws InterruptedException`
- [ ] `boolean tryLock()`
- [ ] `boolean tryLock(long time, TimeUnit unit) throws InterruptedException`
- [ ] `void unlock()`
- [ ] `Condition newCondition()`

### `java.util.concurrent.locks.LockSupport`

`public class LockSupport extends Object`

- [ ] `public static void unpark(Thread thread)`
- [ ] `public static void park(Object blocker)`
- [ ] `public static void parkNanos(Object blocker, long nanos)`
- [ ] `public static void parkUntil(Object blocker, long deadline)`
- [ ] `public static Object getBlocker(Thread t)`
- [ ] `public static void park()`
- [ ] `public static void parkNanos(long nanos)`
- [ ] `public static void parkUntil(long deadline)`

### `java.util.concurrent.locks.ReadWriteLock`

`public interface ReadWriteLock`

- [ ] `Lock readLock()`
- [ ] `Lock writeLock()`

### `java.util.concurrent.locks.ReentrantLock`

`public class ReentrantLock extends Object implements Lock, Serializable`

- [ ] `public ReentrantLock()`
- [ ] `public ReentrantLock(boolean fair)`
- [ ] `public void lock()`
- [ ] `public void lockInterruptibly() throws InterruptedException`
- [ ] `public boolean tryLock()`
- [ ] `public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public void unlock()`
- [ ] `public Condition newCondition()`
- [ ] `public int getHoldCount()`
- [ ] `public boolean isHeldByCurrentThread()`
- [ ] `public boolean isLocked()`
- [ ] `public final boolean isFair()`
- [ ] `protected Thread getOwner()`
- [ ] `public final boolean hasQueuedThreads()`
- [ ] `public final boolean hasQueuedThread(Thread thread)`
- [ ] `public final int getQueueLength()`
- [ ] `protected Collection<Thread> getQueuedThreads()`
- [ ] `public boolean hasWaiters(Condition condition)`
- [ ] `public int getWaitQueueLength(Condition condition)`
- [ ] `protected Collection<Thread> getWaitingThreads(Condition condition)`
- [ ] `public String toString()`

### `java.util.concurrent.locks.ReentrantReadWriteLock`

`public class ReentrantReadWriteLock extends Object implements ReadWriteLock, Serializable`

- [ ] `public ReentrantReadWriteLock()`
- [ ] `public ReentrantReadWriteLock(boolean fair)`
- [ ] `public ReentrantReadWriteLock.WriteLock writeLock()`
- [ ] `public ReentrantReadWriteLock.ReadLock readLock()`
- [ ] `public final boolean isFair()`
- [ ] `protected Thread getOwner()`
- [ ] `public int getReadLockCount()`
- [ ] `public boolean isWriteLocked()`
- [ ] `public boolean isWriteLockedByCurrentThread()`
- [ ] `public int getWriteHoldCount()`
- [ ] `public int getReadHoldCount()`
- [ ] `protected Collection<Thread> getQueuedWriterThreads()`
- [ ] `protected Collection<Thread> getQueuedReaderThreads()`
- [ ] `public final boolean hasQueuedThreads()`
- [ ] `public final boolean hasQueuedThread(Thread thread)`
- [ ] `public final int getQueueLength()`
- [ ] `protected Collection<Thread> getQueuedThreads()`
- [ ] `public boolean hasWaiters(Condition condition)`
- [ ] `public int getWaitQueueLength(Condition condition)`
- [ ] `protected Collection<Thread> getWaitingThreads(Condition condition)`
- [ ] `public String toString()`

### `java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock`

`public static class ReentrantReadWriteLock.ReadLock extends Object implements Lock, Serializable`

- [ ] `protected ReadLock(ReentrantReadWriteLock lock)`
- [ ] `public void lock()`
- [ ] `public void lockInterruptibly() throws InterruptedException`
- [ ] `public boolean tryLock()`
- [ ] `public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public void unlock()`
- [ ] `public Condition newCondition()`
- [ ] `public String toString()`

### `java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock`

`public static class ReentrantReadWriteLock.WriteLock extends Object implements Lock, Serializable`

- [ ] `protected WriteLock(ReentrantReadWriteLock lock)`
- [ ] `public void lock()`
- [ ] `public void lockInterruptibly() throws InterruptedException`
- [ ] `public boolean tryLock()`
- [ ] `public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException`
- [ ] `public void unlock()`
- [ ] `public Condition newCondition()`
- [ ] `public String toString()`
- [ ] `public boolean isHeldByCurrentThread()`
- [ ] `public int getHoldCount()`
