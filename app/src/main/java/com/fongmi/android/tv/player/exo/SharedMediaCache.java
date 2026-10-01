package com.fongmi.android.tv.player.exo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;

import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheEvictor;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.download.DownloadStore;
import com.fongmi.android.tv.download.DownloadTask;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

/** One indexed Media3 cache for foreground playback, preloading, and MP4 export. */
public final class SharedMediaCache {

    private static final String LEGACY = "legacy";
    private static volatile Movie playing;
    private static Index index;
    private static final ExecutorService maintenance = Executors.newSingleThreadExecutor();

    private SharedMediaCache() {
    }

    private static synchronized Index index() {
        if (index == null) index = new Index(App.get());
        return index;
    }

    public static void activate(String movieId, String siteKey, String movieName, String episodeName) {
        playing = new Movie(movieId, siteKey, movieName, episodeName, System.currentTimeMillis());
    }

    public static void clearActive(String movieId) {
        Movie current = playing;
        if (current != null && current.id.equals(movieId)) {
            playing = null;
            pruneAsync();
        }
    }

    public static void pruneAsync() {
        maintenance.execute(() -> {
            try {
                pruneNow();
            } catch (RuntimeException error) {
                Log.w("MyDVDCache", "Could not prune cache", error);
            }
        });
    }

    public static void pruneNow() {
        Cache cache = MediaSourceFactory.getCache();
        pruneGroups(cache, Math.min(PreloadSetting.getPreloadSizeBytes(PlayerSetting.EXO),
                MediaSourceFactory.getCacheCapacityBytes()));
    }

    public static void recordPlaybackUrl(String url) {
        Movie current = playing;
        if (current != null && isHttp(url)) {
            try { index().record(url, current); }
            catch (RuntimeException error) { Log.w("MyDVDCache", "Could not index playback resource", error); }
        }
    }

    public static void recordTaskUrl(DownloadTask task, String url) {
        if (!isHttp(url)) return;
        String id = task.movieId == null || task.movieId.isEmpty()
                ? task.siteKey + ":" + task.movieName : task.movieId;
        try { index().record(url, new Movie(id, task.siteKey, task.movieName, task.episodeName, System.currentTimeMillis())); }
        catch (RuntimeException error) { Log.w("MyDVDCache", "Could not index download resource", error); }
    }

    public static boolean hasCachedBytes(String url) {
        return isHttp(url) && MediaSourceFactory.getCache().getCachedBytes(url, 0, Long.MAX_VALUE) > 0;
    }

