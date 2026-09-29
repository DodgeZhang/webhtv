package com.fongmi.android.tv.ui.presenter;

import android.content.res.ColorStateList;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterTmdbPhotoBinding;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.card.MaterialCardView;

public class TmdbPhotoPresenter extends Presenter {

    private final OnClickListener mListener;
    private final boolean poster;

    public TmdbPhotoPresenter(OnClickListener listener) {
        this(listener, false);
    }

    public TmdbPhotoPresenter(OnClickListener listener, boolean poster) {
        this.mListener = listener;
        this.poster = poster;
    }

    public interface OnClickListener {
        void onItemClick(String url, int position);
    }

    @Override
    public Presenter.ViewHolder onCreateViewHolder(ViewGroup parent) {
        AdapterTmdbPhotoBinding binding = AdapterTmdbPhotoBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        if (poster) {
            ViewGroup.LayoutParams params = binding.getRoot().getLayoutParams();
            params.width = ResUtil.dp2px(148);
            params.height = ResUtil.dp2px(222);
            binding.getRoot().setLayoutParams(params);
        }
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        String url = (String) item;
        ViewHolder holder = (ViewHolder) viewHolder;
        int label = poster ? R.string.tmdb_posters_label : R.string.tmdb_photos_label;
        ImgUtil.load(holder.binding.photo.getContext().getString(label), url, holder.binding.photo);
        bindFocusStyle(holder.binding.getRoot());
        setOnClickListener(holder, view -> {
            if (mListener != null) mListener.onItemClick(url, 0);
        });
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        View root = viewHolder.view;
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
        card.setStrokeWidth(ResUtil.dp2px(1));
        card.setCardElevation(0);
        card.setTranslationZ(0);
    }

    static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterTmdbPhotoBinding binding;

        public ViewHolder(@NonNull AdapterTmdbPhotoBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
