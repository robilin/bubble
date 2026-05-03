import 'dart:async';

import 'package:bubble_head/bubble_head.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final Bubble _bubble = Bubble(showCloseButton: true);
  final TextEditingController _urlController = TextEditingController(
    text: 'https://api.example.com/driver/location',
  );
  StreamSubscription<Map<String, dynamic>>? _eventsSubscription;
  final List<String> _logs = <String>[];

  bool _bubbleRunning = false;
  bool _trackingRunning = false;

  @override
  void initState() {
    super.initState();
    _eventsSubscription = _bubble.locationUpdateEvents.listen((event) {
      _addLog('event: $event');
    });
  }

  @override
  void dispose() {
    _eventsSubscription?.cancel();
    _urlController.dispose();
    super.dispose();
  }

  void _addLog(String message) {
    if (!mounted) return;
    setState(() {
      _logs.insert(0, '${DateTime.now().toIso8601String()}  $message');
      if (_logs.length > 50) {
        _logs.removeLast();
      }
    });
  }

  Future<bool> _ensurePermissions() async {
    final Map<Permission, PermissionStatus> results = await <Permission>[
      Permission.locationWhenInUse,
      Permission.locationAlways,
      Permission.notification,
      Permission.systemAlertWindow,
    ].request();

    final bool locationOk =
        results[Permission.locationWhenInUse]?.isGranted == true ||
            results[Permission.locationWhenInUse]?.isLimited == true;
    final bool backgroundLocationOk =
        results[Permission.locationAlways]?.isGranted == true;
    final bool notificationOk =
        results[Permission.notification]?.isGranted == true ||
            results[Permission.notification]?.isDenied == true;
    final bool overlayOk =
        results[Permission.systemAlertWindow]?.isGranted == true;

    final bool granted =
        locationOk && backgroundLocationOk && notificationOk && overlayOk;

    if (!granted) {
      _addLog('permission check failed: $results');
    }
    return granted;
  }

  Future<void> _startDriverMode() async {
    try {
      final bool permissionsGranted = await _ensurePermissions();
      if (!permissionsGranted) {
        _addLog('cannot start: required permissions are missing');
        return;
      }

      await _bubble.startBubbleHead(sendAppToBackground: false);
      await _bubble.startLocationUpdates(
        httpsUrl: _urlController.text.trim(),
        interval: const Duration(seconds: 15),
        headers: <String, String>{
          'Authorization': 'Bearer ACCESS_TOKEN',
        },
        metadata: <String, dynamic>{
          'driverId': 'drv_123',
          'vehicleType': 'bike',
        },
        maxQueueSize: 300,
        initialBackoff: const Duration(seconds: 3),
        maxBackoff: const Duration(minutes: 1),
        authRefreshUrl: 'https://api.example.com/auth/refresh',
        authRefreshHeaders: <String, String>{
          'Authorization': 'Bearer REFRESH_TOKEN',
        },
        authRefreshBody: <String, dynamic>{
          'deviceId': 'example-device-123',
        },
        authTokenResponseKey: 'accessToken',
      );

      if (!mounted) return;
      setState(() {
        _bubbleRunning = true;
        _trackingRunning = true;
      });
      _addLog('driver mode started');
    } on PlatformException {
      _addLog('platform exception while starting driver mode');
    } on ArgumentError catch (e) {
      _addLog('invalid input: $e');
    } catch (e) {
      _addLog('start failed: $e');
    }
  }

  Future<void> _stopDriverMode() async {
    try {
      await _bubble.stopLocationUpdates();
      await _bubble.stopBubbleHead();
      if (!mounted) return;
      setState(() {
        _bubbleRunning = false;
        _trackingRunning = false;
      });
      _addLog('driver mode stopped');
    } on PlatformException {
      _addLog('platform exception while stopping driver mode');
    } catch (e) {
      _addLog('stop failed: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Bubble Driver Demo'),
        ),
        body: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              const Text(
                'Location Upload URL (HTTPS)',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _urlController,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  hintText: 'https://api.example.com/driver/location',
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: <Widget>[
                  Expanded(
                    child: FilledButton(
                      onPressed: _startDriverMode,
                      child: const Text('Start Driver Mode'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: FilledButton.tonal(
                      onPressed: _stopDriverMode,
                      child: const Text('Stop Driver Mode'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text('Bubble: ${_bubbleRunning ? 'ON' : 'OFF'}'),
                      Text('Tracking: ${_trackingRunning ? 'ON' : 'OFF'}'),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                'Event Log',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
              Expanded(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    border: Border.all(color: Colors.black12),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: ListView.builder(
                    reverse: false,
                    itemCount: _logs.length,
                    itemBuilder: (BuildContext context, int index) {
                      return Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 6,
                        ),
                        child: Text(
                          _logs[index],
                          style: const TextStyle(fontSize: 12),
                        ),
                      );
                    },
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
