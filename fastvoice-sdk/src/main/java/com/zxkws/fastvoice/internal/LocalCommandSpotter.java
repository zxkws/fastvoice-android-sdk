package com.zxkws.fastvoice.internal;

import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 本地中文控制词检测器。音频由主录音线程提供，不单独占用麦克风。 */
final class LocalCommandSpotter {
    enum KeywordRoute {
        WAKE,
        CONTROL,
        IGNORE,
    }

    private static final String DIR =
            "fastvoice/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01";
    private static final Set<String> CONTROL_WORDS = new LinkedHashSet<>(Arrays.asList(
            "换一个", "换个", "下一个", "停止", "停一下", "别说", "闭嘴", "等等", "打住", "重来",
            "退下", "退下吧", "继续"));

    private KeywordSpotter spotter;
    private final ReplaceOnSuccess<OnlineStream> stream = new ReplaceOnSuccess<>();
    private final KeywordLineRegistry keywordLines = new KeywordLineRegistry();

    /** Native sherpa may return labels from its static config; fail closed against our allowlist. */
    static KeywordRoute routeKeyword(String keyword, Collection<String> enabledWakeWords) {
        if (keyword != null && enabledWakeWords != null && enabledWakeWords.contains(keyword)) {
            return KeywordRoute.WAKE;
        }
        return CONTROL_WORDS.contains(keyword) ? KeywordRoute.CONTROL : KeywordRoute.IGNORE;
    }

    public synchronized void init(AssetManager assets) {
        init(assets, Collections.singleton("咘嘀"));
    }

    public synchronized void init(AssetManager assets, Collection<String> wakeWords) {
        if (spotter != null) return;
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig(
                DIR + "/encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx",
                DIR + "/decoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx",
                DIR + "/joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx");
        OnlineModelConfig model = new OnlineModelConfig(
                transducer,
                new OnlineParaformerModelConfig(),
                new OnlineZipformer2CtcModelConfig(),
                new OnlineNeMoCtcModelConfig(),
                new OnlineToneCtcModelConfig(),
                DIR + "/tokens.txt", 1, false, "", "zipformer2", "", "");
        KeywordSpotterConfig config = new KeywordSpotterConfig(
                new FeatureConfig(16000, 80, 0.0f), model, 4,
                // 两阶段协议会再由服务端 ASR 排除误触；这里优先提高真实外放环境下
                // 的召回并缩短候选出现时间，避免短控制词到达时只剩尾音可供确认。
                DIR + "/keywords.txt", 1.5f, 0.25f, 1);
        spotter = new KeywordSpotter(assets, config);
        loadKeywordLines(assets);
        configureWakeWords(wakeWords);
    }

    public synchronized void configureWakeWords(Collection<String> wakeWords) {
        if (spotter == null) return;
        LinkedHashSet<String> enabled = new LinkedHashSet<>(CONTROL_WORDS);
        enabled.addAll(wakeWords);
        String keywords = keywordLines.render(enabled);
        if (keywords.isEmpty()) throw new IllegalArgumentException("没有可用的本地关键词");
        // sherpa-onnx 1.13.2 merges dynamic keywords with config.keywordsFile. Preserve every
        // pronunciation and filter its result again in routeKeyword(). Keep the old stream alive
        // if native createStream throws.
        OnlineStream previous = stream.replace(() -> spotter.createStream(keywords));
        if (previous != null) previous.release();
    }

    private void loadKeywordLines(AssetManager assets) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open(DIR + "/keywords.txt"), StandardCharsets.UTF_8))) {
            keywordLines.load(reader);
        } catch (Exception e) {
            throw new IllegalStateException("读取本地关键词失败", e);
        }
    }

    public synchronized String accept(byte[] pcm) {
        OnlineStream activeStream = stream.current();
        if (spotter == null || activeStream == null) return null;
        float[] samples = new float[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int lo = pcm[i * 2] & 0xff;
            int hi = pcm[i * 2 + 1] << 8;
            samples[i] = (short) (lo | hi) / 32768.0f;
        }
        activeStream.acceptWaveform(samples, 16000);
        while (spotter.isReady(activeStream)) {
            spotter.decode(activeStream);
            KeywordSpotterResult result = spotter.getResult(activeStream);
            String keyword = result.getKeyword();
            if (keyword != null && !keyword.isEmpty()) {
                spotter.reset(activeStream);
                return keyword;
            }
        }
        return null;
    }

    public synchronized void reset() {
        OnlineStream activeStream = stream.current();
        if (spotter != null && activeStream != null) spotter.reset(activeStream);
    }

    public synchronized void release() {
        OnlineStream activeStream = stream.clear();
        if (activeStream != null) activeStream.release();
        if (spotter != null) spotter.release();
        spotter = null;
        keywordLines.clear();
    }
}
