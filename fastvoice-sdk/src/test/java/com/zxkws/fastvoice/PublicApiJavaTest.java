package com.zxkws.fastvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class PublicApiJavaTest {
    @Test
    public void typedOrderAndSingleListenerAreUsableFromJava() throws Exception {
        DeviceCredentials credentials = new DeviceCredentials("rover-1", "secret-1");
        FastVoiceConfig config = FastVoiceConfig.builder("ws://127.0.0.1:8100/ws")
            .device(credentials)
            .allowInsecureConnection(true)
            .localFallbackPromptEnabled(false)
            .preferredWakeWords(Arrays.asList("布丁", "你好布丁"))
            .build();
        FastVoiceListener listener = event -> assertNotNull(event);
        OrderSnapshot snapshot = OrderSnapshot.builder("o1", 1L)
            .put("park_id", "p1")
            .build();

        assertEquals("rover-1", config.getDeviceId());
        assertEquals("o1", snapshot.getId());
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod("startOrder", OrderSnapshot.class).getReturnType()
        );
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod("updateOrder", OrderSnapshot.class).getReturnType()
        );
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "endOrder", String.class, long.class, String.class
            ).getReturnType()
        );
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "playArrival",
                String.class,
                String.class,
                long.class,
                String.class,
                String.class
            ).getReturnType()
        );
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod(
                "playCruise", String.class, String.class
            ).getReturnType()
        );
        assertFalse(
            Arrays.stream(FastVoiceClient.class.getMethods())
                .anyMatch(method ->
                    method.getName().equals("sendTrustedMessage") ||
                    method.getName().equals("playTour")
                )
        );
        listener.onEvent(new FastVoiceEvent.StateChanged(FastVoiceState.LISTENING));
        listener.onEvent(new FastVoiceEvent.TourAck("tour-1"));
        assertEquals(Collections.singletonMap("park_id", "p1"), snapshot.getContext());
    }
}
