package com.fongmi.android.tv.api.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;

import java.util.concurrent.TimeUnit;

/** Refresh age is separate from Config.time, which tracks the selected source. */
final class SourceConfigCache {

    private static final String PREFS = "mydvd_source_refresh";
    private static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(1);

    private SourceConfigCache() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isFresh(Config config) {
        if (!hasStored(config)) return false;
        String key = String.valueOf(config.getId());
        long age = System.currentTimeMillis() - prefs().getLong(key + "_time", 0);
        return age >= 0 && age < MAX_AGE_MS;
    }

    static boolean hasStored(Config config) {
        if (config == null || config.getId() <= 0 || TextUtils.isEmpty(config.getUrl())
                || TextUtils.isEmpty(config.getJson())) return false;
        String key = String.valueOf(config.getId());
        return config.getUrl().equals(prefs().getString(key + "_url", ""));
    }

    static void markRefreshed(Config config) {
        if (config == null || config.getId() <= 0 || TextUtils.isEmpty(config.getUrl())) return;
        String key = String.valueOf(config.getId());
        prefs().edit().putString(key + "_url", config.getUrl())
                .putLong(key + "_time", System.currentTimeMillis()).apply();
    }
}
