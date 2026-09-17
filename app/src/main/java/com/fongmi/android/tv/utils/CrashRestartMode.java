package com.fongmi.android.tv.utils;

import com.github.catvod.utils.Prefers;

public final class CrashRestartMode {

    private static final String KEY = "crash_restart_skip_config_once";

    private CrashRestartMode() {
    }

    public static void arm() {
        Prefers.put(KEY, true);
    }

    public static boolean consume() {
        if (!Prefers.getBoolean(KEY)) return false;
        Prefers.put(KEY, false);
        return true;
    }
}
