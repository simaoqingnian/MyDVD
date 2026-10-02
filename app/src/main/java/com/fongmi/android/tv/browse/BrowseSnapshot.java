package com.fongmi.android.tv.browse;

import android.text.TextUtils;
import android.util.AtomicFile;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.github.catvod.utils.Util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/** Bounded disk snapshots for previously loaded browse pages. */
public final class BrowseSnapshot {

    private static final int MAX_JSON_BYTES = 4_000_000;
    private static final int MAX_FILES = 80;

    private BrowseSnapshot() {
    }

    private static File directory(String kind) {
        return new File(new File(App.get().getFilesDir(), "browse-snapshots"), kind);
    }

    private static File file(String kind, String sourceUrl, String siteKey, String id) {
        String identity = kind + "\n" + sourceUrl + "\n" + siteKey + "\n" + id;
        return new File(directory(kind), Util.md5(identity) + ".json");
    }

    private static String categoryId(String typeId, Map<String, String> filters) {
        return typeId + "\n" + (filters == null ? "" : new TreeMap<>(filters));
    }

    public static Result loadCategory(String sourceUrl, String siteKey, String typeId,
                                      Map<String, String> filters) {
        return load("category", sourceUrl, siteKey, categoryId(typeId, filters));
    }

    public static void saveCategory(String sourceUrl, String siteKey, String typeId,
                                    Map<String, String> filters, Result result) {
        if (result != null && !result.getList().isEmpty())
            save("category", sourceUrl, siteKey, categoryId(typeId, filters), result);
    }

    public static Result load(String kind, String sourceUrl, String siteKey, String id) {
        if (TextUtils.isEmpty(sourceUrl) || TextUtils.isEmpty(siteKey)) return null;
        File path = file(kind, sourceUrl, siteKey, id);
        if (!path.exists() || path.length() <= 0 || path.length() > MAX_JSON_BYTES) return null;
        try (FileInputStream input = new AtomicFile(path).openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) path.length());
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_JSON_BYTES) return null;
                bytes.write(buffer, 0, count);
            }
            Result result = Result.objectFrom(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (result == null || result.getTypes().isEmpty() && result.getList().isEmpty()) return null;
            path.setLastModified(System.currentTimeMillis());
            return result;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    public static void save(String kind, String sourceUrl, String siteKey, String id, Result result) {
        if (TextUtils.isEmpty(sourceUrl) || TextUtils.isEmpty(siteKey) || result == null
                || result.getTypes().isEmpty() && result.getList().isEmpty()) return;
        byte[] bytes;
        try { bytes = result.toString().getBytes(StandardCharsets.UTF_8); }
        catch (RuntimeException ignored) { return; }
        if (bytes.length > MAX_JSON_BYTES) return;
        File directory = directory(kind);
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        AtomicFile target = new AtomicFile(file(kind, sourceUrl, siteKey, id));
        FileOutputStream output = null;
        try {
            output = target.startWrite();
            output.write(bytes);
            target.finishWrite(output);
            output = null;
            prune(directory);
        } catch (IOException | RuntimeException ignored) {
            if (output != null) target.failWrite(output);
        }
    }

    private static void prune(File directory) {
        File[] files = directory.listFiles((parent, name) -> name.endsWith(".json"));
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_FILES; i++) files[i].delete();
    }
}
