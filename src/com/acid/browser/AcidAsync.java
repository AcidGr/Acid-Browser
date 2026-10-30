package com.acid.browser;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Managed background task executor with main-thread result delivery and cancellation.
 *
 * <p>Replaces unmanaged {@code new Thread(...).start()} calls in UI sheets and managers so
 * tasks cancelled when a sheet closes never touch detached views.
 */
public final class AcidAsync {

    public interface Task<T> {
        T execute() throws Exception;
    }

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception error);
    }

    public static final class Handle {
        private final AtomicBoolean mCancelled = new AtomicBoolean(false);
        private volatile Future<?> mFuture;

        void attach(Future<?> future) {
            mFuture = future;
            if (mCancelled.get() && future != null) {
                future.cancel(true);
            }
        }

        public void cancel() {
            if (mCancelled.compareAndSet(false, true)) {
                Future<?> f = mFuture;
                if (f != null) {
                    f.cancel(true);
                }
            }
        }

        public boolean isCancelled() {
            return mCancelled.get();
        }
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "AcidAsync-" + mCount.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private AcidAsync() {}

    public static <T> Handle run(final Task<T> task, final Callback<T> callback) {
        final Handle handle = new Handle();
        Future<?> future = POOL.submit(new Runnable() {
            @Override
            public void run() {
                if (handle.isCancelled()) return;
                try {
                    final T res = task.execute();
                    if (handle.isCancelled()) return;
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            if (!handle.isCancelled() && callback != null) {
                                callback.onSuccess(res);
                            }
                        }
                    });
                } catch (final Exception e) {
                    if (handle.isCancelled()) return;
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            if (!handle.isCancelled() && callback != null) {
                                callback.onError(e);
                            }
                        }
                    });
                }
            }
        });
        handle.attach(future);
        return handle;
    }

    public static void postToMain(Runnable action) {
        if (action == null) return;
        try {
            Looper main = Looper.getMainLooper();
            if (main != null) {
                if (Looper.myLooper() == main) {
                    action.run();
                } else {
                    new Handler(main).post(action);
                }
                return;
            }
        } catch (Throwable ignored) {
            // JVM unit test environment where Looper is stubbed
        }
        action.run();
    }
}
