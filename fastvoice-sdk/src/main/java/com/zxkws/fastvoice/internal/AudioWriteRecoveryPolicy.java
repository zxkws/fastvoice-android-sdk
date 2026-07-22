package com.zxkws.fastvoice.internal;

/** Classifies AudioTrack.write results, including pause-released zero and short writes. */
final class AudioWriteRecoveryPolicy {
    enum Decision { COMPLETE, RETRY, FAIL }

    private final int maxUnheldZeroRetries;
    private int unheldZeroRetries;

    AudioWriteRecoveryPolicy(int maxUnheldZeroRetries) {
        if (maxUnheldZeroRetries < 0) {
            throw new IllegalArgumentException("maxUnheldZeroRetries must be >= 0");
        }
        this.maxUnheldZeroRetries = maxUnheldZeroRetries;
    }

    void onPlaybackHeld() {
        unheldZeroRetries = 0;
    }

    Decision onWriteResult(int written, int expected, boolean pauseAffectedWrite) {
        if (expected <= 0) throw new IllegalArgumentException("expected must be > 0");
        if (written == expected) {
            unheldZeroRetries = 0;
            return Decision.COMPLETE;
        }
        if (written == 0) {
            if (pauseAffectedWrite) {
                onPlaybackHeld();
                return Decision.RETRY;
            }
            unheldZeroRetries++;
            return unheldZeroRetries <= maxUnheldZeroRetries ? Decision.RETRY : Decision.FAIL;
        }
        if (written > 0 && written < expected && pauseAffectedWrite) {
            onPlaybackHeld();
            return Decision.RETRY;
        }
        unheldZeroRetries = 0;
        return Decision.FAIL;
    }

    int unheldZeroRetries() {
        return unheldZeroRetries;
    }
}
