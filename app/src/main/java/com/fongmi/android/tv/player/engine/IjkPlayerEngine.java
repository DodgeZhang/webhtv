package com.fongmi.android.tv.player.engine;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.AudioPlaybackDiagnostics;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;
import java.util.concurrent.TimeUnit;

@UnstableApi
public class IjkPlayerEngine implements PlayerEngine {

    private IjkSimplePlayer player;
    private PlaySpec spec;
    private int decode;

    public IjkPlayerEngine(int decode, Player.Listener listener) {
        this.player = buildPlayer(decode, listener);
        this.decode = decode;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        player.release();
    }

    @Override
    public Player rebuild(Player.Listener listener) {
        player.release();
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "rebuild ijk decode=%d", decode);
        return player = buildPlayer(decode, listener);
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
        player.setDecode(decode);
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    @Override
    public void start(PlaySpec spec) {
        start(spec, C.TIME_UNSET, true);
    }

    @Override
    public void start(PlaySpec spec, boolean playWhenReady) {
        this.spec = spec;
<<<<<<< HEAD
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "start ijk decode=%d play=%s urlLen=%d headers=%d", decode, playWhenReady, spec.getUrl() == null ? 0 : spec.getUrl().length(), spec.getHeaders() == null ? 0 : spec.getHeaders().size());
        player.setMediaItem(ExoUtil.getMediaItem(spec, decode));
=======
        player.setDiagnosticTrace(spec.getPlaybackTraceId());
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "start ijk decode=%d position=%d play=%s urlLen=%d headers=%d", decode, position, playWhenReady, spec.getUrl() == null ? 0 : spec.getUrl().length(), spec.getHeaders() == null ? 0 : spec.getHeaders().size());
        MediaItem item = ExoUtil.getMediaItem(spec, decode);
        if (position > 0) player.setMediaItem(item, position);
        else player.setMediaItem(item);
>>>>>>> upstream/beta
        player.prepare();
        if (playWhenReady) player.play();
        else player.pause();
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return player.getDuration() < TimeUnit.MINUTES.toMillis(1) || player.isCurrentMediaItemLive();
    }

    @Override
    public boolean isVod() {
        return player.getDuration() > TimeUnit.MINUTES.toMillis(1) && !player.isCurrentMediaItemLive();
    }

    @Override
    public void setTrack(List<Track> tracks) {
    }

    @Override
    public void resetTrack() {
    }

    @Override
    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    @Override
    public Tracks getCurrentTracks() {
        return player.getCurrentTracksSnapshot();
    }

    @Override
    public Format getVideoFormat() {
        return TrackUtil.selectedFormat(getCurrentTracks(), C.TRACK_TYPE_VIDEO);
    }

    @Override
<<<<<<< HEAD
=======
    public PlaybackFactsSnapshot getPlaybackFactsSnapshot() {
        Format video = player.getSelectedVideoFormatSnapshot();
        Format audio = player.getSelectedAudioFormatSnapshot();
        return new PlaybackFactsSnapshot(
                video,
                audio,
                video,
                audio,
                player.getVideoCodecInfoSnapshot(),
                player.getAudioCodecInfoSnapshot(),
                decoderKind(player.getVideoDecoderSnapshot()),
                null,
                "",
                "",
                null);
    }

    @Override
    public AudioPlaybackDiagnostics.Snapshot getAudioPlaybackDiagnostics() {
        Format format = player.getSelectedAudioFormatSnapshot();
        AudioPlaybackDiagnostics.Track track =
                AudioPlaybackDiagnostics.track(format, "");
        String decoderName = player.getAudioCodecInfoSnapshot();
        AudioPlaybackDiagnostics.DecodeMode decodeMode = "PCM".equalsIgnoreCase(
                track.codec()) ? AudioPlaybackDiagnostics.DecodeMode.NONE
                : decoderName == null || decoderName.isBlank()
                ? AudioPlaybackDiagnostics.DecodeMode.UNKNOWN
                : AudioPlaybackDiagnostics.DecodeMode.SOFTWARE;
        AudioPlaybackDiagnostics.OutputMode outputMode = decoderName == null
                || decoderName.isBlank()
                ? AudioPlaybackDiagnostics.OutputMode.UNKNOWN
                : AudioPlaybackDiagnostics.OutputMode.PCM;
        ErrorSnapshot error = player.getLastErrorSnapshot();
        boolean decoderFailed = error != null
                && error.stage() != null
                && error.stage().ordinal() >= OpenStage.COMPONENT_OPENED.ordinal();
        if (decoderFailed) {
            AudioPlaybackDiagnostics.FailureReason failureReason = error.prepared()
                    ? AudioPlaybackDiagnostics.FailureReason.DECODER_RUNTIME
                    : AudioPlaybackDiagnostics.FailureReason.DECODER_INIT;
            return new AudioPlaybackDiagnostics.Snapshot(track, track, decodeMode,
                    decoderName, outputMode, 0, 0, false, "",
                    AudioPlaybackDiagnostics.lastAttemptLevel(
                            failureReason, outputMode, decodeMode),
                    AudioPlaybackDiagnostics.RuntimeState.FAILED, failureReason);
        }
        return new AudioPlaybackDiagnostics.Snapshot(track, track, decodeMode,
                decoderName, outputMode, 0, 0, false, "");
    }

    @Override
    public RuntimeMetrics getRuntimeMetrics() {
        long tcpBytesPerSecond = player.getTcpSpeedSnapshot();
        long bandwidth = tcpBytesPerSecond > Long.MAX_VALUE / 8L
                ? Long.MAX_VALUE : tcpBytesPerSecond * 8L;
        long bitrate = player.getBitrateSnapshot();
        IjkDecodePressurePolicy.DecodeSnapshot decode =
                player.getDecodePressureSnapshot();
        return new RuntimeMetrics(
                bandwidth > 0 ? bandwidth : null,
                bitrate > 0 ? bitrate : null,
                decode.available() ? decode.outputFps() : null,
                null);
    }

    @Override
>>>>>>> 2d58d9085640098e3842a859fc3afa15050ac280
    public String getPlaybackTraceId() {
        return spec == null ? PlaybackTrace.NONE : spec.getPlaybackTraceId();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return e.getMessage();
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        PlaybackTrace.log("player-engine", getPlaybackTraceId(), "handleError ijk code=%d message=%s urlLen=%d", e.errorCode, e.getMessage(), spec == null || spec.getUrl() == null ? 0 : spec.getUrl().length());
        return ErrorAction.FATAL;
    }

    private IjkSimplePlayer buildPlayer(int decode, Player.Listener listener) {
        IjkSimplePlayer player = new IjkSimplePlayer(decode);
        player.addListener(listener);
        return player;
    }
}
