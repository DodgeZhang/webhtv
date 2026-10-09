package com.fongmi.android.tv.tts;

/** 朗读参数（来自阅读器面板，均由 JSON 传入）。 */
public final class TtsOptions {

    public static final float RATE_MIN = 0.5f;
    public static final float RATE_MAX = 2.0f;
    public static final float PITCH_MIN = 0.5f;
    public static final float PITCH_MAX = 1.5f;

    public final float rate;
    public final float pitch;
    public final String voice;

    public TtsOptions(float rate, float pitch, String voice) {
        this.rate = clamp(rate <= 0f ? 1f : rate, RATE_MIN, RATE_MAX);
        this.pitch = clamp(pitch <= 0f ? 1f : pitch, PITCH_MIN, PITCH_MAX);
        this.voice = voice == null ? "" : voice;
    }

    public TtsOptions withRate(float value) {
        return new TtsOptions(value, pitch, voice);
    }

    public TtsOptions withPitch(float value) {
        return new TtsOptions(rate, value, voice);
    }

    public TtsOptions withVoice(String value) {
        return new TtsOptions(rate, pitch, value);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
