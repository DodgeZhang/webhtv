package com.fongmi.android.tv.server.process;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;

/** 猫源 {@code openInternalWebview} 的落地：优先内置浏览，取不到前台 Activity 时交给系统。 */
final class CatWebview {

    private CatWebview() {
    }

    static void open(String url) {
        App.post(() -> {
            Activity activity = App.activity();
            try {
                if (activity != null && !activity.isFinishing()) {
                    activity.startActivity(browse(url));
                } else {
                    Intent intent = browse(url);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    App.get().startActivity(intent);
                }
            } catch (Exception e) {
                SpiderDebug.log("cat-msg", e);
            }
        });
    }

    private static Intent browse(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }
}
