/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;

/**
 * Implementation class for thread local handshakes. Contains the parts that can be shared between
 * runtimes.
 */
public abstract class ThreadLocalHandshake {

    /*
     * This map contains all state objects for all threads accessible for other threads. Since the
     * thread needs to be weak and synchronized it is less efficient to access and is only used when
     * accessing the state of other threads.
     */
    private static final Map<Thread, TruffleSafepointImpl> SAFEPOINTS = Collections.synchronizedMap(new WeakHashMap<>());

    protected ThreadLocalHandshake() {
    }

    public abstract void poll(Node enclosingNode);

    public abstract TruffleSafepointImpl getCurrent();

    protected boolean isSupported() {
        return true;
    }

    public void testSupport() {
        if (!isSupported()) {
            throw new UnsupportedOperationException("Thread local handshakes are not supported on this platform. " +
                            "A possible reason may be that the underlying JVMCI version is too old.");
        }
    }

    /**
     * If this method is invoked the thread must be guaranteed to be polled. If the thread dies and
     * {@link #poll(Node)} was not invoked then an {@link IllegalStateException} is thrown;
     */
    @TruffleBoundary
    public final <T extends Consumer<Node>> Future<Void> runThreadLocal(Thread[] threads, T onThread, Consumer<T> onDone, boolean sideEffecting, boolean sync) {
        testSupport();
        Handshake<T> handshake = new Handshake<>(threads, onThread, onDone, sideEffecting, threads.length, sync);
        for (int i = 0; i < threads.length; i++) {
            Thread t = threads[i];
            if (!t.isAlive()) {
                throw new IllegalStateException("Thread no longer alive with pending handshake.");
            }
            getThreadState(t).addHandshake(t, handshake);
        }
        return handshake;
    }

    @SuppressWarnings("static-method")
    public final void activateThread(TruffleSafepoint s, Future<?> f) {
        ((TruffleSafepointImpl) s).activateThread((Handshake<?>) f);
    }

    @SuppressWarnings("static-method")
    public final void deactivateThread(TruffleSafepoint s, Future<?> f) {
        ((TruffleSafepointImpl) s).deactivateThread((Handshake<?>) f);
    }

    public void ensureThreadInitialized() {
    }

    protected abstract void setFastPending(Thread t);

    @TruffleBoundary
    protected final void processHandshake(Node location) {
        TruffleSafepointImpl s = getCurrent();
        if (s.fastPendingSet) {
            s.processHandshakes(location, s.takeHandshakes());
        }
    }

    protected abstract void clearFastPending();

