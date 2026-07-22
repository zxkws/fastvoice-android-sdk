package com.zxkws.fastvoice.internal;

/** Pure-JVM classification of the observable result of an AudioTrack.play() attempt. */
final class AudioTrackResumePolicy {
    enum Decision { RESUMED, FAIL }

    private AudioTrackResumePolicy() {}

    static Decision classify(boolean playCallSucceeded, boolean playStatePlaying) {
        return playCallSucceeded && playStatePlaying ? Decision.RESUMED : Decision.FAIL;
    }
}
