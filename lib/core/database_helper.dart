import 'dart:async';
import 'package:path/path.dart';
import 'package:sqflite/sqflite.dart';

class DatabaseHelper {
  static const _dbName = 'hey_bro.db';
  static const _dbVersion = 1;

  DatabaseHelper._();
  static final DatabaseHelper instance = DatabaseHelper._();

  static Database? _database;
  static Completer<Database>? _dbInitCompleter;

  Future<Database> get database async {
    if (_database != null && _database!.isOpen) return _database!;
    if (_dbInitCompleter != null) return _dbInitCompleter!.future;

    final completer = Completer<Database>();
    _dbInitCompleter = completer;
    try {
      _database = await _initDatabase();
      completer.complete(_database!);
      return _database!;
    } catch (e) {
      completer.completeError(e);
      rethrow;
    } finally {
      _dbInitCompleter = null;
    }
  }

  static const List<Map<String, String>> defaultShortcuts = [
    {'trigger_phrase': 'torch', 'target_type': 'action', 'target_value': 'flashlightOn'},
    {'trigger_phrase': 'turn on flashlight', 'target_type': 'action', 'target_value': 'flashlightOn'},
    {'trigger_phrase': 'turn off flashlight', 'target_type': 'action', 'target_value': 'flashlightOff'},
    {'trigger_phrase': 'torch off', 'target_type': 'action', 'target_value': 'flashlightOff'},
    {'trigger_phrase': 'silence phone', 'target_type': 'action', 'target_value': 'mute'},
    {'trigger_phrase': 'mute', 'target_type': 'action', 'target_value': 'mute'},
    {'trigger_phrase': 'volume up', 'target_type': 'action', 'target_value': 'volumeUp'},
    {'trigger_phrase': 'volume down', 'target_type': 'action', 'target_value': 'volumeDown'},
    {'trigger_phrase': 'take a selfie', 'target_type': 'action', 'target_value': 'takeSelfie'},
    {'trigger_phrase': 'front camera', 'target_type': 'action', 'target_value': 'takeSelfie'},
    {'trigger_phrase': 'take photo', 'target_type': 'action', 'target_value': 'takePhoto'},
    {'trigger_phrase': 'open camera', 'target_type': 'action', 'target_value': 'openCamera'},
    {'trigger_phrase': 'screenshot', 'target_type': 'action', 'target_value': 'screenshot'},
    {'trigger_phrase': 'take screenshot', 'target_type': 'action', 'target_value': 'screenshot'},
    {'trigger_phrase': 'lock screen', 'target_type': 'action', 'target_value': 'lockScreen'},
    {'trigger_phrase': 'battery saver', 'target_type': 'action', 'target_value': 'batterySaver'},
    {'trigger_phrase': 'do not disturb', 'target_type': 'action', 'target_value': 'doNotDisturb'},
    {'trigger_phrase': 'show notifications', 'target_type': 'action', 'target_value': 'notifications'},
    {'trigger_phrase': 'quick settings', 'target_type': 'action', 'target_value': 'quickSettings'},
    {'trigger_phrase': 'recent apps', 'target_type': 'action', 'target_value': 'recents'},
    {'trigger_phrase': 'go home', 'target_type': 'action', 'target_value': 'globalHome'},
    {'trigger_phrase': 'go back', 'target_type': 'action', 'target_value': 'globalBack'},
    {'trigger_phrase': 'pause music', 'target_type': 'action', 'target_value': 'mediaPlayPause'},
    {'trigger_phrase': 'next song', 'target_type': 'action', 'target_value': 'mediaNext'},
    {'trigger_phrase': 'previous song', 'target_type': 'action', 'target_value': 'mediaPrevious'},
    {'trigger_phrase': 'turn on wifi', 'target_type': 'action', 'target_value': 'toggleWifi'},
    {'trigger_phrase': 'turn on bluetooth', 'target_type': 'action', 'target_value': 'toggleBluetooth'},
    {'trigger_phrase': 'turn on hotspot', 'target_type': 'action', 'target_value': 'toggleHotspot'},
    {'trigger_phrase': 'open maps', 'target_type': 'action', 'target_value': 'openMaps'},
    {'trigger_phrase': 'open whatsapp', 'target_type': 'action', 'target_value': 'openApp:com.whatsapp'},
    {'trigger_phrase': 'open youtube', 'target_type': 'action', 'target_value': 'openApp:com.google.android.youtube'},
    {'trigger_phrase': 'open spotify', 'target_type': 'action', 'target_value': 'openApp:com.spotify.music'},
  ];

