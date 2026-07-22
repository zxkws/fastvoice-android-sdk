package com.zxkws.fastvoice.internal;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Starts capture only after its optional audio preprocessor has been prepared. */
final class AudioCaptureStartup {
    static final class Started<R, E> {
        private final R record;
        private final E effect;

        private Started(R record, E effect) {
            this.record = record;
            this.effect = effect;
        }

        R record() {
            return record;
        }

        E effect() {
            return effect;
        }
    }

    private AudioCaptureStartup() {}

    static <R, E> Started<R, E> start(
            R record,
            Function<R, E> prepareEffect,
            Consumer<R> startRecord,
            Predicate<R> isRecording,
            Consumer<E> releaseEffect,
            Consumer<R> stopRecord,
            Consumer<R> releaseRecord) {
        E effect = null;
        try {
            // Some vendor HALs snapshot the preprocessing chain when capture starts.
            effect = prepareEffect.apply(record);
            startRecord.accept(record);
            if (isRecording.test(record)) return new Started<>(record, effect);
        } catch (RuntimeException ignored) {
            // The caller treats an unavailable capture path as a normal open failure.
        }

        if (effect != null) safeAccept(releaseEffect, effect);
        safeAccept(stopRecord, record);
        safeAccept(releaseRecord, record);
        return null;
    }

    private static <T> void safeAccept(Consumer<T> operation, T value) {
        try {
            operation.accept(value);
        } catch (RuntimeException ignored) {
            // Cleanup is best effort, but all resources still get a release attempt.
        }
    }
}
