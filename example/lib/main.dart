import 'dart:async';

import 'package:bubble_head_plus/bubble_head.dart';
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
  final TextEditingController _widgetTitleController = TextEditingController(
    text: 'Driver Status',
  );
  final TextEditingController _widgetSubtitleController = TextEditingController(
    text: 'Active deliveries',
  );
  final TextEditingController _widgetBadgeController = TextEditingController(
    text: 'LIVE',
  );
  StreamSubscription<Map<String, dynamic>>? _eventsSubscription;
  final List<String> _logs = <String>[];

  bool _bubbleRunning = false;
  bool _widgetRunning = false;
  bool _trackingRunning = false;
  String _selectedTemplate = BubbleWidgetTemplate.medium;
  int _widgetValue = 3;

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
    _widgetTitleController.dispose();
    _widgetSubtitleController.dispose();
    _widgetBadgeController.dispose();
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

  Future<bool> _ensureOverlayPermission() async {
    final Map<Permission, PermissionStatus> results = await <Permission>[
      Permission.systemAlertWindow,
      Permission.notification,
    ].request();

    final bool overlayOk =
        results[Permission.systemAlertWindow]?.isGranted == true;
    final bool notificationOk =
        results[Permission.notification]?.isGranted == true ||
            results[Permission.notification]?.isDenied == true;
    final bool granted = overlayOk && notificationOk;

    if (!granted) {
      _addLog('overlay permission check failed: $results');
    }
    return granted;
  }

  Future<bool> _ensureDriverPermissions() async {
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
      final bool permissionsGranted = await _ensureDriverPermissions();
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

  Future<void> _startIconBubble() async {
    try {
      final bool permissionsGranted = await _ensureOverlayPermission();
      if (!permissionsGranted) {
        _addLog('cannot start icon bubble: overlay permission missing');
        return;
      }

      await _bubble.startBubbleHead(sendAppToBackground: false);
      if (!mounted) return;
      setState(() {
        _bubbleRunning = true;
        _widgetRunning = false;
      });
      _addLog('icon bubble started');
    } on PlatformException catch (e) {
      _addLog('start icon bubble failed: ${e.message}');
    } catch (e) {
      _addLog('start icon bubble failed: $e');
    }
  }

  Future<void> _startWidgetBubble() async {
    try {
      final bool permissionsGranted = await _ensureOverlayPermission();
      if (!permissionsGranted) {
        _addLog('cannot start widget bubble: overlay permission missing');
        return;
      }

      await _bubble.startBubbleWidget(
        sendAppToBackground: false,
        template: _selectedTemplate,
        widgetData: <String, dynamic>{
          'title': _widgetTitleController.text.trim(),
          'subtitle': _widgetSubtitleController.text.trim(),
          'value': _widgetValue,
          'badge': _widgetBadgeController.text.trim(),
        },
      );

      if (!mounted) return;
      setState(() {
        _bubbleRunning = false;
        _widgetRunning = true;
      });
      _addLog('widget bubble started ($_selectedTemplate)');
    } on PlatformException catch (e) {
      _addLog('start widget bubble failed: ${e.message}');
    } catch (e) {
      _addLog('start widget bubble failed: $e');
    }
  }

  Future<void> _updateWidgetBubble({bool incrementValue = false}) async {
    try {
      if (incrementValue) {
        setState(() {
          _widgetValue += 1;
        });
      }

      await _bubble.updateBubbleWidgetData(
        <String, dynamic>{
          'title': _widgetTitleController.text.trim(),
          'subtitle': _widgetSubtitleController.text.trim(),
          'value': _widgetValue,
          'badge': _widgetBadgeController.text.trim(),
        },
        template: _selectedTemplate,
      );

      _addLog(
          'widget bubble updated (value=$_widgetValue, template=$_selectedTemplate)');
    } on PlatformException catch (e) {
      _addLog('update widget bubble failed: ${e.message}');
    } catch (e) {
      _addLog('update widget bubble failed: $e');
    }
  }

  Future<void> _stopOverlayOnly() async {
    try {
      await _bubble.stopBubbleWidget();
      if (!mounted) return;
      setState(() {
        _bubbleRunning = false;
        _widgetRunning = false;
      });
      _addLog('overlay stopped');
    } on PlatformException catch (e) {
      _addLog('stop overlay failed: ${e.message}');
    } catch (e) {
      _addLog('stop overlay failed: $e');
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
              const Text(
                'Overlay Playground',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
              Row(
                children: <Widget>[
                  Expanded(
                    child: FilledButton(
                      onPressed: _startIconBubble,
                      child: const Text('Start Icon Bubble'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: FilledButton(
                      onPressed: _startWidgetBubble,
                      child: const Text('Start Widget Bubble'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                value: _selectedTemplate,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: 'Widget Template',
                ),
                items: const <DropdownMenuItem<String>>[
                  DropdownMenuItem<String>(
                    value: BubbleWidgetTemplate.small,
                    child: Text('Small'),
                  ),
                  DropdownMenuItem<String>(
                    value: BubbleWidgetTemplate.medium,
                    child: Text('Medium'),
                  ),
                  DropdownMenuItem<String>(
                    value: BubbleWidgetTemplate.large,
                    child: Text('Large'),
                  ),
                ],
                onChanged: (String? value) {
                  if (value == null) return;
                  setState(() {
                    _selectedTemplate = value;
                  });
                },
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _widgetTitleController,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: 'Widget Title',
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _widgetSubtitleController,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: 'Widget Subtitle',
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _widgetBadgeController,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: 'Widget Badge',
                ),
              ),
              const SizedBox(height: 8),
              Row(
                children: <Widget>[
                  Expanded(
                    child: FilledButton.tonal(
                      onPressed: () =>
                          _updateWidgetBubble(incrementValue: true),
                      child: const Text('Update Widget (+1)'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: FilledButton.tonal(
                      onPressed: _stopOverlayOnly,
                      child: const Text('Stop Overlay'),
                    ),
                  ),
                ],
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
                      Text('Icon bubble: ${_bubbleRunning ? 'ON' : 'OFF'}'),
                      Text('Widget bubble: ${_widgetRunning ? 'ON' : 'OFF'}'),
                      Text('Template: $_selectedTemplate'),
                      Text('Widget value: $_widgetValue'),
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
