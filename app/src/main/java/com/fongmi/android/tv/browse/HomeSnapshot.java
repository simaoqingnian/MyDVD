package com.fongmi.android.tv.browse;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;

/** The last successful native home page, keyed by source URL and home site. */
public final class HomeSnapshot {

    private static final String PREFS = "mydvd_home_snapshot";
    private static final String SOURCE_URL = "source_url";
    private static final String SITE_KEY = "site_key";
    private static final String RESULT = "result";
    private static final int MAX_JSON_CHARS = 1_000_000;

    private HomeSnapshot() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Result load(String sourceUrl, String siteKey) {
        if (TextUtils.isEmpty(sourceUrl) || TextUtils.isEmpty(siteKey)) return null;
        SharedPreferences prefs = prefs();
        if (!sourceUrl.equals(prefs.getString(SOURCE_URL, ""))
                || !siteKey.equals(prefs.getString(SITE_KEY, ""))) return null;
        String json = prefs.getString(RESULT, "");
        if (TextUtils.isEmpty(json)) return null;
        Result result = Result.objectFrom(json);
        return result != null && (!result.getTypes().isEmpty() || !result.getList().isEmpty()) ? result : null;
    }

    public static void save(String sourceUrl, String siteKey, Result result) {
        if (TextUtils.isEmpty(sourceUrl) || TextUtils.isEmpty(siteKey) || result == null
                || (result.getTypes().isEmpty() && result.getList().isEmpty())) return;
        try {
            String json = result.toString();
            if (json.length() > MAX_JSON_CHARS) return;
            prefs().edit().putString(SOURCE_URL, sourceUrl).putString(SITE_KEY, siteKey)
                    .putString(RESULT, json).apply();
        } catch (RuntimeException ignored) {
            // A failed snapshot must never affect the live home request.
        }
    }
}
