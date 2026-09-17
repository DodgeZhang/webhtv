package com.fongmi.android.tv.ui.activity;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.utils.AdBlockPreviewStore;

import java.nio.charset.StandardCharsets;

/** Isolated Media3 session for verifying one removed HLS segment. */
public final class AdBlockPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_SEGMENT_KEY = "segment_key";

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView status;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String key = getIntent().getStringExtra(EXTRA_SEGMENT_KEY);
        String manifest = AdBlockPreviewStore.get(key);
        buildContent(key == null ? "" : key);
        if (key == null || key.isBlank() || manifest == null || manifest.isBlank()) {
            status.setText("不可验证：预览上下文不存在或已过期。旧记录不会生成或猜测播放地址。");
            return;
        }
        startPreview(key, manifest);
    }

    private void buildContent(String key) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);

        status = new TextView(this);
        status.setText("正在验证切片 " + key);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        playerView = new PlayerView(this);
        root.addView(playerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void startPreview(String key, String manifest) {
        byte[] bytes = manifest.getBytes(StandardCharsets.UTF_8);
        DataSource.Factory memory = () -> new ByteArrayDataSource(bytes);
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, memory);
        HlsMediaSource source = new HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(new MediaItem.Builder()
                        .setUri(AdBlockPreviewStore.uri(key))
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build());

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    status.setText("可播放：请人工检查该切片是否为正片，若是则可能存在误杀。");
                } else if (state == Player.STATE_ENDED) {
                    status.setText("验证播放完成：请根据画面判断是否疑似误杀。");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                status.setText("验证失败：切片不可播放或依赖的 HLS 上下文不足。");
            }
        });
        player.setMediaSource(source);
        player.prepare();
        player.play();
    }

    @Override
    protected void onDestroy() {
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }
}
