package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.ui.TimeBar;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.download.DownloadManifest;
import com.fongmi.android.tv.player.cache.PlaybackDiskBufferStore;
import com.fongmi.android.tv.player.cache.PlaybackCachedRangeIndex;
import com.fongmi.android.tv.player.exo.SharedMediaCache;
import com.github.catvod.crawler.SpiderDebug;

import java.util.Formatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CustomSeekView extends FrameLayout implements Player.Listener, TimeBar.OnScrubListener {

    private static final int MAX_UPDATE_INTERVAL_MS = 1000;
    private static final int MIN_UPDATE_INTERVAL_MS = 200;
    private static final long DISK_RANGE_GAP_TOLERANCE_MS = 2000;
    private static final long PAUSED_BUFFER_LOG_INTERVAL_MS = 5000;
    private static final long SEEK_POSITION_TOLERANCE_MS = 1500;
    private static final long SEEK_POSITION_HOLD_TIMEOUT_MS = 10000;
    private static final ExecutorService RANGE_READER = Executors.newSingleThreadExecutor();

    private final StringBuilder timeBuilder = new StringBuilder();
    private final Formatter timeFormatter = new Formatter(timeBuilder, Locale.getDefault());
    private final TextView positionView;
    private final TextView durationView;
    private final CachedRangeTimeBar timeBar;
    private final Runnable runnable;
    private long currentDuration;
    private long currentPosition;
    private long currentBuffered;
    private boolean scrubbing;
    private boolean attached;
    private Player player;
    private Player progressPlayer;
    private long lastPausedBufferLogAtMs;
    private long lastPausedBufferedPosition;
    private long lastBufferReadLogAtMs;
    private long lastBufferReadEffectiveMs = -1;
    private long pendingSeekPosition = C.TIME_UNSET;
    private long pendingSeekOrigin = C.TIME_UNSET;
    private long pendingSeekDeadlineMs;
    private String downloadTaskId;
    private int downloadGeneration;
    private long lastRangeRefreshAtMs;

    public CustomSeekView(Context context) {
        this(context, null);
    }

    public CustomSeekView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomSeekView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_control_seek, this);
        positionView = findViewById(R.id.position);
        durationView = findViewById(R.id.duration);
        timeBar = findViewById(R.id.timeBar);
        runnable = this::updateProgress;
        timeBar.addListener(this);
        resetView();
    }

    public void setPlayer(Player player) {
        if (this.player != null) this.player.removeListener(this);
        clearPendingSeek();
        this.player = player;
        if (player != null) player.addListener(this);
        if (attached) updateTimeline();
    }

    public void setDownloadTaskId(@Nullable String taskId) {
        if (java.util.Objects.equals(downloadTaskId, taskId)) return;
        downloadTaskId = taskId;
        downloadGeneration++;
        lastRangeRefreshAtMs = 0;
        setDisplayedRanges(List.of());
        if (attached) updateDownloadedRanges();
    }

    /**
     * Uses the in-process engine for timeline reads while commands continue to
     * go through the MediaController. MediaSession does not continuously send
     * buffered-position-only changes while playback is paused, which otherwise
     * leaves native-player buffer bars stale until playback resumes.
     */
    public void setProgressPlayer(@Nullable Player progressPlayer) {
        if (this.progressPlayer == progressPlayer) return;
        this.progressPlayer = progressPlayer;
        lastPausedBufferLogAtMs = 0;
        lastPausedBufferedPosition = 0;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("playback-progress", "action=bind source=%s",
                    progressPlayer == null ? "controller" : "direct-engine");
        }
        if (attached) updateTimeline();
    }

    @Nullable
    private Player getProgressPlayer() {
        return progressPlayer != null ? progressPlayer : player;
    }

    private String stringToTime(long time) {
        return Util.getStringForTime(timeBuilder, timeFormatter, time);
    }

    private void updateTimeline() {
        Player progress = getProgressPlayer();
        if (!attached || progress == null) return;
        long duration = progress.getDuration();
        if (duration < 0) duration = 0;
        currentDuration = duration;
        setKeyTimeIncrement(duration);
        timeBar.setDuration(duration);
        durationView.setText(stringToTime(duration));
        updateProgress();
    }

    private void updateProgress() {
        removeCallbacks(runnable);
        Player progress = getProgressPlayer();
        if (!attached || progress == null) return;
        long reportedPosition = Math.max(0, progress.getCurrentPosition());
        long position = stableSeekPosition(reportedPosition);
        long buffered = effectiveBufferedPosition(progress);
        long duration = progress.getDuration();
        if (duration < 0) duration = 0;
        if (duration != currentDuration) {
            currentDuration = duration;
            setKeyTimeIncrement(duration);
            timeBar.setDuration(duration);
            durationView.setText(stringToTime(duration));
        }
        if (position != currentPosition) {
            currentPosition = position;
            if (!scrubbing) {
                timeBar.setPosition(position);
                positionView.setText(stringToTime(position));
            }
        }
        if (buffered != currentBuffered) {
            currentBuffered = buffered;
            timeBar.setBufferedPosition(buffered);
        }
        updateDownloadedRanges();
        logPausedBufferProgress(progress, position, buffered);
        if (progress.isPlaying()) {
            postDelayed(runnable, delayMs(progress, position));
        } else {
            postDelayed(runnable, MAX_UPDATE_INTERVAL_MS);
        }
    }

    private void updateDownloadedRanges() {
        String taskId = downloadTaskId;
        if (!attached) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRangeRefreshAtMs < 2000) return;
        lastRangeRefreshAtMs = now;
        int generation = downloadGeneration;
        long timelineDuration = currentDuration;
        Player progress = getProgressPlayer();
        MediaItem mediaItem = progress == null ? null : progress.getCurrentMediaItem();
        String mediaKey = PlaybackDiskBufferStore.mediaKey(mediaItem);
        String mediaUri = mediaItem == null || mediaItem.localConfiguration == null ? ""
                : mediaItem.localConfiguration.uri.toString();
        String mimeType = mediaItem == null || mediaItem.localConfiguration == null ? ""
                : mediaItem.localConfiguration.mimeType;
        android.content.Context context = getContext().getApplicationContext();
        RANGE_READER.execute(() -> {
            DownloadManifest manifest = taskId == null || taskId.isEmpty()
                    ? null : DownloadManifest.load(context, taskId);
            List<DownloadManifest.Range> ranges = new ArrayList<>();
            if (manifest != null) {
                try {
                    long manifestDuration = manifest.durationMs();
                    addScaledRanges(ranges, manifest.downloadedRanges(context, taskId),
                            manifestDuration, timelineDuration);
                    addScaledRanges(ranges, SharedMediaCache.cachedRanges(manifest),
                            manifestDuration, timelineDuration);
                } catch (java.io.IOException | RuntimeException ignored) { }
            }
            for (PlaybackDiskBufferStore.Range range :
                    PlaybackDiskBufferStore.process().completedRanges(mediaKey)) {
                ranges.add(new DownloadManifest.Range(range.startMs(), range.endMs()));
            }
            SharedMediaCache.PlaybackRanges playback = SharedMediaCache.cachedPlaybackRanges(
                    mediaUri, mimeType, timelineDuration);
            addScaledRanges(ranges, playback.ranges(), playback.durationMs(), timelineDuration);
            try { ranges.addAll(PlaybackCachedRangeIndex.get().ranges(mediaUri)); }
            catch (RuntimeException ignored) { }
            post(() -> {
                if (attached && generation == downloadGeneration) {
                    setDisplayedRanges(ranges);
                }
            });
        });
    }

    private static void addScaledRanges(List<DownloadManifest.Range> target,
                                        List<DownloadManifest.Range> source,
                                        long sourceDuration, long targetDuration) {
        if (sourceDuration <= 0 || targetDuration <= 0) return;
        for (DownloadManifest.Range range : source) {
            long start = Math.round(targetDuration * Math.max(0, range.startMs())
                    / (double) sourceDuration);
            long end = Math.round(targetDuration * Math.max(0, range.endMs())
                    / (double) sourceDuration);
            if (end > start) target.add(new DownloadManifest.Range(
                    Math.min(targetDuration, start), Math.min(targetDuration, end)));
        }
    }

    private void setDisplayedRanges(List<DownloadManifest.Range> ranges) {
        List<DownloadManifest.Range> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingLong(DownloadManifest.Range::startMs));
        List<DownloadManifest.Range> merged = new ArrayList<>();
        for (DownloadManifest.Range range : sorted) {
            if (merged.isEmpty() || range.startMs() > merged.get(merged.size() - 1).endMs()) {
                merged.add(range);
            } else {
                DownloadManifest.Range previous = merged.remove(merged.size() - 1);
                merged.add(new DownloadManifest.Range(previous.startMs(),
                        Math.max(previous.endMs(), range.endMs())));
            }
        }
        timeBar.setCachedRanges(merged);
    }

    private long effectiveBufferedPosition(Player progress) {
        long buffered = Math.max(0, progress.getBufferedPosition());
        String mediaKey = PlaybackDiskBufferStore.mediaKey(progress.getCurrentMediaItem());
        long diskBuffered = PlaybackDiskBufferStore.process().contiguousEnd(
                mediaKey, buffered, DISK_RANGE_GAP_TOLERANCE_MS);
        long effective = Math.max(buffered, diskBuffered);
        long duration = progress.getDuration();
        long bounded = duration > 0 ? Math.min(effective, duration) : effective;
        long now = SystemClock.elapsedRealtime();
        if (SpiderDebug.isEnabled()
                && (bounded != lastBufferReadEffectiveMs
                || now - lastBufferReadLogAtMs >= PAUSED_BUFFER_LOG_INTERVAL_MS)) {
            lastBufferReadLogAtMs = now;
            lastBufferReadEffectiveMs = bounded;
            SpiderDebug.log("playback-progress",
                    "action=buffer-read source=%s positionMs=%d nativeBufferedMs=%d diskBufferedMs=%d effectiveBufferedMs=%d key=%s",
                    progress == player ? "controller" : "direct-engine",
                    Math.max(0, progress.getCurrentPosition()), buffered, diskBuffered, bounded,
                    Integer.toHexString(mediaKey == null ? 0 : mediaKey.hashCode()));
        }
        return bounded;
    }

    private void logPausedBufferProgress(Player progress, long position, long buffered) {
        if (progress.isPlaying() || buffered <= lastPausedBufferedPosition) return;
        long now = SystemClock.elapsedRealtime();
        if (lastPausedBufferLogAtMs > 0
                && now - lastPausedBufferLogAtMs < PAUSED_BUFFER_LOG_INTERVAL_MS) return;
        lastPausedBufferLogAtMs = now;
        lastPausedBufferedPosition = buffered;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("playback-progress",
                    "mode=paused source=%s positionMs=%d bufferedPositionMs=%d bufferedDurationMs=%d",
                    progress == player ? "controller" : "direct-engine",
                    position, buffered, Math.max(0, buffered - position));
        }
    }

    private void resetView() {
        clearPendingSeek();
        positionView.setText("00:00");
        durationView.setText("00:00");
        timeBar.setPosition(currentPosition = 0);
        timeBar.setDuration(currentDuration = 0);
        timeBar.setBufferedPosition(currentBuffered = 0);
    }

    private void setKeyTimeIncrement(long duration) {
        if (duration > TimeUnit.HOURS.toMillis(3)) {
            timeBar.setKeyTimeIncrement(TimeUnit.MINUTES.toMillis(5));
        } else if (duration > TimeUnit.MINUTES.toMillis(30)) {
            timeBar.setKeyTimeIncrement(TimeUnit.MINUTES.toMillis(1));
        } else if (duration > TimeUnit.MINUTES.toMillis(15)) {
            timeBar.setKeyTimeIncrement(TimeUnit.SECONDS.toMillis(30));
        } else if (duration > TimeUnit.MINUTES.toMillis(10)) {
            timeBar.setKeyTimeIncrement(TimeUnit.SECONDS.toMillis(15));
        } else if (duration > 0) {
            timeBar.setKeyTimeIncrement(TimeUnit.SECONDS.toMillis(10));
        }
    }

    private long delayMs(Player progress, long position) {
        float speed = progress.getPlaybackParameters().speed;
        long mediaTimeUntilNextFullSecondMs = 1000 - position % 1000;
        long mediaTimeDelayMs = Math.min(timeBar.getPreferredUpdateDelay(), mediaTimeUntilNextFullSecondMs);
        long delayMs = (long) (mediaTimeDelayMs / Math.max(speed, 0.1f));
        return Util.constrainValue(delayMs, MIN_UPDATE_INTERVAL_MS, MAX_UPDATE_INTERVAL_MS);
    }

    private void seekToTimeBarPosition(long positionMs) {
        beginPendingSeek(positionMs);
        player.seekTo(positionMs);
        updateProgress();
        player.play();
    }

    public void previewSeekPosition(long positionMs) {
        Player progress = getProgressPlayer();
        long duration = currentDuration > 0 ? currentDuration : progress == null ? 0 : Math.max(0, progress.getDuration());
        long position = duration > 0 ? Util.constrainValue(positionMs, 0, duration) : Math.max(0, positionMs);
        removeCallbacks(runnable);
        if (!scrubbing) clearPendingSeek();
        scrubbing = true;
        timeBar.setPosition(position);
        positionView.setText(stringToTime(position));
    }

    public void commitSeekPreview(long positionMs) {
        Player progress = getProgressPlayer();
        long duration = currentDuration > 0 ? currentDuration : progress == null ? 0 : Math.max(0, progress.getDuration());
        long position = duration > 0 ? Util.constrainValue(positionMs, 0, duration) : Math.max(0, positionMs);
        scrubbing = false;
        beginPendingSeek(position);
        removeCallbacks(runnable);
        postDelayed(runnable, MIN_UPDATE_INTERVAL_MS);
    }

    private void beginPendingSeek(long targetPosition) {
        Player progress = getProgressPlayer();
        long origin = progress == null ? currentPosition : Math.max(0, progress.getCurrentPosition());
        pendingSeekOrigin = origin;
        pendingSeekPosition = Math.max(0, targetPosition);
        pendingSeekDeadlineMs = SystemClock.elapsedRealtime() + SEEK_POSITION_HOLD_TIMEOUT_MS;
        currentPosition = pendingSeekPosition;
        timeBar.setPosition(pendingSeekPosition);
        positionView.setText(stringToTime(pendingSeekPosition));
    }

    private long stableSeekPosition(long reportedPosition) {
        if (pendingSeekPosition == C.TIME_UNSET) return reportedPosition;
        if (SystemClock.elapsedRealtime() >= pendingSeekDeadlineMs || hasReachedPendingSeek(reportedPosition)) {
            clearPendingSeek();
            return reportedPosition;
        }
        return pendingSeekPosition;
    }

    private boolean hasReachedPendingSeek(long reportedPosition) {
        long delta = pendingSeekPosition - pendingSeekOrigin;
        if (Math.abs(delta) <= SEEK_POSITION_TOLERANCE_MS) {
            return Math.abs(reportedPosition - pendingSeekPosition) <= SEEK_POSITION_TOLERANCE_MS;
        }
        if (delta > 0) return reportedPosition >= pendingSeekPosition - SEEK_POSITION_TOLERANCE_MS;
        return reportedPosition <= pendingSeekPosition + SEEK_POSITION_TOLERANCE_MS;
    }

    private void clearPendingSeek() {
        pendingSeekPosition = C.TIME_UNSET;
        pendingSeekOrigin = C.TIME_UNSET;
        pendingSeekDeadlineMs = 0;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateTimeline();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(runnable);
        super.onDetachedFromWindow();
    }

    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
        resetView();
        downloadGeneration++;
        lastRangeRefreshAtMs = 0;
        setDisplayedRanges(List.of());
    }

    @Override
    public void onScrubStart(@NonNull TimeBar timeBar, long position) {
        clearPendingSeek();
        scrubbing = true;
        positionView.setText(stringToTime(position));
    }

    @Override
    public void onScrubMove(@NonNull TimeBar timeBar, long position) {
        positionView.setText(stringToTime(position));
    }

    @Override
    public void onScrubStop(@NonNull TimeBar timeBar, long position, boolean canceled) {
        scrubbing = false;
        if (!canceled) seekToTimeBarPosition(position);
        else updateProgress();
    }
}
