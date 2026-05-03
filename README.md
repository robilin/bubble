# bubble_head

Android-first Flutter plugin for:
- Floating chat-head style bubble overlay
- Bringing your app to foreground when the bubble is tapped
- Optional background location uploads for driver mode

This plugin currently supports Android.

## Install

```yaml
dependencies:
  bubble_head: ^0.0.5
```

## Android Setup

Update your app manifest at android/app/src/main/AndroidManifest.xml.

### 1. Add permissions

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>

<!-- Required for location upload mode -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
```

### 2. Add intent-filter to your main activity

```xml
<intent-filter>
    <action android:name="intent.bring.app.to.foreground" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

The plugin declares its foreground services in its own manifest, so you do not need to manually add service entries in your app manifest.

### 3. Request runtime permissions before starting features

Use permission_handler or your own permission flow.

Recommended order:
1. Request locationWhenInUse.
2. Request locationAlways.
3. Request notification (Android 13+).
4. Request systemAlertWindow before starting bubble overlay.

```dart
import 'package:permission_handler/permission_handler.dart';

Future<void> ensurePermissions() async {
  await Permission.locationWhenInUse.request();
  await Permission.locationAlways.request();
  await Permission.notification.request();
  await Permission.systemAlertWindow.request();
}
```

## Quick Start (Bubble Overlay)

```dart
import 'package:bubble_head/bubble.dart';

final Bubble bubble = Bubble(
  shouldBounce: true,
  allowDragToClose: true,
  showCloseButton: true,
);

Future<void> startBubble() async {
  await bubble.startBubbleHead(
    sendAppToBackground: true,
    iconPath: 'assets/images/icon.png',
  );
}

Future<void> stopBubble() async {
  await bubble.stopBubbleHead();
}
```

## Driver Mode (Background Location Uploads)

```dart
import 'dart:async';
import 'package:bubble_head/bubble.dart';

final Bubble bubble = Bubble(showCloseButton: true);
StreamSubscription<Map<String, dynamic>>? sub;

Future<void> startDriverMode() async {
  sub = bubble.locationUpdateEvents.listen((event) {
    print('location event: $event');
  });

  await bubble.startLocationUpdates(
    httpsUrl: 'https://api.example.com/driver/location',
    interval: const Duration(seconds: 15),
    headers: {'Authorization': 'Bearer ACCESS_TOKEN'},
    metadata: {'driverId': 'drv_123', 'vehicleType': 'bike'},
    maxQueueSize: 300,
    initialBackoff: const Duration(seconds: 3),
    maxBackoff: const Duration(minutes: 1),
    authRefreshUrl: 'https://api.example.com/auth/refresh',
    authRefreshHeaders: {'Authorization': 'Bearer REFRESH_TOKEN'},
    authRefreshBody: {'deviceId': 'abc-123'},
    authTokenResponseKey: 'accessToken',
    authHeaderName: 'Authorization',
    authHeaderPrefix: 'Bearer ',
  );
}

Future<void> stopDriverMode() async {
  await bubble.stopLocationUpdates();
  await sub?.cancel();
}
```

### Upload Payload

Each upload is JSON with:
- latitude
- longitude
- accuracy
- speed
- bearing
- altitude
- provider
- timestamp
- metadata (optional)

### Reliability Features

- Foreground service for background operation
- Persistent queue of unsent location payloads
- Exponential retry backoff
- Optional token refresh on 401 or 403

## API Reference

### Bubble constructor

```dart
Bubble({
  bool shouldBounce = true,
  bool allowDragToClose = true,
  bool showCloseButton = false,
  String? customIconPath,
})
```

### startBubbleHead

```dart
Future<void> startBubbleHead({
  bool sendAppToBackground = true,
  String? iconPath,
})
```

### stopBubbleHead

```dart
Future<void> stopBubbleHead()
```

### startLocationUpdates

```dart
Future<void> startLocationUpdates({
  required String httpsUrl,
  Duration interval = const Duration(seconds: 15),
  Map<String, String>? headers,
  Map<String, dynamic>? metadata,
  int maxQueueSize = 200,
  Duration initialBackoff = const Duration(seconds: 3),
  Duration maxBackoff = const Duration(seconds: 60),
  String? authRefreshUrl,
  Map<String, String>? authRefreshHeaders,
  Map<String, dynamic>? authRefreshBody,
  String authTokenResponseKey = 'accessToken',
  String authHeaderName = 'Authorization',
  String authHeaderPrefix = 'Bearer ',
})
```

### stopLocationUpdates

```dart
Future<void> stopLocationUpdates()
```

### locationUpdateEvents

```dart
Stream<Map<String, dynamic>> get locationUpdateEvents
```

Event types currently emitted:
- service_created
- location_updates_started
- location_enqueued
- location_sent
- send_failed
- retry_scheduled
- auth_token_refreshed
- auth_refresh_failed
- queue_trimmed
- service_stopped
- config_invalid
- payload_build_failed
- location_permission_error

## Production Notes

- The upload endpoint must be HTTPS.
- Always request background location permission before starting location uploads.
- Android may still kill apps in extreme cases, force stop, or OEM battery restrictions. Design server-side session recovery accordingly.
- For best real-world reliability on driver apps, prompt users to disable battery optimizations for your app on aggressive OEM ROMs.

## Example App

See the full integration sample in example/lib/main.dart.

## Support

[Buy me a Coffee](https://www.buymeacoffee.com/dsaved)

