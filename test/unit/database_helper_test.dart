import 'package:flutter_test/flutter_test.dart';
import 'package:hey_bro/core/database_helper.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  group('DatabaseHelper Unit Tests', () {
    test('Database initializes and seeds default shortcuts', () async {
      final db = DatabaseHelper.instance;
      final aliases = await db.getAliases();

      expect(aliases, isNotEmpty);
      expect(aliases.length, greaterThanOrEqualTo(25));

      final triggerPhrases = aliases.map((a) => a['trigger_phrase'] as String).toList();
      expect(triggerPhrases, contains('torch'));
      expect(triggerPhrases, contains('silence phone'));
      expect(triggerPhrases, contains('screenshot'));
      expect(triggerPhrases, contains('take a selfie'));
      expect(triggerPhrases, contains('open whatsapp'));
      expect(triggerPhrases, contains('open youtube'));
    });

    test('Exact and fuzzy alias search works as expected', () async {
      final db = DatabaseHelper.instance;

      // Exact match
      final exact = await db.findAlias('torch');
      expect(exact, isNotNull);
      expect(exact!['target_value'], equals('flashlightOn'));

      // Fuzzy match
      final fuzzy = await db.findAlias('please turn on torch now');
      expect(fuzzy, isNotNull);
      expect(fuzzy!['target_value'], equals('flashlightOn'));
    });

    test('Insert and delete custom shortcut alias', () async {
      final db = DatabaseHelper.instance;

      final id = await db.insertAlias('order pizza', 'custom', 'openApp:com.dominos');
      expect(id, isPositive);

      final found = await db.findAlias('order pizza');
      expect(found, isNotNull);
      expect(found!['target_value'], equals('openApp:com.dominos'));

      // Verify custom shortcut appears at the very top of getAliases
      final allWithCustom = await db.getAliases();
      expect(allWithCustom.first['trigger_phrase'], equals('order pizza'));
      expect(allWithCustom.first['target_type'], equals('custom'));

      // Verify getCustomOnlyAliases returns custom shortcut
      final customOnly = await db.getCustomOnlyAliases();
      expect(customOnly.any((a) => a['trigger_phrase'] == 'order pizza'), isTrue);

      final deleted = await db.deleteAlias(id);
      expect(deleted, equals(1));

      final afterDelete = await db.findAlias('order pizza');
      expect(afterDelete, isNull);
    });

    test('Conversation logging and retrieval', () async {
      final db = DatabaseHelper.instance;

      await db.clearConversations();
      var history = await db.getConversations();
      expect(history, isEmpty);

      await db.insertConversation('user', 'What is the weather today?');
      await db.insertConversation('assistant', 'It is sunny and 28 degrees.', actionJson: '{"action":"none"}');

      history = await db.getConversations(limit: 10);
      expect(history.length, equals(2));
      expect(history.first['role'], equals('assistant'));
      expect(history.last['role'], equals('user'));
    });

    test('Command logs record latency and status', () async {
      final db = DatabaseHelper.instance;

      final logId = await db.logCommand('torch', 'SUCCESS', 42);
      expect(logId, isPositive);

      final logs = await db.getCommandLogs(limit: 5);
      expect(logs, isNotEmpty);
      expect(logs.first['command'], equals('torch'));
      expect(logs.first['execution_time_ms'], equals(42));
      expect(logs.first['status'], equals('SUCCESS'));
    });
  });
}
