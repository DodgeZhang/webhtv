package com.fongmi.android.tv.ui.activity;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 手机端「空态 / 加载态 / 错误态」的规格契约。
 *
 * <p>依据是本仓库自己的设计文档，不是外部偏好：
 * <ul>
 *   <li>§9.9：「空态：<b>标题 + 解释 + 一个主动作</b>；<b>不能只显示一句"暂无数据"</b>」，
 *       「加载使用统一 spinner/progress……不使用多种颜色动画」，
 *       「错误态：错误色 + 原因 + 重试动作」；</li>
 *   <li>§6.2 字阶表：空态属 <b>Display</b> 档，手机为 <b>32–40sp</b>。</li>
 * </ul>
 *
 * <p>本契约与电视端的 `TvStateSurfaceContractTest` / `TvErrorStateContractTest` 同构。
 * 电视端批次已经把设计与实现定案，手机端是**应用**这些结论，不是重新设计。
 */
public class MobileStateSurfaceContractTest {

    private static final String EMPTY = "app/src/mobile/res/layout/view_empty.xml";
    private static final String PROGRESS = "app/src/mobile/res/layout/view_progress.xml";
    private static final String KEEP = "app/src/mobile/java/com/fongmi/android/tv/ui/activity/KeepActivity.java";
    private static final String STRINGS = "app/src/main/res/values/strings.xml";
    private static final String STRINGS_CN = "app/src/main/res/values-zh-rCN/strings.xml";

    /** §6.2：手机端空态标题属 Display 档。 */
    private static final int MIN_DISPLAY_SP = 32;
    private static final int MAX_DISPLAY_SP = 40;

    private static final Pattern RAW_COLOR = Pattern.compile("\"#[0-9A-Fa-f]{6,8}\"");

    // ------------------------------------------------------------------ 空态

    @Test
    public void emptyStateCarriesTitleDescriptionAndOnePrimaryAction() throws Exception {
        String layout = read(EMPTY);
        for (String id : new String[]{"text", "description", "action"}) {
            assertTrue("空态必须声明 @+id/" + id + "（§9.9 的标题/解释/主动作）",
                    layout.contains("@+id/" + id));
        }
        // 只有标题时渲染结果与改动前一致，未接入的宿主不被强制改变观感。
        assertTrue("description 必须默认 GONE", block(layout, "description").contains("android:visibility=\"gone\""));
        assertTrue("action 必须默认 GONE", block(layout, "action").contains("android:visibility=\"gone\""));
    }

    @Test
    public void emptyStateTitleUsesTheMobileDisplayTier() throws Exception {
        String title = block(read(EMPTY), "text");
        Matcher size = Pattern.compile("android:textSize=\"(\\d+)sp\"").matcher(title);
        assertTrue("空态标题必须显式声明字号", size.find());
        int sp = Integer.parseInt(size.group(1));
        assertTrue("空态标题 " + sp + "sp 低于 §6.2 的手机 Display 档（" + MIN_DISPLAY_SP + "–" + MAX_DISPLAY_SP + "sp）",
                sp >= MIN_DISPLAY_SP && sp <= MAX_DISPLAY_SP);
    }

    @Test
    public void emptyStateUsesWallpaperRolesNotRawColours() throws Exception {
        String layout = withoutComments(read(EMPTY));
        assertFalse("空态不得出现裸十六进制", RAW_COLOR.matcher(layout).find());
        // 精确比较：webhtvColorOnWallpaperVariant 里包含 webhtvColorOnWallpaper 这个子串，
        // 用 contains 会让「标题误用次级色」也通过。
        assertEquals("标题必须走壁纸前景角色（纯白）",
                "?attr/webhtvColorOnWallpaper", textColourOf(layout, "text"));
        assertEquals("解释必须走壁纸次级角色（否则与标题同权重，解释等于没解释）",
                "?attr/webhtvColorOnWallpaperVariant", textColourOf(layout, "description"));
        // 主按钮用应用自己的语义按钮样式：容器色与文字色由该样式成对提供，因此它**不**声明
        // textColor。改动前它是一个只有 clickable 的 TextView，渲染出来与解释文字没有区别，
        // 用户看不出可以点（实机确认）。
        assertTrue("主按钮必须用语义按钮样式而不是裸文本",
                block(layout, "action").contains("@style/Widget.WebHTV.Button.Tonal"));
    }

    // ------------------------------------------------------------------ 加载态

