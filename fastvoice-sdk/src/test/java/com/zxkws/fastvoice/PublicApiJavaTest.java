package com.zxkws.fastvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class PublicApiJavaTest {
    @Test
    public void typedSessionContentAndSingleListenerAreUsableFromJava() throws Exception {
        DeviceCredentials credentials = new DeviceCredentials("rover-1", "secret-1");
        FastVoiceConfig config = FastVoiceConfig.builder("ws://127.0.0.1:8100/ws")
            .device(credentials)
            .allowInsecureConnection(true)
            .localFallbackPromptEnabled(false)
            .preferredWakeWords(Arrays.asList("布丁", "你好布丁"))
            .build();
        FastVoiceListener listener = event -> assertNotNull(event);
        SessionSnapshot snapshot = SessionSnapshot.builder("s1", 1L)
            .putAttribute("locale", "zh-CN")
            .build();
        SessionRef reference = SessionRef.of("s1", 1L);
        SessionSnapshot emptySnapshot = new SessionSnapshot("s2", 1L);
        ContentRequest unboundContent = new ContentRequest("c2", "idle_message");
        ContentRequest content = ContentRequest.builder("c1", "welcome")
            .session(reference)
            .putAttribute("variant", "short")
            .build();

        assertEquals("rover-1", config.getDeviceId());
        assertEquals("s1", snapshot.getId());
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "startSession", SessionSnapshot.class
            ).getReturnType()
        );
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "updateSession", SessionSnapshot.class
            ).getReturnType()
        );
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "endSession", String.class, long.class, String.class
            ).getReturnType()
        );
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "playContent", ContentRequest.class
            ).getReturnType()
        );
        listener.onEvent(new FastVoiceEvent.StateChanged(FastVoiceState.LISTENING));
        listener.onEvent(new FastVoiceEvent.ContentAck("c1"));
        assertEquals(Collections.singletonMap("locale", "zh-CN"), snapshot.getAttributes());
        assertEquals(Collections.singletonMap("variant", "short"), content.getAttributes());
        assertEquals(reference, content.getSession());
        assertEquals(Collections.emptyMap(), emptySnapshot.getAttributes());
        assertNull(unboundContent.getSession());
    }
}
