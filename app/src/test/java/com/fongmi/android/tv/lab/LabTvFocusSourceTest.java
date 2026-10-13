package com.fongmi.android.tv.lab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实验室详情页必须能用遥控器操作焦点。
 *
 * <p>缺陷实测（leanback arm64 debug，{@code 192.168.50.3:5561}，{@code mInTouchMode=false}）：
 * {@code LabDetailActivity} 的 2 秒轮询调用 {@code updateButtons()} → {@code commandAdapter
 * .notifyDataSetChanged()}，RecyclerView 回收并重新挂载全部子 View，焦点所在的「运行」按钮被移除，
 * 框架把焦点丢回工具栏返回键。实测按下方向键聚焦命令行后 1.2 秒内焦点即回到工具栏，
 * 遥控器按不到「运行」——用户只能靠 lab 配置里的 {@code auto_execute} 启动命令。
 *
 * <p>本测试锁住两条不变量：命令列表只做状态差异刷新；实验室自己的可聚焦控件有聚焦态。
 * 设备上的焦点保持由任务文档记录的遥控实测覆盖，这里只锁会导致回归的源码/资源形态。
 */
public class LabTvFocusSourceTest {

    private static final String ACTIVITY = "src/main/java/com/fongmi/android/tv/lab/LabDetailActivity.java";
    private static final String ADAPTER = "src/main/java/com/fongmi/android/tv/lab/LabCommandAdapter.java";
    private static final String DETAIL_LAYOUT = "src/main/res/layout/activity_lab_detail.xml";

    /** 实验室自己绘制的可聚焦控件：缺 state_focused 就等于遥控看不到焦点。 */
    private static final String[] FOCUS_VISIBLE_DRAWABLES = {
            "src/main/res/drawable/shape_lab_card.xml",
            "src/main/res/drawable/shape_lab_run_btn.xml",
            "src/main/res/drawable/shape_lab_stop_btn.xml",
            "src/main/res/drawable/shape_lab_click_btn.xml",
            "src/main/res/drawable/selector_lab_group_tab.xml",
            "src/main/res/drawable/selector_lab_icon_btn.xml"
    };

    /**
     * 周期刷新不能再整表重建列表。
     *
     * <p>{@code updateButtons()} 由 2 秒轮询驱动，任何整表刷新都会周期性抢走遥控焦点。
     */
    @Test
    public void periodicRefreshUsesDiffUpdatesOnly() throws Exception {
        String activity = stripComments(read(ACTIVITY));
        assertTrue("updateButtons() must refresh the command list through the diff path",
                activity.contains("commandAdapter.refreshStates();"));
        assertFalse("a full notifyDataSetChanged() on the 2s poll steals the remote focus",
                activity.contains("commandAdapter.notifyDataSetChanged()"));
    }

    /**
     * 差异刷新必须按行状态判断，并且只做局部通知。
     *
     * <p>{@code notifyItemChanged} 复用同一个 ViewHolder；整表刷新会回收焦点所在的视图。
     */
    @Test
    public void diffRefreshSkipsUnchangedRowsAndNotifiesSingleItems() throws Exception {
        String refresh = methodBody(stripComments(read(ADAPTER)), "public void refreshStates()");
        assertTrue("refreshStates() must compare per-row state before notifying",
                refresh.contains("stateOf(rows.get(i))"));
        assertTrue("unchanged rows must be skipped entirely",
                refresh.contains("continue;"));
        assertTrue("changed rows are refreshed in place",
                refresh.contains("notifyItemChanged(i)"));
        assertFalse("refreshStates() must never rebuild the whole list",
                refresh.contains("notifyDataSetChanged"));
    }

    /** 行状态签名必须覆盖 onBindViewHolder 会改变的全部可视差异。 */
    @Test
    public void rowStateSignatureCoversRunningAndLabel() throws Exception {
        String state = methodBody(stripComments(read(ADAPTER)), "private String stateOf(");
        assertTrue("the running state drives the dot, tag and button",
                state.contains("LabRunner.isRunning("));
        assertTrue("the label is the only other visible difference",
                state.contains("command.name == null ? command.description : command.name"));
    }

    /**
     * 实验室自绘的可聚焦控件必须都有聚焦态。
     *
     * <p>设备实测：命令行的「运行」按钮聚焦前后像素完全一致（仅有按下态），
     * 遥控用户看不到焦点落在哪一行/哪个按钮上。
     */
    @Test
    public void labFocusableControlsExposeAFocusedState() throws Exception {
        for (String file : FOCUS_VISIBLE_DRAWABLES) {
            assertTrue(file + " must render a focused state for remote navigation",
                    stripComments(read(file)).contains("android:state_focused=\"true\""));
        }
    }

