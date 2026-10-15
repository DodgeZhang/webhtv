package com.fongmi.android.tv.ui.helper;

import android.app.Activity;
import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.appcompat.view.ContextThemeWrapper;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 电视端控制栏「从按钮 DOWN 到进度条，再 UP 回到刚才那颗按钮」的控件级证据。
 *
 * <p>用户报告：遥控焦点从控制栏按钮按 DOWN 落到进度条后，再按 UP 没有回到原来那颗按钮。
 * 根因是 FocusFinder 只认进度条（{@code timeBar}）自己的 nextFocusUpId，而控制栏只把它
 * 固定指向下一集（或干脆不设，退化成几何搜索）。
 *
 * <p>本测试用真实 {@link View} 语义验证两件事：
 * <ol>
 *   <li>{@link PlayerControlFocusHelper#seekReturnTarget} 的判定：哪颗控件可以被记成回来目标，
 *       哪些情况必须放弃写入（否则会把记忆覆盖成进度条自身）；</li>
 *   <li>{@link PlayerControlFocusHelper#handleKey} 在遥控按键路径上确实做了这次同步，
 *       且焦点落在进度条上时不会覆盖记忆。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class PlayerControlFocusHelperSeekReturnTest {

    private Context context;
    private LinearLayout controls;
    private FrameLayout seek;
    private View timeBar;
    private View action;
    private View decode;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_App);
        controls = new LinearLayout(context);
        controls.setId(View.generateViewId());

        seek = new FrameLayout(context);
        seek.setId(R.id.seek);
        timeBar = focusable(R.id.timeBar);
        seek.addView(timeBar);

        action = focusable(R.id.playerNext);
        decode = focusable(R.id.playerDecode);

        controls.addView(seek);
        controls.addView(action);
        controls.addView(decode);
    }

    @Test
    public void buttonAboveTheSeekBecomesTheReturnTarget() {
        assertSame("从「下集」落到进度条后，UP 必须回到「下集」",
                action, PlayerControlFocusHelper.seekReturnTarget(timeBar, controls, action));
        assertSame("用户从哪颗按钮下来，就回到哪颗按钮，而不是固定第一颗",
                decode, PlayerControlFocusHelper.seekReturnTarget(timeBar, controls, decode));
    }

    @Test
    public void seekItselfNeverOverwritesTheRememberedTarget() {
        assertNull("焦点落在进度条（timeBar）上时不得覆盖记忆，否则 UP 目标会变成进度条自身",
                PlayerControlFocusHelper.seekReturnTarget(timeBar, controls, timeBar));
        assertNull("焦点落在进度条容器上时同样不得覆盖记忆",
                PlayerControlFocusHelper.seekReturnTarget(timeBar, controls, seek));
    }

    @Test
    public void focusOutsideTheControlBarIsIgnored() {
        View outside = focusable(View.generateViewId());
        assertNull("控制栏之外的焦点不得成为进度条的 UP 落点",
                PlayerControlFocusHelper.seekReturnTarget(timeBar, controls, outside));
    }

    @Test
    public void idlessOrUnfocusableViewsAreIgnored() {
        View idless = focusable(View.NO_ID);
        assertNull("没有 id 的控件无法写进 nextFocusUpId，必须放弃",
                PlayerControlFocusHelper.seekReturnTarget(timeBar, controls, idless));

        View notFocusable = new View(context);
        notFocusable.setId(View.generateViewId());
        controls.addView(notFocusable);
        assertNull("不可聚焦的控件不能成为 UP 落点",
                PlayerControlFocusHelper.seekReturnTarget(timeBar, controls, notFocusable));
    }

    @Test
    public void syncWritesTheFocusedButtonIntoTheSeekUpTarget() {
        PlayerControlFocusHelper.syncSeekReturnTarget(controls, decode);
        assertEquals("进度条的 UP 目标必须被改写为刚才停留的那颗按钮",
                decode.getId(), timeBar.getNextFocusUpId());
    }

    @Test
    public void syncKeepsTheMemoryWhileTheSeekItselfIsFocused() {
        PlayerControlFocusHelper.syncSeekReturnTarget(controls, decode);
        PlayerControlFocusHelper.syncSeekReturnTarget(controls, timeBar);
        PlayerControlFocusHelper.syncSeekReturnTarget(controls, seek);
        assertEquals("焦点已经在进度条上时不得把记忆覆盖成进度条自身",
                decode.getId(), timeBar.getNextFocusUpId());
    }

    @Test
    public void syncWithoutASeekBarIsANoop() {
        LinearLayout bare = new LinearLayout(context);
        bare.setId(View.generateViewId());
        bare.addView(focusable(View.generateViewId()));
        PlayerControlFocusHelper.syncSeekReturnTarget(bare, decode);
        assertEquals("没有进度条的控制栏不得被写入任何 UP 目标", View.NO_ID, timeBar.getNextFocusUpId());
    }

    @Test
    public void remoteKeyPathSyncsTheReturnTargetBeforeAnyFocusMove() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(controls);
        assertTrue("测试前置：焦点必须先停在按钮上", action.requestFocus());

        PlayerControlFocusHelper.handleKey(controls, action, key(KeyEvent.KEYCODE_DPAD_DOWN));

        assertEquals("遥控按键路径必须在焦点移走之前把回来目标记好",
                action.getId(), timeBar.getNextFocusUpId());
    }

    @Test
    public void remoteKeyPathKeepsTheMemoryWhileTheSeekIsFocused() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(controls);
        assertTrue("测试前置：焦点必须先停在按钮上", decode.requestFocus());
        PlayerControlFocusHelper.handleKey(controls, decode, key(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(decode.getId(), timeBar.getNextFocusUpId());

        assertTrue("测试前置：焦点必须能落到进度条上", timeBar.requestFocus());
        PlayerControlFocusHelper.handleKey(controls, decode, key(KeyEvent.KEYCODE_DPAD_UP));

        assertEquals("进度条上按 UP 时不能把回来目标改写成进度条自身",
                decode.getId(), timeBar.getNextFocusUpId());
    }

    private View focusable(int id) {
        View view = new View(context);
        view.setId(id);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        return view;
    }

    private static KeyEvent key(int keyCode) {
        return new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
    }
}