  Future<Database> _initDatabase() async {
    final dbPath = await getDatabasesPath();
    final path = join(dbPath, _dbName);

    return await openDatabase(
      path,
      version: _dbVersion,
      onCreate: _onCreate,
      onOpen: (db) async {
        await _ensureDefaultShortcuts(db);
      },
    );
  }

  Future<void> _onCreate(Database db, int version) async {
    // 1. Conversations table
    await db.execute('''
      CREATE TABLE conversations (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        role TEXT NOT NULL,
        message TEXT NOT NULL,
        action_json TEXT,
        timestamp INTEGER NOT NULL
      )
    ''');

    // 2. Custom Aliases table (custom shortcuts)
    await db.execute('''
      CREATE TABLE custom_aliases (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        trigger_phrase TEXT NOT NULL UNIQUE,
        target_type TEXT NOT NULL,
        target_value TEXT NOT NULL
      )
    ''');

    // 3. Command Logs table
    await db.execute('''
      CREATE TABLE command_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        command TEXT NOT NULL,
        status TEXT NOT NULL,
        execution_time_ms INTEGER NOT NULL,
        timestamp INTEGER NOT NULL
      )
    ''');

    await _ensureDefaultShortcuts(db);
  }

  Future<void> _ensureDefaultShortcuts(Database db) async {
    for (final shortcut in defaultShortcuts) {
      await db.insert(
        'custom_aliases',
        shortcut,
        conflictAlgorithm: ConflictAlgorithm.ignore,
      );
    }
  }

  // ── Conversation APIs ────────────────────────────────────────────────────────

  Future<int> insertConversation(String role, String message, {String? actionJson}) async {
    final db = await database;
    return await db.insert('conversations', {
      'role': role,
      'message': message,
      'action_json': actionJson,
      'timestamp': DateTime.now().millisecondsSinceEpoch,
    });
  }

  Future<List<Map<String, dynamic>>> getConversations({int limit = 50}) async {
    final db = await database;
    return await db.query(
      'conversations',
      orderBy: 'timestamp DESC',
      limit: limit,
    );
  }

  Future<int> clearConversations() async {
    final db = await database;
    return await db.delete('conversations');
  }

  // ── Custom Aliases APIs ──────────────────────────────────────────────────────

  Future<int> insertAlias(String triggerPhrase, String targetType, String targetValue) async {
    final db = await database;
    return await db.insert(
      'custom_aliases',
      {
        'trigger_phrase': triggerPhrase.trim().toLowerCase(),
        'target_type': targetType.trim(),
        'target_value': targetValue.trim(),
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<List<Map<String, dynamic>>> getAliases() async {
    final db = await database;
    return await db.query('custom_aliases', orderBy: 'trigger_phrase ASC');
  }

  Future<Map<String, dynamic>?> findAlias(String phrase) async {
    final db = await database;
    final normalized = phrase.trim().toLowerCase();
    final results = await db.query(
      'custom_aliases',
      where: 'trigger_phrase = ?',
      whereArgs: [normalized],
      limit: 1,
    );
    if (results.isNotEmpty) return results.first;

    // Fuzzy contains check
    final all = await db.query('custom_aliases');
    for (final row in all) {
      final trigger = row['trigger_phrase'] as String;
      if (normalized.contains(trigger) || trigger.contains(normalized)) {
        return row;
      }
    }
    return null;
  }

  Future<int> deleteAlias(int id) async {
    final db = await database;
    return await db.delete('custom_aliases', where: 'id = ?', whereArgs: [id]);
  }

  // ── Command Logs APIs ────────────────────────────────────────────────────────

  Future<int> logCommand(String command, String status, int executionTimeMs) async {
    final db = await database;
    return await db.insert('command_logs', {
      'command': command,
      'status': status,
      'execution_time_ms': executionTimeMs,
      'timestamp': DateTime.now().millisecondsSinceEpoch,
    });
  }

  Future<List<Map<String, dynamic>>> getCommandLogs({int limit = 50}) async {
    final db = await database;
    return await db.query(
      'command_logs',
      orderBy: 'timestamp DESC',
      limit: limit,
    );
  }
}
