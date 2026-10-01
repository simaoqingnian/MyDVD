package com.fongmi.android.tv.download;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Deletes only files belonging to a download task. Call after the active writer has stopped. */
final class DownloadFiles {

    private DownloadFiles() {
    }

    static void delete(Context context, DownloadTask task) throws IOException {
        try {
            UUID.fromString(task.id);
        } catch (RuntimeException invalid) {
            throw new IOException("下载任务编号无效", invalid);
        }
        File cache = new File(context.getCacheDir(), "mp4-downloads");
        deleteFile(new File(cache, task.id + ".source"));
        deleteFile(new File(cache, task.id + ".mp4"));
        if (task.outputUri == null || task.outputUri.isEmpty()) return;

        Uri uri = Uri.parse(task.outputUri);
        if ("media".equals(uri.getAuthority()) && "content".equals(uri.getScheme())) {
            try {
                context.getContentResolver().delete(uri, null, null);
            } catch (RuntimeException error) {
                throw new IOException("无法删除已下载的影片", error);
            }
            return;
        }

        if (!"content".equals(uri.getScheme())
                || !(context.getPackageName() + ".provider").equals(uri.getAuthority())) {
            throw new IOException("下载文件位置无法识别，请手动删除该文件");
        }
        File downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) throw new IOException("下载目录不可用");
        String name = uri.getLastPathSegment();
        if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
            throw new IOException("下载文件名无效");
        }
        File target = new File(downloads, name).getCanonicalFile();
        if (!downloads.getCanonicalFile().equals(target.getParentFile())) {
            throw new IOException("下载文件不在应用目录内");
        }
        deleteFile(target);
    }

    private static void deleteFile(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("无法删除文件：" + file.getName());
    }
}
