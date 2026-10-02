package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.download.DownloadService;
import com.fongmi.android.tv.download.DownloadStore;
import com.fongmi.android.tv.download.DownloadTask;

import java.util.List;

public class DownloadActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 2000);
        }
    };
    private LinearLayout list;
    private Button concurrencyButton;
    private String lastState = "";

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DownloadActivity.class));
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
        TextView title = text("下载管理", 22);
        root.addView(title);
        root.addView(button("管理播放缓存", () -> PlaybackCacheActivity.start(this)));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        concurrencyButton = smallButton("", this::chooseConcurrency);
        controls.addView(concurrencyButton, new LinearLayout.LayoutParams(0, dp(36), 1));
        controls.addView(smallButton("全部开始", () -> controlAll(DownloadService.ACTION_START_ALL)),
                new LinearLayout.LayoutParams(0, dp(36), 1));
        controls.addView(smallButton("全部暂停", () -> controlAll(DownloadService.ACTION_PAUSE_ALL)),
                new LinearLayout.LayoutParams(0, dp(36), 1));
        root.addView(controls);
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
        updateConcurrencyLabel();
        render();
        handler.postDelayed(refresh, 2000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void render() {
        if (list == null) return;
        List<DownloadTask> tasks = DownloadStore.list(this);
        StringBuilder state = new StringBuilder();
        for (DownloadTask task : tasks) state.append(task.id).append(task.status).append(task.progress)
                .append(task.segmentsDownloaded).append(task.segmentsTotal)
                .append(task.error).append(task.outputUri);
        if (state.toString().equals(lastState)) return;
        lastState = state.toString();
        list.removeAllViews();
        if (tasks.isEmpty()) list.addView(text("暂无下载任务。请在影片详情页选择“批量下载”。", 15));
        for (DownloadTask task : tasks) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            details.addView(singleLine(task.title(), 15));
            details.addView(singleLine(PlaybackOrigin.sourceLabel(task.siteKey, task.movieId), 12));
            String label;
            switch (task.status) {
                case DownloadTask.DONE -> label = "旧版 MP4 已导出 · 无原始分段";
                case DownloadTask.DOWNLOADED -> label = "原始视频已下载 · 待导出 MP4";
                case DownloadTask.EXPORTING -> label = "正在导出 MP4 · 原始分段保留";
                case DownloadTask.EXPORT_FAILED -> label = "导出失败 · " + task.error;
                case DownloadTask.EXPORTED -> label = "MP4 已导出 · 原始分段保留";
                case DownloadTask.FAILED -> label = "失败 · " + task.error;
                case DownloadTask.PAUSED -> label = "已暂停 · " + task.progress + "%";
                case DownloadTask.RUNNING -> label = "下载中 · " + task.progress + "%";
                case DownloadTask.DELETING -> label = "正在删除原始分段";
                case DownloadTask.DELETE_FAILED -> label = "删除失败 · " + task.error;
                default -> label = "等待下载";
            }
            TextView status = text(label, 12);
            status.setMaxLines(2);
            status.setEllipsize(TextUtils.TruncateAt.END);
            details.addView(status);
            if (task.segmentsTotal > 0) {
                details.addView(singleLine("分段 " + task.segmentsDownloaded + " / "
                        + task.segmentsTotal + " · " + task.progress + "%", 12));
            }
            if (DownloadTask.RUNNING.equals(task.status) || DownloadTask.PAUSED.equals(task.status)) {
                ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(100);
                progress.setProgress(task.progress);
                details.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
            }
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            details.addView(actions);
            if ((DownloadTask.DONE.equals(task.status) || DownloadTask.EXPORTED.equals(task.status))
                    && task.outputUri != null && !task.outputUri.isEmpty()) {
                actions.addView(smallButton("播放 MP4", () -> {
                    Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(task.outputUri), "video/mp4");
                    open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(open); }
                    catch (Exception ignored) { android.widget.Toast.makeText(this, "请到下载目录 / MyDVD 打开文件", android.widget.Toast.LENGTH_LONG).show(); }
                }));
            }
            actions.addView(smallButton("播放页", () -> PlaybackOrigin.open(this, task)));
            if (DownloadTask.DOWNLOADED.equals(task.status)
                    || DownloadTask.EXPORT_FAILED.equals(task.status)
                    || DownloadTask.EXPORTED.equals(task.status)) {
                actions.addView(smallButton(DownloadTask.EXPORTED.equals(task.status)
                        ? "再次导出" : "导出 MP4", () -> control(DownloadService.ACTION_EXPORT, task.id)));
            }
            if (DownloadTask.QUEUED.equals(task.status) || DownloadTask.RUNNING.equals(task.status)) {
                actions.addView(smallButton("暂停", () -> control(DownloadService.ACTION_PAUSE_TASK, task.id)));
            } else if (DownloadTask.PAUSED.equals(task.status)) {
                actions.addView(smallButton("继续", () -> control(DownloadService.ACTION_RESUME_TASK, task.id)));
            } else if (DownloadTask.FAILED.equals(task.status)) {
                actions.addView(smallButton("重试", () -> control(DownloadService.ACTION_RETRY_TASK, task.id)));
            }
            if (!DownloadTask.DELETING.equals(task.status)) {
                ImageButton delete = deleteButton(DownloadTask.DONE.equals(task.status)
                        ? "移除旧版下载记录" : DownloadTask.DELETE_FAILED.equals(task.status)
                        ? "重试删除原始分段" : "删除原始分段");
                delete.setOnClickListener(view ->
                        new AlertDialog.Builder(this)
                                .setTitle(DownloadTask.DONE.equals(task.status) ? "移除记录" : "删除原始分段")
                                .setMessage("确定移除“" + task.title()
                                        + "”的原始分段和下载记录？已导出的 MP4 会保留在系统下载目录。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除", (dialog, which) -> {
                                    DownloadService.delete(this, task.id);
                                    lastState = "";
                                    render();
                                })
                                .show());
                row.addView(delete, new LinearLayout.LayoutParams(dp(44), dp(44)));
            }
            View divider = new View(this);
            divider.setBackgroundColor(Color.DKGRAY);
            list.addView(row);
            list.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
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

    private Button smallButton(String label, Runnable action) {
        Button button = button(label, action);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(32)));
        return button;
    }

    private ImageButton deleteButton(String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_action_delete);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(description);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        return button;
    }

    private void control(String action, String taskId) {
        DownloadService.control(this, action, taskId);
        lastState = "";
        handler.postDelayed(this::render, 250);
    }

    private void controlAll(String action) {
        DownloadService.start(this, action);
        lastState = "";
        handler.postDelayed(this::render, 250);
    }

    private void chooseConcurrency() {
        String[] options = new String[DownloadStore.MAX_CONCURRENCY];
        for (int i = 0; i < options.length; i++) options[i] = String.valueOf(i + 1);
        new AlertDialog.Builder(this).setTitle("同时下载任务数")
                .setSingleChoiceItems(options, DownloadStore.getConcurrency(this) - 1, (dialog, which) -> {
                    DownloadStore.setConcurrency(this, which + 1);
                    updateConcurrencyLabel();
                    DownloadService.start(this, DownloadService.ACTION_RUN);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null).show();
    }

    private void updateConcurrencyLabel() {
        if (concurrencyButton != null) {
            concurrencyButton.setText("并发 " + DownloadStore.getConcurrency(this));
        }
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
