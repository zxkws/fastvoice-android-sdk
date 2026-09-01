package com.zxkws.fastvoice.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class AudioRuntimePoliciesTest {
    @Test
    public void persistentReadFailuresHaveAFiniteRecoveryBudget() {
        AudioReadRecoveryPolicy policy = new AudioReadRecoveryPolicy(3, 5);
        assertEquals(AudioReadRecoveryPolicy.Decision.REOPEN, policy.onReadFailure());
        assertEquals(AudioReadRecoveryPolicy.Decision.REOPEN, policy.onReadFailure());
        assertEquals(AudioReadRecoveryPolicy.Decision.REOPEN, policy.onReadFailure());
        assertEquals(AudioReadRecoveryPolicy.Decision.ABORT, policy.onReadFailure());
    }

    @Test
    public void stableCaptureRestoresTheReadRecoveryBudget() {
        AudioReadRecoveryPolicy policy = new AudioReadRecoveryPolicy(1, 2);
        policy.onReadFailure();
        policy.onFrameRead();
        policy.onFrameRead();
        assertEquals(0, policy.consecutiveFailures());
        assertEquals(AudioReadRecoveryPolicy.Decision.REOPEN, policy.onReadFailure());
    }

    @Test
    public void pauseReleasedShortWritesRetryOnlyTheRemainder() {
        AudioWriteRecoveryPolicy policy = new AudioWriteRecoveryPolicy(2);
        assertEquals(
                AudioWriteRecoveryPolicy.Decision.RETRY,
                policy.onWriteResult(640, 1920, true));
        assertEquals(
                AudioWriteRecoveryPolicy.Decision.COMPLETE,
                policy.onWriteResult(1280, 1280, false));
    }

    @Test
    public void unheldZeroAndShortWritesCannotReportSuccess() {
        AudioWriteRecoveryPolicy policy = new AudioWriteRecoveryPolicy(1);
        assertEquals(
                AudioWriteRecoveryPolicy.Decision.RETRY,
                policy.onWriteResult(0, 1920, false));
        assertEquals(
                AudioWriteRecoveryPolicy.Decision.FAIL,
                policy.onWriteResult(0, 1920, false));
        assertEquals(
                AudioWriteRecoveryPolicy.Decision.FAIL,
                policy.onWriteResult(960, 1920, false));
        assertEquals(
                AudioWriteRecoveryPolicy.Decision.FAIL,
                policy.onWriteResult(-3, 1920, false));
    }

    @Test
    public void resumeRequiresBothSuccessfulPlayAndPlayingState() {
        assertEquals(
                AudioTrackResumePolicy.Decision.RESUMED,
                AudioTrackResumePolicy.classify(true, true));
        assertEquals(
                AudioTrackResumePolicy.Decision.FAIL,
                AudioTrackResumePolicy.classify(true, false));
        assertEquals(
                AudioTrackResumePolicy.Decision.FAIL,
                AudioTrackResumePolicy.classify(false, true));
    }

    @Test
    public void keywordRegistryPreservesEveryPronunciationInFileOrder() throws Exception {
        KeywordLineRegistry registry = new KeywordLineRegistry();
        registry.load(new BufferedReader(new StringReader(
                "h uàn y ī g è :2.0 #0.18 @换一个\n"
                        + "h uàn y í g è :2.0 #0.18 @换一个\n"
                        + "x ià y ī g è @下一个\n"
                        + "b ù d ī @咘嘀\n")));
        assertEquals(
                "h uàn y ī g è :2.0 #0.18 @换一个\n"
                        + "h uàn y í g è :2.0 #0.18 @换一个\n"
                        + "x ià y ī g è @下一个\n",
                registry.render(Arrays.asList("换一个", "下一个")));
    }

    @Test
    public void nativeKeywordLabelsFailClosedAgainstNegotiatedWakeWords() {
        assertEquals(
                LocalCommandSpotter.KeywordRoute.WAKE,
                LocalCommandSpotter.routeKeyword("咘嘀", Collections.singleton("咘嘀")));
        assertEquals(
                LocalCommandSpotter.KeywordRoute.IGNORE,
                LocalCommandSpotter.routeKeyword("咘嘀咘嘀", Collections.singleton("咘嘀")));
        assertEquals(
                LocalCommandSpotter.KeywordRoute.CONTROL,
                LocalCommandSpotter.routeKeyword("退下", Collections.singleton("咘嘀")));
        assertEquals(
                LocalCommandSpotter.KeywordRoute.CONTROL,
                LocalCommandSpotter.routeKeyword("继续", Collections.singleton("咘嘀")));
    }

    @Test
    public void failedNativeStreamReplacementKeepsWorkingStream() {
        ReplaceOnSuccess<Object> slot = new ReplaceOnSuccess<>();
        Object working = new Object();
        assertNull(slot.replace(() -> working));
        try {
            slot.replace(() -> { throw new IllegalStateException("native create failed"); });
            fail("replacement failure expected");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertSame(working, slot.current());
    }
}
