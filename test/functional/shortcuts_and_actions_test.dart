import 'package:flutter_test/flutter_test.dart';
import 'package:hey_bro/core/database_helper.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  group('Functional Shortcuts & Actions Mapping Tests', () {
    test('All required common categories are seeded with valid action values', () async {
      final db = DatabaseHelper.instance;
      final aliases = await db.getAliases();

      final supportedActionPrefixes = [
        'flashlight',
        'mute',
        'volume',
        'takeSelfie',
        'takePhoto',
        'openCamera',
        'screenshot',
        'lockScreen',
        'batterySaver',
        'doNotDisturb',
        'notifications',
        'quickSettings',
        'recents',
        'globalBack',
        'globalHome',
        'mediaPlayPause',
        'mediaNext',
        'mediaPrevious',
        'toggleWifi',
        'toggleBluetooth',
        'toggleHotspot',
        'openMaps',
        'openApp:'
      ];

      for (final alias in aliases) {
        final action = alias['target_value'] as String;
        final isValid = supportedActionPrefixes.any((prefix) => action.startsWith(prefix));
        expect(
          isValid,
          isTrue,
          reason: 'Action "$action" for "${alias['trigger_phrase']}" should match a supported action prefix',
        );
      }
    });

    test('Hardware shortcuts map to proper hardware action tokens', () async {
      final db = DatabaseHelper.instance;

      expect((await db.findAlias('torch'))?['target_value'], equals('flashlightOn'));
      expect((await db.findAlias('torch off'))?['target_value'], equals('flashlightOff'));
      expect((await db.findAlias('silence phone'))?['target_value'], equals('mute'));
      expect((await db.findAlias('volume up'))?['target_value'], equals('volumeUp'));
      expect((await db.findAlias('volume down'))?['target_value'], equals('volumeDown'));
      expect((await db.findAlias('take a selfie'))?['target_value'], equals('takeSelfie'));
      expect((await db.findAlias('screenshot'))?['target_value'], equals('screenshot'));
      expect((await db.findAlias('lock screen'))?['target_value'], equals('lockScreen'));
    });

    test('App shortcuts map to package names', () async {
      final db = DatabaseHelper.instance;

      final whatsapp = await db.findAlias('open whatsapp');
      expect(whatsapp, isNotNull);
      expect(whatsapp!['target_value'], equals('openApp:com.whatsapp'));

      final youtube = await db.findAlias('open youtube');
      expect(youtube, isNotNull);
      expect(youtube!['target_value'], equals('openApp:com.google.android.youtube'));

      final spotify = await db.findAlias('open spotify');
      expect(spotify, isNotNull);
      expect(spotify!['target_value'], equals('openApp:com.spotify.music'));
    });

    test('Case-insensitivity and punctuation resilience in trigger matching', () async {
      final db = DatabaseHelper.instance;

      // Uppercase & punctuation
      final res1 = await db.findAlias('TORCH!');
      expect(res1, isNotNull);
      expect(res1!['target_value'], equals('flashlightOn'));

      final res2 = await db.findAlias('Silence Phone, please');
      expect(res2, isNotNull);
      expect(res2!['target_value'], equals('mute'));
    });
  });
}
