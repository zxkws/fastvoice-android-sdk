package com.zxkws.fastvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import org.junit.Test;

public class PublicApiJavaTest {
    @Test
    public void buildersAndListenerAdapterAreUsableFromJava() throws Exception {
        DeviceCredentials credentials = new DeviceCredentials("rover-1", "secret-1");
        FastVoiceConfig config = FastVoiceConfig.builder("ws://127.0.0.1:8100/ws")
            .device(credentials)
            .allowInsecureConnection(true)
            .localFallbackPromptEnabled(false)
            .preferredWakeWords(Arrays.asList("布丁", "你好布丁"))
            .build();
        FastVoiceListener listener = new FastVoiceListenerAdapter() {
            @Override
            public void onStateChanged(FastVoiceState state) {
                assertNotNull(state);
            }

            @Override
            public void onContextUpdated(FastVoiceEvent.ContextUpdated event) {
                assertNotNull(event);
            }
        };

        assertEquals("rover-1", config.getDeviceId());
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod("sendTrustedMessage", String.class).getReturnType()
        );
        listener.onStateChanged(FastVoiceState.LISTENING);
        listener.onContextUpdated(new FastVoiceEvent.ContextUpdated(1L));
    }
}
