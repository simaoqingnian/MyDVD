package com.fongmi.android.tv.download;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/** Deletes the task's original media. Exported MP4 files stay in the system Downloads folder. */
final class DownloadFiles {

    private DownloadFiles() {
    }

    static void delete(Context context, DownloadTask task) throws IOException {
        File raw = DownloadManifest.directory(context, task.id);
        deleteTree(raw);
        File cache = new File(context.getCacheDir(), "mp4-downloads");
        deleteFile(new File(cache, task.id + ".source"));
        deleteFile(new File(cache, task.id + ".mp4"));
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取原始下载目录");
            for (File child : children) deleteTree(child);
        }
        deleteFile(file);
    }

    private static void deleteFile(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("无法删除文件：" + file.getName());
    }
}
