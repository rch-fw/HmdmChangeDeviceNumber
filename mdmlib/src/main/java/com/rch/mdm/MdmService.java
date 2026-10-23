package com.rch.mdm;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Convenience wrapper that runs {@link HeadwindMdmClient} calls on a background
 * thread and delivers the result on the Android main thread.
 *
 * <pre>{@code
 * MdmService service = new MdmService(
 *         new MdmConfig("mdm_server_url", "user", "password"));
 *
 * service.changeDeviceNumber("old_device_name", "new_device_name", new MdmService.Callback<Long>() {
 *     @Override public void onSuccess(Long deviceId) {
 *         Log.i("MDM", "Renamed device id " + deviceId);
 *     }
 *     @Override public void onError(MdmException e) {
 *         Log.e("MDM", "Rename failed (HTTP " + e.getStatusCode() + ")", e);
 *     }
 * });
 *
 * // call service.shutdown() when you are done (e.g. in onDestroy)
 * }</pre>
 */
public class MdmService {

    /** Result callback, always invoked on the main thread. */
    public interface Callback<T> {
        void onSuccess(T result);
        void onError(MdmException error);
    }

    private final HeadwindMdmClient client;
    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public MdmService(MdmConfig config) {
        this(new HeadwindMdmClient(config), Executors.newSingleThreadExecutor());
    }

    public MdmService(HeadwindMdmClient client, ExecutorService executor) {
        this.client = client;
        this.executor = executor;
    }

    public HeadwindMdmClient getClient() {
        return client;
    }

    /** Async equivalent of {@link HeadwindMdmClient#changeDeviceNumber}. */
    public void changeDeviceNumber(final String currentNumber, final String newNumber,
                                   final Callback<Long> callback) {
        run(new Task<Long>() {
            @Override public Long execute() throws MdmException {
                return client.changeDeviceNumber(currentNumber, newNumber);
            }
        }, callback);
    }

    /** Async equivalent of {@link HeadwindMdmClient#changeDevice}. */
    public void changeDevice(final String currentNumber, final String newNumber,
                             final List<String> groups, final Callback<Long> callback) {
        run(new Task<Long>() {
            @Override public Long execute() throws MdmException {
                return client.changeDevice(currentNumber, newNumber, groups);
            }
        }, callback);
    }

    /** Stop the internal executor. Call this from your component's teardown. */
    public void shutdown() {
        executor.shutdown();
    }

    // ------------------------------------------------------------------

    private interface Task<T> {
        T execute() throws MdmException;
    }

    private <T> void run(final Task<T> task, final Callback<T> callback) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final T result = task.execute();
                    mainHandler.post(new Runnable() {
                        @Override public void run() { callback.onSuccess(result); }
                    });
                } catch (final MdmException e) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { callback.onError(e); }
                    });
                } catch (final RuntimeException e) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            callback.onError(new MdmException("Unexpected error: " + e.getMessage(), e));
                        }
                    });
                }
            }
        });
    }
}
