package com.android.myexoplayer.player;

/**
 * Created by Nirajan on 9/11/2015.
 */

import android.media.MediaCodec.CryptoException;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.google.android.exoplayer.CodecCounters;
import com.google.android.exoplayer.DummyTrackRenderer;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.MediaCodecTrackRenderer.DecoderInitializationException;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.TimeRange;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioTrack;
import com.google.android.exoplayer.chunk.ChunkSampleSource;
import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.chunk.MultiTrackChunkSource;
import com.google.android.exoplayer.dash.DashChunkSource;
import com.google.android.exoplayer.drm.StreamingDrmSessionManager;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.metadata.MetadataTrackRenderer;
import com.google.android.exoplayer.text.Cue;
import com.google.android.exoplayer.text.TextRenderer;
import com.google.android.exoplayer.upstream.BandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.util.DebugTextViewHelper;
import com.google.android.exoplayer.util.PlayerControl;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A wrapper around {@link ExoPlayer} that provides a higher level interface. It
 * can be prepared with one of a number of {@link RendererBuilder} classes to suit different
 * use cases list below:
 *      Dynamic Adaptive Streaming over HTTP (DASH)
 *      SmoothStreaming
 *      HTTP Live Streaming (HLS)
 *      MP4
 *      WebM
 *      M4A
 *      MPEG-TS
 *      AAC
 */

