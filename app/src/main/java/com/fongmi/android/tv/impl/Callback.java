package com.fongmi.android.tv.impl;

import androidx.annotation.NonNull;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Response;

public class Callback implements okhttp3.Callback {

    public void success() {
    }

    public void success(String result) {
    }

    public void error() {
    }

    public void start() {
    }

    /**
     * 还没有配置任何内容源（接口列表为空）。
     *
     * <p>这不是失败：没有地址可请求，所以既没有失败原因，重试也永远不会成功。改动前
     * {@code BaseConfig} 把它当作 {@code error("")} 抛出来，于是错误态渲染成
     * 「内容源加载失败 / 重试」——用户看到一个不存在的故障和一个点了没用的按钮
     * （重置应用、重装后首次启动就是这个状态）。宿主应把它渲染成空态并给出
     * 「添加接口」这类**可执行**的主动作。
     */
    public void noConfig() {
    }

    public void error(String msg) {
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
    }
}