    private static Throwable combineThrowable(Throwable current, Throwable t) {
        if (current == null) {
            return t;
        }
        if (t instanceof ThreadDeath) {
            t.addSuppressed(current);
            return t;
        } else {
            current.addSuppressed(t);
            return current;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable ex) throws T {
        throw (T) ex;
    }

    public static final class Handshake<T extends Consumer<Node>> implements Future<Void> {

        private final boolean sideEffecting;
        private final Phaser phaser;
        private volatile boolean cancelled;
        private final T action;
        private final boolean sync;
        // avoid rescheduling on the same thread again
        private final Set<Thread> threads;
        private final Consumer<T> onDone;

        @SuppressWarnings("unchecked")
        Handshake(Thread[] initialThreads, T action, Consumer<T> onDone, boolean sideEffecting, int numberOfThreads, boolean sync) {
            this.action = action;
            this.onDone = onDone;
            this.sideEffecting = sideEffecting;
            this.sync = sync;
            this.phaser = new Phaser(numberOfThreads);
            this.threads = Collections.synchronizedSet(new HashSet<>(Arrays.asList(initialThreads)));
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        void perform(Node node) {
            try {
                if (sync) {
                    phaser.arriveAndAwaitAdvance();
                }
                if (!cancelled) {
                    action.accept(node);
                }
            } finally {
                if (sync) {
                    phaser.arriveAndDeregister();
                    phaser.awaitAdvance(1);

                    assert phaser.isTerminated();
                    onDone.accept(action);
                } else {
                    phaser.arriveAndDeregister();

                    if (phaser.isTerminated()) {
                        onDone.accept(action);
                    }
                }
            }
        }

        boolean activateThread() {
            int result = phaser.register();
            if (result != 0) {
                // did not activate on time.
                phaser.arriveAndDeregister();
                return false;
            }
            return true;
        }

        void deactivateThread() {
            phaser.arriveAndDeregister();
            if (phaser.isTerminated()) {
                onDone.accept(action);
            }
        }

        @Override
        public Void get() throws InterruptedException {
            if (sync) {
                phaser.awaitAdvanceInterruptibly(0);
                phaser.awaitAdvanceInterruptibly(1);
            } else {
                phaser.awaitAdvanceInterruptibly(0);
            }
            return null;
        }

        public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (sync) {
                phaser.awaitAdvanceInterruptibly(0, timeout, unit);
                phaser.awaitAdvanceInterruptibly(1, timeout, unit);
            } else {
                phaser.awaitAdvanceInterruptibly(0, timeout, unit);
            }
            return null;
        }

        public boolean isDone() {
            return cancelled || phaser.isTerminated();
        }

        public boolean cancel(boolean mayInterruptIfRunning) {
            if (phaser.getUnarrivedParties() > 0) {
                cancelled = true;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return "Handshake[action=" + action + ", phaser=" + phaser + ", cancelled=" + cancelled + ", sideEffecting=" + sideEffecting + ", sync=" + sync + "]";
        }

    }

    static final class HandshakeEntry {

        final Handshake<?> handshake;
        boolean active = true;

        HandshakeEntry(Handshake<?> handshake) {
            this.handshake = handshake;
        }

        @Override
        public String toString() {
            return "HandshakeEntry[" + handshake + " active=" + active + "]";
        }
    }

    protected final TruffleSafepointImpl getThreadState(Thread thread) {
        return SAFEPOINTS.computeIfAbsent(thread, (t) -> new TruffleSafepointImpl(this));
    }

    protected static final class TruffleSafepointImpl extends TruffleSafepoint {

        private final ReentrantLock lock = new ReentrantLock();
        private final ThreadLocalHandshake impl;
        private volatile boolean fastPendingSet;
        private boolean sideEffectsEnabled = true;
        private Interrupter blockedAction;
        /*
         * This is read outside the lock because some Interrupter's need to have resetInterrupted()
         * called concurrently to interrupt(). interrupt() is called under the lock (avoids
         * concurrent calls for the same thread), so resetInterrupted() must be called outside the
         * lock.
         */
        private volatile boolean interrupted;

        private final LinkedList<HandshakeEntry> handshakes = new LinkedList<>();

        TruffleSafepointImpl(ThreadLocalHandshake handshake) {
            super(DefaultRuntimeAccessor.ENGINE);
            this.impl = handshake;
        }

        void processHandshakes(Node location, List<HandshakeEntry> toProcess) {
            if (toProcess == null) {
                return;
            }
            Throwable ex = null;
            for (HandshakeEntry current : toProcess) {
                if (claimEntry(current)) {
                    try {
                        current.handshake.perform(location);
                    } catch (Throwable e) {
                        ex = combineThrowable(ex, e);
                    }
                }
            }
            if (fastPendingSet) {
                resetPending();
            }
            if (ex != null) {
                throw sneakyThrow(ex);
            }
        }

        public void deactivateThread(Handshake<?> handshake) {
            lock.lock();
            try {
                HandshakeEntry current = lookupEntry(handshake);
                if (current != null) {
                    if (!current.active) {
                        // already inactive
                        return;
                    }
                    // still active
                    assert current.active;
                    current.active = false;
                    handshake.deactivateThread();
                    claimEntry(current);
                    resetPending();
                }

            } finally {
                lock.unlock();
            }
        }

        public void activateThread(Handshake<?> handshake) {
            if (handshake.isDone()) {
                return;
            }
            lock.lock();
            try {
                HandshakeEntry current = lookupEntry(handshake);
                if (current != null) {
                    /*
                     * The handshake has already been put to this thread and it is active or it is
                     * inactive and must not be re-activated.
                     */
                    return;
                }
                // not yet put or already processed
                if (!handshake.threads.add(Thread.currentThread())) {
                    // already processed on that thread, we don't want to process twice.
                    return;
                }
                if (handshake.activateThread()) {
                    addHandshakeImpl(Thread.currentThread(), handshake);
                }
            } finally {
                lock.unlock();
            }
        }

        private HandshakeEntry lookupEntry(Handshake<?> handshake) {
            assert lock.isHeldByCurrentThread();

            for (HandshakeEntry entry : handshakes) {
                if (entry.handshake == handshake) {
                    return entry;
                }
            }
            return null;
        }

        void addHandshake(Thread t, Handshake<?> handshake) {
            lock.lock();
            try {
                addHandshakeImpl(t, handshake);
            } finally {
                lock.unlock();
            }
        }

        private void addHandshakeImpl(Thread t, Handshake<?> handshake) {
            handshakes.add(new HandshakeEntry(handshake));
            if (isPending()) {
                setFastPendingAndInterrupt(t);
            }
        }

        private void setFastPendingAndInterrupt(Thread t) {
            assert lock.isHeldByCurrentThread();
            if (!fastPendingSet) {
                fastPendingSet = true;
                impl.setFastPending(t);
            }
            Interrupter action = this.blockedAction;
            if (action != null) {
                interrupted = true;
                action.interrupt(t);
            }
        }

        List<HandshakeEntry> takeHandshakes() {
            lock.lock();
            try {
                if (this.interrupted) {
                    this.blockedAction.resetInterrupted();
                    this.interrupted = false;
                }
                if (isPending()) {
                    List<HandshakeEntry> taken = takeHandshakeImpl();
                    assert !taken.isEmpty();
                    return taken;
                }
                return null;
            } finally {
                lock.unlock();
            }
        }

        private void resetPending() {
            lock.lock();
            try {
                if (fastPendingSet && !isPending()) {
                    fastPendingSet = false;
                    impl.clearFastPending();
                }
            } finally {
                lock.unlock();
            }
        }

        private boolean claimEntry(HandshakeEntry entry) {
            lock.lock();
            try {
                return this.handshakes.removeFirstOccurrence(entry);
            } finally {
                lock.unlock();
            }
        }

        private List<HandshakeEntry> takeHandshakeImpl() {
            List<HandshakeEntry> toProcess = new ArrayList<>(this.handshakes.size());
            for (HandshakeEntry entry : this.handshakes) {
                if (isPending(entry)) {
                    toProcess.add(entry);
                }
            }
            return toProcess;
        }

        private boolean isPending(HandshakeEntry entry) {
            if (!entry.active) {
                return false;
            }
            if (sideEffectsEnabled || !entry.handshake.sideEffecting) {
                return true;
            }
            return false;
        }

        @Override
        public <T> void setBlocked(Node location, Interrupter interrupter, Interruptible<T> interruptible, T object, Runnable beforeInterrupt, Runnable afterInterrupt) {
            assert impl.getCurrent() == this : "Cannot be used from a different thread.";

            /*
             * We want to avoid to ever call the Interruptible interface on compiled code paths to
             * make native image avoid marking it as runtime compiled. It is common that
             * interruptibles are just a method reference to Lock::lockInterruptibly which could no
             * longer be used otherwise as PE would fail badly for these methods and we would get
             * black list method errors in native image.
             *
             * A good workaround is to use our own interface that is a subclass of Interruptible but
             * that must be used to opt-in to compilation.
             */
            if (CompilerDirectives.inCompiledCode() && CompilerDirectives.isPartialEvaluationConstant(interruptible) && interruptible instanceof CompiledInterruptible<?>) {
                setBlockedCompiled(location, interrupter, (CompiledInterruptible<T>) interruptible, object, beforeInterrupt, afterInterrupt);
            } else {
                setBlockedBoundary(location, interrupter, interruptible, object, beforeInterrupt, afterInterrupt);
            }
        }

        private <T> void setBlockedCompiled(Node location, Interrupter interrupter, CompiledInterruptible<T> interruptible, T object, Runnable beforeInterrupt, Runnable afterInterrupt) {
            Interrupter prev = this.blockedAction;
            try {
                while (true) {
                    try {
                        setBlockedImpl(location, interrupter, false);
                        interruptible.apply(object);
                        break;
                    } catch (InterruptedException e) {
                        setBlockedAfterInterrupt(location, prev, beforeInterrupt, afterInterrupt);
                        continue;
                    }
                }
            } finally {
                setBlockedImpl(location, prev, false);
            }
        }

        @TruffleBoundary
        private <T> void setBlockedBoundary(Node location, Interrupter interrupter, Interruptible<T> interruptible, T object, Runnable beforeInterrupt, Runnable afterInterrupt) {
            Interrupter prev = this.blockedAction;
            try {
                while (true) {
                    try {
                        setBlockedImpl(location, interrupter, false);
                        interruptible.apply(object);
                        break;
                    } catch (InterruptedException e) {
                        setBlockedAfterInterrupt(location, prev, beforeInterrupt, afterInterrupt);
                        continue;
                    }
                }
            } finally {
                setBlockedImpl(location, prev, false);
            }
        }

        @TruffleBoundary
        private void setBlockedAfterInterrupt(final Node location, final Interrupter interrupter, Runnable beforeInterrupt, Runnable afterInterrupt) {
            if (beforeInterrupt != null) {
                beforeInterrupt.run();
            }
            try {
                setBlockedImpl(location, interrupter, true);
            } finally {
                if (afterInterrupt != null) {
                    afterInterrupt.run();
                }
            }
        }

        @TruffleBoundary
        private void setBlockedImpl(final Node location, final Interrupter interrupter, boolean processSafepoints) {
            List<HandshakeEntry> toProcess = null;
            lock.lock();
            try {
                if (processSafepoints) {
                    if (isPending()) {
                        toProcess = takeHandshakeImpl();
                    }
                }
                if (interrupted) {
                    assert this.blockedAction != null;
                    this.blockedAction.resetInterrupted();
                    this.interrupted = false;
                }
                this.blockedAction = interrupter;
            } finally {
                lock.unlock();
            }

            processHandshakes(location, toProcess);

            if (interrupter != null) {
                /*
                 * We can only process once. Now we need to continue running, but interrupt.
                 */
                interruptIfPending(interrupter);
            }
        }

        private void interruptIfPending(final Interrupter interrupter) {
            boolean doInterrupt = false;
            lock.lock();
            try {
                if (interrupter != null && isPending()) {
                    doInterrupt = true;
                }
            } finally {
                lock.unlock();
            }
            if (doInterrupt) {
                interrupted = true;
                interrupter.interrupt(Thread.currentThread());
            }
        }

        /**
         * Is a handshake really pending?
         */
        private boolean isPending() {
            assert lock.isHeldByCurrentThread();

            for (HandshakeEntry entry : this.handshakes) {
                if (isPending(entry)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        @TruffleBoundary
        public boolean setAllowSideEffects(boolean enabled) {
            assert impl.getCurrent() == this : "Cannot be used from a different thread.";
            lock.lock();
            try {
                boolean prev = this.sideEffectsEnabled;
                this.sideEffectsEnabled = enabled;
                updateFastPending();
                return prev;
            } finally {
                lock.unlock();
            }
        }

        @Override
        @TruffleBoundary
        public boolean hasPendingSideEffectingActions() {
            assert impl.getCurrent() == this : "Cannot be used from a different thread.";
            lock.lock();
            try {
                return !sideEffectsEnabled && hasSideEffecting();
            } finally {
                lock.unlock();
            }
        }

        private boolean hasSideEffecting() {
            assert lock.isHeldByCurrentThread();

            for (HandshakeEntry entry : this.handshakes) {
                if (entry.active && entry.handshake.sideEffecting) {
                    return true;
                }
            }
            return false;
        }

        private void updateFastPending() {
            if (isPending()) {
                setFastPendingAndInterrupt(Thread.currentThread());
            } else {
                if (fastPendingSet) {
                    fastPendingSet = false;
                    impl.clearFastPending();
                }
            }
        }
    }

}
