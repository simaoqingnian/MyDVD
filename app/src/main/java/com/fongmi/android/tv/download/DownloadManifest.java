package com.fongmi.android.tv.download;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import com.fongmi.android.tv.App;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent source files and timeline for one episode. The exported MP4 is separate. */
public final class DownloadManifest {

    public static final String HLS = "hls";
    public static final String MP4 = "mp4";

    public String kind;
    public String url;
    public Map<String, String> headers;
    public long contentLength;
    public List<Segment> segments = new ArrayList<>();

    public static final class Segment {
        public String url;
        public String keyUrl;
        public String iv;
        public long sequence;
        public long durationUs;
        public int period;
        public String initUrl;

        public Segment() {
        }

        public Segment(String url, String keyUrl, String iv, long sequence, long durationUs,
                       int period, String initUrl) {
            this.url = url;
            this.keyUrl = keyUrl;
            this.iv = iv;
            this.sequence = sequence;
            this.durationUs = durationUs;
            this.period = period;
            this.initUrl = initUrl;
        }
    }

    public record Range(long startMs, long endMs) {
    }

    private DownloadManifest() {
    }

    public static DownloadManifest create(String kind, String url, Map<String, String> headers) {
        DownloadManifest manifest = new DownloadManifest();
        manifest.kind = kind;
        manifest.url = url;
        manifest.headers = headers;
        return manifest;
    }

    public static File directory(Context context, String taskId) throws IOException {
        try { UUID.fromString(taskId); }
        catch (RuntimeException error) { throw new IOException("下载任务编号无效", error); }
        return new File(new File(context.getFilesDir(), "raw-downloads"), taskId);
    }

    public static DownloadManifest load(Context context, String taskId) {
        try {
            AtomicFile file = new AtomicFile(new File(directory(context, taskId), "manifest.json"));
            byte[] bytes;
            try (FileInputStream input = file.openRead()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                bytes = output.toByteArray();
            }
            DownloadManifest manifest = App.gson().fromJson(new String(bytes, StandardCharsets.UTF_8), DownloadManifest.class);
            return manifest != null && (HLS.equals(manifest.kind) || MP4.equals(manifest.kind))
                    && manifest.segments != null ? manifest : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public void save(Context context, String taskId) throws IOException {
        File directory = directory(context, taskId);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建原始下载目录");
        AtomicFile file = new AtomicFile(new File(directory, "manifest.json"));
        FileOutputStream output = file.startWrite();
        try {
            output.write(App.gson().toJson(this).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
    }

    public File segmentFile(Context context, String taskId, int index) throws IOException {
        Segment segment = segments.get(index);
        String path = Uri.parse(segment.url).getPath();
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        String extension = segment.initUrl != null ? "m4s" : lower.endsWith(".aac") ? "aac"
                : lower.endsWith(".mp3") ? "mp3" : "ts";
        return new File(directory(context, taskId), String.format(java.util.Locale.ROOT,
                "%06d.%s", index, extension));
    }

    public File initFile(Context context, String taskId, int period) throws IOException {
        return new File(directory(context, taskId), "init-" + period + ".mp4");
    }

    public File mp4File(Context context, String taskId) throws IOException {
        return new File(directory(context, taskId), "source.mp4");
    }

    public int downloadedSegments(Context context, String taskId) throws IOException {
        if (MP4.equals(kind)) return mp4File(context, taskId).length() >= 1024 ? 1 : 0;
        int count = 0;
        for (int i = 0; i < segments.size(); i++) {
            if (segmentFile(context, taskId, i).length() > 0) count++;
        }
        return count;
    }

    public int totalSegments() {
        return MP4.equals(kind) ? 1 : segments.size();
    }

    public boolean complete(Context context, String taskId) throws IOException {
        if (MP4.equals(kind)) return mp4File(context, taskId).length() >= 1024;
        if (segments.isEmpty()) return false;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segmentFile(context, taskId, i).length() <= 0) return false;
            if (segment.initUrl != null && !initFile(context, taskId, segment.period).isFile()) return false;
        }
        return new File(directory(context, taskId), "index.m3u8").length() > 0;
    }

    public Uri localUri(Context context, String taskId) throws IOException {
        return Uri.fromFile(MP4.equals(kind) ? mp4File(context, taskId)
                : new File(directory(context, taskId), "index.m3u8"));
    }

    public List<Range> downloadedRanges(Context context, String taskId) throws IOException {
        List<Range> result = new ArrayList<>();
        if (!HLS.equals(kind)) return result;
        long positionUs = 0;
        for (int i = 0; i < segments.size(); i++) {
            long endUs = positionUs + Math.max(0, segments.get(i).durationUs);
            if (segmentFile(context, taskId, i).length() > 0) {
                result.add(new Range(positionUs / 1000, endUs / 1000));
            }
            positionUs = endUs;
        }
        return result;
    }

    public long durationMs() {
        long durationUs = 0;
        for (Segment segment : segments) durationUs += Math.max(0, segment.durationUs);
        return durationUs / 1000;
    }

    public void writeLocalPlaylist(Context context, String taskId) throws IOException {
        if (!HLS.equals(kind)) return;
        if (segments.isEmpty()) throw new IOException("没有可播放的 HLS 分段");
        long maxDurationUs = 0;
        boolean fragmented = false;
        for (Segment segment : segments) {
            maxDurationUs = Math.max(maxDurationUs, segment.durationUs);
            fragmented |= segment.initUrl != null;
        }
        StringBuilder text = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:")
                .append(fragmented ? 7 : 3).append("\n#EXT-X-TARGETDURATION:")
                .append(Math.max(1, (maxDurationUs + 999_999) / 1_000_000))
                .append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        int previousPeriod = -1;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (i > 0 && segment.period != previousPeriod) text.append("#EXT-X-DISCONTINUITY\n");
            if (segment.initUrl != null && segment.period != previousPeriod) {
                text.append("#EXT-X-MAP:URI=\"init-").append(segment.period).append(".mp4\"\n");
            }
            text.append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.6f", segment.durationUs / 1_000_000d))
                    .append(",\n").append(segmentFile(context, taskId, i).getName()).append('\n');
            previousPeriod = segment.period;
        }
        text.append("#EXT-X-ENDLIST\n");
        AtomicFile file = new AtomicFile(new File(directory(context, taskId), "index.m3u8"));
        FileOutputStream output = file.startWrite();
        try {
            output.write(text.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
    }
}
