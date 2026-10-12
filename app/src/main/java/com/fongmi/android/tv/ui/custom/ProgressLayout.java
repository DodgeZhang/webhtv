package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ViewEmptyBinding;
import com.fongmi.android.tv.databinding.ViewProgressBinding;

import java.util.ArrayList;
import java.util.List;

public class ProgressLayout extends RelativeLayout {

    private static final String TAG_PROGRESS = "ProgressLayout.TAG_PROGRESS";

    public enum State {
        CONTENT, PROGRESS, EMPTY
    }

    private List<View> mContentViews;
    private View mProgressView;
    private View mEmptyView;
    private int mEmptyTitleRes;
    private int mEmptyDescriptionRes;
    private int mEmptyActionRes;
    private OnClickListener mEmptyActionListener;
    private State mState;

    public ProgressLayout(Context context) {
        super(context);
    }

    public ProgressLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ProgressLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        mState = State.CONTENT;
        mContentViews = new ArrayList<>();
    }

    private void ensureEmptyView() {
        if (mEmptyView != null) return;
        mEmptyView = ViewEmptyBinding.inflate(LayoutInflater.from(getContext())).getRoot();
        mEmptyView.setTag(TAG_PROGRESS);
        mEmptyView.setVisibility(GONE);
        addView(mEmptyView, centerParams());
        applyEmptyContent();
    }
    /**
     * 配置空态内容。
     *
     * <p>设计文档 §9.9 要求空态是「标题 + 解释 + 一个主动作」，并明确「不能只显示一句
     * '暂无数据'」。本方法让宿主按需接入：<b>不调用时渲染结果与改动前完全一致</b>
     * （只有标题、解释与动作保持 GONE），因此既有调用点无需改动。
     *
     * <p>可在任意时刻调用（早于首次显示也可以）：空态视图尚未创建时会先记住参数，
     * 在创建时应用。
     *
     * @param titleRes       标题；传 0 表示沿用布局里的默认文案
     * @param descriptionRes 解释；传 0 表示不显示
     * @param actionRes      主按钮文案；传 0 或不传监听器表示不显示按钮
     * @param listener       主按钮动作；为空则不显示按钮（避免出现点了没反应的死按钮）
     */
    public void setEmptyContent(int titleRes, int descriptionRes, int actionRes, OnClickListener listener) {
        mEmptyTitleRes = titleRes;
        mEmptyDescriptionRes = descriptionRes;
        mEmptyActionRes = actionRes;
        mEmptyActionListener = listener;
        applyEmptyContent();
    }

    private void applyEmptyContent() {
        if (mEmptyView == null) return;
        // 用 findViewById 而不是 ViewEmptyBinding 的字段：view_empty.xml 是**分 flavor** 的，
        // mobile 版没有 description/action 两个控件，而本类在 main 源集。直接引用 binding
        // 字段会让 mobile 编译失败；这里按 id 查找并对缺失做空值守卫，于是 TV 端拿到完整空态、
        // 手机端保持原样（传进来的内容会被静默忽略）。
        TextView title = mEmptyView.findViewById(R.id.text);
        TextView description = mEmptyView.findViewById(R.id.description);
        TextView action = mEmptyView.findViewById(R.id.action);
        if (title != null && mEmptyTitleRes != 0) title.setText(mEmptyTitleRes);
        if (description != null) {
            if (mEmptyDescriptionRes != 0) {
                description.setText(mEmptyDescriptionRes);
                description.setVisibility(VISIBLE);
            } else {
                description.setVisibility(GONE);
            }
        }
        if (action != null) {
            // 没有动作就不显示按钮：避免出现点了没反应的死按钮。
            if (mEmptyActionRes != 0 && mEmptyActionListener != null) {
                action.setText(mEmptyActionRes);
                action.setOnClickListener(mEmptyActionListener);
                action.setVisibility(VISIBLE);
            } else {
                action.setOnClickListener(null);
                action.setVisibility(GONE);
            }
        }
    }

    private void ensureProgressView() {
        if (mProgressView != null) return;
        mProgressView = ViewProgressBinding.inflate(LayoutInflater.from(getContext())).getRoot();
        mProgressView.setTag(TAG_PROGRESS);
        mProgressView.setVisibility(GONE);
        addView(mProgressView, centerParams());
    }

    private LayoutParams centerParams() {
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(CENTER_IN_PARENT);
        return params;
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        if (child.getTag() == null || !child.getTag().equals(TAG_PROGRESS)) {
            mContentViews.add(child);
        }
    }

    /** Adds a child that must not follow CONTENT/PROGRESS/EMPTY visibility changes. */
    public void addOverlayView(View child, ViewGroup.LayoutParams params) {
        addView(child, params);
        mContentViews.remove(child);
    }

    public void showProgress() {
        switchState(State.PROGRESS);
    }

    public void showEmpty() {
        switchState(State.EMPTY);
    }

    public void showContent() {
        switchState(State.CONTENT);
    }

    // 强制把内容视图重新隐藏（仅在 PROGRESS/EMPTY 态有意义）：
    // 用于内容填充（如 setText）在 loading 期间把部分子视图改回 VISIBLE 后，重新压回隐藏，避免泄漏
    public void hideContent() {
        if (mState == State.CONTENT) return;
        setContentVisibility(false);
    }

    public void showContent(boolean flag, int size) {
        if (flag && size == 0) showEmpty();
        else showContent();
    }

    public boolean isProgress() {
        return mState == State.PROGRESS;
    }

    public boolean isContent() {
        return mState == State.CONTENT;
    }

    public boolean isEmpty() {
        return mState == State.EMPTY;
    }

    public void switchState(State state) {
        if (mState == state) return;
        mState = state;
        switch (state) {
            case CONTENT:
                if (mEmptyView != null) mEmptyView.setVisibility(GONE);
                if (mProgressView != null) mProgressView.setVisibility(GONE);
                setContentVisibility(true);
                break;
            case PROGRESS:
                ensureProgressView();
                if (mEmptyView != null) mEmptyView.setVisibility(GONE);
                mProgressView.setVisibility(VISIBLE);
                setContentVisibility(false);
                break;
            case EMPTY:
                ensureEmptyView();
                mEmptyView.setVisibility(VISIBLE);
                if (mProgressView != null) mProgressView.setVisibility(GONE);
                setContentVisibility(false);
                break;
        }
    }

    private void setContentVisibility(boolean visible) {
        for (View view : mContentViews) {
            if (visible) showView(view);
            else hideView(view);
        }
    }

    private void showView(View view) {
        view.setAlpha(0f);
        view.setVisibility(VISIBLE);
        view.animate().alpha(1f).setDuration(100);
    }

    private void hideView(View view) {
        view.setVisibility(INVISIBLE);
    }
}
