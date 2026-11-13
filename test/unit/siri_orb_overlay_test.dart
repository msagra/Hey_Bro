import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hey_bro/ui/siri_orb_overlay.dart';

void main() {
  Widget buildTestOrb({
    required SiriState state,
    String transcript = '',
    String responseText = '',
    String screenContext = '',
    VoidCallback? onMicTap,
    VoidCallback? onDismiss,
    VoidCallback? onInterrupt,
  }) {
    return MaterialApp(
      home: Scaffold(
        body: SiriOrbOverlay(
          state: state,
          transcript: transcript,
          responseText: responseText,
          screenContext: screenContext,
          onMicTap: onMicTap ?? () {},
          onDismiss: onDismiss ?? () {},
          onInterrupt: onInterrupt ?? () {},
        ),
      ),
    );
  }

  group('SiriOrbOverlay Widget & Interaction Tests', () {
    testWidgets('Renders properly in idle state', (WidgetTester tester) async {
      await tester.pumpWidget(buildTestOrb(state: SiriState.idle));
      expect(find.byType(SiriOrbOverlay), findsOneWidget);
      expect(find.byType(CustomPaint), findsWidgets);
    });

    testWidgets('Renders listening state with user transcript', (WidgetTester tester) async {
      await tester.pumpWidget(
        buildTestOrb(
          state: SiriState.listening,
          transcript: 'Turn on the torch',
        ),
      );

      await tester.pump(const Duration(milliseconds: 200));
      expect(find.textContaining('Turn on the torch'), findsOneWidget);
    });

    testWidgets('Renders processing state with status indicator', (WidgetTester tester) async {
      await tester.pumpWidget(
        buildTestOrb(
          state: SiriState.processing,
          transcript: 'What is the capital of France?',
        ),
      );

      await tester.pump(const Duration(milliseconds: 300));
      expect(find.byType(SiriOrbOverlay), findsOneWidget);
    });

    testWidgets('Renders speaking state and responds to tap to interrupt', (WidgetTester tester) async {
      bool interrupted = false;

      await tester.pumpWidget(
        buildTestOrb(
          state: SiriState.speaking,
          transcript: 'What time is it?',
          responseText: 'It is 3:30 AM.',
          onInterrupt: () {
            interrupted = true;
          },
        ),
      );

      await tester.pump(const Duration(milliseconds: 200));
      expect(find.text('It is 3:30 AM.'), findsOneWidget);

      // Tap the orb (which has CustomPaint with size 110x110)
      final orbFinder = find.byType(CustomPaint).last;
      await tester.tap(orbFinder);
      expect(interrupted, isTrue);
    });

    testWidgets('Mic tap in idle state triggers onMicTap callback', (WidgetTester tester) async {
      bool micTapped = false;

      await tester.pumpWidget(
        buildTestOrb(
          state: SiriState.idle,
          onMicTap: () {
            micTapped = true;
          },
        ),
      );

      final orbFinder = find.byType(CustomPaint).last;
      await tester.tap(orbFinder);
      expect(micTapped, isTrue);
    });

    testWidgets('Dismiss button triggers onDismiss callback', (WidgetTester tester) async {
      bool dismissed = false;

      await tester.pumpWidget(
        buildTestOrb(
          state: SiriState.idle,
          onDismiss: () {
            dismissed = true;
          },
        ),
      );

      final closeButton = find.byIcon(Icons.close_rounded);
      expect(closeButton, findsOneWidget);
      await tester.tap(closeButton);
      expect(dismissed, isTrue);
    });
  });
}
