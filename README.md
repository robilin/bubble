# bubble_head_plus

Android-first Flutter plugin for:
- A Truecaller-like floating bubble experience over other apps
- A widget overlay implementation for richer in-app or over-app UI
- Bringing your app to foreground when the bubble is tapped
- Callback-style location update events plus optional background location uploads for driver mode

This plugin currently supports Android.

## Reliable background integration

The floating overlay and native GPS uploader are separate features. An icon
alone does not upload locations, and neither service can make an app immune to
Android termination, user Stop/Force stop, or device battery restrictions.

Request overlay and notification permissions through the host app while its
activity is visible. Start native location mode when the user explicitly goes
online; stop it on offline/sign-out. Feed `location_updated` events into your
Flutter map without uploading those same coordinates a second time.

Prime the overlay before the activity moves to the background:

```dart
await bubble.startBubbleHead(
  sendAppToBackground: false,
  initiallyVisible: false,
  iconPath: 'assets/images/icon.png',
);
// On app background/resume, change the existing window, not service lifetime.
await bubble.setBubbleVisible(true);
final status = await bubble.getStatus();
// status: bubbleRunning, bubbleVisible, locationRunning
```

`startBubbleWidget` also accepts `initiallyVisible`. Service startup must occur
from a resumed, visible activity. Starting location mode there requires fine or
coarse location permission; Always permission may be needed for later background
restart scenarios. Android 15+ places additional restrictions on starting services
from the background: overlay permission alone is not a general exemption.

The native location interval supports a minimum of two seconds. Upload payloads
include both `bearing` and `heading`. `location_updated` includes the sampled
coordinates so the UI can display the same positions uploaded natively.

For a short-lived incoming offer, a headless FCM handler can call:

```dart
await Bubble().showBookingAlert({
  'booking_id': '123',
  'title': 'New delivery nearby',
  'body': 'Open the app to review this delivery',
  'expiresAtMs': DateTime.now().millisecondsSinceEpoch + 20000,
  'openApp': true,
});
```

The host must supply the **actual server offer deadline**, not create a fresh
deadline when an old push arrives. Native tracking must already be active;
expired offers are rejected. A notification remains the fallback when Android
blocks an automatic foreground launch. This API does **not** accept bookings.
The host must validate assignment, eligibility and expiry on its backend before
accepting. For Android background handling, send high-priority data messages and
render a user-visible alert; notification-plus-data messages can instead be
handled by the system notification tray until the user taps them.

## Install

```yaml
dependencies:
  bubble_head_plus: ^0.0.9
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
import 'package:bubble_head_plus/bubble_head.dart';

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

## Quick Start (Floating Widget Overlay)

```dart
import 'package:bubble_head_plus/bubble_head.dart';

final Bubble bubble = Bubble(
  shouldBounce: true,
  allowDragToClose: true,
  showCloseButton: true,
);

Future<void> startBubbleWidget() async {
  await bubble.startBubbleWidget(
    sendAppToBackground: true,
    template: BubbleWidgetTemplate.large,
    widgetData: {
      'title': 'Driver Status',
      'subtitle': 'Active deliveries',
      'value': '3',
      'badge': 'LIVE',
    },
  );
}

Future<void> updateBubbleWidget() async {
  await bubble.updateBubbleWidgetData({
    'value': '4',
    'subtitle': 'New order assigned',
  }, template: BubbleWidgetTemplate.small);
}

Future<void> stopBubbleWidget() async {
  await bubble.stopBubbleWidget();
}
```

### Widget Data Keys

Supported keys for `widgetData`:
- `title`: Main title text
- `subtitle`: Secondary description text
- `value`: Primary highlighted value
- `badge`: Optional tag text; hidden when empty

All values are converted to strings on Android before rendering.

### Template Behavior

- `BubbleWidgetTemplate.small`: compact card, smaller typography, subtitle hidden, badge hidden
- `BubbleWidgetTemplate.medium`: default card size and typography
- `BubbleWidgetTemplate.large`: wider card, larger typography, up to 3 subtitle lines

### Backward Compatibility

- Existing `startBubbleHead` and `stopBubbleHead` behavior is unchanged.
- Widget mode is opt-in via `startBubbleWidget`.
- You can keep using icon mode and location mode exactly as before.

## Driver Mode (Background Location Uploads)

```dart
import 'dart:async';
import 'package:bubble_head_plus/bubble_head.dart';

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
    metadata: {
      'driverId': 'drv_123',
      'vehicleType': 'bike',
      'region': 'nairobi',
      'shiftId': 'shift_456',
      'priority': 1,
      'isVerified': true,
      // add as many key/value pairs as you need
    },
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

Each upload is a JSON object posted to your `httpsUrl`:

```json
{
  "latitude": -1.286389,
  "longitude": 36.817223,
  "accuracy": 5.0,
  "speed": 0.0,
  "bearing": 0.0,
  "altitude": 1661.0,
  "provider": "gps",
  "timestamp": 1746310000000,
  "metadata": {
    "driverId": "drv_123",
    "vehicleType": "bike",
    "region": "nairobi",
    "shiftId": "shift_456",
    "priority": 1,
    "isVerified": true
  }
}
```

#### Metadata

The `metadata` field is a free-form `Map<String, dynamic>`. You can pass **any number of key/value pairs** and the plugin will include them all in every upload. Supported value types are:

- `String`
- `int` / `double`
- `bool`
- Nested `Map<String, dynamic>`
- `List`

If no metadata is provided the `"metadata"` key is omitted from the payload entirely.

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

### startBubbleWidget

```dart
Future<void> startBubbleWidget({
  bool sendAppToBackground = true,
  Map<String, dynamic>? widgetData,
  String template = BubbleWidgetTemplate.medium,
})
```

Supported Android keys in `widgetData`: `title`, `subtitle`, `value`, `badge`.

Supported templates:
- `BubbleWidgetTemplate.small`
- `BubbleWidgetTemplate.medium`
- `BubbleWidgetTemplate.large`

### BubbleWidgetTemplate

```dart
class BubbleWidgetTemplate {
  static const String small = 'small';
  static const String medium = 'medium';
  static const String large = 'large';
}
```

### updateBubbleWidgetData

```dart
Future<void> updateBubbleWidgetData(
  Map<String, dynamic> widgetData, {
  String? template,
})
```

### stopBubbleHead

```dart
Future<void> stopBubbleHead()
```

### stopBubbleWidget

```dart
Future<void> stopBubbleWidget()
```

## Testing Checklist

Before publishing, validate these scenarios on a real Android device:
1. Overlay permission denied: plugin returns a clear error.
2. `startBubbleHead`: icon bubble appears and drag/snap still works.
3. `startBubbleWidget` with `small`, `medium`, and `large` templates.
4. Runtime updates via `updateBubbleWidgetData` including template switching.
5. Tap overlay behavior still returns app to foreground.
6. Long-press drag to close still works in icon and widget modes.
7. `startLocationUpdates` behavior remains unchanged.

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

