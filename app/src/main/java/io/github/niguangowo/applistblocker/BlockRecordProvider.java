/*
 * This file is part of AppListUploadBlocker.
 *
 * AppListUploadBlocker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

package io.github.niguangowo.applistblocker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 拦截记录的写入通道（主通道）。
 *
 * <p>Hook 运行在目标进程（{@code com.miui.guardprovider}）内，框架的 remote preferences 在
 * Hook 侧是<b>只读</b>实现（{@code edit()} 会抛 {@link UnsupportedOperationException}），
 * 因此记录必须由 Hook 侧主动投递到本模块进程落库。
 *
 * <p>投递通道选择 ContentProvider 而非广播：HyperOS 的 Greezer 会把处于 cached 状态的
 * 模块进程冻结，并被 {@code BroadcastQueueInjector} 以「process is not permitted to auto start」
 * 拦截，广播会被静默丢弃；而 ContentProvider 的获取不受这两处门控限制，模块进程即使已被
 * force-stop，一次 {@code call} 也能把它拉起（已实机验证）。
 *
 * <p>调用约定：方法名 {@link #METHOD_RECORD}，{@code extras} 携带 {@link BlockRecordStore#EXTRA_TYPE}
 * 与 {@link BlockRecordStore#EXTRA_TIMESTAMP}；返回的 {@link Bundle} 中
 * {@link #RESULT_OK} 为 {@code true} 表示已成功落库。
 *
 * <p>本类不抛出任何异常：记录功能属于附带能力，任何失败都不应影响拦截本身。
 */
public final class BlockRecordProvider extends ContentProvider {

    private static final String TAG = "AppListBlocker";

    /** 本 Provider 的 authority，须与 AndroidManifest 中声明一致。 */
    public static final String AUTHORITY = "io.github.niguangowo.applistblocker.records";

    /** 写入记录所用的 URI。 */
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    /** 写入一条拦截记录的方法名。 */
    public static final String METHOD_RECORD = "record";

    /** 返回 Bundle 中表示落库成功的键。 */
    public static final String RESULT_OK = "ok";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Bundle result = new Bundle();
        result.putBoolean(RESULT_OK, false);

        if (!METHOD_RECORD.equals(method)) {
            Log.w(TAG, "Rejected unknown record provider method: " + method);
            return result;
        }

        try {
            Context context = getContext();
            if (context == null) {
                Log.w(TAG, "Record provider has no context");
                return result;
            }

            String type = extras == null ? null : extras.getString(BlockRecordStore.EXTRA_TYPE);
            if (!BlockRecordStore.TYPE_EGRESS.equals(type)
                && !BlockRecordStore.TYPE_COLLECTOR.equals(type)) {
                Log.w(TAG, "Rejected block record with unknown type: " + type);
                return result;
            }

            long now = System.currentTimeMillis();
            long timestamp = extras == null
                ? now
                : extras.getLong(BlockRecordStore.EXTRA_TIMESTAMP, now);
            // 夹取策略：非正数或明显来自未来的时间戳回退到当前时刻，
            // 既保证定宽编码的排序前提，也不会因投递延迟而丢记录。
            if (timestamp <= 0L || timestamp > now + BlockRecordStore.TIMESTAMP_TOLERANCE_MS) {
                Log.w(TAG, "Clamped out-of-range block record timestamp: " + timestamp);
                timestamp = now;
            }

            SharedPreferences preferences = BlockRecordStore.preferences(context);
            if (preferences == null) {
                Log.w(TAG, "Block record storage is unavailable");
                return result;
            }

            if (BlockRecordStore.record(preferences, type, timestamp)) {
                result.putBoolean(RESULT_OK, true);
            } else {
                Log.w(TAG, "Block record was not persisted: " + type);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to store block record", t);
        }
        return result;
    }

    @Nullable
    @Override
    public Cursor query(
        @NonNull Uri uri,
        @Nullable String[] projection,
        @Nullable String selection,
        @Nullable String[] selectionArgs,
        @Nullable String sortOrder
    ) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(
        @NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs
    ) {
        return 0;
    }

    @Override
    public int update(
        @NonNull Uri uri,
        @Nullable ContentValues values,
        @Nullable String selection,
        @Nullable String[] selectionArgs
    ) {
        return 0;
    }
}
