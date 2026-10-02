package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.exo.MediaSourceFactory;
import com.fongmi.android.tv.player.exo.SharedMediaCache;
import com.fongmi.android.tv.setting.KernelPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PreloadSetting;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PlaybackCacheActivity extends AppCompatActivity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService statsWorker = Executors.newSingleThreadExecutor();
    private final Map<String, TextView> statsViews = new HashMap<>();
    private LinearLayout list;
    private TextView quota;
    private Future<?> statsJob;
    private int refreshGeneration;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, PlaybackCacheActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(18, 18, 18));
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(18, 18, 18));
        root.addView(text("播放缓存", 22));
        root.addView(text("EXO 播放、预载和下载共用此缓存；上限与设置中的 EXO 磁盘预载配额同步。达到上限时先清理最久未使用的影片，下载任务占用的缓存受到保护。", 14));
        quota = text("", 14);
        root.addView(quota);
        Button limit = new Button(this);
        limit.setText("设置缓存上限");
        limit.setOnClickListener(view -> chooseLimit());
        root.addView(limit);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateQuota();
        refresh();
    }

    private void updateQuota() {
        quota.setText("EXO 共享缓存上限：" + Formatter.formatFileSize(this,
                KernelPerformanceSetting.getPreloadSizeMb(PlayerSetting.EXO) * 1024L * 1024L));
    }

    private void chooseLimit() {
        String[] options = new String[PreloadSetting.getPreloadSizeOptionCount()];
        int selected = 0;
        for (int i = 0; i < options.length; i++) {
            int mb = PreloadSetting.getPreloadSizeMbAt(i);
            options[i] = Formatter.formatFileSize(this, mb * 1024L * 1024L);
            if (mb == KernelPerformanceSetting.getPreloadSizeMb(PlayerSetting.EXO)) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("EXO 共享缓存上限")
                .setSingleChoiceItems(options, selected, (dialog, which) -> {
                    PlaybackPerformanceSetting.markCustom(PlayerSetting.EXO);
                    KernelPerformanceSetting.putPreloadSizeMb(PlayerSetting.EXO,
                            PreloadSetting.getPreloadSizeMbAt(which));
                    dialog.dismiss();
                    updateQuota();
                    worker.execute(() -> {
                        MediaSourceFactory.applyCacheLimit();
                        SharedMediaCache.pruneNow();
                        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) refresh(); });
                    });
                })
                .setNegativeButton("取消", null).show();
    }

    private void refresh() {
        int generation = ++refreshGeneration;
        if (statsJob != null) statsJob.cancel(true);
        worker.execute(() -> {
            try {
                List<SharedMediaCache.CachedMovie> movies = SharedMediaCache.listMovies();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != refreshGeneration) return;
                    showMovies(movies);
                    if (!movies.isEmpty()) loadSegmentStats(generation);
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()
                        && generation == refreshGeneration) {
                    if (list.getChildCount() == 0) list.addView(text("读取缓存失败：" + error.getMessage(), 14));
                    else Toast.makeText(this, "刷新缓存失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                }});
            }
        });
    }

    private void loadSegmentStats(int generation) {
        statsJob = statsWorker.submit(() -> {
            try {
                Map<String, SharedMediaCache.SegmentStats> stats = SharedMediaCache.listMovieSegmentStats();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != refreshGeneration) return;
                    for (Map.Entry<String, TextView> entry : statsViews.entrySet()) {
                        SharedMediaCache.SegmentStats value = stats.get(entry.getKey());
                        if (value == null || value.totalSegments() <= 0) {
                            entry.getValue().setText("缓存分段 — / — · 无分段清单");
                        } else {
                            int percent = (int) Math.round(value.cachedSegments() * 100.0
                                    / value.totalSegments());
                            entry.getValue().setText("缓存分段 " + value.cachedSegments() + " / "
                                    + value.totalSegments() + " · " + percent + "%");
                        }
                    }
                });
            } catch (RuntimeException ignored) { }
        });
    }

    private void showMovies(List<SharedMediaCache.CachedMovie> movies) {
        list.removeAllViews();
        statsViews.clear();
        if (movies.isEmpty()) {
            list.addView(text("暂无可管理的播放缓存", 15));
            return;
        }
        for (SharedMediaCache.CachedMovie movie : movies) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            details.addView(singleLine(movie.title(), 15));
            details.addView(singleLine(PlaybackOrigin.sourceLabel(movie.siteKey(), movie.id()), 12));
            TextView segments = singleLine("缓存分段计算中…", 12);
            details.addView(segments);
            statsViews.put(movie.id(), segments);
            details.addView(text(Formatter.formatFileSize(this, movie.bytes())
                    + (movie.protectedFromDeletion() ? " · 播放或下载中" : ""), 12));
            Button open = new Button(this);
            open.setText("播放页");
            open.setAllCaps(false);
            open.setTextSize(12);
            open.setMinWidth(0);
            open.setMinimumWidth(0);
            open.setMinHeight(0);
            open.setMinimumHeight(0);
            open.setPadding(dp(7), 0, dp(7), 0);
            open.setOnClickListener(view -> PlaybackOrigin.open(this, movie.id(), movie.siteKey(), movie.title()));
            details.addView(open, new LinearLayout.LayoutParams(-2, dp(32)));
            ImageButton delete = new ImageButton(this);
            delete.setImageResource(R.drawable.ic_action_delete);
            delete.setBackgroundColor(Color.TRANSPARENT);
            delete.setContentDescription(movie.protectedFromDeletion() ? "播放或下载中，暂不可删除" : "删除这部影片的缓存");
            delete.setPadding(dp(9), dp(9), dp(9), dp(9));
            delete.setEnabled(!movie.protectedFromDeletion());
            delete.setAlpha(movie.protectedFromDeletion() ? 0.35f : 1f);
            delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("删除播放缓存")
                    .setMessage("删除“" + movie.title() + "”的缓存？再次播放时需要重新获取数据。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, which) -> deleteMovie(movie.id()))
                    .show());
            row.addView(delete, new LinearLayout.LayoutParams(dp(44), dp(44)));
            list.addView(row);
            View divider = new View(this);
            divider.setBackgroundColor(Color.DKGRAY);
            list.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void deleteMovie(String movieId) {
        worker.execute(() -> {
            try {
                SharedMediaCache.deleteMovie(movieId);
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) refresh(); });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setPadding(0, dp(1), 0, dp(1));
        return view;
    }

    private TextView singleLine(String value, int size) {
        TextView view = text(value, size);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        if (statsJob != null) statsJob.cancel(true);
        statsWorker.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }
}
