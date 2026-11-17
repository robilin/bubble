package com.dsaved.bubblehead.bubble;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class BubblePlugin implements FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private MethodChannel channel;
    private EventChannel events;
    private EventChannel.EventSink ownedSink;
    private Activity activity;
    private Context applicationContext;

    @Override public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        applicationContext = binding.getApplicationContext();
        channel = new MethodChannel(binding.getBinaryMessenger(), "com.dsaved.bubble.head");
        channel.setMethodCallHandler(this);
        events = new EventChannel(binding.getBinaryMessenger(), "com.dsaved.bubble.head/location_events");
        events.setStreamHandler(new EventChannel.StreamHandler() {
            @Override public void onListen(Object args, EventChannel.EventSink sink) {
                ownedSink = sink;
                LocationUpdatesEventBridge.setEventSink(sink);
            }
            @Override public void onCancel(Object args) { clearSink(); }
        });
    }

    @Override public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        try {
            Context context = applicationContext;
            if (context == null) {
                result.error("ENOCTX", "Plugin is detached from its engine", null);
                return;
            }
            switch (call.method) {
                case "getStatus": {
                    Map<String, Object> status = LocationUpdatesService.getStatus(context);
                    status.put("bubbleRunning", BubbleHeadService.isRunning());
                    status.put("bubbleVisible", BubbleHeadService.isVisible());
                    result.success(status);
                    return;
                }
                case "startBubbleHead":
                case "startBubbleWidget":
                    if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
                        result.error("EPERMNOTGRANTED", "Overlay permission is required", null);
                        return;
                    }
                    requireForeground();
                    BubbleHeadService.bounce(bool(call, "bounce", true));
                    BubbleHeadService.shouldShowCloseButton(bool(call, "showClose", false));
                    BubbleHeadService.dragToClose(bool(call, "dragToClose", true));
                    BubbleHeadService.sendAppToBackground(bool(call, "sendAppToBackground", true));
                    BubbleHeadService.initiallyVisible(bool(call, "initiallyVisible", true));
                    if (call.method.equals("startBubbleHead")) {
                        BubbleHeadService.startService(context, call.argument("image"));
                    } else {
                        BubbleHeadService.startWidgetService(context, objectMap(call, "widgetData"), call.argument("template"));
                    }
                    result.success(true);
                    return;
                case "setBubbleVisible":
                    BubbleHeadService.setVisible(bool(call, "visible", true));
                    result.success(true);
                    return;
                case "updateBubbleWidgetData":
                    if (call.argument("widgetData") == null) throw new IllegalArgumentException("widgetData is required");
                    BubbleHeadService.updateWidgetData(objectMap(call, "widgetData"), call.argument("template"));
                    result.success(true);
                    return;
                case "stopBubbleHead":
                case "stopBubbleWidget":
                    BubbleHeadService.stopService(context);
                    result.success(true);
                    return;
                case "bringAppToForeground":
                    bringAppToForeground(context);
                    result.success(true);
                    return;
                case "startLocationUpdates":
                    requireForeground();
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                            && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        result.error("EPERMLOCATION", "Location permission is required", null);
                        return;
                    }
                    LocationUpdatesService.start(context, new JSONObject(objectMap(call, null)));
                    result.success(true);
                    return;
                case "stopLocationUpdates":
                    LocationUpdatesService.stop(context);
                    result.success(true);
                    return;
                case "showBookingAlert":
                    showBookingAlert(context, objectMap(call, null));
                    result.success(true);
                    return;
                default: result.notImplemented();
            }
        } catch (SecurityException error) {
            result.error("EPERMISSION", error.getMessage(), null);
        } catch (IllegalArgumentException error) {
            result.error("EBADARGS", error.getMessage(), null);
        } catch (IllegalStateException error) {
            result.error("ESTATE", error.getMessage(), null);
        } catch (Exception error) {
            result.error("ENATIVE", error.getClass().getSimpleName() + ": " + error.getMessage(), null);
        }
    }

    private void requireForeground() {
        Activity current = activity;
        if (current == null || current.isFinishing() || current.isDestroyed() || !current.hasWindowFocus()) {
            throw new IllegalStateException("Start services from the resumed foreground activity");
        }
    }

    private static boolean bool(MethodCall call, String key, boolean fallback) {
        Object value = call.argument(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String, Object> objectMap(MethodCall call, String key) {
        Object value = key == null ? call.arguments : call.argument(key);
        if (value == null) return new HashMap<>();
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a map");
        return new HashMap<>((Map<String, Object>) value);
    }

    static void bringAppToForeground(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) throw new IllegalStateException("No launcher activity is available");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launch);
    }

    private void showBookingAlert(Context context, Map<String, Object> data) {
        Object expiry = data.get("expiresAtMs");
        if (!(expiry instanceof Number) || ((Number) expiry).longValue() <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("Booking is expired or expiresAtMs is missing");
        }
        if (!Boolean.TRUE.equals(LocationUpdatesService.getStatus(context).get("locationRunning"))) {
            throw new IllegalStateException("Native driver location is not running");
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Notification permission is required");
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) throw new IllegalStateException("Notification manager unavailable");
        String channelId = "bubble_booking_alerts";
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(channelId, "New booking requests", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) throw new IllegalStateException("No launcher activity is available");
        String bookingId = String.valueOf(data.get("booking_id"));
        launch.putExtra("booking_id", bookingId);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent tap = PendingIntent.getActivity(context, bookingId.hashCode(), launch, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, channelId) : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(String.valueOf(data.containsKey("title") ? data.get("title") : "New booking request"))
                .setContentText(String.valueOf(data.containsKey("body") ? data.get("body") : "Open the app to review"))
                .setContentIntent(tap).setAutoCancel(true).setPriority(Notification.PRIORITY_HIGH);
        if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(((Number) expiry).longValue() - System.currentTimeMillis());
        manager.notify("bubble.booking", bookingId.hashCode(), builder.build());
        if (Boolean.TRUE.equals(data.get("openApp")) && Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(context)) {
            bringAppToForeground(context);
        }
    }

    private void clearSink() {
        LocationUpdatesEventBridge.clearEventSink(ownedSink);
        ownedSink = null;
    }
    @Override public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        events.setStreamHandler(null);
        clearSink();
        activity = null;
        applicationContext = null;
    }
    @Override public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) { activity = binding.getActivity(); }
    @Override public void onDetachedFromActivityForConfigChanges() { activity = null; }
    @Override public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) { activity = binding.getActivity(); }
    @Override public void onDetachedFromActivity() { activity = null; }
}
