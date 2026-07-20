package com.zxkws.fastvoice.internal;

import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.TenVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.util.ArrayDeque;

/**
 * 麦克风上行二级门控。原始音频仍可供本地 KWS 使用，只有送往云端 ASR 的副本会被处理。
 */
final class MicVoiceGate {
    private static final int FRAME_SAMPLES = 320;
    private static final int WINDOW_SAMPLES = 512;
    private static final int PRE_ROLL_FRAMES = 10;
    private static final int SPEECH_HANGOVER_FRAMES = 35;
    private static final int TONE_HANGOVER_FRAMES = 25;
    private static final double[] HANN_WINDOW = new double[FRAME_SAMPLES];
    private static final double[] GOERTZEL_COEFFICIENTS = new double[81];

    static {
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            HANN_WINDOW[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FRAME_SAMPLES - 1));
        }
        for (int bin = 3; bin <= 80; bin++) {
            GOERTZEL_COEFFICIENTS[bin] = 2.0 * Math.cos(2.0 * Math.PI * bin / FRAME_SAMPLES);
        }
    }

    private final ArrayDeque<byte[]> delayedFrames = new ArrayDeque<>();
    private final float[] vadWindow = new float[WINDOW_SAMPLES];
    private int vadWindowSamples;
    private Vad vad;
    private int frameNumber;
    private int speechUntilFrame;
    private int toneUntilFrame;
    private int stableToneFrames;
    private int previousToneLag;
    private float lastVadProbability;

    public void init(AssetManager assets) {
        SileroVadModelConfig silero = new SileroVadModelConfig(
                "fastvoice/silero_vad.onnx", 0.5f, 0.25f, 0.10f, WINDOW_SAMPLES, 10.0f);
        TenVadModelConfig tenVad = new TenVadModelConfig("", 0.5f, 0.25f, 0.25f, 256, 5.0f);
        vad = new Vad(assets, new VadModelConfig(silero, tenVad, 16000, 1, "cpu", false));
    }

    public synchronized byte[] process(byte[] pcm) {
        if (pcm.length != FRAME_SAMPLES * 2) throw new IllegalArgumentException("PCM frame must be 20 ms");
        frameNumber++;

        short[] samples = new short[FRAME_SAMPLES];
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            int lo = pcm[i * 2] & 0xff;
            samples[i] = (short) (lo | (pcm[i * 2 + 1] << 8));
        }

        boolean stableTone = updateToneDetector(samples);
        if (stableTone) toneUntilFrame = frameNumber + TONE_HANGOVER_FRAMES;

        float probability = acceptVad(samples);
        if (probability >= 0.48f && !stableTone) {
            speechUntilFrame = frameNumber + SPEECH_HANGOVER_FRAMES;
        }

        delayedFrames.addLast(pcm.clone());
        if (delayedFrames.size() <= PRE_ROLL_FRAMES) return new byte[pcm.length];

        byte[] oldest = delayedFrames.removeFirst();
        boolean toneBlocked = frameNumber <= toneUntilFrame && probability < 0.75f;
        boolean speechAllowed = frameNumber <= speechUntilFrame;
        return speechAllowed && !toneBlocked ? oldest : new byte[oldest.length];
    }

    private float acceptVad(short[] samples) {
        int source = 0;
        while (source < samples.length) {
            int count = Math.min(samples.length - source, WINDOW_SAMPLES - vadWindowSamples);
            for (int i = 0; i < count; i++) {
                vadWindow[vadWindowSamples + i] = samples[source + i] / 32768.0f;
            }
            vadWindowSamples += count;
            source += count;
            if (vadWindowSamples == WINDOW_SAMPLES) {
                lastVadProbability = vad.compute(vadWindow);
                vadWindowSamples = 0;
            }
        }
        return lastVadProbability;
    }

    private boolean updateToneDetector(short[] samples) {
        double energy = 0.0;
        for (short sample : samples) energy += (double) sample * sample;
        double rms = Math.sqrt(energy / samples.length);
        if (rms < 450.0) {
            stableToneFrames = 0;
            previousToneLag = 0;
            return false;
        }

        int bestLag = 0;
        double bestCorrelation = 0.0;
        for (int lag = 16; lag <= 160; lag++) {
            double cross = 0.0;
            double left = 0.0;
            double right = 0.0;
            for (int i = lag; i < samples.length; i++) {
                double a = samples[i];
                double b = samples[i - lag];
                cross += a * b;
                left += a * a;
                right += b * b;
            }
            double correlation = cross / Math.sqrt(left * right + 1.0);
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestLag = lag;
            }
        }

        boolean narrowBand = bestCorrelation >= 0.94 && spectralConcentration(samples) >= 0.72;
        if (narrowBand &&
                (previousToneLag == 0 || Math.abs(bestLag - previousToneLag) <= 2)) {
            stableToneFrames++;
        } else {
            stableToneFrames = 0;
        }
        previousToneLag = narrowBand ? bestLag : 0;
        return stableToneFrames >= 6;
    }

    // 单频铃声的能量集中在极少数频点；人声即便有稳定基频，也会分散到多个谐波。
    private double spectralConcentration(short[] samples) {
        double total = 0.0;
        double first = 0.0;
        double second = 0.0;
        double third = 0.0;
        for (int bin = 3; bin <= 80; bin++) {
            double coefficient = GOERTZEL_COEFFICIENTS[bin];
            double q1 = 0.0;
            double q2 = 0.0;
            for (int i = 0; i < samples.length; i++) {
                double windowed = samples[i] * HANN_WINDOW[i];
                double q0 = coefficient * q1 - q2 + windowed;
                q2 = q1;
                q1 = q0;
            }
            double power = q1 * q1 + q2 * q2 - coefficient * q1 * q2;
            total += power;
            if (power > first) {
                third = second; second = first; first = power;
            } else if (power > second) {
                third = second; second = power;
            } else if (power > third) {
                third = power;
            }
        }
        return (first + second + third) / (total + 1.0);
    }

    public synchronized void release() {
        if (vad != null) {
            vad.release();
            vad = null;
        }
        delayedFrames.clear();
    }
}
