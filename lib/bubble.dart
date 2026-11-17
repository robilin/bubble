import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

class BubbleWidgetTemplate {
  static const String small = 'small';
  static const String medium = 'medium';
  static const String large = 'large';
}

class Bubble {
  static const MethodChannel _channel =
      const MethodChannel('com.dsaved.bubble.head');
  static const EventChannel _locationEventsChannel =
      EventChannel('com.dsaved.bubble.head/location_events');

  bool shouldBounce;
  bool showCloseButton;
  bool allowDragToClose;
  String? customIconPath;

  Bubble({
    this.shouldBounce = true,
    this.allowDragToClose = true,
    this.showCloseButton = false,
    this.customIconPath,
  });

  /// puts app in background and shows floaty-bubble head
  Future<void> startBubbleHead({
    bool sendAppToBackground = true,
    bool initiallyVisible = true,
    String? iconPath,
  }) async {
    try {
      // Use provided iconPath, or instance customIconPath, or default
      String finalIconPath =
          iconPath ?? customIconPath ?? 'assets/images/icon.png';

      ByteData bytes = await rootBundle.load(finalIconPath);
      var buffer = bytes.buffer;
      var encodedImage = base64
          .encode(buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes));

      await _channel.invokeMethod('startBubbleHead', {
        "image": encodedImage,
        "bounce": shouldBounce,
        "showClose": showCloseButton,
        "dragToClose": allowDragToClose,
        "sendAppToBackground": sendAppToBackground,
        "initiallyVisible": initiallyVisible,
      });

      print('✅ Bubble started with icon: $finalIconPath');
    } catch (e) {
      print('❌ Error starting bubble: $e');
      rethrow;
    }
  }

  /// Puts app in background and shows a floating widget overlay.
  ///
  /// Supported keys inside [widgetData] on Android are:
  /// - title
  /// - subtitle
  /// - value
  /// - badge
  ///
  /// [template] controls size and density of the widget card.
  /// Use [BubbleWidgetTemplate.small], [BubbleWidgetTemplate.medium],
  /// or [BubbleWidgetTemplate.large].
  Future<void> startBubbleWidget({
    bool sendAppToBackground = true,
    bool initiallyVisible = true,
    Map<String, dynamic>? widgetData,
    String template = BubbleWidgetTemplate.medium,
  }) async {
    try {
      await _channel.invokeMethod('startBubbleWidget', {
        'bounce': shouldBounce,
        'showClose': showCloseButton,
        'dragToClose': allowDragToClose,
        'sendAppToBackground': sendAppToBackground,
        'initiallyVisible': initiallyVisible,
        'widgetData': widgetData ?? <String, dynamic>{},
        'template': template,
      });
    } catch (e) {
      print('❌ Error starting bubble widget: $e');
      rethrow;
    }
  }

  /// Updates dynamic content of the active floating widget overlay.
  Future<void> updateBubbleWidgetData(
    Map<String, dynamic> widgetData, {
    String? template,
  }) async {
    await _channel.invokeMethod('updateBubbleWidgetData', {
      'widgetData': widgetData,
      'template': template,
    });
  }

  /// closes floaty-bubble head
  Future<void> stopBubbleHead() async {
    try {
      await _channel.invokeMethod('stopBubbleHead');
      print('✅ Bubble stopped');
    } catch (e) {
      print('❌ Error stopping bubble: $e');
      rethrow;
    }
  }

  /// Alias for stopping the floating widget overlay.
  Future<void> stopBubbleWidget() async {
    try {
      await _channel.invokeMethod('stopBubbleWidget');
    } catch (e) {
      print('❌ Error stopping bubble widget: $e');
      rethrow;
    }
  }

  /// Query native state instead of assuming a previous Dart call stayed alive.
  Future<Map<String, dynamic>> getStatus() async {
    final value = await _channel.invokeMapMethod<String, dynamic>('getStatus');
    return value ?? <String, dynamic>{};
  }

  /// Hide/show the existing overlay without restarting its foreground service.
  /// Prime it while the app is visible using initiallyVisible: false.
  Future<void> setBubbleVisible(bool visible) async {
    await _channel.invokeMethod('setBubbleVisible', {'visible': visible});
  }

  /// Android can reject background launches; callers must retain notifications.
  Future<void> bringAppToForeground() async {
    await _channel.invokeMethod('bringAppToForeground');
  }

  /// Show a short-lived offer notification from a foreground or headless engine.
  /// Requires native location mode to be running. Never accepts a booking.
  /// Keys: booking_id, title, body, expiresAtMs and optional openApp.
  Future<void> showBookingAlert(Map<String, dynamic> data) async {
    final expiry = data['expiresAtMs'];
    if (expiry is! num || expiry <= DateTime.now().millisecondsSinceEpoch) {
      throw ArgumentError('A future expiresAtMs is required.');
    }
    if ('${data['booking_id'] ?? ''}'.trim().isEmpty) {
      throw ArgumentError('booking_id is required.');
    }
    await _channel.invokeMethod('showBookingAlert', data);
  }

  /// Starts periodic background location uploads to the configured HTTPS endpoint.
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
  }) async {
    if (!httpsUrl.startsWith('https://')) {
      throw ArgumentError('Only HTTPS endpoints are allowed.');
    }

    if (authRefreshUrl != null && !authRefreshUrl.startsWith('https://')) {
      throw ArgumentError('authRefreshUrl must be HTTPS when provided.');
    }

    final int intervalMs =
        interval.inMilliseconds < 2000 ? 2000 : interval.inMilliseconds;
    final int initialBackoffMs = initialBackoff.inMilliseconds < 1000
        ? 1000
        : initialBackoff.inMilliseconds;
    final int maxBackoffMs = maxBackoff.inMilliseconds < initialBackoffMs
        ? initialBackoffMs
        : maxBackoff.inMilliseconds;

    await _channel.invokeMethod('startLocationUpdates', {
      'url': httpsUrl,
      'intervalMs': intervalMs,
      'headers': headers ?? <String, String>{},
      'metadata': metadata ?? <String, dynamic>{},
      'maxQueueSize': maxQueueSize < 10 ? 10 : maxQueueSize,
      'initialBackoffMs': initialBackoffMs,
      'maxBackoffMs': maxBackoffMs,
      'authRefreshUrl': authRefreshUrl,
      'authRefreshHeaders': authRefreshHeaders ?? <String, String>{},
      'authRefreshBody': authRefreshBody ?? <String, dynamic>{},
      'authTokenResponseKey': authTokenResponseKey,
      'authHeaderName': authHeaderName,
      'authHeaderPrefix': authHeaderPrefix,
    });
  }

  /// Stops background location uploads started by [startLocationUpdates].
  Future<void> stopLocationUpdates() async {
    await _channel.invokeMethod('stopLocationUpdates');
  }

  /// Emits status updates from the native background location pipeline.
  Stream<Map<String, dynamic>> get locationUpdateEvents {
    return _locationEventsChannel.receiveBroadcastStream().map((dynamic event) {
      return Map<String, dynamic>.from(event as Map);
    });
  }
}
