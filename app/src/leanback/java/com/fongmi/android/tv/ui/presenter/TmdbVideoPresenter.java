package com.fongmi.android.tv.ui.presenter;

import android.content.res.ColorStateList;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbVideo;
import com.fongmi.android.tv.databinding.AdapterTmdbVideoBinding;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.card.MaterialCardView;

public class TmdbVideoPresenter extends Presenter {

    private static final int STROKE_WIDTH_FOCUSED_DP = 3;
    private static final int STROKE_WIDTH_NORMAL_DP = 1;

    public interface OnClickListener {
        void onItemClick(TmdbVideo item);
    }

    private final OnClickListener listener;

    public TmdbVideoPresenter(OnClickListener listener) {
        this.listener = listener;
    }

    @Override
    public Presenter.ViewHolder onCreateViewHolder(ViewGroup parent) {
        return new ViewHolder(AdapterTmdbVideoBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        TmdbVideo video = (TmdbVideo) item;
        ViewHolder holder = (ViewHolder) viewHolder;
        String name = video.getName().isEmpty() ? video.getDisplayType() : video.getName();
        holder.binding.title.setText(name);
        holder.binding.subtitle.setText(video.getDisplayType() + " ? " + video.getScopeLabel());
        ImgUtil.load(name, video.getThumbnailUrl(), holder.binding.poster, true, 300, 169);
        bindFocusStyle(holder.binding.getRoot());
        setOnClickListener(holder, view -> {
            if (listener != null) listener.onItemClick(video);
        });
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        View root = viewHolder.view;
        root.animate().cancel();
        root.setScaleX(1.0f);
        root.setScaleY(1.0f);
        root.setOnFocusChangeListener(null);
        root.setForeground(null);
        root.setActivated(false);
        root.setSelected(false);
    }

    private static void bindFocusStyle(MaterialCardView card) {
        card.setRippleColor(ColorStateList.valueOf(0x00000000));
        card.setForeground(card.getContext().getDrawable(R.drawable.selector_tmdb_media_focus));
        card.setStateListAnimator(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) card.setDefaultFocusHighlightEnabled(false);
        applyFocusStyle(card, card.hasFocus());
        card.setOnFocusChangeListener((view, focused) -> applyFocusStyle(card, focused));
    }

    private static void applyFocusStyle(MaterialCardView card, boolean focused) {
        card.setSelected(false);
        card.setActivated(focused);
        card.setStrokeColor(card.getContext().getColor(R.color.tv_item_normal_stroke));
        card.setStrokeWidth(ResUtil.dp2px(focused ? STROKE_WIDTH_FOCUSED_DP : STROKE_WIDTH_NORMAL_DP));
        card.animate().cancel();
        float scale = focused ? 1.04f : 1.0f;
        card.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
    }

    static final class ViewHolder extends Presenter.ViewHolder {
        private final AdapterTmdbVideoBinding binding;

        ViewHolder(@NonNull AdapterTmdbVideoBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
