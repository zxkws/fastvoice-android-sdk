package com.zxkws.fastvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import org.junit.Test;

public class PublicApiJavaTest {
    @Test
    public void locationApiAndSingleListenerAreUsableFromJava() throws Exception {
        FastVoiceConfig config = FastVoiceConfig.builder("ws://127.0.0.1:8100/ws")
            .preferredWakeWords(Arrays.asList("布丁", "你好布丁"))
            .build();
        FastVoiceListener listener = event -> assertNotNull(event);

        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "updateLocation", String.class, String.class
            ).getReturnType()
        );
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod("clearLocation").getReturnType()
        );
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "updateCoordinates", double.class, double.class
            ).getReturnType()
        );
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "playWelcome", String.class, String.class
            ).getReturnType()
        );
        listener.onEvent(new FastVoiceEvent.StateChanged(FastVoiceState.LISTENING));
        listener.onEvent(new FastVoiceEvent.LocationAck("nanyuan", "station_1907"));
        listener.onEvent(new FastVoiceEvent.WelcomeAck("nanyuan", null));
    }
}
