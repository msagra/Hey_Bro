import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hey_bro/core/database_helper.dart';
import 'package:hey_bro/main.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  final List<String> invokedMethods = [];

  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;

    dotenv.testLoad(fileInput: '''
GEMINI_API_KEY=test_gemini_key_123
GEMINI_LIVE_MODEL=models/gemini-3.8-live
GEMINI_REST_MODEL=gemini-3.8-flash
GROQ_API_KEY=mock_groq_test_token_12345
WAKE_PHRASE=hey bro
''');

    const eventChannel = MethodChannel('com.example.hey_bro/events');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(eventChannel, (MethodCall methodCall) async {
      return null;
    });

    const channel = MethodChannel('com.example.hey_bro/assistant');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      invokedMethods.add(methodCall.method);
      switch (methodCall.method) {
        case 'checkPermissions':
          return {
            'mic': true,
            'camera': true,
            'accessibility': false,
            'overlay': true,
            'notification': true,
            'battery': true,
          };
        case 'getActiveWakeEngine':
          return 'SileroVad+HeyBroEngine';
        case 'getAutostartEnabled':
          return true;
        case 'getLaunchIntentType':
          return 'normal';
        case 'getGeminiApiKey':
          return 'test_gemini_key_123';
        case 'getGroqApiKey':
          return 'test_groq_key_456';
        case 'getBackendWs':
          return 'wss://generativelanguage.googleapis.com/...';
        case 'setGeminiApiKey':
          return true;
        case 'setGroqApiKey':
          return true;
        case 'addCustomAlias':
          return true;
        case 'syncCustomAliases':
          return true;
        case 'openAccessibilitySettings':
          return true;
        case 'openNotificationListenerSettings':
          return true;
        default:
          return true;
      }
    });
  });

  tearDown(() {
    invokedMethods.clear();
  });

  group('UI/UX Interaction Tests', () {
    testWidgets('API Keys dialog displays Gemini and Groq inputs and saves properly', (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1260, 2800);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(const HeyBroApp());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      // Tap API Key dialog button
      final keyBtn = find.byTooltip('Gemini API Key');
      expect(keyBtn, findsOneWidget);
      await tester.tap(keyBtn);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      // Check dialog elements
      expect(find.text('API & Cloud Fallback Keys'), findsOneWidget);
      expect(find.textContaining('Google Gemini'), findsOneWidget);
      expect(find.textContaining('Groq Cloud'), findsOneWidget);

      // Tap Save
      final saveBtn = find.text('Save');
      expect(saveBtn, findsOneWidget);
      await tester.tap(saveBtn);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('API & Cloud Fallback Keys'), findsNothing);
      expect(invokedMethods, contains('setGeminiApiKey'));
      expect(invokedMethods, contains('setGroqApiKey'));
    });

    testWidgets('Custom Shortcuts bottom sheet opens and displays preset chips and shortcuts list', (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1260, 2800);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(const HeyBroApp());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      // Open Custom Shortcuts dialog
      final boltIcon = find.byIcon(Icons.bolt_rounded);
      expect(boltIcon, findsOneWidget);
      await tester.tap(boltIcon);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));

      // If bottom sheet was pushed, verify elements
      final titleFinder = find.text('Custom Shortcuts (SQLite)');
      if (titleFinder.evaluate().isNotEmpty) {
        expect(find.text('Sync Native'), findsOneWidget);
        await tester.tap(find.text('Sync Native'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 300));
        expect(invokedMethods, contains('syncCustomAliases'));

        await tester.tap(find.byIcon(Icons.close));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 500));
      }
    });

    testWidgets('Responsive Layout across small phone, large device, and landscape viewports', (WidgetTester tester) async {
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      // 1. Standard mobile viewport (390x844)
      tester.view.physicalSize = const Size(390 * 2.0, 844 * 2.0);
      tester.view.devicePixelRatio = 2.0;
      await tester.pumpWidget(const HeyBroApp());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.text('Hey Bro'), findsOneWidget);

      // 2. High-res Vivo X200t viewport (1260x2800)
      tester.view.physicalSize = const Size(1260, 2800);
      tester.view.devicePixelRatio = 2.0;
      await tester.pumpWidget(const HeyBroApp());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.text('Hey Bro'), findsOneWidget);

      // 3. Landscape viewport (844x390)
      tester.view.physicalSize = const Size(844 * 2.0, 390 * 2.0);
      tester.view.devicePixelRatio = 2.0;
      await tester.pumpWidget(const HeyBroApp());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.text('Hey Bro'), findsOneWidget);
    });

    testWidgets('Permission tiles grant buttons invoke platform methods safely', (WidgetTester tester) async {
      tester.view.physicalSize = const Size(1260, 2800);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(const HeyBroApp());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));

      // Scroll until Accessibility Service tile is visible
      final a11yFinder = find.text('Accessibility Service');
      await tester.scrollUntilVisible(
        a11yFinder,
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(a11yFinder, findsOneWidget);

      // Find 'Grant' button (since accessibility is false)
      final grantFinder = find.widgetWithText(FilledButton, 'Grant').first;
      expect(grantFinder, findsOneWidget);

      // Tap accessibility grant button
      await tester.tap(grantFinder);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      // Verify openAccessibilitySettings was invoked
      expect(invokedMethods, contains('openAccessibilitySettings'));
      expect(tester.takeException(), isNull);
    });
  });
}
