package com.fongmi.android.tv.download;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.ParseJob;
import com.fongmi.android.tv.player.exo.MediaSourceFactory;
import com.fongmi.android.tv.player.exo.SharedMediaCache;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Response;

/** Saves a CatVod episode as durable raw media, then exports MP4 on request. */
public final class DownloadEngine {

    public interface Callback {
        void progress(int percent) throws InterruptedException;
        void segments(int downloaded, int total) throws InterruptedException;
        void checkpoint() throws InterruptedException;
    }

    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");
    private final Context context;
    private final Callback callback;
    private DownloadTask currentTask;

    public DownloadEngine(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void run(DownloadTask task) throws Exception {
        currentTask = task;
        MediaSourceFactory.acquireCacheSession();
        try {
            DownloadManifest manifest = DownloadManifest.load(context, task.id);
            if (manifest == null) manifest = prepare(task);
            callback.segments(manifest.downloadedSegments(context, task.id), manifest.totalSegments());
            if (DownloadManifest.HLS.equals(manifest.kind)) downloadHlsSegments(task, manifest);
            else downloadMp4(task, manifest);
            if (!manifest.complete(context, task.id)) throw new IOException("原始视频分段尚未完整下载");
            callback.progress(100);
        } finally {
            MediaSourceFactory.releaseCacheSession();
        }
    }

    public String export(DownloadTask task) throws Exception {
        currentTask = task;
        DownloadManifest manifest = DownloadManifest.load(context, task.id);
        if (manifest == null || !manifest.complete(context, task.id)) {
            throw new IOException("原始视频尚未下载完成");
        }
        if (DownloadManifest.MP4.equals(manifest.kind)) return publish(manifest.mp4File(context, task.id), task);
        File directory = new File(context.getCacheDir(), "mp4-downloads");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建导出临时目录");
        File source = new File(directory, task.id + ".source");
        File output = new File(directory, task.id + ".mp4");
        try {
            HlsDownload download = assembleForExport(task, manifest, source);
            remux(source, output, download);
            if (!output.isFile() || output.length() < 1024) throw new IOException("导出的 MP4 文件为空");
            return publish(output, task);
        } finally {
            source.delete();
            output.delete();
        }
    }

    private DownloadManifest prepare(DownloadTask task) throws Exception {
        Result result = SiteApi.playerContentForDownload(task.siteKey, task.flag, task.episodeId);
        if (result.getDrm() != null) throw new IOException("此线路使用 DRM，无法下载原始视频");
        Map<String, String> resolvedHeaders = new HashMap<>(result.getHeader());
        String resolvedUrl = result.getUrl().v();
        if (result.needParse() || result.shouldUseParse() || !result.getPlayUrl().isEmpty()) {
            resolvedUrl = parse(result, resolvedHeaders);
        }
        if (!resolvedUrl.startsWith("https://") && !resolvedUrl.startsWith("http://")) {
            throw new IOException("此线路不是可下载的 HTTP 视频地址");
        }
        boolean usePlayingUrl = task.preferredUrl != null
                && (task.preferredUrl.startsWith("https://") || task.preferredUrl.startsWith("http://"));
        String url = usePlayingUrl ? task.preferredUrl : resolvedUrl;
        Map<String, String> headers = usePlayingUrl && task.preferredHeaders != null
                ? new HashMap<>(task.preferredHeaders) : resolvedHeaders;
        Response initial;
        try { initial = request(url, headers); }
        catch (IOException expired) {
            if (!usePlayingUrl || url.equals(resolvedUrl)) throw expired;
            url = resolvedUrl;
            headers = resolvedHeaders;
            initial = request(url, headers);
        }
        DownloadManifest manifest;
        try (Response response = initial) {
            BufferedInputStream input = new BufferedInputStream(body(response));
            input.mark(32);
            byte[] signature = new byte[16];
            int read = 0;
            while (read < signature.length) {
                int count = input.read(signature, read, signature.length - read);
                if (count < 0) break;
                read += count;
            }
            input.reset();
            String prefix = new String(signature, 0, Math.max(0, read), StandardCharsets.UTF_8);
            boolean hls = url.toLowerCase(Locale.ROOT).contains(".m3u8")
                    || prefix.startsWith("#EXTM3U") || contentType(response).contains("mpegurl");
            if (hls) {
                manifest = parseHls(url, readText(input, 2 * 1024 * 1024), headers);
            } else {
                boolean mp4 = read >= 8 && signature[4] == 'f' && signature[5] == 't'
                        && signature[6] == 'y' && signature[7] == 'p';
                if (!mp4) throw new IOException("播放地址未返回 MP4 或 HLS 视频");
                manifest = DownloadManifest.create(DownloadManifest.MP4, url, headers);
                manifest.contentLength = response.body() == null ? -1 : response.body().contentLength();
            }
        }
        manifest.save(context, task.id);
        return manifest;
    }

    private String parse(Result result, Map<String, String> headers) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resolved = new AtomicReference<>();
        ParseJob job = ParseJob.create(new ParseCallback() {
            @Override
            public void onParseSuccess(Map<String, String> parsedHeaders, String url, String from) {
                headers.putAll(parsedHeaders);
                resolved.set(url);
                latch.countDown();
            }

            @Override
            public void onParseError() {
                latch.countDown();
            }
        }).start(result, result.shouldUseParse());
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) throw new IOException("该集的播放网页解析超时");
        } finally {
            App.post(job::stop);
        }
        if (resolved.get() == null) throw new IOException("该集的播放网页无法解析为视频地址");
        return resolved.get();
    }

    private Response request(String url, Map<String, String> headers) throws IOException {
        return request(url, headers, 0);
    }

    private Response request(String url, Map<String, String> headers, long position) throws IOException {
        Response response = SharedMediaCache.openHttp(currentTask, url, headers, position);
        if (!response.isSuccessful() || response.body() == null) {
            int code = response.code();
            response.close();
            throw new IOException("视频请求失败：HTTP " + code);
        }
        return response;
    }

    private InputStream body(Response response) throws IOException {
        if (response.body() == null) throw new IOException("视频响应为空");
        return response.body().byteStream();
    }

    private String contentType(Response response) {
        String value = response.header("Content-Type");
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String readText(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > maxBytes) throw new IOException("HLS 播放列表过大");
            out.write(buffer, 0, count);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private DownloadManifest parseHls(String playlistUrl, String playlist, Map<String, String> headers) throws Exception {
        for (int depth = 0; depth < 4 && playlist.contains("#EXT-X-STREAM-INF"); depth++) {
            String next = selectVariant(playlistUrl, playlist);
            try (Response response = request(next, headers)) {
                playlist = readText(body(response), 2 * 1024 * 1024);
            }
            playlistUrl = next;
        }
        if (playlist.contains("#EXT-X-STREAM-INF")) throw new IOException("HLS 多级索引过深");
        if (!playlist.contains("#EXT-X-ENDLIST")) throw new IOException("直播流无法完整下载为离线视频");
        if (playlist.contains("#EXT-X-BYTERANGE")) throw new IOException("此 HLS 使用分段字节范围，暂不支持导出");
        long sequence = 0;
        String keyUrl = null;
        String ivHex = null;
        String initUrl = null;
        long durationUs = 0;
        int period = 0;
        boolean pendingDiscontinuity = false;
        List<Segment> segments = new ArrayList<>();
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) sequence = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
            else if (line.equals("#EXT-X-DISCONTINUITY")) pendingDiscontinuity = true;
            else if (line.startsWith("#EXT-X-MAP:")) {
                String nextInitUrl = resolve(playlistUrl, attributes(line).get("URI"));
                if (initUrl != null && !initUrl.equals(nextInitUrl)
                        && !segments.isEmpty() && !pendingDiscontinuity) {
                    throw new IOException("HLS 更换初始化分段时缺少时间轴切换标记");
                }
                initUrl = nextInitUrl;
            }
            else if (line.startsWith("#EXTINF:")) {
                String value = line.substring(8).split(",", 2)[0].trim();
                try { durationUs = Math.max(0, Math.round(Double.parseDouble(value) * 1_000_000)); }
                catch (NumberFormatException error) { throw new IOException("HLS 分段时长无效", error); }
            }
            else if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> attrs = attributes(line);
                String method = attrs.get("METHOD");
                if ("NONE".equals(method)) { keyUrl = null; ivHex = null; }
                else if ("AES-128".equals(method)) { keyUrl = resolve(playlistUrl, attrs.get("URI")); ivHex = attrs.get("IV"); }
                else throw new IOException("此 HLS 使用不支持的加密方式：" + method);
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                if (pendingDiscontinuity && !segments.isEmpty()) period++;
                pendingDiscontinuity = false;
                segments.add(new Segment(resolve(playlistUrl, line), keyUrl, ivHex, sequence++, durationUs,
                        period, initUrl));
                durationUs = 0;
            }
        }
        if (segments.isEmpty()) throw new IOException("HLS 没有可下载的分段");
        DownloadManifest manifest = DownloadManifest.create(DownloadManifest.HLS, playlistUrl, headers);
        for (Segment segment : segments) {
            manifest.segments.add(new DownloadManifest.Segment(segment.url, segment.keyUrl,
                    segment.iv, segment.sequence, segment.durationUs, segment.period, segment.initUrl));
        }
        return manifest;
    }

    private void downloadHlsSegments(DownloadTask task, DownloadManifest manifest) throws Exception {
        File directory = DownloadManifest.directory(context, task.id);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建原始下载目录");
        Map<String, byte[]> keys = new HashMap<>();
        int done = manifest.downloadedSegments(context, task.id);
        for (int i = 0; i < manifest.segments.size(); i++) {
            callback.checkpoint();
            DownloadManifest.Segment item = manifest.segments.get(i);
            if (item.initUrl != null) {
                File init = manifest.initFile(context, task.id, item.period);
                if (init.length() <= 0) writeSegment(init, item.initUrl, manifest.headers, null, keys);
            }
            File file = manifest.segmentFile(context, task.id, i);
            if (file.length() <= 0) {
                Segment segment = new Segment(item.url, item.keyUrl, item.iv, item.sequence,
                        item.durationUs, item.period, item.initUrl);
                writeSegment(file, item.url, manifest.headers, segment, keys);
                done++;
                callback.segments(done, manifest.segments.size());
                callback.progress((int) (100L * done / manifest.segments.size()));
            }
        }
        manifest.writeLocalPlaylist(context, task.id);
    }

    private void writeSegment(File target, String url, Map<String, String> headers,
                              Segment segment, Map<String, byte[]> keys) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        try {
            if (target.exists() && !target.delete()) throw new IOException("无法重置损坏的分段");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                append(url, headers, output, segment, keys);
                output.getFD().sync();
            }
            if (temporary.length() == 0) throw new IOException("HLS 分段为空：" + target.getName());
            if (!temporary.renameTo(target)) throw new IOException("无法保存分段：" + target.getName());
        } finally {
            temporary.delete();
        }
    }

    private void downloadMp4(DownloadTask task, DownloadManifest manifest) throws Exception {
        File target = manifest.mp4File(context, task.id);
        if (target.length() >= 1024) return;
        if (target.exists() && !target.delete()) throw new IOException("无法重置损坏的原始 MP4");
        File temporary = new File(target.getParentFile(), "source.mp4.part");
        long resume = temporary.isFile() ? temporary.length() : 0;
        if (manifest.contentLength > 0 && resume > manifest.contentLength) {
            if (!temporary.delete()) throw new IOException("无法重置损坏的 MP4 分段");
            resume = 0;
        }
        if (resume >= 1024 && manifest.contentLength > 0 && resume == manifest.contentLength) {
            if (!temporary.renameTo(target)) throw new IOException("无法保存原始 MP4");
            callback.segments(1, 1);
            return;
        }
        try (Response response = request(manifest.url, manifest.headers, resume);
             InputStream input = body(response);
             FileOutputStream output = new FileOutputStream(temporary, resume > 0)) {
            byte[] buffer = new byte[65536];
            long written = resume;
            int count;
            while ((count = input.read(buffer)) != -1) {
                callback.checkpoint();
                output.write(buffer, 0, count);
                written += count;
                if (manifest.contentLength > 0) {
                    callback.progress((int) Math.min(99, 100L * written / manifest.contentLength));
                }
            }
            output.getFD().sync();
        }
        if (temporary.length() < 1024 || (manifest.contentLength > 0
                && temporary.length() != manifest.contentLength)) {
            throw new IOException("MP4 下载长度不完整，可点击继续下载");
        }
        if (!temporary.renameTo(target)) throw new IOException("无法保存原始 MP4");
        callback.segments(1, 1);
    }

    private HlsDownload assembleForExport(DownloadTask task, DownloadManifest manifest,
                                          File source) throws Exception {
        List<SegmentRange> ranges = new ArrayList<>(manifest.segments.size());
        List<SegmentRange> periods = new ArrayList<>();
        int previousPeriod = -1;
        try (FileOutputStream output = new FileOutputStream(source)) {
            for (int i = 0; i < manifest.segments.size(); i++) {
                callback.checkpoint();
                DownloadManifest.Segment segment = manifest.segments.get(i);
                boolean newPeriod = i == 0 || segment.period != previousPeriod;
                long periodStart = output.getChannel().position();
                if (newPeriod && segment.initUrl != null) {
                    appendFile(manifest.initFile(context, task.id, segment.period), output);
                }
                long start = output.getChannel().position();
                appendFile(manifest.segmentFile(context, task.id, i), output);
                long length = output.getChannel().position() - start;
                if (length == 0) throw new IOException("原始分段为空：" + (i + 1));
                ranges.add(new SegmentRange(start, length, segment.durationUs));
                if (newPeriod) {
                    periods.add(new SegmentRange(periodStart,
                            output.getChannel().position() - periodStart, segment.durationUs));
                } else {
                    SegmentRange previous = periods.remove(periods.size() - 1);
                    periods.add(new SegmentRange(previous.offset,
                            output.getChannel().position() - previous.offset,
                            previous.durationUs + segment.durationUs));
                }
                previousPeriod = segment.period;
            }
            output.getFD().sync();
        }
        boolean discontinuity = periods.size() > 1;
        boolean transport = manifest.segments.get(0).initUrl == null && looksLikeTs(source);
        return new HlsDownload(transport, discontinuity, discontinuity ? periods : ranges);
    }

    private void appendFile(File input, OutputStream output) throws IOException {
        try (FileInputStream source = new FileInputStream(input)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private boolean looksLikeTs(File source) throws IOException {
        byte[] signature = new byte[377];
        try (FileInputStream in = new FileInputStream(source)) {
            int read = 0;
            while (read < signature.length) {
                int count = in.read(signature, read, signature.length - read);
                if (count < 0) break;
                read += count;
            }
            return read >= signature.length && signature[0] == 0x47
                    && signature[188] == 0x47 && signature[376] == 0x47;
        }
    }

    private String selectVariant(String base, String playlist) throws IOException {
        String[] lines = playlist.split("\\r?\\n");
        String selected = null;
        long best = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
            Map<String, String> attrs = attributes(lines[i]);
            if (attrs.containsKey("AUDIO") && playlist.contains("#EXT-X-MEDIA:TYPE=AUDIO")) {
                throw new IOException("此 HLS 的音频与视频分离，暂不能合并");
            }
            int next = i + 1;
            while (next < lines.length && (lines[next].trim().isEmpty() || lines[next].startsWith("#"))) next++;
            if (next >= lines.length) continue;
            long bandwidth;
            try { bandwidth = Long.parseLong(attrs.getOrDefault("BANDWIDTH", "0")); }
            catch (NumberFormatException ignored) { bandwidth = 0; }
            if (bandwidth > best) { best = bandwidth; selected = lines[next].trim(); }
        }
        if (selected == null) throw new IOException("HLS 主列表没有可用清晰度");
        return resolve(base, selected);
    }

    private Map<String, String> attributes(String line) {
        Map<String, String> attrs = new HashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(line.substring(line.indexOf(':') + 1));
        while (matcher.find()) attrs.put(matcher.group(1), matcher.group(2) == null ? matcher.group(3) : matcher.group(2));
        return attrs;
    }

    private String resolve(String base, String relative) throws IOException {
        if (relative == null || relative.isEmpty()) throw new IOException("HLS 分段地址为空");
        try { return URI.create(base).resolve(relative).toString(); }
        catch (IllegalArgumentException e) { throw new IOException("HLS 分段地址无效", e); }
    }

    private void append(String url, Map<String, String> headers, OutputStream out, Segment segment, Map<String, byte[]> keys) throws Exception {
        try (Response response = request(url, headers)) {
            InputStream in = body(response);
            if (segment != null && segment.keyUrl != null) {
                byte[] key = keys.get(segment.keyUrl);
                if (key == null) {
                    try (Response keyResponse = request(segment.keyUrl, headers)) {
                        key = readBytes(body(keyResponse), 32);
                    }
                    if (key.length != 16) throw new IOException("HLS AES-128 密钥长度错误");
                    keys.put(segment.keyUrl, key);
                }
                byte[] iv = segment.iv == null ? sequenceIv(segment.sequence) : parseIv(segment.iv);
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                in = new CipherInputStream(in, cipher);
            }
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                callback.checkpoint();
                out.write(buffer, 0, count);
            }
        }
    }

    private byte[] readBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("HLS 密钥响应过大");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private byte[] sequenceIv(long sequence) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 8; i--) { iv[i] = (byte) sequence; sequence >>>= 8; }
        return iv;
    }

    private byte[] parseIv(String value) throws IOException {
        String hex = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        if (hex.length() > 32 || (hex.length() & 1) != 0) throw new IOException("HLS IV 格式无效");
        byte[] iv = new byte[16];
        for (int i = 0; i < hex.length() / 2; i++) {
            try { iv[16 - hex.length() / 2 + i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16); }
            catch (NumberFormatException e) { throw new IOException("HLS IV 格式无效", e); }
        }
        return iv;
    }

    private void copyFile(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
    }

    private void remux(File source, File output, HlsDownload download) throws Exception {
        if (download.hasDiscontinuity) {
            remuxSource(source, output, download.ranges, true);
            return;
        }
        if (!download.transportStream) {
            remuxSource(source, output, null, false);
            return;
        }
        try {
            remuxSource(source, output, download.ranges, false);
        } catch (IOException segmentError) {
            if (output.exists() && !output.delete()) throw segmentError;
            try {
                remuxSource(source, output, null, false);
            } catch (IOException wholeFileError) {
                wholeFileError.addSuppressed(segmentError);
                throw wholeFileError;
            }
        }
    }

    private void remuxSource(File source, File output, List<SegmentRange> ranges,
                             boolean verifyFormats) throws Exception {
        MediaMuxer muxer = null;
        boolean started = false;
        boolean stopped = false;
        TrackLayout layout = null;
        try (FileInputStream input = new FileInputStream(source)) {
            int parts = ranges == null ? 1 : ranges.size();
            long partStartUs = 0;
            for (int part = 0; part < parts; part++) {
                callback.checkpoint();
                MediaExtractor extractor = new MediaExtractor();
                try {
                    long offset = ranges == null ? 0 : ranges.get(part).offset;
                    long length = ranges == null ? source.length() : ranges.get(part).length;
                    extractor.setDataSource(input.getFD(), offset, length);
                    if (extractor.getTrackCount() == 0) {
                        throw new IOException("第 " + (part + 1) + " 段没有可导出的音视频轨道");
                    }
                    if (layout == null) {
                        muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                        layout = new TrackLayout(extractor, muxer, input.getFD(), offset, length);
                        muxer.start();
                        started = true;
                    }
                    int[] tracks;
                    try {
                        tracks = layout.selectTracks(extractor, input.getFD(), offset, length,
                                verifyFormats && part > 0);
                    } catch (IncompatiblePeriodException mismatch) {
                        // Some playlists insert short clips encoded differently between two
                        // stretches of the same movie. Only omit a clip when the following
                        // period returns to the movie's original track layout.
                        if (!isShortInterstitial(ranges, part)
                                || !nextPeriodMatches(input.getFD(), ranges.get(part + 1), layout)) {
                            throw mismatch;
                        }
                        continue;
                    }
                    long lastSampleUs = writeSamples(extractor, muxer, layout, tracks, partStartUs);
                    if (ranges != null) {
                        long durationUs = ranges.get(part).durationUs;
                        partStartUs = Math.max(partStartUs + durationUs, lastSampleUs + 33_333);
                    }
                } finally {
                    extractor.release();
                }
            }
            if (layout == null || layout.totalSamples == 0) throw new IOException("源视频没有可封装的音视频样本");
            for (int track = 0; track < layout.samples.length; track++) {
                if (layout.samples[track] == 0) {
                    throw new IOException("音视频轨道没有有效样本：" + layout.mimes.get(track));
                }
            }
            muxer.stop();
            stopped = true;
        } catch (RuntimeException error) {
            String tracks = layout == null ? "" : "；轨道 " + layout.describe();
            throw new IOException("此视频无法封装为 MP4：" + error.getMessage() + tracks, error);
        } finally {
            if (muxer != null) {
                if (started && !stopped) {
                    // MediaMuxer.release() also calls stop(). Keep a cleanup error from hiding the real failure.
                    try { muxer.stop(); } catch (RuntimeException ignored) { }
                }
                muxer.release();
            }
        }
    }

    private boolean isShortInterstitial(List<SegmentRange> ranges, int part) {
        return ranges != null && part > 0 && part + 1 < ranges.size()
                && ranges.get(part).durationUs > 0
                && ranges.get(part).durationUs <= 30_000_000L;
    }

    private boolean nextPeriodMatches(FileDescriptor fd, SegmentRange next,
                                      TrackLayout layout) {
        MediaExtractor probe = new MediaExtractor();
        try {
            probe.setDataSource(fd, next.offset, next.length);
            layout.selectTracks(probe, fd, next.offset, next.length, true);
            return true;
        } catch (IOException | RuntimeException mismatch) {
            return false;
        } finally {
            probe.release();
        }
    }

    private long writeSamples(MediaExtractor extractor, MediaMuxer muxer, TrackLayout layout,
                              int[] tracks, long partStartUs) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
        long firstTimestamp = -1;
        long lastSampleUs = partStartUs;
        while (true) {
            callback.checkpoint();
            int sourceTrack = extractor.getSampleTrackIndex();
            if (sourceTrack < 0) break;
            long sampleSize = extractor.getSampleSize();
            if (sampleSize > Integer.MAX_VALUE) throw new IOException("视频样本过大");
            if (sampleSize > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) sampleSize);
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            int targetTrack = tracks[sourceTrack];
            if (targetTrack >= 0 && size > 0) {
                long timestamp = extractor.getSampleTime();
                if (timestamp < 0) throw new IOException("视频样本时间戳无效");
                if (firstTimestamp < 0) firstTimestamp = timestamp;
                long presentationUs = partStartUs + Math.max(0, timestamp - firstTimestamp);
                // Correct a reset at a segment boundary without rewriting small B-frame PTS reordering.
                if (presentationUs + 500_000 < layout.lastTimestampUs[targetTrack]) {
                    presentationUs = layout.lastTimestampUs[targetTrack] + 1;
                }
                int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                if (layout.videoTracks[targetTrack] && layout.samples[targetTrack] == 0) {
                    flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                }
                info.set(0, size, presentationUs, flags);
                muxer.writeSampleData(targetTrack, buffer, info);
                layout.samples[targetTrack]++;
                layout.totalSamples++;
                layout.lastTimestampUs[targetTrack] = presentationUs;
                lastSampleUs = Math.max(lastSampleUs, presentationUs);
            }
            extractor.advance();
        }
        return lastSampleUs;
    }

    private String publish(File file, DownloadTask task) throws Exception {
        String filename = safeName(task.title()) + "-" + task.id.substring(0, 8)
                + "-" + System.currentTimeMillis() + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MyDVD");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("无法写入系统下载目录");
            try {
                try (InputStream in = new FileInputStream(file); OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("无法打开系统下载文件");
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = in.read(buffer)) != -1) { callback.checkpoint(); out.write(buffer, 0, count); }
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
                return uri.toString();
            } catch (Exception error) {
                context.getContentResolver().delete(uri, null, null);
                throw error;
            }
        }
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) throw new IOException("设备没有可用的下载目录");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建下载目录");
        File target = new File(directory, filename);
        try {
            copyFile(file, target);
            return FileProvider.getUriForFile(context, context.getPackageName() + ".provider", target).toString();
        } catch (Exception error) {
            target.delete();
            throw error;
        }
    }

    private String safeName(String value) {
        String clean = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.length() > 120) clean = clean.substring(0, 120);
        return clean.isEmpty() ? "video" : clean;
    }

    private static final class Segment {
        final String url;
        final String keyUrl;
        final String iv;
        final long sequence;
        final long durationUs;
        final int period;
        final String initUrl;

        Segment(String url, String keyUrl, String iv, long sequence, long durationUs,
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

    private static final class SegmentRange {
        final long offset;
        final long length;
        final long durationUs;

        SegmentRange(long offset, long length, long durationUs) {
            this.offset = offset;
            this.length = length;
            this.durationUs = durationUs;
        }
    }

    private static final class HlsDownload {
        final boolean transportStream;
        final boolean hasDiscontinuity;
        final List<SegmentRange> ranges;

        HlsDownload(boolean transportStream, boolean hasDiscontinuity, List<SegmentRange> ranges) {
            this.transportStream = transportStream;
            this.hasDiscontinuity = hasDiscontinuity;
            this.ranges = ranges;
        }
    }

    private static final class IncompatiblePeriodException extends IOException {
        IncompatiblePeriodException(String message) {
            super(message);
        }
    }

    private static final class TrackLayout {
        final List<String> mimes = new ArrayList<>();
        final List<MediaFormat> formats = new ArrayList<>();
        final List<String> descriptions = new ArrayList<>();
        final boolean[] videoTracks;
        final long[] lastTimestampUs;
        final long[] samples;
        long totalSamples;

        TrackLayout(MediaExtractor extractor, MediaMuxer muxer, FileDescriptor fd,
                    long offset, long length) throws IOException {
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || (!mime.startsWith("video/") && !mime.startsWith("audio/"))) continue;
                VideoCodecConfig.ensure(format, mime, fd, offset, length, i);
                try { muxer.addTrack(format); }
                catch (RuntimeException error) { throw new IOException("此视频编码无法封装为 MP4：" + mime, error); }
                mimes.add(mime);
                formats.add(format);
                descriptions.add(mime + " csd0=" + format.containsKey("csd-0")
                        + " csd1=" + format.containsKey("csd-1"));
            }
            if (mimes.isEmpty()) throw new IOException("视频没有可导出的音视频轨道");
            videoTracks = new boolean[mimes.size()];
            lastTimestampUs = new long[mimes.size()];
            samples = new long[mimes.size()];
            Arrays.fill(lastTimestampUs, -1);
            for (int i = 0; i < mimes.size(); i++) videoTracks[i] = mimes.get(i).startsWith("video/");
        }

        int[] selectTracks(MediaExtractor extractor, FileDescriptor fd, long offset, long length,
                           boolean verifyFormats) throws IOException {
            int[] tracks = new int[extractor.getTrackCount()];
            Arrays.fill(tracks, -1);
            for (int target = 0; target < mimes.size(); target++) {
                boolean found = false;
                for (int source = 0; source < tracks.length; source++) {
                    MediaFormat format = extractor.getTrackFormat(source);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (tracks[source] < 0 && mimes.get(target).equals(mime)) {
                        if (verifyFormats) {
                            VideoCodecConfig.ensure(format, mime, fd, offset, length, source);
                            if (!sameFormat(formats.get(target), format, mime)) {
                                throw new IncompatiblePeriodException("HLS 时间轴切换处音视频编码或参数变化，无法合并为单个 MP4：" + mime);
                            }
                        }
                        tracks[source] = target;
                        extractor.selectTrack(source);
                        found = true;
                        break;
                    }
                }
                if (!found) throw new IncompatiblePeriodException("HLS 分段缺少音视频轨道：" + mimes.get(target));
            }
            if (verifyFormats) {
                for (int source = 0; source < tracks.length; source++) {
                    if (tracks[source] >= 0) continue;
                    String mime = extractor.getTrackFormat(source).getString(MediaFormat.KEY_MIME);
                    if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                        throw new IncompatiblePeriodException("HLS 时间轴切换处增加了音视频轨道，无法合并为单个 MP4：" + mime);
                    }
                }
            }
            return tracks;
        }

        private static boolean sameFormat(MediaFormat first, MediaFormat second, String mime) {
            String[] keys = mime.startsWith("video/")
                    ? new String[]{MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT}
                    : new String[]{MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT};
            for (String key : keys) {
                if (first.containsKey(key) != second.containsKey(key)) return false;
                if (first.containsKey(key) && first.getInteger(key) != second.getInteger(key)) return false;
            }
            for (String key : new String[]{"csd-0", "csd-1", "csd-2"}) {
                if (first.containsKey(key) != second.containsKey(key)) return false;
                if (first.containsKey(key)) {
                    ByteBuffer before = first.getByteBuffer(key);
                    ByteBuffer after = second.getByteBuffer(key);
                    if (before == null || after == null) return false;
                    ByteBuffer left = before.duplicate();
                    ByteBuffer right = after.duplicate();
                    left.position(0);
                    right.position(0);
                    if (!left.equals(right)) return false;
                }
            }
            return true;
        }

        String describe() {
            return String.join(", ", descriptions);
        }
    }

    private static final class VideoCodecConfig {
        private static final byte[] START_CODE = {0, 0, 0, 1};
        private byte[] vps;
        private byte[] sps;
        private byte[] pps;

        static void ensure(MediaFormat format, String mime, FileDescriptor fd,
                           long offset, long length, int track) throws IOException {
            boolean avc = "video/avc".equals(mime);
            boolean hevc = "video/hevc".equals(mime);
            if (!avc && !hevc) return;
            if (hasData(format, "csd-0") && (!avc || hasData(format, "csd-1"))) return;

            VideoCodecConfig config = new VideoCodecConfig();
            MediaExtractor probe = new MediaExtractor();
            try {
                probe.setDataSource(fd, offset, length);
                probe.selectTrack(track);
                ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
                long scanned = 0;
                for (int sample = 0; sample < 256 && scanned < 16L * 1024 * 1024; sample++) {
                    if (probe.getSampleTrackIndex() < 0) break;
                    long sampleSize = probe.getSampleSize();
                    if (sampleSize < 0 || sampleSize > 16L * 1024 * 1024) break;
                    if (sampleSize > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) sampleSize);
                    buffer.clear();
                    int size = probe.readSampleData(buffer, 0);
                    if (size < 0) break;
                    byte[] bytes = new byte[size];
                    buffer.position(0);
                    buffer.get(bytes);
                    config.scan(bytes, avc);
                    scanned += size;
                    if (config.complete(avc)) break;
                    probe.advance();
                }
            } finally {
                probe.release();
            }
            if (!config.complete(avc)) {
                throw new IOException("视频轨道缺少 " + (avc ? "H.264 SPS/PPS" : "H.265 VPS/SPS/PPS")
                        + " 编码配置，无法封装 MP4");
            }
            if (avc) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(config.sps));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(config.pps));
            } else {
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                all.write(config.vps, 0, config.vps.length);
                all.write(config.sps, 0, config.sps.length);
                all.write(config.pps, 0, config.pps.length);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(all.toByteArray()));
            }
        }

        private static boolean hasData(MediaFormat format, String key) {
            if (!format.containsKey(key)) return false;
            ByteBuffer data = format.getByteBuffer(key);
            return data != null && data.remaining() > 0;
        }

        private boolean complete(boolean avc) {
            return sps != null && pps != null && (avc || vps != null);
        }

        private void scan(byte[] bytes, boolean avc) {
            int start = findStartCode(bytes, 0);
            if (start >= 0) {
                while (start >= 0) {
                    int prefix = bytes[start + 2] == 1 ? 3 : 4;
                    int nalStart = start + prefix;
                    int next = findStartCode(bytes, nalStart);
                    int end = next < 0 ? bytes.length : next;
                    while (end > nalStart && bytes[end - 1] == 0) end--;
                    accept(bytes, nalStart, end, avc);
                    start = next;
                }
                return;
            }
            // MP4 samples may use four-byte NAL lengths instead of Annex B start codes.
            int cursor = 0;
            while (cursor + 4 < bytes.length) {
                long size = ((long) (bytes[cursor] & 255) << 24)
                        | ((long) (bytes[cursor + 1] & 255) << 16)
                        | ((long) (bytes[cursor + 2] & 255) << 8)
                        | (bytes[cursor + 3] & 255);
                cursor += 4;
                if (size <= 0 || size > bytes.length - cursor) break;
                accept(bytes, cursor, cursor + (int) size, avc);
                cursor += (int) size;
            }
        }

        private static int findStartCode(byte[] bytes, int from) {
            for (int i = from; i + 3 < bytes.length; i++) {
                if (bytes[i] == 0 && bytes[i + 1] == 0
                        && (bytes[i + 2] == 1 || (bytes[i + 2] == 0 && bytes[i + 3] == 1))) return i;
            }
            return -1;
        }

        private void accept(byte[] bytes, int start, int end, boolean avc) {
            if (start >= end) return;
            int type = avc ? bytes[start] & 31 : (bytes[start] >> 1) & 63;
            if (avc && type != 7 && type != 8) return;
            if (!avc && type != 32 && type != 33 && type != 34) return;
            byte[] data = new byte[START_CODE.length + end - start];
            System.arraycopy(START_CODE, 0, data, 0, START_CODE.length);
            System.arraycopy(bytes, start, data, START_CODE.length, end - start);
            if (avc) {
                if (type == 7 && sps == null) sps = data;
                else if (type == 8 && pps == null) pps = data;
            } else {
                if (type == 32 && vps == null) vps = data;
                else if (type == 33 && sps == null) sps = data;
                else if (type == 34 && pps == null) pps = data;
            }
        }
    }
}
