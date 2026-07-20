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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 本地中文控制词检测器。音频由主录音线程提供，不单独占用麦克风。 */
final class LocalCommandSpotter {
    private static final String DIR =
            "fastvoice/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01";
    private static final Set<String> CONTROL_WORDS = new LinkedHashSet<>(Arrays.asList(
            "换一个", "换个", "下一个", "停止", "停一下", "别说", "闭嘴", "等等", "打住", "重来"));
    private KeywordSpotter spotter;
    private OnlineStream stream;
    private final Map<String, String> keywordLines = new LinkedHashMap<>();

    public synchronized void init(AssetManager assets) {
        init(assets, Collections.singleton("布丁"));
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
                DIR + "/keywords.txt", 1.0f, 0.25f, 2);
        spotter = new KeywordSpotter(assets, config);
        loadKeywordLines(assets);
        configureWakeWords(wakeWords);
    }

    public synchronized void configureWakeWords(Collection<String> wakeWords) {
        if (spotter == null) return;
        LinkedHashSet<String> enabled = new LinkedHashSet<>(CONTROL_WORDS);
        enabled.addAll(wakeWords);
        StringBuilder keywords = new StringBuilder();
        for (Map.Entry<String, String> entry : keywordLines.entrySet()) {
            if (enabled.contains(entry.getKey())) keywords.append(entry.getValue()).append('\n');
        }
        if (keywords.length() == 0) throw new IllegalArgumentException("没有可用的本地关键词");
        if (stream != null) stream.release();
        // 非空字符串覆盖 config.keywordsFile，避免“布丁”抢先命中后导致“布丁布丁”永远无法命中。
        stream = spotter.createStream(keywords.toString());
    }

    private void loadKeywordLines(AssetManager assets) {
        keywordLines.clear();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open(DIR + "/keywords.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int labelAt = line.lastIndexOf('@');
                if (labelAt >= 0 && labelAt + 1 < line.length()) {
                    keywordLines.put(line.substring(labelAt + 1).trim(), line.trim());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取本地关键词失败", e);
        }
    }

    public synchronized String accept(byte[] pcm) {
        if (spotter == null || stream == null) return null;
        float[] samples = new float[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int lo = pcm[i * 2] & 0xff;
            int hi = pcm[i * 2 + 1] << 8;
            samples[i] = (short) (lo | hi) / 32768.0f;
        }
        stream.acceptWaveform(samples, 16000);
        while (spotter.isReady(stream)) {
            spotter.decode(stream);
            KeywordSpotterResult result = spotter.getResult(stream);
            String keyword = result.getKeyword();
            if (keyword != null && !keyword.isEmpty()) {
                spotter.reset(stream);
                return keyword;
            }
        }
        return null;
    }

    public synchronized void reset() {
        if (spotter != null && stream != null) spotter.reset(stream);
    }

    public synchronized void release() {
        if (stream != null) stream.release();
        if (spotter != null) spotter.release();
        stream = null;
        spotter = null;
        keywordLines.clear();
    }
}
