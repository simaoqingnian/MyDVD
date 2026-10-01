package com.fongmi.android.tv.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.exo.SharedMediaCache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadService extends Service {

    public static final String ACTION_RUN = "com.mydvd.app.DOWNLOAD_RUN";
    public static final String ACTION_PAUSE_TASK = "com.mydvd.app.DOWNLOAD_PAUSE_TASK";
    public static final String ACTION_RESUME_TASK = "com.mydvd.app.DOWNLOAD_RESUME_TASK";
    public static final String ACTION_RETRY_TASK = "com.mydvd.app.DOWNLOAD_RETRY_TASK";
    public static final String ACTION_DELETE = "com.mydvd.app.DOWNLOAD_DELETE";
    public static final String ACTION_START_ALL = "com.mydvd.app.DOWNLOAD_START_ALL";
    public static final String ACTION_PAUSE_ALL = "com.mydvd.app.DOWNLOAD_PAUSE_ALL";
    private static final String EXTRA_TASK_ID = "task_id";
    private static final String CHANNEL = "mydvd_downloads";
    private static final int NOTIFICATION = 4862;

    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private final ExecutorService workers = Executors.newFixedThreadPool(DownloadStore.MAX_CONCURRENCY);
    private final Object lifecycleLock = new Object();
    private final Object cleanupLock = new Object();
    private final Map<String, DownloadTask> active = new HashMap<>();
    private final Set<String> throttled = ConcurrentHashMap.newKeySet();
    private volatile int lastStartId;

    public static void start(Context context, String action) {
        Intent intent = new Intent(context, DownloadService.class).setAction(action);
        start(context, intent);
    }

    public static void delete(Context context, String taskId) {
        start(context, new Intent(context, DownloadService.class).setAction(ACTION_DELETE).putExtra(EXTRA_TASK_ID, taskId));
    }

    public static void control(Context context, String action, String taskId) {
        start(context, new Intent(context, DownloadService.class).setAction(action).putExtra(EXTRA_TASK_ID, taskId));
    }

    private static void start(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DownloadStore.recoverRunning(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "MyDVD 下载", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RUN : intent.getAction();
        startForeground(NOTIFICATION, notification(null));
        String taskId = intent == null ? null : intent.getStringExtra(EXTRA_TASK_ID);
        synchronized (lifecycleLock) {
            if (ACTION_PAUSE_ALL.equals(action)) DownloadStore.pauseAll(this);
            else if (ACTION_START_ALL.equals(action)) DownloadStore.startAll(this);
            else if (taskId != null) {
                if (ACTION_PAUSE_TASK.equals(action)) DownloadStore.pauseTask(this, taskId);
                else if (ACTION_RESUME_TASK.equals(action)) DownloadStore.resumeTask(this, taskId);
                else if (ACTION_RETRY_TASK.equals(action)) DownloadStore.retryTask(this, taskId);
                else if (ACTION_DELETE.equals(action)) DownloadStore.markDeleting(this, taskId);
            }
            lastStartId = startId;
        }
        changed();
        coordinator.execute(this::dispatch);
        return START_NOT_STICKY;
    }

    private void dispatch() {
        int stopId = 0;
        synchronized (lifecycleLock) {
            for (DownloadTask task : DownloadStore.list(this)) {
                if (DownloadTask.DELETING.equals(task.status) && !active.containsKey(task.id)) {
                    removeTask(task);
                }
            }
            int limit = DownloadStore.getConcurrency(this);
            throttled.clear();
            if (active.size() > limit) {
                int kept = 0;
                for (String id : active.keySet()) {
                    if (kept++ >= limit) throttled.add(id);
                }
            }
            for (DownloadTask task : DownloadStore.list(this)) {
                if (active.size() >= limit) break;
                if (!DownloadTask.QUEUED.equals(task.status) || active.containsKey(task.id)) continue;
                task.status = DownloadTask.RUNNING;
                task.error = "";
                active.put(task.id, task);
                save(task);
                workers.execute(() -> runTask(task));
            }
            if (active.isEmpty()) stopId = lastStartId;
        }
        if (stopId != 0 && stopSelfResult(stopId)) stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void runTask(DownloadTask task) {
        boolean completed = false;
        try {
            String uri = new DownloadEngine(this, new DownloadEngine.Callback() {
                @Override
                public void progress(int percent) throws InterruptedException {
                    synchronized (lifecycleLock) {
                        if (percent < 100) checkpoint();
                        if (percent != task.progress) {
                            task.progress = Math.max(0, Math.min(percent, 100));
                            save(task);
                        }
                    }
                }

                @Override
                public void checkpoint() throws InterruptedException {
                    if (DownloadStore.isDeleting(DownloadService.this, task.id)) throw new InterruptedException("已删除");
                    if (DownloadStore.isPausedTask(DownloadService.this, task.id)) throw new InterruptedException("已暂停");
                    if (throttled.contains(task.id)) throw new InterruptedException("并发数已降低");
                }
            }).run(task);
            synchronized (lifecycleLock) {
                task.outputUri = uri;
                if (DownloadStore.isDeleting(this, task.id)) {
                    DownloadStore.update(this, task);
                    removeTask(task);
                } else {
                    task.status = DownloadTask.DONE;
                    task.progress = 100;
                    save(task);
                    completed = true;
                }
            }
        } catch (InterruptedException interrupted) {
            failTask(task, interrupted, true);
        } catch (Exception error) {
            failTask(task, error, false);
        } finally {
            synchronized (lifecycleLock) {
                active.remove(task.id);
                throttled.remove(task.id);
            }
            try { coordinator.execute(this::dispatch); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
        if (completed) SharedMediaCache.pruneAsync();
    }

    private void failTask(DownloadTask task, Exception error, boolean interrupted) {
        synchronized (lifecycleLock) {
            if (DownloadStore.isDeleting(this, task.id)) {
                removeTask(task);
            } else if (DownloadStore.isPausedTask(this, task.id)) {
                task.status = DownloadTask.PAUSED;
                save(task);
            } else {
                task.status = interrupted ? DownloadTask.QUEUED : DownloadTask.FAILED;
                task.error = interrupted ? "" : error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                save(task);
            }
        }
    }

    private void save(DownloadTask task) {
        DownloadStore.update(this, task);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION, notification(task));
        changed();
    }

    private void changed() {
        sendBroadcast(new Intent("com.mydvd.app.DOWNLOAD_CHANGED").setPackage(getPackageName()));
    }

    private void removeTask(DownloadTask task) {
        synchronized (cleanupLock) {
            if (!DownloadStore.isDeleting(this, task.id)) return;
            try {
                DownloadFiles.delete(this, task);
                DownloadStore.remove(this, task.id);
                SharedMediaCache.pruneAsync();
                sendBroadcast(new Intent("com.mydvd.app.DOWNLOAD_CHANGED").setPackage(getPackageName()));
            } catch (Exception error) {
                task.status = DownloadTask.DELETE_FAILED;
                task.error = error.getMessage() == null ? "文件删除失败" : error.getMessage();
                save(task);
            }
        }
    }

    private Notification notification(DownloadTask task) {
        int running;
        synchronized (lifecycleLock) { running = active.size(); }
        String title = running > 0 ? "正在下载 " + running + " 项" : "DVD 下载";
        String state = task == null ? "等待任务" : task.title() + " · " + task.progress + "%";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(state)
                .setOngoing(running > 0)
                .setOnlyAlertOnce(true);
        if (running > 0) {
            Intent intent = new Intent(this, DownloadService.class).setAction(ACTION_PAUSE_ALL);
            PendingIntent pause = PendingIntent.getService(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, "全部暂停", pause);
            if (running == 1 && task != null && DownloadTask.RUNNING.equals(task.status)) {
                builder.setProgress(100, task.progress, false);
            }
        }
        return builder.build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        coordinator.shutdownNow();
        workers.shutdownNow();
        super.onDestroy();
    }
}
