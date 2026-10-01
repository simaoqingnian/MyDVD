package com.fongmi.android.tv.download;

import java.util.UUID;
import java.util.Map;

public class DownloadTask {

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";
    public static final String FAILED = "failed";
    public static final String DONE = "done";
    public static final String DELETING = "deleting";
    public static final String DELETE_FAILED = "delete_failed";

    public String id;
    public String siteKey;
    public String flag;
    public String episodeId;
    public String movieName;
    public String movieId;
    public String episodeName;
    public String preferredUrl;
    public Map<String, String> preferredHeaders;
    public String status;
    public String error;
    public String outputUri;
    public int progress;

    public DownloadTask() {
    }

    public static DownloadTask create(String siteKey, String flag, String episodeId, String movieId, String movieName, String episodeName) {
        DownloadTask task = new DownloadTask();
        task.id = UUID.randomUUID().toString();
        task.siteKey = siteKey;
        task.flag = flag;
        task.episodeId = episodeId;
        task.movieName = movieName;
        task.movieId = movieId;
        task.episodeName = episodeName;
        task.status = QUEUED;
        task.error = "";
        task.outputUri = "";
        return task;
    }

    public String title() {
        return movieName + " - " + episodeName;
    }
}
