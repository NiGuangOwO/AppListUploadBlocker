/*
 * This file is part of AppListUploadBlocker.
 *
 * AppListUploadBlocker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

package io.github.niguangowo.applistblocker;

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

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class ModuleEntry extends XposedModule {

    private static final String TAG = "AppListBlocker";

    private static final String TARGET_PACKAGE = "com.miui.guardprovider";

    private static final String MODULE_PACKAGE = "io.github.niguangowo.applistblocker";

    private static final String DEXKIT_LIBRARY = "dexkit";

    private static final String UPLOAD_URL = "https://flash.sec.miui.com/detect/app";

    private static volatile boolean sDexKitLoaded = false;

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
                    return null;
                });
            } catch (Throwable t) {
                log(Log.ERROR, "Failed to hook upload egress " + uploadEgress, t);
            }
        }

        if (appListCollector != null) {
            try {
                hook(appListCollector).intercept(chain -> {
                    return new ArrayList<>();
                });
            } catch (Throwable t) {
                log(Log.ERROR, "Failed to hook app list collector " + appListCollector, t);
            }
        }

        log(Log.INFO, "Hooks installed.");
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
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Object systemContext = activityThread.getMethod("getSystemContext").invoke(thread);
            android.content.Context context = (android.content.Context) systemContext;
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
