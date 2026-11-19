import 'package:flutter_test/flutter_test.dart';
import 'package:hey_bro/core/native_event_order.dart';

void main() {
  group('NativeEventOrder', () {
    test('accepts increasing events and rejects duplicates or stale events', () {
      final order = NativeEventOrder();

      expect(
        order.accepts(sessionId: 'session-a', sequenceId: 1),
        isTrue,
      );
      expect(
        order.accepts(sessionId: 'session-a', sequenceId: 3),
        isTrue,
      );
      expect(
        order.accepts(sessionId: 'session-a', sequenceId: 3),
        isFalse,
      );
      expect(
        order.accepts(sessionId: 'session-a', sequenceId: 2),
        isFalse,
      );
    });

    test('requires a state snapshot to switch native sessions', () {
      final order = NativeEventOrder();
      expect(
        order.accepts(sessionId: 'session-a', sequenceId: 10),
        isTrue,
      );
      expect(
        order.accepts(sessionId: 'session-b', sequenceId: 1),
        isFalse,
      );
      expect(
        order.accepts(
          sessionId: 'session-b',
          sequenceId: 1,
          stateSync: true,
        ),
        isTrue,
      );
      expect(
        order.accepts(sessionId: 'session-a', sequenceId: 11),
        isFalse,
      );
    });

    test('accepts legacy events without ordering metadata', () {
      final order = NativeEventOrder();
      expect(order.accepts(sessionId: null, sequenceId: null), isTrue);
    });

    test('rejects malformed ordering metadata', () {
      final order = NativeEventOrder();
      expect(order.accepts(sessionId: 'session-a', sequenceId: 0), isFalse);
      expect(order.accepts(sessionId: 'session-a', sequenceId: 1.5), isFalse);
      expect(order.accepts(sessionId: '', sequenceId: 1), isFalse);
    });
  });
}
