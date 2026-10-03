/*
 * This file is part of AppListUploadBlocker.
 *
 * AppListUploadBlocker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

package io.github.niguangowo.applistblocker;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class ModuleEntry extends XposedModule {

    private static final String TAG = "AppListBlocker";

    private static final String TARGET_PACKAGE = "com.miui.guardprovider";

    private static final String MODULE_PACKAGE = "io.github.niguangowo.applistblocker";

    private static final String DEXKIT_LIBRARY = "dexkit";

    private static final String UPLOAD_URL = "https://flash.sec.miui.com/detect/app";

    private static volatile boolean sDexKitLoaded = false;

    /**
     * 投递拦截记录的线程池。Provider 首次调用需要拉起模块进程，放在目标应用线程上会拖慢其
     * 上报路径；单线程即可，记录投递本身必须串行。
     *
     * <p>队列有界：Provider 拉起进程期间任务会堆积，无界队列在异常场景下会持续吃内存。
     * 队列满时丢弃并打日志——记录功能是附带能力，不能反过来拖垮目标应用。
     */
    private static final int RECORD_QUEUE_CAPACITY = 64;

    private static final ExecutorService RECORD_EXECUTOR = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(RECORD_QUEUE_CAPACITY),
        r -> {
            Thread thread = new Thread(r, "AppListBlocker-Record");
            thread.setDaemon(true);
            return thread;
        },
        (task, executor) -> Log.w(TAG, "Block record queue is full, dropping one record"));

    /**
     * 目标进程内的 system context。仅在无法从 Hook 参数中取到 Context 时作为兜底。
     * 懒加载后缓存；为 null 表示尚未解析成功，下次调用会重试。
     */
    @Nullable
    private static volatile Context sSystemContext = null;

    @Override
    public void onPackageLoaded(@NonNull XposedModuleInterface.PackageLoadedParam param) {
        String packageName = param.getPackageName();
        if (!TARGET_PACKAGE.equals(packageName)) {
            return;
        }

        ClassLoader classLoader = param.getDefaultClassLoader();
        if (classLoader == null) {
            log(Log.ERROR, "Target class loader is null, skip.");
            return;
        }

        try {
            install(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, "Failed to install hooks", t);
        }
    }

    private void install(ClassLoader classLoader) {
        if (!ensureDexKitLoaded()) {
            log(Log.ERROR, "DexKit native library is unavailable, cannot locate hook targets");
            return;
        }

        Method uploadEgress = findUploadEgress(classLoader);
        Method appListCollector = findAppListCollector(classLoader);

        log(Log.INFO, "Resolved upload egress = " + uploadEgress
            + ", app list collector = " + appListCollector);

        if (uploadEgress == null && appListCollector == null) {
            log(Log.ERROR, "No matcher hit the app list upload path of " + TARGET_PACKAGE
                + " (unsupported app version?)");
            return;
        }

        if (uploadEgress != null) {
            try {
                hook(uploadEgress).intercept(chain -> {
                    log(Log.INFO, "Blocked app list upload to " + UPLOAD_URL);
                    recordBlock(contextFrom(chain), BlockRecordStore.TYPE_EGRESS);
                    return null;
                });
            } catch (Throwable t) {
                log(Log.ERROR, "Failed to hook upload egress " + uploadEgress, t);
            }
        }

        if (appListCollector != null) {
            try {
                hook(appListCollector).intercept(chain -> {
                    log(Log.INFO, "Blocked app list collection");
                    recordBlock(contextFrom(chain), BlockRecordStore.TYPE_COLLECTOR);
                    return new ArrayList<>();
                });
            } catch (Throwable t) {
                log(Log.ERROR, "Failed to hook app list collector " + appListCollector, t);
            }
        }

        log(Log.INFO, "Hooks installed.");
    }

    /**
     * 记录一次成功拦截：调用模块进程内的 {@code BlockRecordProvider} 落库。
     *
     * <p>不直接写 SharedPreferences —— 框架的 remote preferences 在 Hook 侧是只读实现
     * （{@code edit()} 抛 {@link UnsupportedOperationException}）；也不使用广播 —— HyperOS
     * 的 Greezer 会冻结处于 cached 状态的模块进程并丢弃广播，而 ContentProvider 不受该门控
     * 限制。任何失败都只打日志，绝不向上抛出，避免影响拦截本身。
     *
     * <p>投递在独立线程上进行：provider 首次调用需要拉起模块进程（实机约 1.3 秒），
     * 不能阻塞目标应用的线程。
     */
    private void recordBlock(@Nullable Context context, @NonNull String type) {
        if (context == null) {
            log(Log.WARN, "Record channel unavailable, skip recording " + type);
            return;
        }

        try {
            Bundle extras = new Bundle();
            extras.putString(BlockRecordStore.EXTRA_TYPE, type);
            extras.putLong(BlockRecordStore.EXTRA_TIMESTAMP, System.currentTimeMillis());

            Context appContext = context.getApplicationContext();
            final Context target = appContext == null ? context : appContext;

            RECORD_EXECUTOR.execute(() -> {
                try {
                    Bundle reply = target.getContentResolver().call(
                        BlockRecordProvider.CONTENT_URI,
                        BlockRecordProvider.METHOD_RECORD,
                        null,
                        extras);
                    if (reply == null || !reply.getBoolean(BlockRecordProvider.RESULT_OK, false)) {
                        log(Log.WARN, "Block record was rejected by the provider: " + type);
                    }
                } catch (Throwable t) {
                    log(Log.WARN, "Failed to deliver block record " + type, t);
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列已满：记录功能是附带能力，宁可丢记录也不阻塞目标应用。
            log(Log.WARN, "Block record queue is full, dropped " + type);
        } catch (Throwable t) {
            log(Log.WARN, "Failed to dispatch block record " + type, t);
        }
    }

    /**
     * 从被 Hook 方法的参数里取出目标进程的 Context。
     *
     * <p>两个 Hook 点的首个参数都是 {@code GuardApplication}（即目标的 Application），
     * 本身就是 Context，因此无需再反射隐藏 API 获取 system context。
     */
    @Nullable
    private Context contextFrom(@NonNull Chain chain) {
        try {
            for (Object arg : chain.getArgs()) {
                if (arg instanceof Context) {
                    return (Context) arg;
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, "Failed to read hook arguments", t);
        }
        return systemContext();
    }

    /**
     * 取得目标进程内的 system context 并缓存。失败时返回 null 且不缓存，下次拦截会重试。
     */
    @Nullable
    private Context systemContext() {
        Context cached = sSystemContext;
        if (cached != null) {
            return cached;
        }
        synchronized (ModuleEntry.class) {
            if (sSystemContext != null) {
                return sSystemContext;
            }
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
                Context context = (Context) activityThread.getMethod("getSystemContext")
                    .invoke(thread);
                if (context == null) {
                    log(Log.WARN, "System context is null");
                    return null;
                }
                sSystemContext = context;
                return context;
            } catch (Throwable t) {
                log(Log.ERROR, "Failed to resolve system context", t);
                return null;
            }
        }
    }

    private boolean ensureDexKitLoaded() {
        if (sDexKitLoaded) {
            return true;
        }
        synchronized (ModuleEntry.class) {
            if (sDexKitLoaded) {
                return true;
            }

            try {
                System.loadLibrary(DEXKIT_LIBRARY);
                sDexKitLoaded = true;
                log(Log.INFO, "Loaded lib" + DEXKIT_LIBRARY + ".so via module namespace");
                return true;
            } catch (Throwable t) {
                log(Log.WARN, "System.loadLibrary(" + DEXKIT_LIBRARY + ") failed", t);
            }

            String directory = moduleNativeLibraryDir();
            if (directory == null || directory.isEmpty()) {
                log(Log.ERROR, "Module native library directory is unavailable");
                return false;
            }
            String absolute = directory + "/lib" + DEXKIT_LIBRARY + ".so";
            try {
                System.load(absolute);
                sDexKitLoaded = true;
                log(Log.INFO, "Loaded " + absolute);
                return true;
            } catch (Throwable t) {
                log(Log.ERROR, "System.load(" + absolute + ") failed", t);
                return false;
            }
        }
    }

    @Nullable
    private String moduleNativeLibraryDir() {
        try {
            Context context = systemContext();
            if (context == null) {
                return null;
            }
            return context.getPackageManager()
                .getApplicationInfo(MODULE_PACKAGE, 0)
                .nativeLibraryDir;
        } catch (Throwable t) {
            log(Log.ERROR, "Failed to resolve module native library directory", t);
            return null;
        }
    }

    @Nullable
    private Method findUploadEgress(ClassLoader classLoader) {
        Method m = findSingleMethod(classLoader,
            new String[]{UPLOAD_URL, "NetworkApiHelper"});
        if (m != null) {
            return m;
        }

        m = findSingleMethod(classLoader,
            new String[]{UPLOAD_URL, "6988567a-4220-4b51-bc2d-ccdec27a74a1"});
        if (m != null) {
            return m;
        }

        return findSingleMethod(classLoader, new String[]{UPLOAD_URL});
    }

    @Nullable
    private Method findAppListCollector(ClassLoader classLoader) {
        Method m = findSingleMethod(classLoader,
            new String[]{"AntiDefraudAppManager", "getUnSystemAppList error, "});
        if (m != null) {
            return m;
        }
        return findSingleMethod(classLoader,
            new String[]{"AntiDefraudAppManager", "getAllUnSystemAppsStatus error, "});
    }

    @Nullable
    private Method findSingleMethod(ClassLoader classLoader, String[] usingStrings) {
        String label = String.join(" + ", usingStrings);
        DexKitBridge bridge = null;
        try {
            bridge = DexKitBridge.create(classLoader, false);

            MethodMatcher matcher = MethodMatcher.create().usingStrings(usingStrings);
            MethodDataList results = bridge.findMethod(FindMethod.create().matcher(matcher));

            if (results == null || results.isEmpty()) {
                log(Log.WARN, "No match for " + label);
                return null;
            }
            if (results.size() > 1) {
                log(Log.WARN, "Ambiguous match (" + results.size() + ") for " + label
                    + ", using the first one");
            }

            MethodData data = results.get(0);
            Method method = data.getMethodInstance(classLoader);
            log(Log.INFO, "Matched " + data.getDescriptor());
            return method;
        } catch (Throwable t) {
            log(Log.ERROR, "DexKit query failed for " + label, t);
            return null;
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void log(int priority, String message) {
        try {
            log(priority, TAG, message);
        } catch (Throwable ignored) {
        }
    }

    private void log(int priority, String message, @Nullable Throwable throwable) {
        try {
            log(priority, TAG, message, throwable);
        } catch (Throwable ignored) {
        }
    }
}
