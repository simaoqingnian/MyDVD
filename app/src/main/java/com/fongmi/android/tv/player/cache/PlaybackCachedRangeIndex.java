package com.fongmi.android.tv.player.cache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.download.DownloadManifest;
import com.fongmi.android.tv.player.exo.SharedMediaCache;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persists completed preload intervals and verifies their underlying cache before display. */
public final class PlaybackCachedRangeIndex extends SQLiteOpenHelper {

    private static final int MEDIA3 = 1;
    private static final int FILE = 2;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static PlaybackCachedRangeIndex instance;

    public static synchronized PlaybackCachedRangeIndex get() {
        if (instance == null) instance = new PlaybackCachedRangeIndex(App.get());
        return instance;
    }

    private PlaybackCachedRangeIndex(Context context) {
        super(context.getApplicationContext(), "mydvd_cached_ranges.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE ranges (media_uri TEXT NOT NULL, kind INTEGER NOT NULL, "
                + "resource TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, "
                + "PRIMARY KEY(media_uri,kind,resource,start_ms,end_ms))");
        db.execSQL("CREATE INDEX ranges_media_uri ON ranges(media_uri)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public synchronized void recordMedia3(String mediaUri, String cacheKey, long startMs, long endMs) {
        try { record(mediaUri, MEDIA3, cacheKey, startMs, endMs); }
        catch (RuntimeException ignored) { }
    }

    public synchronized void recordFile(String mediaUri, File file, long startMs, long endMs) {
        if (file != null && file.isFile() && file.length() > 0) {
            try { record(mediaUri, FILE, file.getAbsolutePath(), startMs, endMs); }
            catch (RuntimeException ignored) { }
        }
    }

    public record FileRange(File file, long startMs, long endMs) {
    }

    public void recordFilesAsync(String mediaUri, List<FileRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return;
        WRITER.execute(() -> recordFiles(mediaUri, ranges));
    }

    public synchronized void recordFiles(String mediaUri, List<FileRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                for (FileRange range : ranges) {
                    File file = range.file();
                    if (file != null && file.isFile() && file.length() > 0) {
                        record(mediaUri, FILE, file.getAbsolutePath(), range.startMs(), range.endMs());
                    }
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (RuntimeException ignored) { }
    }

    private void record(String mediaUri, int kind, String resource, long startMs, long endMs) {
        if (mediaUri == null || mediaUri.isEmpty() || resource == null || resource.isEmpty()
                || startMs < 0 || endMs <= startMs) return;
        ContentValues values = new ContentValues();
        values.put("media_uri", mediaUri);
        values.put("kind", kind);
        values.put("resource", resource);
        values.put("start_ms", startMs);
        values.put("end_ms", endMs);
        getWritableDatabase().insertWithOnConflict("ranges", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized List<DownloadManifest.Range> ranges(String mediaUri) {
        List<DownloadManifest.Range> result = new ArrayList<>();
        if (mediaUri == null || mediaUri.isEmpty()) return result;
        List<Long> stale = new ArrayList<>();
        Map<String, Boolean> media3Present = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT rowid,kind,resource,start_ms,end_ms FROM ranges WHERE media_uri=?",
                new String[]{mediaUri})) {
            while (cursor.moveToNext()) {
                int kind = cursor.getInt(1);
                String resource = cursor.getString(2);
                boolean present = kind == FILE ? new File(resource).isFile()
                        && new File(resource).length() > 0
                        : kind == MEDIA3 && media3Present.computeIfAbsent(resource,
                        SharedMediaCache::hasCachedBytes);
                if (present) result.add(new DownloadManifest.Range(cursor.getLong(3), cursor.getLong(4)));
                else stale.add(cursor.getLong(0));
            }
        }
        if (!stale.isEmpty()) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                for (long rowId : stale) db.delete("ranges", "rowid=?", new String[]{Long.toString(rowId)});
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        return result;
    }
}
