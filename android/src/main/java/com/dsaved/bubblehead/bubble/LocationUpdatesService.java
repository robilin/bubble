package com.dsaved.bubblehead.bubble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

public class LocationUpdatesService extends Service {
    public static final String EXTRA_URL = "extra.url";
    public static final String EXTRA_INTERVAL_MS = "extra.interval.ms";
    public static final String EXTRA_HEADERS = "extra.headers";
    public static final String EXTRA_METADATA = "extra.metadata";
    public static final String EXTRA_MAX_QUEUE_SIZE = "extra.max.queue.size";
    public static final String EXTRA_INITIAL_BACKOFF_MS = "extra.initial.backoff.ms";
    public static final String EXTRA_MAX_BACKOFF_MS = "extra.max.backoff.ms";
    public static final String EXTRA_AUTH_REFRESH_URL = "extra.auth.refresh.url";
    public static final String EXTRA_AUTH_REFRESH_HEADERS = "extra.auth.refresh.headers";
    public static final String EXTRA_AUTH_REFRESH_BODY = "extra.auth.refresh.body";
    public static final String EXTRA_AUTH_TOKEN_RESPONSE_KEY = "extra.auth.token.response.key";
    public static final String EXTRA_AUTH_HEADER_NAME = "extra.auth.header.name";
    public static final String EXTRA_AUTH_HEADER_PREFIX = "extra.auth.header.prefix";

    private static final String CHANNEL_ID = "bubble_location_service_channel";
    private static final int NOTIFICATION_ID = 1002;

    private static final String PREFS_NAME = "bubble.location.service";
    private static final String PREFS_KEY_QUEUE = "pending.queue";
    private static final String PREFS_KEY_ENDPOINT = "cfg.endpoint";
    private static final String PREFS_KEY_INTERVAL = "cfg.interval";
    private static final String PREFS_KEY_HEADERS = "cfg.headers";
    private static final String PREFS_KEY_METADATA = "cfg.metadata";
    private static final String PREFS_KEY_MAX_QUEUE = "cfg.max.queue";
    private static final String PREFS_KEY_INITIAL_BACKOFF = "cfg.initial.backoff";
    private static final String PREFS_KEY_MAX_BACKOFF = "cfg.max.backoff";
    private static final String PREFS_KEY_REFRESH_URL = "cfg.refresh.url";
    private static final String PREFS_KEY_REFRESH_HEADERS = "cfg.refresh.headers";
    private static final String PREFS_KEY_REFRESH_BODY = "cfg.refresh.body";
    private static final String PREFS_KEY_REFRESH_TOKEN_KEY = "cfg.refresh.token.key";
    private static final String PREFS_KEY_AUTH_HEADER_NAME = "cfg.auth.header.name";
    private static final String PREFS_KEY_AUTH_HEADER_PREFIX = "cfg.auth.header.prefix";

    private long intervalMs = 15000L;
    private String endpointUrl;
    private HashMap<String, String> headers = new HashMap<>();
    private HashMap<String, Object> metadata = new HashMap<>();
    private int maxQueueSize = 200;
    private long initialBackoffMs = 3000L;
    private long maxBackoffMs = 60000L;
    private long nextBackoffMs = initialBackoffMs;
    private String authRefreshUrl;
    private HashMap<String, String> authRefreshHeaders = new HashMap<>();
    private HashMap<String, Object> authRefreshBody = new HashMap<>();
    private String authTokenResponseKey = "accessToken";
    private String authHeaderName = "Authorization";
    private String authHeaderPrefix = "Bearer ";

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private ExecutorService networkExecutor;

    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();
    private final Deque<String> pendingPayloads = new ArrayDeque<>();
    private boolean retryScheduled = false;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (queueLock) {
                retryScheduled = false;
            }
            flushQueueAsync("scheduled_retry");
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        networkExecutor = Executors.newSingleThreadExecutor();
        loadConfig();
        loadQueue();
        nextBackoffMs = initialBackoffMs;
        LocationUpdatesEventBridge.emit("service_created", mapOf("queued", queueSize()));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        applyConfigFromIntent(intent);
        if (endpointUrl == null || !endpointUrl.startsWith("https://")) {
            LocationUpdatesEventBridge.emit("config_invalid", mapOf("message", "Missing or invalid HTTPS endpoint"));
            stopSelf();
            return START_NOT_STICKY;
        }

