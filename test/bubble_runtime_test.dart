import 'package:bubble_head_plus/bubble_head.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.dsaved.bubble.head');
  final calls = <MethodCall>[];
  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'getStatus') {
        return {
          'locationRunning': true,
          'bubbleRunning': true,
          'bubbleVisible': false
        };
      }
      return true;
    });
  });
  tearDown(() => TestDefaultBinaryMessengerBinding
      .instance.defaultBinaryMessenger
      .setMockMethodCallHandler(channel, null));

  test('reads actual native state and changes only bubble visibility',
      () async {
    final bubble = Bubble();
    expect((await bubble.getStatus())['locationRunning'], isTrue);
    await bubble.setBubbleVisible(true);
    expect(calls.last.method, 'setBubbleVisible');
    expect(calls.last.arguments, {'visible': true});
    await bubble.bringAppToForeground();
    expect(calls.last.method, 'bringAppToForeground');
  });

  test(
      'native location supports a two-second interval and authenticated upload',
      () async {
    await Bubble().startLocationUpdates(
      httpsUrl: 'https://example.com/api/location/update',
      interval: const Duration(seconds: 2),
      headers: {'Authorization': 'Bearer test'},
    );
    expect(calls.single.arguments['intervalMs'], 2000);
    expect(calls.single.arguments['headers'], {'Authorization': 'Bearer test'});
  });

  test('rejects insecure location endpoints before native startup', () async {
    await expectLater(
        Bubble().startLocationUpdates(httpsUrl: 'http://example.com'),
        throwsArgumentError);
    expect(calls, isEmpty);
  });

  test('expired booking never reaches native while a live offer retains expiry',
      () async {
    await expectLater(
        Bubble().showBookingAlert({'booking_id': '1', 'expiresAtMs': 1}),
        throwsArgumentError);
    expect(calls, isEmpty);
    final future = DateTime.now().millisecondsSinceEpoch + 20000;
    await Bubble().showBookingAlert({'booking_id': '1', 'expiresAtMs': future});
    expect(calls.single.method, 'showBookingAlert');
    expect(calls.single.arguments['expiresAtMs'], future);
  });

  test('widget can be primed hidden without sending the app home', () async {
    await Bubble()
        .startBubbleWidget(sendAppToBackground: false, initiallyVisible: false);
    expect(calls.single.arguments['initiallyVisible'], isFalse);
    expect(calls.single.arguments['sendAppToBackground'], isFalse);
  });
}
