package com.fongmi.android.tv.theme;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Controlled runtime channel for user theme overrides.
 *
 * <p>Android offers no public API to rewrite a compiled {@code ?attr/color*}
 * value, so the binder walks the already-created view tree and swaps only colors
 * that provably came from a semantic role: a view color is rewritten when it
 * exactly equals a frozen baseline role value and is not stateful. Everything
 * else - player surfaces, media, posters, brand and health colors, focus
 * selectors, unknown colors - is left untouched.
 *
 * <p>This runs on the main thread only, is a no-op when the active tokens equal
 * the baseline, and never touches a view more than once per token signature.
 */
public final class ThemeBinder {

    private static final String IGNORE_TAG = "webhtv:ignore";
    private static final String[] EXEMPT_CLASS_MARKERS = {
            "surface", "texture", "video", "player", "danmaku", "subtitle",
            "karaoke", "wall", "logo", "rating", "media",
    };

    private static final Map<View, Long> APPLIED = new WeakHashMap<>();
    private static final Map<RecyclerView, RecyclerView.OnChildAttachStateChangeListener> WATCHED = new WeakHashMap<>();
    private static final Map<View, int[]> ROOTS = new WeakHashMap<>();
    private static final Map<ColorStateList, ColorStateList> REBUILT = new WeakHashMap<>();

    private static long cachedSignature;

    private static volatile long lastBindMillis;
    private static volatile int lastBoundViews;
    private static volatile int lastWalkedViews;

    private ThemeBinder() {
    }

    /** Elapsed time of the most recent non-trivial bind, for the performance gate. */
    public static long lastBindMillis() {
        return lastBindMillis;
    }

    /** Number of views rewritten by the most recent non-trivial bind. */
    public static int lastBoundViews() {
        return lastBoundViews;
    }

