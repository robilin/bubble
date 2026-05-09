package com.dsaved.bubblehead.bubble;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.util.HashMap;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * BubblePlugin
 */
public class BubblePlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private MethodChannel channel;
    private EventChannel locationEventsChannel;
    private Context activity;
    private Context applicationContext;


    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        applicationContext = flutterPluginBinding.getApplicationContext();
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "com.dsaved.bubble.head");
        channel.setMethodCallHandler(this);
        locationEventsChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "com.dsaved.bubble.head/location_events");
        locationEventsChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                LocationUpdatesEventBridge.setEventSink(events);
            }

            @Override
            public void onCancel(Object arguments) {
                LocationUpdatesEventBridge.clearEventSink();
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (call.method.equals("startBubbleHead")) {
            startBubbleHead(result, call);
        } else if (call.method.equals("startBubbleWidget")) {
            startBubbleWidget(result, call);
        } else if (call.method.equals("updateBubbleWidgetData")) {
            updateBubbleWidgetData(result, call);
        } else if (call.method.equals("stopBubbleHead")) {
            BubbleHeadService.stopService(getValidContext());
            result.success(true);
        } else if (call.method.equals("stopBubbleWidget")) {
            BubbleHeadService.stopService(getValidContext());
            result.success(true);
        } else if (call.method.equals("startLocationUpdates")) {
            startLocationUpdates(result, call);
        } else if (call.method.equals("stopLocationUpdates")) {
            stopLocationUpdates(result);
        } else {
            result.notImplemented();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public void startBubbleHead(@NonNull Result result, MethodCall  call) {
        Context context = getValidContext();
        if (context == null) {
            result.error("ENOCTX", "No context available", "Attach plugin to an activity before starting the bubble.");
            return;
        }

        if (Settings.canDrawOverlays(context)) {
            boolean bounce = call.argument("bounce");
            BubbleHeadService.bounce(bounce);

            boolean showClose = call.argument("showClose");
            BubbleHeadService.shouldShowCloseButton(showClose);

            boolean dragToClose = call.argument("dragToClose");
            BubbleHeadService.dragToClose(dragToClose);

            boolean sendAppToBackground = call.argument("sendAppToBackground");
            BubbleHeadService.sendAppToBackground(sendAppToBackground);

            String imageByte = call.argument("image");
            BubbleHeadService.startService(context, imageByte);
            result.success(true);
        } else {
            //Permission is not available
            result.error("EPERMNOTGRANTED", "permission not available", "Please request permission for: android.permission.SYSTEM_ALERT_WINDOW. with out this permission you cannot launch the bubble head.");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public void startBubbleWidget(@NonNull Result result, MethodCall call) {
        Context context = getValidContext();
        if (context == null) {
            result.error("ENOCTX", "No context available", "Attach plugin to an activity before starting the bubble widget.");
            return;
        }

        if (Settings.canDrawOverlays(context)) {
            boolean bounce = call.argument("bounce");
            BubbleHeadService.bounce(bounce);

            boolean showClose = call.argument("showClose");
            BubbleHeadService.shouldShowCloseButton(showClose);

            boolean dragToClose = call.argument("dragToClose");
            BubbleHeadService.dragToClose(dragToClose);

            boolean sendAppToBackground = call.argument("sendAppToBackground");
            BubbleHeadService.sendAppToBackground(sendAppToBackground);

            @SuppressWarnings("unchecked")
            HashMap<String, Object> data = call.argument("widgetData");
            if (data == null) {
                data = new HashMap<>();
            }

            String template = call.argument("template");

            BubbleHeadService.startWidgetService(context, data, template);
            result.success(true);
        } else {
            result.error("EPERMNOTGRANTED", "permission not available", "Please request permission for: android.permission.SYSTEM_ALERT_WINDOW. with out this permission you cannot launch the bubble widget.");
        }
    }

    public void updateBubbleWidgetData(@NonNull Result result, MethodCall call) {
        @SuppressWarnings("unchecked")
        HashMap<String, Object> data = call.argument("widgetData");
        if (data == null) {
            result.error("EBADARGS", "widgetData is required", "Provide widgetData as a Map<String, dynamic>.");
            return;
        }

        String template = call.argument("template");
        BubbleHeadService.updateWidgetData(data, template);
        result.success(true);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public void startLocationUpdates(@NonNull Result result, MethodCall call) {
        Context context = getValidContext();
        if (context == null) {
            result.error("ENOCTX", "No context available", "Attach plugin to an activity before starting location updates.");
            return;
        }

        String url = call.argument("url");
        Number intervalMsArg = call.argument("intervalMs");
        long intervalMs = intervalMsArg == null ? 15000L : Math.max(5000L, intervalMsArg.longValue());
        Number maxQueueSizeArg = call.argument("maxQueueSize");
        int maxQueueSize = maxQueueSizeArg == null ? 200 : Math.max(10, maxQueueSizeArg.intValue());
        Number initialBackoffMsArg = call.argument("initialBackoffMs");
        long initialBackoffMs = initialBackoffMsArg == null ? 3000L : Math.max(1000L, initialBackoffMsArg.longValue());
        Number maxBackoffMsArg = call.argument("maxBackoffMs");
        long maxBackoffMs = maxBackoffMsArg == null ? 60000L : Math.max(initialBackoffMs, maxBackoffMsArg.longValue());
        String authRefreshUrl = call.argument("authRefreshUrl");
        String authTokenResponseKey = call.argument("authTokenResponseKey");
        if (authTokenResponseKey == null || authTokenResponseKey.isEmpty()) {
            authTokenResponseKey = "accessToken";
        }
        String authHeaderName = call.argument("authHeaderName");
        if (authHeaderName == null || authHeaderName.isEmpty()) {
            authHeaderName = "Authorization";
        }
        String authHeaderPrefix = call.argument("authHeaderPrefix");
        if (authHeaderPrefix == null) {
            authHeaderPrefix = "Bearer ";
        }

        @SuppressWarnings("unchecked")
        HashMap<String, String> headers = call.argument("headers");
        if (headers == null) {
            headers = new HashMap<>();
        }

        @SuppressWarnings("unchecked")
        HashMap<String, Object> metadata = call.argument("metadata");
        if (metadata == null) {
            metadata = new HashMap<>();
        }

        @SuppressWarnings("unchecked")
        HashMap<String, String> authRefreshHeaders = call.argument("authRefreshHeaders");
        if (authRefreshHeaders == null) {
            authRefreshHeaders = new HashMap<>();
        }

        @SuppressWarnings("unchecked")
        HashMap<String, Object> authRefreshBody = call.argument("authRefreshBody");
        if (authRefreshBody == null) {
            authRefreshBody = new HashMap<>();
        }

        if (url == null || !url.startsWith("https://")) {
            result.error("EBADURL", "Only HTTPS endpoints are allowed", "Provide a valid https:// URL.");
            return;
        }

        boolean hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) {
            result.error("EPERMLOCATION", "Location permission not granted", "Request ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION before starting updates.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean hasBackground = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!hasBackground) {
                result.error("EPERMLOCATIONBACKGROUND", "Background location permission not granted", "Request ACCESS_BACKGROUND_LOCATION for background updates.");
                return;
            }
        }

        Intent serviceIntent = new Intent(context, LocationUpdatesService.class);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_URL, url);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_INTERVAL_MS, intervalMs);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_HEADERS, headers);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_METADATA, metadata);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_MAX_QUEUE_SIZE, maxQueueSize);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_INITIAL_BACKOFF_MS, initialBackoffMs);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_MAX_BACKOFF_MS, maxBackoffMs);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_AUTH_REFRESH_URL, authRefreshUrl);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_AUTH_REFRESH_HEADERS, authRefreshHeaders);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_AUTH_REFRESH_BODY, authRefreshBody);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_AUTH_TOKEN_RESPONSE_KEY, authTokenResponseKey);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_AUTH_HEADER_NAME, authHeaderName);
        serviceIntent.putExtra(LocationUpdatesService.EXTRA_AUTH_HEADER_PREFIX, authHeaderPrefix);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        result.success(true);
    }

    public void stopLocationUpdates(@NonNull Result result) {
        Context context = getValidContext();
        if (context == null) {
            result.error("ENOCTX", "No context available", "Attach plugin to an activity before stopping location updates.");
            return;
        }

        Intent serviceIntent = new Intent(context, LocationUpdatesService.class);
        context.stopService(serviceIntent);
        result.success(true);
    }

    private Context getValidContext() {
        if (activity != null) {
            return activity;
        }
        return applicationContext;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        if (locationEventsChannel != null) {
            locationEventsChannel.setStreamHandler(null);
            locationEventsChannel = null;
        }
        LocationUpdatesEventBridge.clearEventSink();
        applicationContext = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {

    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {

    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }
}
