import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hey_bro/main.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;

    dotenv.testLoad(fileInput: '''
GEMINI_API_KEY=mock_test_key_12345
GEMINI_LIVE_MODEL=models/gemini-3.8-live
GEMINI_REST_MODEL=gemini-3.8-flash
GROQ_API_KEY=mock_groq_test_token_12345
WAKE_PHRASE=hey bro
''');

    const channel = MethodChannel('com.example.hey_bro/assistant');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      switch (methodCall.method) {
        case 'checkPermissions':
          return {
            'hasMic': true,
            'hasOverlay': true,
            'hasA11y': true,
            'hasNotificationAccess': true,
            'hasBatteryExemption': true,
          };
        case 'getActiveWakeEngine':
          return 'SileroVad+HeyBroEngine';
        case 'getAutostartEnabled':
          return true;
        case 'getLaunchIntentType':
          return 'normal';
        case 'getGeminiApiKey':
          return 'mock_test_key_12345';
        case 'getGroqApiKey':
          return 'mock_groq_test_token_12345';
        case 'getBackendWs':
          return 'wss://generativelanguage.googleapis.com/...';
        case 'addCustomAlias':
          return true;
        default:
          return true;
      }
    });
  });

  group('Hey Bro App Smoke Tests', () {
    testWidgets('App boots up cleanly and renders primary assistant shell', (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1260, 2800);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(const HeyBroApp());
      await tester.pumpAndSettle();

      expect(find.text('Hey Bro'), findsOneWidget);
      expect(find.text('Permissions'), findsOneWidget);
      expect(find.text('Microphone'), findsOneWidget);
      expect(find.text('Accessibility Service'), findsOneWidget);
      expect(find.text('Draw Over Other Apps'), findsOneWidget);
      expect(find.text('Notifications Access'), findsOneWidget);
      expect(find.text('Battery Optimization'), findsOneWidget);

      // Verify header action icons
      expect(find.byTooltip('Open Siri Floating Orb'), findsOneWidget);
      expect(find.byTooltip('Custom Shortcuts'), findsOneWidget);
      expect(find.byTooltip('Gemini API Key'), findsOneWidget);
    });

    testWidgets('Status subtitle renders in initial idle state', (WidgetTester tester) async {
      await tester.pumpWidget(const HeyBroApp());
      await tester.pumpAndSettle();

      expect(find.text('Tap orb to start'), findsOneWidget);
    });
  });
}