    public static void bind(View root, ThemeTokens baseline, ThemeTokens active) {
        if (root == null || baseline == null || active == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        if (baseline.equals(active)) {
            // Default profile: the static Layer 1 theme is already correct, so the
            // binder must cost nothing and change nothing.
            lastBindMillis = 0L;
            lastBoundViews = 0;
            return;
        }
        long started = SystemClock.elapsedRealtimeNanos();
        long signature = signature(active);
        if (signature != cachedSignature) {
            REBUILT.clear();
            cachedSignature = signature;
        }
        ThemeColorIndex index = ThemeColorIndex.of(baseline);
        int bound = walk(root, index, active, signature);
        watchRoot(root, index, active, signature);
        lastBoundViews = bound;
        lastBindMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L;
    }

    private static int walk(View root, ThemeColorIndex index, ThemeTokens active, long signature) {
        int bound = 0;
        int walked = 0;
        Deque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view == null) continue;
            walked++;
            if (isExempt(view)) continue;
            if (bindView(view, index, active, signature)) bound++;
            if (view instanceof RecyclerView recyclerView) watchChildren(recyclerView, index, active, signature);
            if (view instanceof ViewGroup group) {
                for (int child = 0; child < group.getChildCount(); child++) {
                    queue.add(group.getChildAt(child));
                }
            }
        }
        lastWalkedViews = walked;
        return bound;
    }

    /**
     * Fragments and async content attach after the Activity bind points. The root
     * is re-walked only when its descendant count actually changed, which keeps the
     * scroll path free of repeated traversals.
     */
    private static void watchRoot(View root, ThemeColorIndex index, ThemeTokens active, long signature) {
        if (ROOTS.containsKey(root)) return;
        int[] state = {countDescendants(root)};
        ROOTS.put(root, state);
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (state[0] == countDescendants(view)) return;
            state[0] = countDescendants(view);
            walk(view, index, active, signature);
        });
    }

    private static int countDescendants(View view) {
        if (!(view instanceof ViewGroup group)) return 1;
        int count = 1;
        for (int child = 0; child < group.getChildCount(); child++) count += countDescendants(group.getChildAt(child));
        return count;
    }

    private static void watchChildren(RecyclerView recyclerView, ThemeColorIndex index, ThemeTokens active, long signature) {
        if (WATCHED.containsKey(recyclerView)) return;
        RecyclerView.OnChildAttachStateChangeListener listener = new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
                walk(view, index, active, signature);
            }

            @Override
            public void onChildViewDetachedFromWindow(View view) {
                APPLIED.remove(view);
            }
        };
        WATCHED.put(recyclerView, listener);
        recyclerView.addOnChildAttachStateChangeListener(listener);
    }

    private static boolean bindView(View view, ThemeColorIndex index, ThemeTokens active, long signature) {
        Long applied = APPLIED.get(view);
        if (applied != null && applied == signature) return false;
        ThemeRole explicit = ThemeRole.fromTag(view.getTag());
        boolean changed = false;
        if (view instanceof TextView textView) changed |= bindText(textView, explicit, index, active);
        if (view instanceof ImageView imageView) changed |= bindImageTint(imageView, explicit, index, active);
        if (view instanceof MaterialCardView cardView) changed |= bindCard(cardView, explicit, index, active);
        changed |= bindBackground(view, explicit, index, active);
        if (changed) APPLIED.put(view, signature);
        return changed;
    }

    private static boolean bindText(TextView view, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        boolean changed = false;
        ColorStateList colors = rewrite(view.getTextColors(), explicit, index, active);
        if (colors != null) {
            view.setTextColor(colors);
            changed = true;
        }
        ColorStateList hint = rewrite(view.getHintTextColors(), explicit, index, active);
        if (hint != null) {
            view.setHintTextColor(hint);
            changed = true;
        }
        return changed;
    }

    private static boolean bindImageTint(ImageView view, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        ColorStateList tint = rewrite(view.getImageTintList(), explicit, index, active);
        if (tint == null) return false;
        view.setImageTintList(tint);
        return true;
    }

    private static boolean bindCard(MaterialCardView view, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        boolean changed = false;
        ColorStateList background = rewrite(view.getCardBackgroundColor(), explicit, index, active);
        if (background != null) {
            view.setCardBackgroundColor(background);
            changed = true;
        }
        ColorStateList stroke = rewrite(ColorStateList.valueOf(view.getStrokeColor()),
                index.uniqueRoleFor(view.getStrokeColor()) == null ? ThemeRole.OUTLINE : null, index, active);
        if (stroke != null) {
            view.setStrokeColor(stroke);
            changed = true;
        }
        return changed;
    }

    private static boolean bindBackground(View view, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        Drawable background = view.getBackground();
        if (!(background instanceof GradientDrawable shape)) return false;
        ColorStateList color = rewrite(shape.getColor(), explicit, index, active);
        if (color == null) return false;
        shape.setColor(color);
        return true;
    }

    /**
     * Returns a recolored copy of the list, preserving its state behaviour, or null
     * when the colour is unknown/unchanged or the state shape cannot be rebuilt.
     */
    private static ColorStateList rewrite(ColorStateList source, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        if (source == null) return null;
        if (!source.isStateful()) {
            Integer replacement = singleColor(source.getDefaultColor(), explicit, index, active);
            return replacement == null ? null : ColorStateList.valueOf(replacement);
        }
        ColorStateList cached = REBUILT.get(source);
        if (cached != null) return cached;
        int[][] states = ThemeColorIndex.stateSkeleton();
        int[] colors = new int[states.length];
        boolean changed = false;
        // Material state lists usually vary alpha only. Resolve the role from the
        // default colour first, then keep each state's original alpha so pressed /
        // disabled / focused states stay distinguishable.
        int defaultColor = source.getDefaultColor();
        Integer defaultReplacement = singleColor(defaultColor, explicit, index, active);
        for (int position = 0; position < states.length; position++) {
            int original = resolvedColor(source, states[position]);
            Integer replacement = singleColor(original, explicit, index, active);
            if (replacement == null && defaultReplacement != null && sameRgb(original, defaultColor)) {
                replacement = withAlpha(defaultReplacement, original >>> 24);
            }
            colors[position] = replacement == null ? original : replacement;
            changed |= replacement != null;
        }
        if (!changed) return null;
        // ColorStateList exposes a public (int[][], int[]) constructor, so the list
        // can be rebuilt directly without XML inflation or hidden platform APIs.
        ColorStateList rebuilt = new ColorStateList(states, colors);
        REBUILT.put(source, rebuilt);
        return rebuilt;
    }

    private static boolean sameRgb(int first, int second) {
        return (first & 0x00FFFFFF) == (second & 0x00FFFFFF);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int resolvedColor(ColorStateList source, int[] state) {
        int color = source.getColorForState(state, source.getDefaultColor());
        return color == 0 ? source.getDefaultColor() : color;
    }

    private static Integer singleColor(int current, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        if (explicit != null) {
            Integer baseline = index.baselineOf(explicit);
            int target = explicit.colorOf(active);
            if (target == 0 || target == current) return null;
            if (baseline != null && baseline != current) return null;
            return target;
        }
        return index.replacementFor(current, active);
    }

    private static boolean isExempt(View view) {
        Object tag = view.getTag();
        if (tag instanceof String value && IGNORE_TAG.equals(value.trim().toLowerCase(Locale.ROOT))) return true;
        String name = view.getClass().getName().toLowerCase(Locale.ROOT);
        for (String marker : EXEMPT_CLASS_MARKERS) {
            if (name.contains(marker)) return true;
        }
        return false;
    }

    private static long signature(ThemeTokens tokens) {
        // ThemeTokens is a content-based record; its generated hashCode is stable
        // across the desugared Android runtime. Java-16 record reflection is
        // deliberately avoided because it does not exist on Android API < 33.
        return tokens.hashCode();
    }
}
