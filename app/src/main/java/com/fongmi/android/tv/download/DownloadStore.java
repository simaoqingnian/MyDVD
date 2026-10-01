package com.fongmi.android.tv.download;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.fongmi.android.tv.App;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DownloadStore {

    private static final String PREFS = "mydvd_downloads";
    private static final String TASKS = "tasks";
    private static final String CONCURRENCY = "concurrency";
    public static final int MAX_CONCURRENCY = 10;
    private static final Type LIST_TYPE = TypeToken.getParameterized(List.class, DownloadTask.class).getType();

    private DownloadStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getConcurrency(Context context) {
        return Math.max(1, Math.min(MAX_CONCURRENCY, prefs(context).getInt(CONCURRENCY, 1)));
    }

    public static void setConcurrency(Context context, int count) {
        prefs(context).edit().putInt(CONCURRENCY, Math.max(1, Math.min(MAX_CONCURRENCY, count))).apply();
    }

    public static synchronized List<DownloadTask> list(Context context) {
        try {
            List<DownloadTask> items = App.gson().fromJson(prefs(context).getString(TASKS, "[]"), LIST_TYPE);
            return items == null ? new ArrayList<>() : new ArrayList<>(items);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    /** Returns an exported MP4 only when the exact episode and its file are still available. */
    public static Uri findCompletedUri(Context context, String siteKey, String movieId,
                                       String flag, String episodeId) {
        List<DownloadTask> tasks = list(context);
        for (int i = tasks.size() - 1; i >= 0; i--) {
            DownloadTask task = tasks.get(i);
            if (!DownloadTask.DONE.equals(task.status) || task.outputUri == null
                    || task.outputUri.isEmpty() || !Objects.equals(siteKey, task.siteKey)
                    || !Objects.equals(movieId, task.movieId) || !Objects.equals(flag, task.flag)
                    || !Objects.equals(episodeId, task.episodeId)) continue;
            try {
                Uri uri = Uri.parse(task.outputUri);
                if (!"content".equalsIgnoreCase(uri.getScheme())) continue;
                try (ParcelFileDescriptor file = context.getContentResolver().openFileDescriptor(uri, "r")) {
                    if (file != null && file.getStatSize() != 0) return uri;
                }
            } catch (Exception ignored) {
                // The user may have deleted or moved the exported file outside the app.
            }
        }
        return null;
    }

    private static void save(Context context, List<DownloadTask> items) {
        prefs(context).edit().putString(TASKS, App.gson().toJson(items)).apply();
    }

    public static synchronized void add(Context context, List<DownloadTask> incoming) {
        List<DownloadTask> items = list(context);
        items.addAll(incoming);
        save(context, items);
    }

    public static synchronized void update(Context context, DownloadTask task) {
        List<DownloadTask> items = list(context);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(task.id)) {
                if (DownloadTask.DELETING.equals(items.get(i).status)
                        && !DownloadTask.DELETING.equals(task.status)
                        && !DownloadTask.DELETE_FAILED.equals(task.status)) {
                    if (task.outputUri != null && !task.outputUri.isEmpty()) {
                        items.get(i).outputUri = task.outputUri;
                        save(context, items);
                    }
                    return;
                }
                items.set(i, task);
                save(context, items);
                return;
            }
        }
    }

    public static synchronized boolean markDeleting(Context context, String id) {
        List<DownloadTask> items = list(context);
        for (DownloadTask task : items) {
            if (!task.id.equals(id)) continue;
            task.status = DownloadTask.DELETING;
            task.error = "";
            save(context, items);
            return true;
        }
        return false;
    }

    public static synchronized boolean isDeleting(Context context, String id) {
        for (DownloadTask task : list(context)) {
            if (task.id.equals(id)) return DownloadTask.DELETING.equals(task.status);
        }
        return false;
    }

    public static synchronized void remove(Context context, String id) {
        List<DownloadTask> items = list(context);
        if (items.removeIf(task -> task.id.equals(id))) save(context, items);
    }

    public static synchronized boolean pauseTask(Context context, String id) {
        List<DownloadTask> items = list(context);
        for (DownloadTask task : items) {
            if (task.id.equals(id) && (DownloadTask.QUEUED.equals(task.status)
                    || DownloadTask.RUNNING.equals(task.status))) {
                task.status = DownloadTask.PAUSED;
                save(context, items);
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean pauseAll(Context context) {
        List<DownloadTask> items = list(context);
        boolean changed = false;
        for (DownloadTask task : items) {
            if (DownloadTask.QUEUED.equals(task.status) || DownloadTask.RUNNING.equals(task.status)) {
                task.status = DownloadTask.PAUSED;
                changed = true;
            }
        }
        if (changed) save(context, items);
        return changed;
    }

    public static synchronized boolean startAll(Context context) {
        List<DownloadTask> items = list(context);
        boolean changed = false;
        for (DownloadTask task : items) {
            if (DownloadTask.PAUSED.equals(task.status) || DownloadTask.FAILED.equals(task.status)) {
                task.status = DownloadTask.QUEUED;
                task.error = "";
                task.progress = 0;
                changed = true;
            }
        }
        if (changed) save(context, items);
        return changed;
    }

    public static synchronized boolean resumeTask(Context context, String id) {
        List<DownloadTask> items = list(context);
        for (DownloadTask task : items) {
            if (task.id.equals(id) && DownloadTask.PAUSED.equals(task.status)) {
                task.status = DownloadTask.QUEUED;
                task.progress = 0;
                save(context, items);
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean retryTask(Context context, String id) {
        List<DownloadTask> items = list(context);
        for (DownloadTask task : items) {
            if (task.id.equals(id) && DownloadTask.FAILED.equals(task.status)) {
                task.status = DownloadTask.QUEUED;
                task.error = "";
                task.progress = 0;
                save(context, items);
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean isPausedTask(Context context, String id) {
        for (DownloadTask task : list(context)) {
            if (task.id.equals(id)) return DownloadTask.PAUSED.equals(task.status);
        }
        return false;
    }

    public static synchronized boolean isQueuedTask(Context context, String id) {
        for (DownloadTask task : list(context)) {
            if (task.id.equals(id)) return DownloadTask.QUEUED.equals(task.status);
        }
        return false;
    }

    public static synchronized void recoverRunning(Context context) {
        List<DownloadTask> items = list(context);
        for (DownloadTask task : items) {
            if (DownloadTask.RUNNING.equals(task.status)) {
                task.status = DownloadTask.QUEUED;
                task.progress = 0;
            }
        }
        save(context, items);
    }

}
