import 'package:path/path.dart';
import 'package:sqflite/sqflite.dart';

class DatabaseHelper {
  static const _dbName = 'hey_bro.db';
  static const _dbVersion = 1;

  DatabaseHelper._();
  static final DatabaseHelper instance = DatabaseHelper._();

  static Database? _database;

  Future<Database> get database async {
    if (_database != null) return _database!;
    _database = await _initDatabase();
    return _database!;
  }

  Future<Database> _initDatabase() async {
    final dbPath = await getDatabasesPath();
    final path = join(dbPath, _dbName);

    return await openDatabase(
      path,
      version: _dbVersion,
      onCreate: _onCreate,
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

    // Pre-populate sensible default aliases
    await db.insert('custom_aliases', {
      'trigger_phrase': 'torch',
      'target_type': 'action',
      'target_value': 'flashlightOn',
    });
    await db.insert('custom_aliases', {
      'trigger_phrase': 'silence phone',
      'target_type': 'action',
      'target_value': 'mute',
    });
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
