/*
 * This file is part of AppListUploadBlocker.
 *
 * AppListUploadBlocker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

package io.github.niguangowo.applistblocker

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 进程级的框架服务桥。
 *
 * <p>[XposedServiceHelper] 只保存<b>一个</b>静态 listener，且没有注销接口。若让
 * [SettingsActivity] 直接注册，每次重建都会覆盖上一个实例，并使旧 Activity 被静态引用
 * 而永远无法回收。因此改由本单例注册唯一 listener，再把绑定状态转达给当前活跃的观察者。
 *
 * <p>框架只推送一次 binder，而 [XposedServiceHelper.registerListener] 会先重放已缓存的
 * service，因此 Activity 重建后 [observe] 能立即拿到当前状态，无需再次注册。
 *
 * <p>回调可能来自 Binder 线程（框架经 ContentProvider.call 派发），所有状态变更与
 * 通知都切回主线程，避免在非主线程写 Compose 状态。
 */
internal object ServiceBridge : XposedServiceHelper.OnServiceListener {

    private const val TAG = "AppListBlocker"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否已经注册过框架 listener；[XposedServiceHelper] 无注销接口，只注册一次。 */
    @Volatile
    private var registered = false

    /** 最近一次绑定成功的 service，供 Activity 重建后复用。 */
    @Volatile
    var service: XposedService? = null
        private set

    /** 当前活跃的观察者（设置界面），仅弱化为「最新一个」即可。 */
    private var observer: (() -> Unit)? = null

    /** 注册唯一的框架 listener。重复调用无副作用；失败时允许下次重试。 */
    fun start() {
        if (registered) {
            return
        }
        try {
            XposedServiceHelper.registerListener(this)
            registered = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register Xposed service listener", t)
        }
    }

    /**
     * 设置当前观察者，并立刻用现有状态回调一次。
     *
     * <p>[onChange] 必须由调用方保存为字段并在 [clearObserver] 时原样传回，注销才会生效。
     */
    fun observe(onChange: () -> Unit) {
        observer = onChange
        onChange()
    }

    /**
     * 观察者（Activity）停止时调用，避免静态引用已停止的界面。
     *
     * <p>只清理由 [onChange] 本人注册的观察者：界面可能短暂存在多个实例（重建、多窗口），
     * 若无条件置空，旧实例停止时会把新实例的观察者一起清掉，导致新界面此后不再更新。
     */
    fun clearObserver(onChange: () -> Unit) {
        if (observer === onChange) {
            observer = null
        }
    }

    override fun onServiceBind(service: XposedService) {
        mainHandler.post {
            this.service = service
            observer?.invoke()
        }
    }

    override fun onServiceDied(service: XposedService) {
        mainHandler.post {
            // 只处理当前 service 的死亡通知，忽略过期实例。
            if (this.service !== service) {
                return@post
            }
            this.service = null
            observer?.invoke()
        }
    }
}
