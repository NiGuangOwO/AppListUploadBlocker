/*
 * This file is part of AppListUploadBlocker.
 *
 * AppListUploadBlocker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

package io.github.niguangowo.applistblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 拦截记录的存储与编码。
 *
 * <p>Hook 运行在目标进程（{@code com.miui.guardprovider}）内，设置界面运行在本模块进程内。
 * 框架的 remote preferences 在 Hook 侧是<b>只读</b>实现（{@code edit()} 会抛
 * {@link UnsupportedOperationException}），因此记录不能由 Hook 直接写入。
 *
 * <p>实际通道为：Hook 侧调用本模块进程内 {@code BlockRecordProvider} 的
 * {@code call("record", ...)}（{@link BlockRecordProvider#METHOD_RECORD}），由该 Provider
 * 写入本进程的私有 SharedPreferences（{@link #PREFERENCES_NAME}）。
 * 设置界面直接读取同一个文件，不再经过框架 IPC。
 *
 * <p>存储结构：
 * <ul>
 *     <li>{@code total_count}：累计拦截次数，只增不减，清空记录时一并重置。</li>
 *     <li>{@code entries}：最近 {@link #MAX_ENTRIES} 条记录，每项编码为
 *     {@code <13 位毫秒时间戳>|<类型>|<序号>}，定宽时间戳保证字典序与时间序一致，
 *     序号用于区分同一毫秒内的同类型拦截。</li>
 * </ul>
 *
 * <p>本类不抛出任何异常：记录功能属于附带能力，任何失败都不应影响拦截本身。
 */
public final class BlockRecordStore {

    private static final String TAG = "AppListBlocker";

    /** 本模块进程私有的记录文件名。 */
    public static final String PREFERENCES_NAME = "block_records";

    /** 投递参数中承载拦截点类型的键。 */
    public static final String EXTRA_TYPE = "type";

    /** 投递参数中承载拦截时刻（epoch millis）的键。 */
    public static final String EXTRA_TIMESTAMP = "timestamp";

    /**
     * 允许的时间戳偏差上限：超出「当前时刻 + 本值」的时间戳会被回退为当前时刻。
     * 目前仅 Provider 侧（落库入口）使用，集中定义以免日后新增入口时阈值漂移。
     */
    public static final long TIMESTAMP_TOLERANCE_MS = 60_000L;

    /** 外发出口拦截（upload egress）。 */
    public static final String TYPE_EGRESS = "EGRESS";

    /** 采集源头拦截（app list collector）。 */
    public static final String TYPE_COLLECTOR = "COLLECTOR";

    private static final String KEY_TOTAL = "total_count";

    private static final String KEY_ENTRIES = "entries";

    /** 最多保留的记录条数。 */
    private static final int MAX_ENTRIES = 50;

    /** 13 位定宽时间戳能表达的最大毫秒值，超出会被夹取，避免编码串变宽破坏排序。 */
    private static final long MAX_ENCODABLE_TIMESTAMP = 9_999_999_999_999L;

    private static final char SEPARATOR = '|';

    /** 保护「读取 → 追加 → 写回」整段，避免并发拦截互相覆盖。 */
    private static final Object LOCK = new Object();

    /**
     * 全局自增序号，用于让同一毫秒内的多条记录也具备确定的全序（避免依赖 Set 迭代顺序）。
     * 取模仅为限制编码宽度；回绕需要累计 1e6 次拦截，且只影响同毫秒记录的相对顺序。
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private BlockRecordStore() {
    }

    /** 取得本模块进程私有的记录存储，失败时返回 null。 */
    @Nullable
    public static SharedPreferences preferences(@NonNull Context context) {
        try {
            return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to open block record storage", t);
            return null;
        }
    }

    /**
     * 追加一条拦截记录。
     *
     * @param preferences 记录存储，为 null 时静默忽略
     * @param type        拦截点类型，见 {@link #TYPE_EGRESS} / {@link #TYPE_COLLECTOR}
     * @param timestamp   拦截时刻（epoch millis）
     * @return 是否已成功落盘；调用方据此判断记录是否真的写进去了
     */
    public static boolean record(
        @Nullable SharedPreferences preferences, @NonNull String type, long timestamp
    ) {
        if (preferences == null) {
            return false;
        }
        try {
            synchronized (LOCK) {
                Set<String> entries = new HashSet<>();
                Set<String> stored = preferences.getStringSet(KEY_ENTRIES, null);
                if (stored != null) {
                    entries.addAll(stored);
                }
                entries.add(encode(timestamp, type));

                if (entries.size() > MAX_ENTRIES) {
                    List<String> sorted = new ArrayList<>(entries);
                    Collections.sort(sorted);
                    while (sorted.size() > MAX_ENTRIES) {
                        sorted.remove(0);
                    }
                    entries = new HashSet<>(sorted);
                }

                // commit() 而非 apply()：Provider 返回后调用进程可能立即结束等待，
                // 必须同步落盘。
                // 传入新集合而非复用引用，因为 SharedPreferences 会直接持有该 Set。
                boolean committed = preferences.edit()
                    .putInt(KEY_TOTAL, total(preferences) + 1)
                    .putStringSet(KEY_ENTRIES, new HashSet<>(entries))
                    .commit();
                if (!committed) {
                    Log.w(TAG, "Failed to persist block record " + type);
                }
                return committed;
            }
        } catch (Throwable t) {
            // 记录失败不影响拦截结果
            Log.w(TAG, "Failed to record block record " + type, t);
            return false;
        }
    }

