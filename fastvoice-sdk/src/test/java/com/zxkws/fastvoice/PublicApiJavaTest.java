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
        VehicleContext context = VehicleContext.builder()
            .parkId("park-1")
            .operationStatus(VehicleContext.STATUS_ARRIVED)
            .batteryPercent(80.0)
            .build();
        SpotArrival arrival = new SpotArrival("park-1", "route-a", "station-3", "spot-3");
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
        assertEquals("park-1", context.getParkId());
        assertEquals("spot-3", arrival.getSpotId());
        assertEquals(
            boolean.class,
            FastVoiceClient.class.getMethod("sendTrustedMessage", String.class).getReturnType()
        );
        listener.onStateChanged(FastVoiceState.LISTENING);
        listener.onContextUpdated(new FastVoiceEvent.ContextUpdated(1L));
    }
}
