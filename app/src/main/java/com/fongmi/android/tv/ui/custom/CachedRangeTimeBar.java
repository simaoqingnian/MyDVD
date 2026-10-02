package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.media3.ui.DefaultTimeBar;

import com.fongmi.android.tv.download.DownloadManifest;

import java.lang.reflect.Field;
import java.util.List;

/** Draws noncontiguous disk cache on the native buffered track, using its exact bounds. */
public class CachedRangeTimeBar extends DefaultTimeBar {

    private static final Field PROGRESS_BAR = field("progressBar");
    private static final Field SCRUBBER_BAR = field("scrubberBar");
    private static final Field BUFFERED_PAINT = field("bufferedPaint");

    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<DownloadManifest.Range> cachedRanges = List.of();
    private long durationMs;

    public CachedRangeTimeBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        fallbackPaint.setColor(0xCCFFFFFF);
    }

    @Override
    public void setDuration(long duration) {
        super.setDuration(duration);
        durationMs = duration;
    }

    public void setCachedRanges(List<DownloadManifest.Range> ranges) {
        cachedRanges = List.copyOf(ranges);
        invalidate();
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (durationMs <= 0 || cachedRanges.isEmpty()) return;
        Rect track = rect(PROGRESS_BAR);
        Rect played = rect(SCRUBBER_BAR);
        if (track == null || played == null || track.width() <= 0) return;
        Paint paint = paint(BUFFERED_PAINT);
        for (DownloadManifest.Range range : cachedRanges) {
            int left = track.left + (int) (track.width() * Math.max(0, Math.min(durationMs,
                    range.startMs())) / (double) durationMs);
            int right = track.left + (int) (track.width() * Math.max(0, Math.min(durationMs,
                    range.endMs())) / (double) durationMs);
            left = Math.max(left, played.right);
            if (right > left) canvas.drawRect(left, track.top, right, track.bottom,
                    paint == null ? fallbackPaint : paint);
        }
    }

    private static Field field(String name) {
        try {
            Field field = DefaultTimeBar.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private Rect rect(Field field) {
        try { return field == null ? null : (Rect) field.get(this); }
        catch (IllegalAccessException ignored) { return null; }
    }

    private Paint paint(Field field) {
        try { return field == null ? null : (Paint) field.get(this); }
        catch (IllegalAccessException ignored) { return null; }
    }
}
