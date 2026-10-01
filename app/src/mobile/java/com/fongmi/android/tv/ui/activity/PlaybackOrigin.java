package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.db.AppDatabase;

/** Opens the film detail page recorded by downloads and shared playback cache. */
final class PlaybackOrigin {

    private PlaybackOrigin() {
    }

    static String sourceLabel(String siteKey, String historyKey) {
        String key = siteKey(historyKey, siteKey);
        if (TextUtils.isEmpty(key)) return "来源：旧版缓存，未知片源";
        Site site = VodConfig.get().getSite(key);
        String name = key.equals(site.getKey()) && !TextUtils.isEmpty(site.getName()) ? site.getName() : key;
        String cid = configId(historyKey);
        return "来源片源：" + name + (TextUtils.isEmpty(cid) ? "" : " · 源配置 #" + cid);
    }

    static void open(Activity activity, String historyKey, String siteKey, String title) {
        String key = siteKey(historyKey, siteKey);
        String vodId = vodId(historyKey, key);
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(vodId)) {
            new AlertDialog.Builder(activity).setTitle("无法定位播放页")
                    .setMessage("这条旧记录没有保存完整的影片来源，无法打开原播放页。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        Site site = VodConfig.get().getSite(key);
        boolean siteAvailable = key.equals(site.getKey());
        String cid = configId(historyKey);
        boolean configChanged = !TextUtils.isEmpty(cid) && !cid.equals(String.valueOf(VodConfig.getCid()));
        String selectedKey = VodConfig.get().getHome().getKey();
        boolean selectedSiteChanged = !TextUtils.isEmpty(selectedKey) && !key.equals(selectedKey);
        if (!siteAvailable || configChanged || selectedSiteChanged) {
            String reason = !siteAvailable ? "当前源配置中没有这个片源。"
                    : configChanged ? "当前源配置与记录时不同。" : "当前选中的片源与记录来源不同。";
            AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                    .setTitle("播放来源不匹配")
                    .setMessage(sourceLabel(siteKey, historyKey) + "\n" + reason
                            + "\n请切换回原来的源配置后再打开，当前源可能找不到这部影片。")
                    .setNegativeButton("取消", null);
            if (siteAvailable) dialog.setPositiveButton("仍然打开", (ignored, which) ->
                    VideoActivity.start(activity, key, vodId, title));
            else dialog.setPositiveButton("知道了", null);
            dialog.show();
            return;
        }
        VideoActivity.start(activity, key, vodId, title);
    }

    private static String siteKey(String historyKey, String fallback) {
        if (!TextUtils.isEmpty(fallback)) return fallback;
        if (TextUtils.isEmpty(historyKey)) return "";
        int separator = historyKey.indexOf(AppDatabase.SYMBOL);
        return separator < 1 ? "" : historyKey.substring(0, separator);
    }

    private static String vodId(String historyKey, String key) {
        if (TextUtils.isEmpty(historyKey) || TextUtils.isEmpty(key)) return "";
        String prefix = key + AppDatabase.SYMBOL;
        if (!historyKey.startsWith(prefix)) return "";
        int end = historyKey.lastIndexOf(AppDatabase.SYMBOL);
        return end <= prefix.length() ? "" : historyKey.substring(prefix.length(), end);
    }

    private static String configId(String historyKey) {
        if (TextUtils.isEmpty(historyKey)) return "";
        int separator = historyKey.lastIndexOf(AppDatabase.SYMBOL);
        if (separator < 0) return "";
        String value = historyKey.substring(separator + AppDatabase.SYMBOL.length());
        try { return String.valueOf(Integer.parseInt(value)); }
        catch (NumberFormatException ignored) { return ""; }
    }
}
