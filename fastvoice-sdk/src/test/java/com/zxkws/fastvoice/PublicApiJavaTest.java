package com.zxkws.fastvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import org.junit.Test;

public class PublicApiJavaTest {
    @Test
    public void locationApiAndSingleListenerAreUsableFromJava() throws Exception {
        FastVoiceConfig directConfig = new FastVoiceConfig("18", "ws://127.0.0.1:8100/ws");
        FastVoiceConfig config = FastVoiceConfig.builder("ws://127.0.0.1:8100/ws")
            .areaId("18")
            .getLocation(callback -> callback.invoke(null))
            .preferredWakeWords(Arrays.asList("咘嘀", "你好咘嘀"))
            .build();
        FastVoiceListener listener = event -> assertNotNull(event);

        assertNotNull(config.getGetLocation());
        assertEquals("18", directConfig.getAreaId());
        assertEquals("18", config.getAreaId());
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "updateLocation", String.class
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
                "playWelcome", String.class
            ).getReturnType()
        );
        listener.onEvent(new FastVoiceEvent.StateChanged(FastVoiceState.LISTENING));
        listener.onEvent(new FastVoiceEvent.LocationAck("station_1907"));
        listener.onEvent(new FastVoiceEvent.WelcomeAck(null));
    }
}
