package com.zxkws.fastvoice.internal;

/** Limits consecutive AudioRecord recovery attempts so a broken HAL cannot spin forever. */
final class AudioReadRecoveryPolicy {
    enum Decision { REOPEN, ABORT }

    private final int maxReopenAttempts;
    private final int stableFramesToReset;
    private int consecutiveFailures;
    private int stableFrames;

    AudioReadRecoveryPolicy(int maxReopenAttempts, int stableFramesToReset) {
        if (maxReopenAttempts < 0 || stableFramesToReset <= 0) {
            throw new IllegalArgumentException("invalid recovery limits");
        }
        this.maxReopenAttempts = maxReopenAttempts;
        this.stableFramesToReset = stableFramesToReset;
    }

    synchronized Decision onReadFailure() {
        stableFrames = 0;
        consecutiveFailures++;
        return consecutiveFailures <= maxReopenAttempts ? Decision.REOPEN : Decision.ABORT;
    }

    synchronized void onFrameRead() {
        if (consecutiveFailures == 0) return;
        stableFrames++;
        if (stableFrames >= stableFramesToReset) {
            consecutiveFailures = 0;
            stableFrames = 0;
        }
    }

    synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}
