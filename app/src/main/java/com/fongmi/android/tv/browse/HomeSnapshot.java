package com.fongmi.android.tv.browse;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;

/** Last successful native home page for each source URL and home site. */
public final class HomeSnapshot {

    private static final String PREFS = "mydvd_home_snapshot";
    private static final String SOURCE_URL = "source_url";
    private static final String SITE_KEY = "site_key";
    private static final String RESULT = "result";

    private HomeSnapshot() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Result load(String sourceUrl, String siteKey) {
        if (TextUtils.isEmpty(sourceUrl) || TextUtils.isEmpty(siteKey)) return null;
        Result saved = BrowseSnapshot.load("home", sourceUrl, siteKey, "");
        if (saved != null) return saved;
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
        BrowseSnapshot.save("home", sourceUrl, siteKey, "", result);
    }

    /** Preserve previously known categories when a source sends a partial home response. */
    public static Result complete(String sourceUrl, String siteKey, Result fresh) {
        if (fresh == null || !fresh.getTypes().isEmpty() && !fresh.getList().isEmpty()) return fresh;
        Result previous = load(sourceUrl, siteKey);
        if (previous == null) return fresh;
        if (fresh.getTypes().isEmpty()) {
            fresh.setTypes(previous.getTypes());
            fresh.setFilters(previous.getFilters());
        }
        if (fresh.getList().isEmpty()) fresh.setList(previous.getList());
        return fresh;
    }

}