        persistConfig();
        trimQueueIfNeeded();
        startForegroundCompat();
        startLocationUpdates();
        flushQueueAsync("start_command");
        return START_STICKY;
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Driver location tracking",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Sends periodic driver location updates");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 10, launchIntent, pendingFlags);

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Driver mode active")
                    .setContentText("Location sharing is running")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .build();
        } else {
            //noinspection deprecation
            notification = new Notification.Builder(this)
                    .setContentTitle("Driver mode active")
                    .setContentText("Location sharing is running")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .build();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startLocationUpdates() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(Math.max(2000L, intervalMs / 2L))
                .setWaitForAccurateLocation(false)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    enqueueLocation(location);
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, getMainLooper());
            LocationUpdatesEventBridge.emit("location_updates_started", mapOf("intervalMs", intervalMs));
        } catch (SecurityException ignored) {
            LocationUpdatesEventBridge.emit("location_permission_error", mapOf("message", "Missing location runtime permission"));
            stopSelf();
        }
    }

    private void enqueueLocation(Location location) {
        try {
            JSONObject payload = buildPayload(location);
            enqueuePayload(payload.toString());
            LocationUpdatesEventBridge.emit("location_enqueued", mapOf("queued", queueSize()));
            flushQueueAsync("location_update");
        } catch (Exception ignored) {
            LocationUpdatesEventBridge.emit("payload_build_failed", mapOf("message", "Could not build JSON payload"));
        }
    }

    private JSONObject buildPayload(Location location) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("latitude", location.getLatitude());
        payload.put("longitude", location.getLongitude());
        payload.put("accuracy", location.getAccuracy());
        payload.put("speed", location.getSpeed());
        payload.put("bearing", location.getBearing());
        payload.put("altitude", location.getAltitude());
        payload.put("provider", location.getProvider());
        payload.put("timestamp", location.getTime());

        if (!metadata.isEmpty()) {
            JSONObject extra = new JSONObject();
            for (Map.Entry<String, Object> item : metadata.entrySet()) {
                if (item.getKey() != null) {
                    extra.put(item.getKey(), JSONObject.wrap(item.getValue()));
                }
            }
            payload.put("metadata", extra);
        }
        return payload;
    }

    private void flushQueueAsync(final String reason) {
        if (networkExecutor == null) {
            return;
        }
        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                flushQueue(reason);
            }
        });
    }

    private void flushQueue(String reason) {
        while (true) {
            String payload;
            synchronized (queueLock) {
                payload = pendingPayloads.peekFirst();
            }

            if (payload == null) {
                clearRetrySchedule();
                nextBackoffMs = initialBackoffMs;
                return;
            }

            SendResult result = sendPayload(payload);
            if (result.authFailure) {
                boolean refreshed = tryRefreshAuthToken();
                if (refreshed) {
                    result = sendPayload(payload);
                }
            }

            if (result.success) {
                synchronized (queueLock) {
                    pendingPayloads.pollFirst();
                    persistQueueLocked();
                }
                LocationUpdatesEventBridge.emit("location_sent", mapOf(
                        "queued", queueSize(),
                        "statusCode", result.statusCode,
                        "reason", reason));
                continue;
            }

            LocationUpdatesEventBridge.emit("send_failed", mapOf(
                    "queued", queueSize(),
                    "statusCode", result.statusCode,
                    "message", result.message));
            scheduleRetry(result.message);
            return;
        }
    }

    private SendResult sendPayload(String payload) {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(endpointUrl);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");

            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }

            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            OutputStream out = connection.getOutputStream();
            out.write(body);
            out.flush();
            out.close();

            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return SendResult.success(code);
            }

            String responseBody = readResponseBody(connection);
            if (code == 401 || code == 403) {
                return SendResult.authFailure(code, responseBody);
            }
            return SendResult.failure(code, responseBody);
        } catch (Exception e) {
            return SendResult.failure(-1, e.getMessage() == null ? "network_error" : e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean tryRefreshAuthToken() {
        if (authRefreshUrl == null || !authRefreshUrl.startsWith("https://")) {
            return false;
        }

        HttpsURLConnection connection = null;
        try {
            URL url = new URL(authRefreshUrl);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");

            for (Map.Entry<String, String> header : authRefreshHeaders.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }

            JSONObject bodyObject = new JSONObject();
            for (Map.Entry<String, Object> entry : authRefreshBody.entrySet()) {
                if (entry.getKey() != null) {
                    bodyObject.put(entry.getKey(), JSONObject.wrap(entry.getValue()));
                }
            }
            byte[] body = bodyObject.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out = connection.getOutputStream();
            out.write(body);
            out.flush();
            out.close();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                LocationUpdatesEventBridge.emit("auth_refresh_failed", mapOf("statusCode", code));
                return false;
            }

            String responseBody = readResponseBody(connection);
            if (responseBody == null || responseBody.isEmpty()) {
                LocationUpdatesEventBridge.emit("auth_refresh_failed", mapOf("message", "Empty refresh response"));
                return false;
            }

            JSONObject json = new JSONObject(responseBody);
            String token = json.optString(authTokenResponseKey, null);
            if (token == null || token.isEmpty()) {
                LocationUpdatesEventBridge.emit("auth_refresh_failed", mapOf("message", "Token key missing in refresh response"));
                return false;
            }

            headers.put(authHeaderName, authHeaderPrefix + token);
            persistConfig();
            LocationUpdatesEventBridge.emit("auth_token_refreshed", mapOf("header", authHeaderName));
            return true;
        } catch (Exception e) {
            LocationUpdatesEventBridge.emit("auth_refresh_failed", mapOf("message", e.getMessage() == null ? "auth_refresh_error" : e.getMessage()));
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readResponseBody(HttpsURLConnection connection) {
        InputStream inputStream = null;
        BufferedReader reader = null;
        try {
            inputStream = connection.getErrorStream();
            if (inputStream == null) {
                inputStream = connection.getInputStream();
            }
            if (inputStream == null) {
                return "";
            }
            reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } catch (Exception ignored) {
            return "";
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void enqueuePayload(String payload) {
        synchronized (queueLock) {
            if (pendingPayloads.size() >= maxQueueSize) {
                pendingPayloads.pollFirst();
                LocationUpdatesEventBridge.emit("queue_trimmed", mapOf("queued", pendingPayloads.size()));
            }
            pendingPayloads.addLast(payload);
            persistQueueLocked();
        }
    }

    private void trimQueueIfNeeded() {
        synchronized (queueLock) {
            while (pendingPayloads.size() > maxQueueSize) {
                pendingPayloads.pollFirst();
            }
            persistQueueLocked();
        }
    }

    private int queueSize() {
        synchronized (queueLock) {
            return pendingPayloads.size();
        }
    }

    private void scheduleRetry(String reason) {
        long delay;
        synchronized (queueLock) {
            if (retryScheduled) {
                return;
            }
            retryScheduled = true;
            delay = nextBackoffMs;
            nextBackoffMs = Math.min(maxBackoffMs, nextBackoffMs * 2L);
        }
        retryHandler.postDelayed(retryRunnable, delay);
        LocationUpdatesEventBridge.emit("retry_scheduled", mapOf("delayMs", delay, "reason", reason));
    }

    private void clearRetrySchedule() {
        synchronized (queueLock) {
            retryScheduled = false;
        }
        retryHandler.removeCallbacks(retryRunnable);
    }

    private void persistQueueLocked() {
        JSONArray array = new JSONArray();
        for (String item : pendingPayloads) {
            array.put(item);
        }
        getPrefs().edit().putString(PREFS_KEY_QUEUE, array.toString()).apply();
    }

    private void loadQueue() {
        synchronized (queueLock) {
            pendingPayloads.clear();
            String raw = getPrefs().getString(PREFS_KEY_QUEUE, null);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    String value = array.optString(i, null);
                    if (value != null && !value.isEmpty()) {
                        pendingPayloads.addLast(value);
                    }
                }
            } catch (Exception ignored) {
                pendingPayloads.clear();
            }
        }
    }

    private void applyConfigFromIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String configuredUrl = intent.getStringExtra(EXTRA_URL);
        if (configuredUrl != null && configuredUrl.startsWith("https://")) {
            endpointUrl = configuredUrl;
        }

        long configuredInterval = intent.getLongExtra(EXTRA_INTERVAL_MS, intervalMs);
        if (configuredInterval > 0) {
            intervalMs = Math.max(5000L, configuredInterval);
        }

        int configuredMaxQueue = intent.getIntExtra(EXTRA_MAX_QUEUE_SIZE, maxQueueSize);
        maxQueueSize = Math.max(10, configuredMaxQueue);

        long configuredInitialBackoff = intent.getLongExtra(EXTRA_INITIAL_BACKOFF_MS, initialBackoffMs);
        initialBackoffMs = Math.max(1000L, configuredInitialBackoff);

        long configuredMaxBackoff = intent.getLongExtra(EXTRA_MAX_BACKOFF_MS, maxBackoffMs);
        maxBackoffMs = Math.max(initialBackoffMs, configuredMaxBackoff);
        nextBackoffMs = initialBackoffMs;

        @SuppressWarnings("unchecked")
        HashMap<String, String> configuredHeaders = (HashMap<String, String>) intent.getSerializableExtra(EXTRA_HEADERS);
        if (configuredHeaders != null) {
            headers = configuredHeaders;
        }

        @SuppressWarnings("unchecked")
        HashMap<String, Object> configuredMetadata = (HashMap<String, Object>) intent.getSerializableExtra(EXTRA_METADATA);
        if (configuredMetadata != null) {
            metadata = configuredMetadata;
        }

        String configuredRefreshUrl = intent.getStringExtra(EXTRA_AUTH_REFRESH_URL);
        if (configuredRefreshUrl != null) {
            authRefreshUrl = configuredRefreshUrl;
        }

        @SuppressWarnings("unchecked")
        HashMap<String, String> configuredAuthHeaders = (HashMap<String, String>) intent.getSerializableExtra(EXTRA_AUTH_REFRESH_HEADERS);
        if (configuredAuthHeaders != null) {
            authRefreshHeaders = configuredAuthHeaders;
        }

        @SuppressWarnings("unchecked")
        HashMap<String, Object> configuredAuthBody = (HashMap<String, Object>) intent.getSerializableExtra(EXTRA_AUTH_REFRESH_BODY);
        if (configuredAuthBody != null) {
            authRefreshBody = configuredAuthBody;
        }

        String tokenKey = intent.getStringExtra(EXTRA_AUTH_TOKEN_RESPONSE_KEY);
        if (tokenKey != null && !tokenKey.isEmpty()) {
            authTokenResponseKey = tokenKey;
        }

        String configuredAuthHeaderName = intent.getStringExtra(EXTRA_AUTH_HEADER_NAME);
        if (configuredAuthHeaderName != null && !configuredAuthHeaderName.isEmpty()) {
            authHeaderName = configuredAuthHeaderName;
        }

        String configuredAuthHeaderPrefix = intent.getStringExtra(EXTRA_AUTH_HEADER_PREFIX);
        if (configuredAuthHeaderPrefix != null) {
            authHeaderPrefix = configuredAuthHeaderPrefix;
        }
    }

    private void persistConfig() {
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putString(PREFS_KEY_ENDPOINT, endpointUrl);
        editor.putLong(PREFS_KEY_INTERVAL, intervalMs);
        editor.putInt(PREFS_KEY_MAX_QUEUE, maxQueueSize);
        editor.putLong(PREFS_KEY_INITIAL_BACKOFF, initialBackoffMs);
        editor.putLong(PREFS_KEY_MAX_BACKOFF, maxBackoffMs);
        editor.putString(PREFS_KEY_REFRESH_URL, authRefreshUrl);
        editor.putString(PREFS_KEY_REFRESH_TOKEN_KEY, authTokenResponseKey);
        editor.putString(PREFS_KEY_AUTH_HEADER_NAME, authHeaderName);
        editor.putString(PREFS_KEY_AUTH_HEADER_PREFIX, authHeaderPrefix);
        editor.putString(PREFS_KEY_HEADERS, new JSONObject(headers).toString());
        editor.putString(PREFS_KEY_METADATA, new JSONObject(metadata).toString());
        editor.putString(PREFS_KEY_REFRESH_HEADERS, new JSONObject(authRefreshHeaders).toString());
        editor.putString(PREFS_KEY_REFRESH_BODY, new JSONObject(authRefreshBody).toString());
        editor.apply();
    }

    private void loadConfig() {
        SharedPreferences prefs = getPrefs();
        endpointUrl = prefs.getString(PREFS_KEY_ENDPOINT, endpointUrl);
        intervalMs = Math.max(5000L, prefs.getLong(PREFS_KEY_INTERVAL, intervalMs));
        maxQueueSize = Math.max(10, prefs.getInt(PREFS_KEY_MAX_QUEUE, maxQueueSize));
        initialBackoffMs = Math.max(1000L, prefs.getLong(PREFS_KEY_INITIAL_BACKOFF, initialBackoffMs));
        maxBackoffMs = Math.max(initialBackoffMs, prefs.getLong(PREFS_KEY_MAX_BACKOFF, maxBackoffMs));
        nextBackoffMs = initialBackoffMs;
        authRefreshUrl = prefs.getString(PREFS_KEY_REFRESH_URL, authRefreshUrl);
        authTokenResponseKey = nonEmptyOrDefault(prefs.getString(PREFS_KEY_REFRESH_TOKEN_KEY, authTokenResponseKey), "accessToken");
        authHeaderName = nonEmptyOrDefault(prefs.getString(PREFS_KEY_AUTH_HEADER_NAME, authHeaderName), "Authorization");
        String prefix = prefs.getString(PREFS_KEY_AUTH_HEADER_PREFIX, authHeaderPrefix);
        authHeaderPrefix = prefix == null ? "Bearer " : prefix;

        headers = jsonToStringMap(prefs.getString(PREFS_KEY_HEADERS, null));
        metadata = jsonToObjectMap(prefs.getString(PREFS_KEY_METADATA, null));
        authRefreshHeaders = jsonToStringMap(prefs.getString(PREFS_KEY_REFRESH_HEADERS, null));
        authRefreshBody = jsonToObjectMap(prefs.getString(PREFS_KEY_REFRESH_BODY, null));
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private String nonEmptyOrDefault(String value, String fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        return value;
    }

    private HashMap<String, String> jsonToStringMap(String raw) {
        HashMap<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }

        try {
            JSONObject json = new JSONObject(raw);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, json.optString(key, ""));
            }
        } catch (Exception ignored) {
            map.clear();
        }
        return map;
    }

    private HashMap<String, Object> jsonToObjectMap(String raw) {
        HashMap<String, Object> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }

        try {
            JSONObject json = new JSONObject(raw);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, json.opt(key));
            }
        } catch (Exception ignored) {
            map.clear();
        }
        return map;
    }

    private HashMap<String, Object> mapOf(Object... entries) {
        HashMap<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            Object key = entries[i];
            Object value = entries[i + 1];
            if (key instanceof String) {
                map.put((String) key, value);
            }
        }
        return map;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        clearRetrySchedule();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
        }
        LocationUpdatesEventBridge.emit("service_stopped", mapOf("queued", queueSize()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    private static class SendResult {
        final boolean success;
        final boolean authFailure;
        final int statusCode;
        final String message;

        private SendResult(boolean success, boolean authFailure, int statusCode, String message) {
            this.success = success;
            this.authFailure = authFailure;
            this.statusCode = statusCode;
            this.message = message == null ? "" : message;
        }

        static SendResult success(int statusCode) {
            return new SendResult(true, false, statusCode, "ok");
        }

        static SendResult authFailure(int statusCode, String message) {
            return new SendResult(false, true, statusCode, message);
        }

        static SendResult failure(int statusCode, String message) {
            return new SendResult(false, false, statusCode, message);
        }
    }
}