public class DemoPlayer implements ExoPlayer.Listener, ChunkSampleSource.EventListener,
        HlsSampleSource.EventListener, DefaultBandwidthMeter.EventListener,
        MediaCodecVideoTrackRenderer.EventListener, MediaCodecAudioTrackRenderer.EventListener,
        StreamingDrmSessionManager.EventListener, DashChunkSource.EventListener, TextRenderer,
        MetadataTrackRenderer.MetadataRenderer<Map<String, Object>>, DebugTextViewHelper.Provider {

    /**
     * Interface to build renderer for a player
     */
    public interface RendererBuilder{
        /**
         * Builds renderers for playback
         *
         * @param player The player for which renderers are built.
         *               {@link DemoPlayer#onRenderers} should be invoked once the
         *               renderers have been built. If building fails,
         *               {@link DemoPlayer#onRenderersError} should be invoked.
         */
        void buildRenderers(DemoPlayer player);

        /**
         * Cancels the current renderer build operation, if there is one. Else does nothing.
         * <p>
         *     A cancelled build operation must not invoke {@link DemoPlayer#onRenderers} or
         *     {@link DemoPlayer#onRenderersError} on the player, which may have been released.
         * </p>
         */
        void cancel();
    }

    /**
     * Interface for listener to core events.
     */
    public interface Listener{
        void onStateChanged(boolean playWhenReady, int playbackState);
        void onError(Exception e);
        void onVideoSizeChanged(int width, int height, float pixelWidthHeightRatio);
    }

    /**
     * Interface for listener to internal errors
     * <p>
     *     These errors are not visible to the user, and hence this listener is provided for
     *     informational purposes only. Note however, that an internal error may cause a fatal
     *     error if the player fails to recover. If this happens, {@link Listener#onError(Exception)}
     *     will be invoked
     * </p>
     */
    public interface InternalErrorListener{
        void onRendererInitializationError(Exception e);
        void onAudioTrackInitializationError(AudioTrack.InitializationException e);
        void onAudioTrackWriteError(AudioTrack.WriteException e);
        void onDecoderInitializationError(DecoderInitializationException e);
        void onCryptoError(CryptoException e);
        void onLoadError(int sourceId, IOException e);
        void onDrmSessionManagerError(Exception e);
    }

    /**
     * Interface for Debugging information
     */
    public interface InfoListener{
        void onVideoFormatEnabled(Format format, int trigger, int mediaTimeMs);
        void onAudioFormatEnabled(Format format, int trigger, int mediaTimeMs);
        void onDroppedFrames(int count, long elapsed);
        void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate);
        void onLoadStarted(int sourceId, long length, int type, int trigger, Format format,
                           int mediaStartTimeMs, int mediaEndTimeMs);
        void onLoadCompleted(int sourceId, long bytesLoaded, int type, int trigger, Format format,
                             int mediaStartTimeMs, int mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs);
        void onDecoderInitialized(String decoderName, long elapsedRealtimeMs,
                                  long initializationDurationMs);
        void onSeekRangeChanged(TimeRange seekRange);
    }

    /**
     * Interface for listener to notifications of timed text
     */
    public interface CaptionListener{
        void onCues(List<Cue>cues);
    }

    /**
     * Interface for listener to ID3 metadata parsed from the media stream
     */
    public interface Id3MetadataListener{
        void onId3Metadata(Map<String, Object> metadata);
    }


    // Constants representing different states of the Player

    /**
     * The player is neither prepared or being prepared
     */
    public static final int STATE_IDLE = ExoPlayer.STATE_IDLE;
    /**
     * The player is being prepared
     */
    public static final int STATE_PREPARING = ExoPlayer.STATE_PREPARING;
    /**
     * The player is prepared but not able to immediately play from the current position. The cause
     * is {@link com.google.android.exoplayer.TrackRenderer} specific, but this state typically occurs
     * when more data needs to be buffered for playback to start.
     */
    public static final int STATE_BUFFERING = ExoPlayer.STATE_BUFFERING;
    /**
     * The player is prepared and able to immediately play from the current position. The player will
     * be playing if {@link ExoPlayer#setPlayWhenReady(boolean)} returns true, and paused otherwise.
     */
    public static final int STATE_READY = ExoPlayer.STATE_READY;
    /**
     * The player has finished playing the media.
     */
    public static final int STATE_ENDED = ExoPlayer.STATE_ENDED;

    public static final int DISABLED_TRACK = -1;
    public static final int PRIMARY_TRACK = 0;

    /**
     * The number of {@link com.google.android.exoplayer.TrackRenderer}s
     * that will be passed
     */
    public static final int RENDERER_COUNT = 4;
    /**
     * A minimum duration of data that must be buffered for playback to start
     * or resume following a user action as a seek.
     */
    public static final int MIN_BUFFERS_COUNT = 1000;
    /**
     * A minimum duration of data that must be buffered for playback to resume
     * after a player invoked rebuffer (i.e. a rebuffer that occurs due to buffer depletion,
     * and not due to a user action such as starting playback or seeking).
     */
    public static final int MIN_REBUFFERS_COUNT = 5000;

    // Index of Media Tracks associated with the player
    public static final int TYPE_VIDEO = 0;
    public static final int TYPE_AUDIO = 1;
    public static final int TYPE_TEXT = 2;
    public static final int TYPE_METADATA = 3;

    // Constants representing different states of renderer building

    private static final int RENDERER_BUILDING_STATE_IDLE = 1;
    private static final int RENDERER_BUILDING_STATE_BUILDING = 2;
    private static final int RENDERER_BUILDING_STATE_BUILT = 3;


    // Instance Variables for class


    private final RendererBuilder rendererBuilder;
    /**
     * Instance of the {@link ExoPlayer}
     */
    private ExoPlayer player;
    /**
     * Instance of {@link PlayerControl} which extends {@link android.widget.MediaController.MediaPlayerControl}
     * for controlling an {@link ExoPlayer} instance
     */
    private final PlayerControl playerControl;
    /**
     * Main handler for the class
     */
    private final Handler mainHandler;
    /**
     * A thread safe random-access {@link List} of listeners associated with the class
     */
    private final CopyOnWriteArrayList<Listener> listeners;


    /**
     * Represents the last reported state of the player
     */
    private int lastReportedPlaybackState;
    /**
     * Track last reported when the player was ready
     */
    private boolean lastReportedPlayWhenReady;
    /**
     * Represents the current state of the renderer builder
     */
    private int rendererBuildingState;


    /**
     * Represents a {@link Surface}
     */
    private Surface surface;
    /**
     * Format of the video
     */
    private Format videoFormat;
    /**
     * Video Track Renderer
     */
    private TrackRenderer videoRenderer;
    /**
     * Codec event counts, for debugging purposes only
     */
    private CodecCounters codecCounters;


    /**
     * Provides estimates of the currently available bandwidth
     */
    private BandwidthMeter bandwidthMeter;
    /**
     * Stores multiple  {@link com.google.android.exoplayer.chunk.ChunkSource}, which provides
     *      {@link com.google.android.exoplayer.chunk.Chunk} for a {@link com.google.android.exoplayer.chunk.ChunkSampleSource}
     *      to load
     */
    private MultiTrackChunkSource[] multiTrackSources;
    /**
     * Array to store the media tracks and index
     */
    private String[][] trackNames;
    /**
     * Stores the selected tracks
     */
    private int[] selectedTracks;
    /**
     * Flag to track if the player is in background
     */
    private boolean backgrounded;
    /**
     * Represents the video that is in background state, and is to be restored
     */
    private int videoTrackToRestore;


    // Interface implementations
    private CaptionListener captionListener;
    private Id3MetadataListener id3MetadataListener;
    private InternalErrorListener internalErrorListener;
    private InfoListener infoListener;

    /**
     * Constructor for DemoPlayer class
     * @param rendererBuilder RendererBuilder for the player
     */
    public DemoPlayer(RendererBuilder rendererBuilder) {
        this.rendererBuilder = rendererBuilder;
        player = ExoPlayer.Factory.newInstance(RENDERER_COUNT, MIN_BUFFERS_COUNT, MIN_REBUFFERS_COUNT);
        player.addListener(this);
        playerControl = new PlayerControl(player);
        mainHandler = new Handler();
        listeners = new CopyOnWriteArrayList<>();
        lastReportedPlaybackState = STATE_IDLE;
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;

        selectedTracks = new int[RENDERER_COUNT];
        // Disable text initially
        selectedTracks[TYPE_TEXT] = DISABLED_TRACK;
    }

    /**
     * Returns the player control
     *
     * @return playerControl
     */
    public PlayerControl getPlayerControl() {
        return playerControl;
    }

    /**
     * Adds a listener to the {@link CopyOnWriteArrayList<Listener> listeners}
     *
     * @param listener
     */
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a listener from the {@link CopyOnWriteArrayList<Listener> listeners}
     *
     * @param listener
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Sets the {@link com.android.myexoplayer.player.DemoPlayer.InternalErrorListener} interface
     *
     * @param listener
     */
    public void setInternalErrorListener(InternalErrorListener listener) {
        internalErrorListener = listener;
    }

    /**
     * Sets the {@link com.android.myexoplayer.player.DemoPlayer.InfoListener} interface
     *
     * @param listener
     */
    public void setInfoListener(InfoListener listener) {
        infoListener = listener;
    }

    /**
     * Sets the {@link com.android.myexoplayer.player.DemoPlayer.CaptionListener} interface
     *
     * @param listener
     */
    public void setCaptionListener(CaptionListener listener) {
        captionListener = listener;
    }

    /**
     * Sets the {@link com.android.myexoplayer.player.DemoPlayer.Id3MetadataListener} interface
     *
     * @param listener
     */
    public void setMetadataListener(Id3MetadataListener listener) {
        id3MetadataListener = listener;
    }

    /**
     * Sets the {@link Surface} associated with the player
     *
     * @param surface
     */
    public void setSurface(Surface surface) {
        this.surface = surface;
        pushSurface(false);
    }

    /**
     * Returns the {@link Surface}
     *
     * @return surface
     */
    public Surface getSurface() {
        return surface;
    }

    /**
     * Clears the {@link Surface} associated with the player
     */
    public void blockingClearSurface() {
        surface = null;
        pushSurface(true);
    }

    /**
     * Returns the number of tracks
     *
     * @param type
     * @return track count
     */
    public int getTrackCount(int type) {
        return !player.getRendererHasMedia(type) ? 0 : trackNames[type].length;
    }

    /**
     * Returns the track type and index
     *
     * @param type
     * @param index
     * @return String
     */
    public String getTrackName(int type, int index) {
        return trackNames[type][index];
    }

    /**
     * Returns the index of the selected track
     *
     * @param type
     * @return int
     */
    public int getSelectedTrackIndex(int type) {
        return selectedTracks[type];
    }

    /**
     * Selects a media track
     *
     * @param type
     * @param index
     */
    public void selectTrack(int type, int index) {
        if (selectedTracks[type] == index)
            return;
        selectedTracks[type] = index;
        pushTrackSelection(type, true);
        if (type == TYPE_TEXT && index == DISABLED_TRACK && captionListener != null) {
            captionListener.onCues(Collections.<Cue>emptyList());
        }
    }

    /**
     * Invoked when the video is set to background
     *
     * @param backgrounded
     */
    public void setBackgrounded(boolean backgrounded) {
        if (this.backgrounded == backgrounded)
            return;
        this.backgrounded = backgrounded;
        if (backgrounded){
            videoTrackToRestore = getSelectedTrackIndex(TYPE_VIDEO);
            selectTrack(TYPE_VIDEO, DISABLED_TRACK);
            blockingClearSurface();
        } else {
            selectTrack(TYPE_VIDEO, videoTrackToRestore);
        }
    }

    /**
     * Injects the renderers to the {@link DemoPlayer}
     */
    public void prepare() {
        // stop the rendering build state if it is already built
        if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILT) {
            player.stop();
        }
        rendererBuilder.cancel();
        videoFormat = null;
        videoRenderer = null;
        multiTrackSources = null;
        rendererBuildingState = RENDERER_BUILDING_STATE_BUILDING;
        maybeReportPlayerState();
        rendererBuilder.buildRenderers(this);
    }

    /**
     * Invoked with the results from a {@link RendererBuilder}.
     *
     * @param trackNames The names of the available tracks, indexed by {@link DemoPlayer} TYPE_*
     *     constants. May be null if the track names are unknown. An individual element may be null
     *     if the track names are unknown for the corresponding type.
     * @param multiTrackSources Sources capable of switching between multiple available tracks,
     *     indexed by {@link DemoPlayer} TYPE_* constants. May be null if there are no types with
     *     multiple tracks. An individual element may be null if it does not have multiple tracks.
     * @param renderers Renderers indexed by {@link DemoPlayer} TYPE_* constants. An individual
     *     element may be null if there do not exist tracks of the corresponding type.
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth. May be null.
     */
    void onRenderers(String [][] trackNames, MultiTrackChunkSource[] multiTrackSources,
                     TrackRenderer[] renderers, BandwidthMeter bandwidthMeter) {
        // Normalize the results
        if (trackNames == null)
            trackNames = new String[RENDERER_COUNT][];
        if (multiTrackSources == null)
            multiTrackSources = new MultiTrackChunkSource[RENDERER_COUNT];
        for (int rendererIndex = 0; rendererIndex < RENDERER_COUNT; rendererIndex++){
            if (renderers[rendererIndex] == null){
                // Convert a null renderer to a dummy renderer
                renderers[rendererIndex] = new DummyTrackRenderer();
            }
            if (trackNames[rendererIndex] == null){
                // Convert a null trackNames to an array of suitable length.
                int trackCount = multiTrackSources[rendererIndex] != null
                        ? multiTrackSources[rendererIndex].getTrackCount() : 1;
                trackNames[rendererIndex] = new String[trackCount];
            }
        }
        // Complete preparation
        this.trackNames = trackNames;
        this.videoRenderer = renderers[TYPE_VIDEO];
        this.codecCounters = videoRenderer instanceof MediaCodecTrackRenderer
                ? ((MediaCodecTrackRenderer) videoRenderer).codecCounters
                : renderers[TYPE_AUDIO] instanceof MediaCodecTrackRenderer
                ? ((MediaCodecTrackRenderer) renderers[TYPE_AUDIO]).codecCounters : null;
        this.multiTrackSources = multiTrackSources;
        this.bandwidthMeter = bandwidthMeter;
        pushSurface(false);
        pushTrackSelection(TYPE_VIDEO, true);
        pushTrackSelection(TYPE_AUDIO, true);
        pushTrackSelection(TYPE_TEXT, true);
        player.prepare(renderers);
        rendererBuildingState = RENDERER_BUILDING_STATE_BUILT;
    }

    /**
     * Invoked if a {@link RendererBuilder} encounters an error.
     *
     * @param e Describes the error.
     */
    /* package */ void onRenderersError(Exception e) {
        if (internalErrorListener != null) {
            internalErrorListener.onRendererInitializationError(e);
        }
        for (Listener listener : listeners) {
            listener.onError(e);
        }
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        maybeReportPlayerState();
    }

    /**
     * Sets whether playback should proceed when {@link #getPlaybackState()} == {@link #STATE_READY}.
     * If the player is already in this state, then this method can be used to pause and resume
     * playback.
     *
     * @param playWhenReady Whether playback should proceed when ready.
     */
    public void setPlayWhenReady(boolean playWhenReady) {
        player.setPlayWhenReady(playWhenReady);
    }

    /**
     * Sets the position of the {@link DemoPlayer} specified in milliseconds
     *
     * @param positionMs
     */
    public void seekTo(long positionMs) {
        player.seekTo(positionMs);
    }

    /**
     * Releases the {@link DemoPlayer}.
     * This should be called when the player is no longer required.
     */
    public void release() {
        rendererBuilder.cancel();
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        surface = null;
        player.release();
    }

    /**
     * Returns the current state of the player
     *
     * @return One of the {@code STATE} constants
     */
    public int getPlaybackState() {
        if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILDING) {
            return STATE_PREPARING;
        }
        int playerState = player.getPlaybackState();
        if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILT &&
                playerState == STATE_IDLE) {
            // This is an edge case where the renderers are built, but are still being passed to the
            // player's playback thread.
            return STATE_PREPARING;
        }
        return playerState;
    }



    /**
     * Implementation of {@link ExoPlayer.Listener} interface methods
     */
    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int state) {
        maybeReportPlayerState();
    }

    @Override
    public void onPlayWhenReadyCommitted() {
        // Do Nothing
    }

    @Override
    public void onPlayerError(ExoPlaybackException e) {
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        for (Listener listener : listeners) {
            listener.onError(e);
        }
    }

    /**
     * Implementation of {@link com.google.android.exoplayer.chunk.ChunkSampleSource.EventListener}
     * interface methods
     */
    @Override
    public void onLoadStarted(int sourceId, long length, int type, int trigger, Format format,
                              int mediaStartTimeMs, int mediaEndTimeMs) {
        if (infoListener != null) {
            infoListener.onLoadStarted(sourceId, length, type, trigger, format, mediaStartTimeMs,
                    mediaEndTimeMs);
        }
    }

    @Override
    public void onLoadCompleted(int sourceId, long bytesLoaded, int type, int trigger, Format format,
                                int mediaStartTimeMs, int mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs) {
        if (infoListener != null) {
            infoListener.onLoadCompleted(sourceId, bytesLoaded, type, trigger, format, mediaStartTimeMs,
                    mediaEndTimeMs, elapsedRealtimeMs, loadDurationMs);
        }
    }

    @Override
    public void onLoadCanceled(int sourceId, long bytesLoaded) {
        // Do Nothing
    }

    @Override
    public void onLoadError(int sourceId, IOException e) {
        if (internalErrorListener != null) {
            internalErrorListener.onLoadError(sourceId, e);
        }
    }

    @Override
    public void onUpstreamDiscarded(int sourceId, int mediaStartTimeMs, int mediaEndTimeMs) {
        // Do nothing.
    }

    @Override
    public void onDownstreamFormatChanged(int sourceId, Format format, int trigger, int mediaTimeMs) {
        if (infoListener == null) {
            return;
        }
        if (sourceId == TYPE_VIDEO) {
            videoFormat = format;
            infoListener.onVideoFormatEnabled(format, trigger, mediaTimeMs);
        } else if (sourceId == TYPE_AUDIO) {
            infoListener.onAudioFormatEnabled(format, trigger, mediaTimeMs);
        }
    }

    /**
     * Implementation of {@link DefaultBandwidthMeter.EventListener }
     *
     */

    @Override
    public void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate) {
        if (infoListener != null) {
            infoListener.onBandwidthSample(elapsedMs, bytes, bitrateEstimate);
        }
    }

    /**
     * Implementation of {@link com.google.android.exoplayer.MediaCodecVideoTrackRenderer.EventListener}
     * interface
     */
    @Override
    public void onDroppedFrames(int count, long elapsed) {
        if (infoListener != null) {
            infoListener.onDroppedFrames(count, elapsed);
        }

    }

    @Override
    public void onVideoSizeChanged(int width, int height, float pixelWidthHeightRatio) {
        for (Listener listener : listeners) {
            listener.onVideoSizeChanged(width, height, pixelWidthHeightRatio);
        }
    }

    @Override
    public void onDrawnToSurface(Surface surface) {
        // Do Nothing
    }

    @Override
    public void onDecoderInitializationError(DecoderInitializationException e) {
        if (internalErrorListener != null) {
            internalErrorListener.onDecoderInitializationError(e);
        }
    }

    @Override
    public void onCryptoError(CryptoException e) {
        if (internalErrorListener != null) {
            internalErrorListener.onCryptoError(e);
        }
    }

    @Override
    public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs,
                                     long initializationDurationMs) {
        if (infoListener != null) {
            infoListener.onDecoderInitialized(decoderName, elapsedRealtimeMs, initializationDurationMs);
        }
    }

    /**
     * Implementation of {@link com.google.android.exoplayer.MediaCodecAudioTrackRenderer.EventListener}
     * interface
     */
    @Override
    public void onAudioTrackInitializationError(AudioTrack.InitializationException e) {
        if (internalErrorListener != null) {
            internalErrorListener.onAudioTrackInitializationError(e);
        }
    }

    @Override
    public void onAudioTrackWriteError(AudioTrack.WriteException e) {
        if (internalErrorListener != null) {
            internalErrorListener.onAudioTrackWriteError(e);
        }
    }

    /**
     * Implementation of {@link com.google.android.exoplayer.drm.StreamingDrmSessionManager.EventListener}
     * interface
     */
    @Override
    public void onDrmSessionManagerError(Exception e) {
        if (internalErrorListener != null) {
            internalErrorListener.onDrmSessionManagerError(e);
        }
    }

    /**
     * Implementation of {@link com.google.android.exoplayer.dash.DashChunkSource.EventListener}
     * interface
     */
    @Override
    public void onSeekRangeChanged(TimeRange seekRange) {
        if (infoListener != null) {
            infoListener.onSeekRangeChanged(seekRange);
        }
    }

    /**
     * Implementation of {@link TextRenderer}
     * interface
     */
    @Override
    public void onCues(List<Cue> cues) {
        if (captionListener != null && selectedTracks[TYPE_TEXT] != DISABLED_TRACK) {
            captionListener.onCues(cues);
        }
    }

    /**
     * Implementation of {@link com.google.android.exoplayer.metadata.MetadataTrackRenderer.MetadataRenderer}
     */
    @Override
    public void onMetadata(Map<String, Object> metadata) {
        if (id3MetadataListener != null && selectedTracks[TYPE_METADATA] != DISABLED_TRACK) {
            id3MetadataListener.onId3Metadata(metadata);
        }
    }

    /**
     * Implementation of {@link com.google.android.exoplayer.util.DebugTextViewHelper.Provider}
     * interface
     */

    @Override
    public long getCurrentPosition() {
        return player.getCurrentPosition();
    }

    @Override
    public Format getFormat() {
        return videoFormat;
    }

    @Override
    public BandwidthMeter getBandwidthMeter() {
        return bandwidthMeter;
    }

    @Override
    public CodecCounters getCodecCounters() {
        return codecCounters;
    }

    // Getter Methods
    public long getDuration() {
        return player.getDuration();
    }

    public int getBufferedPercentage() {
        return player.getBufferedPercentage();
    }

    public boolean getPlayWhenReady() {
        return player.getPlayWhenReady();
    }

    /* package */ Looper getPlaybackLooper() {
        return player.getPlaybackLooper();
    }

    /* package */ Handler getMainHandler() {
        return mainHandler;
    }

    private void maybeReportPlayerState() {
        boolean playWhenReady = player.getPlayWhenReady();
        int playbackState = getPlaybackState();
        if (lastReportedPlayWhenReady != playWhenReady || lastReportedPlaybackState != playbackState) {
            for (Listener listener : listeners) {
                listener.onStateChanged(playWhenReady, playbackState);
            }
            lastReportedPlayWhenReady = playWhenReady;
            lastReportedPlaybackState = playbackState;
        }
    }

    private void pushSurface(boolean blockForSurfacePush) {
        if (videoRenderer == null) {
            return;
        }

        if (blockForSurfacePush) {
            player.blockingSendMessage(
                    videoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE, surface);
        } else {
            player.sendMessage(
                    videoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE, surface);
        }
    }

    private void pushTrackSelection(int type, boolean allowRendererEnable) {
        if (multiTrackSources == null) {
            return;
        }

        int trackIndex = selectedTracks[type];
        if (trackIndex == DISABLED_TRACK) {
            player.setRendererEnabled(type, false);
        } else if (multiTrackSources[type] == null) {
            player.setRendererEnabled(type, allowRendererEnable);
        } else {
            boolean playWhenReady = player.getPlayWhenReady();
            player.setPlayWhenReady(false);
            player.setRendererEnabled(type, false);
            player.sendMessage(multiTrackSources[type], MultiTrackChunkSource.MSG_SELECT_TRACK,
                    trackIndex);
            player.setRendererEnabled(type, allowRendererEnable);
            player.setPlayWhenReady(playWhenReady);
        }
    }


}