    public static Response openHttp(DownloadTask task, String url, Map<String, String> headers) throws IOException {
        recordTaskUrl(task, url);
        CacheDataSource dataSource = new CacheDataSource.Factory()
                .setCache(MediaSourceFactory.getCache())
                .setUpstreamDataSourceFactory(new OkHttpDataSource.Factory(OkHttp.player()))
                .setCacheWriteDataSinkFactory(new CacheDataSink.Factory().setCache(MediaSourceFactory.getCache()).setFragmentSize(2L * 1024 * 1024))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource();
        DataSpec spec = new DataSpec.Builder().setUri(Uri.parse(url))
                .setHttpRequestHeaders(headers == null ? Map.of() : headers).build();
        long length;
        try {
            length = dataSource.open(spec);
        } catch (IOException error) {
            dataSource.close();
            throw error;
        }
        InputStream input = new InputStream() {
            private final byte[] single = new byte[1];
            @Override public int read() throws IOException {
                int count = read(single, 0, 1);
                return count < 0 ? -1 : single[0] & 0xff;
            }
            @Override public int read(byte[] buffer, int offset, int count) throws IOException {
                if (count == 0) return 0;
                return dataSource.read(buffer, offset, count);
            }
            @Override public void close() throws IOException { dataSource.close(); }
        };
        BufferedSource source = Okio.buffer(Okio.source(input));
        String mime = null;
        for (Map.Entry<String, List<String>> entry : dataSource.getResponseHeaders().entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) mime = entry.getValue().get(0);
        }
        MediaType mediaType = mime == null ? null : MediaType.parse(mime);
        ResponseBody body = new ResponseBody() {
            @Override public MediaType contentType() { return mediaType; }
            @Override public long contentLength() { return length; }
            @Override public BufferedSource source() { return source; }
        };
        Response.Builder response = new Response.Builder()
                .request(new Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body);
        if (mime != null) response.header("Content-Type", mime);
        return response.build();
    }

    public static List<CachedMovie> listMovies() {
        Cache cache = MediaSourceFactory.getCache();
        List<Group> groups = groups(cache);
        List<CachedMovie> result = new ArrayList<>();
        for (Group group : groups) {
            if (group.bytes <= 0) continue;
            result.add(new CachedMovie(group.movie.id, group.movie.siteKey, group.movie.name, group.bytes,
                    group.movie.lastAccess, isProtected(group.movie)));
        }
        result.sort(Comparator.comparingLong(CachedMovie::lastAccess).reversed());
        return result;
    }

    public static void deleteMovie(String movieId) throws IOException {
        Cache cache = MediaSourceFactory.getCache();
        for (Group group : groups(cache)) {
            if (!group.movie.id.equals(movieId)) continue;
            if (isProtected(group.movie)) throw new IOException("影片正在播放或被下载任务使用");
            try {
                for (String key : group.keys) cache.removeResource(key);
                index().removeGroup(movieId);
            } catch (RuntimeException error) {
                throw new IOException("删除播放缓存失败", error);
            }
            return;
        }
    }

    private static boolean isProtected(Movie movie) {
        Movie current = playing;
        if (current != null && current.id.equals(movie.id)) return true;
        for (DownloadTask task : DownloadStore.list(App.get())) {
            if (DownloadTask.DONE.equals(task.status) || DownloadTask.DELETING.equals(task.status)
                    || DownloadTask.DELETE_FAILED.equals(task.status)) continue;
            String id = task.movieId == null || task.movieId.isEmpty()
                    ? task.siteKey + ":" + task.movieName : task.movieId;
            if (movie.id.equals(id)) return true;
            if ((task.movieId == null || task.movieId.isEmpty())
                    && (LEGACY.equals(movie.id) || movie.siteKey.equals(task.siteKey)
                    && movie.name.equals(task.movieName))) return true;
        }
        return false;
    }

    private static boolean isHttp(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static List<Group> groups(Cache cache) {
        Map<String, String> resources = index().resources();
        Map<String, Movie> movies = index().movies();
        Map<String, Group> groups = new HashMap<>();
        for (String key : cache.getKeys()) {
            long bytes = 0;
            for (CacheSpan span : cache.getCachedSpans(key)) bytes += Math.max(0, span.length);
            if (bytes <= 0) continue;
            String movieId = resources.getOrDefault(key, LEGACY);
            Movie movie = movies.get(movieId);
            if (movie == null) movie = new Movie(movieId, "", "未归类的旧版缓存", "", 0);
            Group group = groups.get(movieId);
            if (group == null) groups.put(movieId, group = new Group(movie));
            group.keys.add(key);
            group.bytes += bytes;
        }
        return new ArrayList<>(groups.values());
    }

    private static void pruneGroups(Cache cache, long maxBytes) {
        if (cache.getCacheSpace() <= maxBytes) return;
        List<Group> candidates = groups(cache);
        candidates.sort(Comparator.comparingLong(group -> group.movie.lastAccess));
        for (Group group : candidates) {
            if (cache.getCacheSpace() <= maxBytes) break;
            if (isProtected(group.movie)) continue;
            for (String key : group.keys) cache.removeResource(key);
            index().removeGroup(group.movie.id);
        }
    }

    public record CachedMovie(String id, String siteKey, String title, long bytes, long lastAccess, boolean protectedFromDeletion) {
    }

    private record Movie(String id, String siteKey, String name, String episodeName, long lastAccess) {
    }

    private static final class Group {
        final Movie movie;
        final List<String> keys = new ArrayList<>();
        long bytes;

        Group(Movie movie) {
            this.movie = movie;
        }
    }

    /** Evicts whole movies in least recently used order; active downloads are pinned. */
    static final class MovieEvictor implements CacheEvictor {
        private final long maxBytes;
        private boolean ready;
        private boolean pruning;

        MovieEvictor(long maxBytes) {
            this.maxBytes = Math.max(0, maxBytes);
        }

        @Override
        public void onCacheInitialized() {
            ready = true;
        }

        @Override
        public void onStartFile(Cache cache, String key, long position, long length) {
        }

        @Override
        public void onSpanAdded(Cache cache, CacheSpan span) {
            try {
                if (!index().hasResource(span.key)) {
                    Movie current = playing;
                    index().record(span.key, current == null
                            ? new Movie(LEGACY, "", "未归类的旧版缓存", "", System.currentTimeMillis()) : current);
                }
                if (!ready || pruning || cache.getCacheSpace() <= maxBytes) return;
                pruning = true;
                try {
                    pruneGroups(cache, maxBytes);
                } finally {
                    pruning = false;
                }
            } catch (RuntimeException error) {
                Log.w("MyDVDCache", "Could not enforce cache limit", error);
            }
        }

        @Override
        public void onSpanRemoved(Cache cache, CacheSpan span) {
        }

        @Override
        public void onSpanTouched(Cache cache, CacheSpan oldSpan, CacheSpan newSpan) {
            try { index().touchKey(newSpan.key); }
            catch (RuntimeException error) { Log.w("MyDVDCache", "Could not update cache access", error); }
        }

        @Override
        public boolean requiresCacheSpanTouches() {
            return true;
        }
    }

    private static final class Index extends SQLiteOpenHelper {
        private final Map<String, Long> recentTouches = new HashMap<>();

        Index(Context context) {
            super(context.getApplicationContext(), "mydvd_media_cache.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE movies (id TEXT PRIMARY KEY, site_key TEXT, title TEXT, last_access INTEGER)");
            db.execSQL("CREATE TABLE resources (cache_key TEXT PRIMARY KEY, movie_id TEXT NOT NULL)");
            db.execSQL("CREATE INDEX resources_movie ON resources(movie_id)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }

        synchronized void record(String url, Movie movie) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues movieValues = new ContentValues();
            movieValues.put("id", movie.id);
            movieValues.put("site_key", movie.siteKey);
            movieValues.put("title", movie.name);
            movieValues.put("last_access", System.currentTimeMillis());
            db.insertWithOnConflict("movies", null, movieValues, SQLiteDatabase.CONFLICT_REPLACE);
            ContentValues resourceValues = new ContentValues();
            resourceValues.put("cache_key", url);
            resourceValues.put("movie_id", movie.id);
            db.insertWithOnConflict("resources", null, resourceValues, SQLiteDatabase.CONFLICT_REPLACE);
        }

        synchronized Map<String, String> resources() {
            Map<String, String> result = new HashMap<>();
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT cache_key,movie_id FROM resources", null)) {
                while (cursor.moveToNext()) result.put(cursor.getString(0), cursor.getString(1));
            }
            return result;
        }

        synchronized boolean hasResource(String key) {
            try (Cursor cursor = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM resources WHERE cache_key=? LIMIT 1", new String[]{key})) {
                return cursor.moveToFirst();
            }
        }

        synchronized Map<String, Movie> movies() {
            Map<String, Movie> result = new HashMap<>();
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,site_key,title,last_access FROM movies", null)) {
                while (cursor.moveToNext()) {
                    Movie movie = new Movie(cursor.getString(0), cursor.getString(1), cursor.getString(2), "", cursor.getLong(3));
                    result.put(movie.id, movie);
                }
            }
            return result;
        }

        synchronized void touchKey(String key) {
            long now = System.currentTimeMillis();
            Long previous = recentTouches.get(key);
            if (previous != null && now - previous < 60_000) return;
            recentTouches.put(key, now);
            getWritableDatabase().execSQL("UPDATE movies SET last_access=? WHERE id=(SELECT movie_id FROM resources WHERE cache_key=?)", new Object[]{now, key});
        }

        synchronized void removeGroup(String movieId) {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("resources", "movie_id=?", new String[]{movieId});
            db.delete("movies", "id=?", new String[]{movieId});
        }
    }
}