    /**
     * 读取累计拦截次数。
     *
     * <p>不加 {@link #LOCK}：{@link #record} 每次都以新集合整体写回，读取方只会看到
     * 某一次完整写入的结果，不会读到中间状态。
     */
    public static int total(@Nullable SharedPreferences preferences) {
        if (preferences == null) {
            return 0;
        }
        try {
            return preferences.getInt(KEY_TOTAL, 0);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read block record total", t);
            return 0;
        }
    }

    /**
     * 读取记录明细，按时间从新到旧排序，最多 {@link #MAX_ENTRIES} 条。
     *
     * <p>不加 {@link #LOCK}：{@link #record} 每次都以新集合整体写回，这里只做遍历与排序。
     *
     * @return 永不为 null 的列表
     */
    @NonNull
    public static List<Entry> recent(@Nullable SharedPreferences preferences) {
        List<Entry> result = new ArrayList<>();
        if (preferences == null) {
            return result;
        }
        try {
            Set<String> stored = preferences.getStringSet(KEY_ENTRIES, null);
            if (stored == null) {
                return result;
            }
            // 先对编码串排序再解析：编码以 13 位定宽时间戳开头，字典序倒序即为时间倒序；
            // 同一毫秒的记录再按类型与序号倒序，保证多次读取的顺序稳定（Set 本身无序）。
            List<String> sorted = new ArrayList<>(stored);
            sorted.sort(Collections.reverseOrder());
            for (String raw : sorted) {
                Entry entry = decode(raw);
                if (entry != null) {
                    result.add(entry);
                }
            }
            if (result.size() > MAX_ENTRIES) {
                return new ArrayList<>(result.subList(0, MAX_ENTRIES));
            }
        } catch (Throwable t) {
            // 读取失败时返回已解析的部分
            Log.w(TAG, "Failed to read block records", t);
        }
        return result;
    }

    /**
     * 清空累计次数与明细。由设置界面调用。
     *
     * @return 是否已成功清空
     */
    public static boolean clear(@Nullable SharedPreferences preferences) {
        if (preferences == null) {
            return false;
        }
        try {
            synchronized (LOCK) {
                boolean cleared = preferences.edit().clear().commit();
                if (!cleared) {
                    Log.w(TAG, "Failed to clear block records");
                }
                return cleared;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to clear block records", t);
            return false;
        }
    }

    @NonNull
    private static String encode(long timestamp, @NonNull String type) {
        // 负数会被 %013d 编成 14 字符（"-000000000001"），超过 13 位的数值同样会变宽，
        // 两种都会破坏定宽前提；调用方已夹取，这里再兜一次底。
        long normalized = Math.min(MAX_ENCODABLE_TIMESTAMP, Math.max(0L, timestamp));
        return String.format(Locale.US, "%013d", normalized)
            + SEPARATOR + type
            + SEPARATOR + String.format(Locale.US, "%06d", SEQUENCE.incrementAndGet() % 1000000L);
    }

    @Nullable
    private static Entry decode(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        // 兼容历史格式 <时间戳>|<类型>，新格式为 <时间戳>|<类型>|<序号>。
        String[] parts = raw.split("\\" + SEPARATOR, 3);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        try {
            long timestamp = Long.parseLong(parts[0]);
            return new Entry(timestamp, parts[1]);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to decode block record: " + raw, t);
            return null;
        }
    }

    /** 一条拦截记录。 */
    public static final class Entry {

        /** 拦截发生的时刻（epoch millis）。 */
        public final long timestamp;

        /** 拦截点类型，见 {@link #TYPE_EGRESS} / {@link #TYPE_COLLECTOR}。 */
        @NonNull
        public final String type;

        public Entry(long timestamp, @NonNull String type) {
            this.timestamp = timestamp;
            this.type = type;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry that = (Entry) other;
            return timestamp == that.timestamp && type.equals(that.type);
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(timestamp) + type.hashCode();
        }
    }
}
