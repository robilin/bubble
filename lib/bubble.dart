import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/services.dart';

class Bubble {
  static const MethodChannel _channel =
      const MethodChannel('com.dsaved.bubble.head');

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
    String? iconPath,
  }) async {
    try {
      // Use provided iconPath, or instance customIconPath, or default
      String finalIconPath =
          iconPath ?? customIconPath ?? 'assets/images/icon.png';

      ByteData bytes = await rootBundle.load(finalIconPath);
      var buffer = bytes.buffer;
      var encodedImage = base64.encode(Uint8List.view(buffer));

      await _channel.invokeMethod('startBubbleHead', {
        "image": encodedImage,
        "bounce": shouldBounce,
        "showClose": showCloseButton,
        "dragToClose": allowDragToClose,
        "sendAppToBackground": sendAppToBackground,
      });

      print('✅ Bubble started with icon: $finalIconPath');
    } catch (e) {
      print('❌ Error starting bubble: $e');
      rethrow;
    }
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
}