    /**
     * 命令弹窗的初始焦点必须落在按钮上。
     *
     * <p>BottomSheetDialog 默认聚焦第一个可聚焦控件（命令预览 EditText）。实测遥控方向键在单行输入框里
     * 出不去（DOWN / RIGHT / UP 都不移动焦点，只有 TAB 能到按钮），电视遥控器没有 TAB，
     * 用户打开了弹窗却按不到「运行」。
     */
    @Test
    public void commandSheetAnchorsFocusOnAButton() throws Exception {
        String sheet = stripComments(read("src/main/java/com/fongmi/android/tv/lab/LabCommandSheet.java"));
        assertTrue("the sheet must move the initial focus onto a button",
                sheet.contains("requestSheetFocus();"));
        assertTrue("the run button wins when it is usable, otherwise the edit button",
                sheet.contains("sheetBtnRun.getVisibility() == View.VISIBLE && sheetBtnRun.isEnabled() ? sheetBtnRun : sheetBtnEdit"));

        String poll = methodBody(sheet, "public void run()");
        assertFalse("the 1s state poll must not steal focus back while the user types",
                poll.contains("requestSheetFocus"));
    }

    /** 分组标签的聚焦态必须由 selector 通过 state_focused 接上（聚焦图形本身是纯 shape）。 */    @Test
    public void groupTabSelectorWiresTheFocusedShape() throws Exception {
        String selector = stripComments(read("src/main/res/drawable/selector_lab_group_tab.xml"));
        assertTrue("the focused branch must come first so it wins over activated",
                selector.indexOf("state_focused") < selector.indexOf("state_activated"));
        assertTrue("the focused branch must point at the focused shape",
                selector.contains("android:drawable=\"@drawable/shape_lab_group_tab_focused\""));
        assertTrue("the focused shape must keep the tab padding so the label does not jump",
                read("src/main/res/drawable/shape_lab_group_tab_focused.xml").contains("android:left=\"12dp\""));
    }

    /**
     * 详情页图标按钮必须显式声明焦点邻居。
     *
     * <p>实测（leanback arm64 debug，小 UI 缩放）：从「下载安装」按 DOWN 会直接跳到第一条命令，
     * 跳过终端/新增/刷新三个图标 —— 焦点搜索按加权距离（13×主轴² + 次轴²）比较，
     * 图标在右侧且行高只有 32px 时，正下方的命令行更「近」。用 nextFocus* 固定顺序即可，
     * 目标控件不可见时框架会自动回退到几何搜索。
     */
    @Test
    public void detailHeaderIconsDeclareFocusNeighbours() throws Exception {
        String layout = stripComments(read(DETAIL_LAYOUT));
        assertTrue("the download button must hand focus down to the icon row",
                layout.contains("android:nextFocusDown=\"@+id/btnTerminal\""));
        assertTrue("the terminal icon must lead back up to the download button",
                layout.contains("android:nextFocusUp=\"@+id/btnDownload\""));
        assertTrue("the three icons must chain left to right",
                layout.contains("android:nextFocusRight=\"@+id/btnAddCommand\"")
                        && layout.contains("android:nextFocusRight=\"@+id/btnRefreshCommand\"")
                        && layout.contains("android:nextFocusLeft=\"@+id/btnAddCommand\""));
    }

    /** 详情页顶部图标按钮必须用带聚焦态的 selector，而不是只有按下态的 borderless ripple。 */
    @Test
    public void detailHeaderIconButtonsUseTheFocusSelector() throws Exception {
        String layout = stripComments(read(DETAIL_LAYOUT));
        Matcher matcher = Pattern.compile("android:background=\"([^\"]+)\"").matcher(layout);
        int focusSelectors = 0;
        while (matcher.find()) {
            if ("@drawable/selector_lab_icon_btn".equals(matcher.group(1))) focusSelectors++;
        }
        assertTrue("terminal / add / refresh icons must use selector_lab_icon_btn, found " + focusSelectors,
                focusSelectors == 3);
        assertFalse("a borderless ripple has no focused state",
                layout.contains("?attr/selectableItemBackgroundBorderless"));
    }

    // ------------------------------------------------------------------ helpers

    /** 取出方法体，避免断言被同文件其它方法的文本意外满足。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(signature + " is missing", start >= 0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError(signature + " has no closing brace");
    }

    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?sm)^\\s*/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    private static String read(String path) throws Exception {
        Path root = Files.exists(Path.of("src")) ? Path.of("") : Path.of("app");
        return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
    }
}
