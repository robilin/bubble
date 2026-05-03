package com.dsaved.bubblehead.bubble;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;

public final class LocationUpdatesEventBridge {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static EventChannel.EventSink eventSink;

    private LocationUpdatesEventBridge() {
    }

    public static synchronized void setEventSink(EventChannel.EventSink sink) {
        eventSink = sink;
    }

    public static synchronized void clearEventSink() {
        eventSink = null;
    }

    public static void emit(final String type, final Map<String, Object> payload) {
        final EventChannel.EventSink sink;
        synchronized (LocationUpdatesEventBridge.class) {
            sink = eventSink;
        }
        if (sink == null) {
            return;
        }

        final HashMap<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("timestampMs", System.currentTimeMillis());
        if (payload != null) {
            event.putAll(payload);
        }

        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                sink.success(event);
            }
        });
    }
}