    @Test
    public void progressIndicatorUsesASemanticRoleInsteadOfARawColour() throws Exception {
        String layout = withoutComments(read(PROGRESS));
        assertFalse("加载指示器不得写死颜色", layout.contains("app:indicatorColor=\"@color/"));
        assertFalse("加载指示器不得出现裸十六进制", RAW_COLOR.matcher(layout).find());
        assertTrue("加载指示器必须走壁纸前景角色",
                layout.contains("app:indicatorColor=\"?attr/webhtvColorOnWallpaper\""));
    }

    // ------------------------------------------------------------------ 宿主接入

    @Test
    public void favoritesWiresARealEmptyStateWithAnAction() throws Exception {
        String source = read(KEEP);
        assertTrue("收藏页必须接入空态内容", source.contains("setEmptyContent("));
        for (String string : new String[]{
                "keep_empty_title", "keep_empty_description", "keep_empty_action"}) {
            assertTrue("收藏页必须引用 " + string, source.contains(string));
            assertTrue("默认字符串表必须声明 " + string, read(STRINGS).contains("name=\"" + string + "\""));
            assertTrue("简中字符串表必须声明 " + string, read(STRINGS_CN).contains("name=\"" + string + "\""));
        }
        assertTrue("主按钮必须有真实动作（不能是死按钮）", source.contains("view -> goHome()"));
        assertTrue("goHome 必须真的回首页", source.contains("HomeActivity.class"));
    }

    /**
     * 删除路径必须驱动状态机。
     *
     * <p>与电视端此前同款缺陷：`onItemDelete` 只退出删除模式，从不调用
     * `showContent(true, size)`，于是用户删掉最后一条收藏后看到的是**一片空白**——
     * 连空态都不出现。
     */
    @Test
    public void deletePathDrivesTheStateMachineSoTheEmptyStateIsReachable() throws Exception {
        String handler = methodBody(read(KEEP), "public void onItemDelete(Keep item)");
        assertTrue("删除回调必须驱动状态机，否则删空后是一片空白而不是空态",
                handler.contains("showContent(true,"));
    }

    @Test
    public void favoritesConfigLoadFailureEntersTheErrorStateWithARetry() throws Exception {
        String handler = methodBody(read(KEEP), "public void error(String msg)");
        assertTrue("失败必须进入错误态", handler.contains("showError("));
        assertTrue("错误态标题必须是 error_config_load", handler.contains("R.string.error_config_load"));
        assertTrue("必须有重试按钮文案", handler.contains("R.string.error_retry"));
        assertTrue("重试必须复用同一条加载路径", handler.contains("loadConfig(config, item);"));
        assertTrue("应保留原有的吐司提示", handler.contains("Notify.show(msg);"));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 取方法体：先剥注释再按花括号配对，这样匿名内部类（缩进不是 4 空格）也能正确收尾，
     * 不会一路取到文件末尾而让断言失去意义。
     */
    private static String methodBody(String source, String signature) {
        String cleaned = withoutJavaComments(source);
        int start = cleaned.indexOf(signature);
        assertTrue("必须存在方法 " + signature, start > 0);
        int open = cleaned.indexOf('{', start);
        assertTrue("方法签名后必须有方法体", open > 0);
        int depth = 0;
        for (int i = open; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return cleaned.substring(start, i + 1);
        }
        return cleaned.substring(start);
    }

    /** 取某个 {@code @+id/xxx} 控件自身的属性文本。 */
    private static String block(String layout, String id) {
        Matcher matcher = Pattern.compile("<[A-Za-z0-9_.]+[^>]*@\\+id/" + Pattern.quote(id)
                + "\"(.*?)(?:/>|>)", Pattern.DOTALL).matcher(layout);
        assertTrue("布局里必须能找到 @+id/" + id, matcher.find());
        return matcher.group(1);
    }

    /** 取某个 {@code @+id/xxx} 控件声明的 {@code android:textColor} 原文。 */
    private static String textColourOf(String layout, String id) {
        Matcher colour = Pattern.compile("android:textColor=\"([^\"]+)\"").matcher(block(layout, id));
        assertTrue("@+id/" + id + " 必须显式声明 textColor", colour.find());
        return colour.group(1);
    }

    /** 剥掉 XML 注释，避免负向断言命中说明文字（本仓库已踩过多次同类坑）。 */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)<!--.*?-->", " ");
    }

    private static String withoutJavaComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static String read(String path) throws Exception {
        Path direct = Path.of(path);
        if (Files.exists(direct)) return Files.readString(direct, StandardCharsets.UTF_8);
        return Files.readString(Path.of("..").resolve(path), StandardCharsets.UTF_8);
    }
}
