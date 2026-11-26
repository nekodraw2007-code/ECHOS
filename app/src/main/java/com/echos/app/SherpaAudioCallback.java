package com.echos.app;

import android.media.AudioTrack;

import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.jvm.functions.Function1;

/**
 * Explicit JVM callback for sherpa-onnx JNI.
 *
 * The native binding resolves invoke([F)Ljava/lang/Integer; by name and
 * descriptor. A Kotlin lambda may be emitted as an ART synthetic lambda
 * without this exact method on some Android/R8 combinations.
 */
final class SherpaAudioCallback implements Function1<float[], Integer> {
    private final AudioTrack track;
    private final AtomicBoolean stopRequested;
    private final int style;

    SherpaAudioCallback(AudioTrack track, AtomicBoolean stopRequested, int style) {
        this.track = track;
        this.stopRequested = stopRequested;
        this.style = style;
    }

    @Override
    public Integer invoke(float[] samples) {
        if (stopRequested.get()) return Integer.valueOf(0);

        short[] shorts = new short[samples.length];
        float previous = 0.0f;
        for (int i = 0; i < samples.length; i++) {
            float sample = samples[i];
            switch (style) {
                case 0: // Whisper-like airy signal: high-pass, light noise, soft compression.
                    float high = sample - previous * 0.90f;
                    previous = sample;
                    float air = ((i * 1103515245L + 12345L) & 0x7fffffffL)
                            / 2147483647.0f - 0.5f;
                    sample = (float) Math.tanh(high * 1.55f) * 0.18f + air * 0.004f;
                    break;
                case 1:
                    sample *= 0.88f;
                    break;
                case 2:
                    sample = Math.max(-1.0f, Math.min(1.0f, sample * 1.10f));
                    break;
                case 3:
                    sample *= 0.92f;
                    break;
                default:
                    sample *= 0.98f;
                    break;
            }
            sample = Math.max(-1.0f, Math.min(1.0f, sample));
            shorts[i] = (short) (sample * 32767.0f);
        }

        int offset = 0;
        while (offset < shorts.length && !stopRequested.get()) {
            int written = track.write(shorts, offset, shorts.length - offset,
                    AudioTrack.WRITE_BLOCKING);
            if (written <= 0) break;
            offset += written;
        }
        return Integer.valueOf(stopRequested.get() ? 0 : 1);
    }
}
