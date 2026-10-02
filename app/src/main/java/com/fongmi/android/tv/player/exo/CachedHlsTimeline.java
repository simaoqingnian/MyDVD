package com.fongmi.android.tv.player.exo;

import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;

import com.fongmi.android.tv.download.DownloadManifest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Rebuilds a VOD timeline from playlists and segments already present in Media3's disk cache. */
final class CachedHlsTimeline {

    private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DEPTH = 4;

    private CachedHlsTimeline() {
    }

    static List<DownloadManifest.Range> ranges(Cache cache, String rootUrl) {
        return snapshot(cache, rootUrl).ranges();
    }

    static Snapshot snapshot(Cache cache, String rootUrl) {
        if (rootUrl == null || rootUrl.isEmpty()) return Snapshot.EMPTY;
        return readPlaylist(cache, rootUrl, 0, new HashSet<>());
    }

    /** Only direct VOD media playlists have a meaningful segment count. */
    static Snapshot mediaSnapshot(Cache cache, String url) {
        Snapshot snapshot = snapshot(cache, url);
        return snapshot.mediaPlaylist() ? snapshot : Snapshot.EMPTY;
    }

    static boolean isPlaylist(Cache cache, String url) {
        String text = normalize(cachedText(cache, url));
        return text != null && text.startsWith("#EXTM3U");
    }

    private static Snapshot readPlaylist(Cache cache, String url, int depth, Set<String> visited) {
        if (depth >= MAX_DEPTH || !visited.add(url)) return Snapshot.EMPTY;
        String text = normalize(cachedText(cache, url));
        if (text == null || !text.startsWith("#EXTM3U")) return Snapshot.EMPTY;
        if (text.contains("#EXT-X-STREAM-INF")) {
            String[] lines = text.split("\\r?\\n");
            List<DownloadManifest.Range> ranges = new ArrayList<>();
            long durationMs = 0;
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
                int next = i + 1;
                while (next < lines.length && (lines[next].trim().isEmpty()
                        || lines[next].trim().startsWith("#"))) next++;
                if (next < lines.length) {
                    String child = resolve(url, lines[next].trim());
                    if (child != null) {
                        Snapshot snapshot = readPlaylist(cache, child, depth + 1, visited);
                        ranges.addAll(snapshot.ranges());
                        durationMs = Math.max(durationMs, snapshot.durationMs());
                    }
                }
            }
            return new Snapshot(ranges, durationMs, 0, 0, false);
        }
        if (!text.contains("#EXTINF") || !text.contains("#EXT-X-ENDLIST")) return Snapshot.EMPTY;
        List<DownloadManifest.Range> ranges = new ArrayList<>();
        int cachedSegments = 0;
        int totalSegments = 0;
        long positionUs = 0;
        long durationUs = 0;
        long nextRangeOffset = 0;
        long rangeLength = -1;
        String initUrl = null;
        long initRangeOffset = 0;
        long initRangeLength = -1;
        String keyUrl = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                String value = line.substring(8).split(",", 2)[0].trim();
                try { durationUs = Math.max(0, Math.round(Double.parseDouble(value) * 1_000_000)); }
                catch (NumberFormatException ignored) { durationUs = 0; }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                String value = line.substring(line.indexOf(':') + 1).trim();
                String[] parts = value.split("@", 2);
                try {
                    rangeLength = Long.parseLong(parts[0]);
                    if (parts.length == 2) nextRangeOffset = Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) { rangeLength = -1; }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                initUrl = resolve(url, quotedUri(line));
                initRangeOffset = 0;
                initRangeLength = -1;
                String mapRange = quotedAttribute(line, "BYTERANGE");
                if (mapRange != null) {
                    String[] parts = mapRange.split("@", 2);
                    try {
                        initRangeLength = Long.parseLong(parts[0]);
                        if (parts.length == 2) initRangeOffset = Long.parseLong(parts[1]);
                    } catch (NumberFormatException ignored) { initRangeLength = -1; }
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                if (line.toUpperCase(Locale.ROOT).contains("METHOD=NONE")) keyUrl = null;
                else keyUrl = resolve(url, quotedUri(line));
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String segmentUrl = resolve(url, line);
                long endUs = positionUs + durationUs;
                boolean dependenciesCached = (initUrl == null || fullyCached(cache, initUrl,
                        initRangeOffset, initRangeLength))
                        && (keyUrl == null || fullyCached(cache, keyUrl, 0, -1));
                if (durationUs > 0 && dependenciesCached && segmentUrl != null
                        && fullyCached(cache, segmentUrl,
                        rangeLength > 0 ? nextRangeOffset : 0, rangeLength)) {
                    ranges.add(new DownloadManifest.Range(positionUs / 1000, endUs / 1000));
                    cachedSegments++;
                }
                if (durationUs > 0) totalSegments++;
                if (rangeLength > 0) nextRangeOffset += rangeLength;
                else nextRangeOffset = 0;
                rangeLength = -1;
                durationUs = 0;
                positionUs = endUs;
            }
        }
        return new Snapshot(ranges, positionUs / 1000, cachedSegments, totalSegments, true);
    }

    record Snapshot(List<DownloadManifest.Range> ranges, long durationMs,
                    int cachedSegments, int totalSegments, boolean mediaPlaylist) {
        static final Snapshot EMPTY = new Snapshot(List.of(), 0, 0, 0, false);
    }

    private static String quotedUri(String line) {
        return quotedAttribute(line, "URI");
    }

    private static String quotedAttribute(String line, String name) {
        int start = line.indexOf(name + "=\"");
        if (start < 0) return null;
        start += name.length() + 2;
        int end = line.indexOf('"', start);
        return end < 0 ? null : line.substring(start, end);
    }

    private static String resolve(String base, String relative) {
        if (relative == null || relative.isEmpty()) return null;
        try { return URI.create(base).resolve(relative).toString(); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static String normalize(String text) {
        return text != null && text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static boolean fullyCached(Cache cache, String url, long offset, long rangeLength) {
        if (offset < 0) return false;
        long length = rangeLength > 0 ? rangeLength
                : ContentMetadata.getContentLength(cache.getContentMetadata(url));
        return length > 0 && (rangeLength > 0 || offset == 0)
                && cache.isCached(url, offset, length);
    }

    private static String cachedText(Cache cache, String url) {
        long length = ContentMetadata.getContentLength(cache.getContentMetadata(url));
        if (length <= 0 || length > MAX_PLAYLIST_BYTES || !cache.isCached(url, 0, length)) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) length);
        long cursor = 0;
        for (CacheSpan span : cache.getCachedSpans(url)) {
            if (cursor >= length) break;
            if (span.position > cursor) return null;
            long offset = cursor - span.position;
            long count = Math.min(span.length - offset, length - cursor);
            File file = span.file;
            if (file == null || count <= 0) continue;
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                if (cursor == 0 && span.position == 0) {
                    byte[] prefix = new byte[(int) Math.min(10, count)];
                    int read = input.read(prefix);
                    String beginning = read <= 0 ? "" : new String(prefix, 0, read, StandardCharsets.UTF_8);
                    if (!beginning.startsWith("#EXTM3U") && !beginning.startsWith("\uFEFF#EXTM3U")) return null;
                }
                input.seek(offset);
                byte[] buffer = new byte[8192];
                while (count > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, count));
                    if (read < 0) return null;
                    output.write(buffer, 0, read);
                    cursor += read;
                    count -= read;
                }
            } catch (IOException ignored) { return null; }
        }
        return cursor == length ? new String(output.toByteArray(), StandardCharsets.UTF_8) : null;
    }
}
