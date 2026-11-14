import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:hey_bro/core/database_helper.dart';
import 'package:hey_bro/ui/siri_orb_overlay.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  try {
    await dotenv.load(fileName: '.env');
  } catch (_) {
    try {
      await dotenv.load(fileName: '.env.example');
    } catch (_) {}
  }
  runApp(const HeyBroApp());
}

// ─────────────────────────────────────────────────────────────────────────────
//  App root
// ─────────────────────────────────────────────────────────────────────────────

class HeyBroApp extends StatelessWidget {
  const HeyBroApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Hey Bro',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF07070E),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF00E5CC),
          surface: Color(0xFF111118),
          surfaceContainerHighest: Color(0xFF1C1C26),
        ),
        fontFamily: 'Roboto',
        useMaterial3: true,
      ),
      home: const DashboardScreen(),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
//  State model
// ─────────────────────────────────────────────────────────────────────────────

enum AssistantState { idle, listening, processing, playing, disconnected }

extension AssistantStateX on AssistantState {
  String get label {
    switch (this) {
      case AssistantState.idle:         return 'Ready';
      case AssistantState.listening:    return 'Listening…';
      case AssistantState.processing:   return 'Thinking…';
      case AssistantState.playing:      return 'Speaking…';
      case AssistantState.disconnected: return 'Offline';
    }
  }

  Color get color {
    switch (this) {
      case AssistantState.idle:         return const Color(0xFF00E5CC);
      case AssistantState.listening:    return const Color(0xFF00E5CC);
      case AssistantState.processing:   return const Color(0xFFFFB300);
      case AssistantState.playing:      return const Color(0xFF2979FF);
      case AssistantState.disconnected: return const Color(0xFFEF5350);
    }
  }

  IconData get icon {
    switch (this) {
      case AssistantState.idle:         return Icons.mic_none_rounded;
      case AssistantState.listening:    return Icons.mic_rounded;
      case AssistantState.processing:   return Icons.psychology_rounded;
      case AssistantState.playing:      return Icons.volume_up_rounded;
      case AssistantState.disconnected: return Icons.mic_off_rounded;
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Dashboard
// ─────────────────────────────────────────────────────────────────────────────

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen>
    with TickerProviderStateMixin, WidgetsBindingObserver {

  static const _platform     = MethodChannel('com.example.hey_bro/assistant');
  static const _eventChannel = EventChannel('com.example.hey_bro/events');

  // ── Service State ──────────────────────────────────────────────────────────
  bool           _serviceRunning = false;
  AssistantState _assistantState = AssistantState.idle;
  String         _activeEngine   = 'HeyBroPhraseWakeEngine';
  bool           _autostart      = false;

  // ── Gemini Live Endpoint ───────────────────────────────────────────────────
  static const _geminiLiveWs =
      'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent';
  String _backendWs   = _geminiLiveWs;
  bool   _wsConnected = false;

  // ── Permissions ────────────────────────────────────────────────────────────
  bool _hasMic        = false;
  bool _hasCamera     = false;
  bool _hasA11y       = false;
  bool _hasOverlay    = false;
  bool _hasBattery    = false;
  bool _hasPhone      = false;
  bool _hasCallLog    = false;
  bool _hasContacts   = false;
  bool _hasReadSms    = false;
  bool _hasSendSms    = false;
  bool _hasNotificationAccess = false;

  // ── Conversation & Shortcuts ───────────────────────────────────────────────
  final List<_Exchange> _history = [];
  List<Map<String, dynamic>> _customShortcuts = [];
  String? _pendingTranscript;

  // ── Animations ────────────────────────────────────────────────────────────
  late final AnimationController _pulseCtrl;
  late final AnimationController _waveCtrl;
  late final Animation<double>   _pulseAnim;

  // ── Timers / Subscriptions ─────────────────────────────────────────────────
  Timer?              _permissionTimer;
  StreamSubscription? _eventSub;
  String?             _lastRawEvent;
  bool                _showSiriOverlay = false;
  String              _screenContext   = '';
  Timer?              _siriDismissTimer;

  @override
  void initState() {
    super.initState();

    _pulseCtrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200));
    _pulseAnim = Tween<double>(begin: 1.0, end: 1.12).animate(
      CurvedAnimation(parent: _pulseCtrl, curve: Curves.easeInOut),
    );

    _waveCtrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1800));

    _checkPermissions();
    WidgetsBinding.instance.addObserver(this);
    _permissionTimer = Timer.periodic(const Duration(seconds: 5), (_) => _checkPermissions());
    _loadBackendConfig();
    _loadHistoryFromDb();
    _syncAliasesToNative();
    _checkLaunchIntent();

    _eventSub = _eventChannel.receiveBroadcastStream().listen(
      _onNativeEvent,
      onError: (e) => debugPrint('EventChannel error: $e'),
    );
  }

  void _updateTickers() {
    if (!mounted) return;
    final shouldAnimate = _assistantState == AssistantState.listening || _assistantState == AssistantState.playing;
    if (shouldAnimate) {
      if (!_pulseCtrl.isAnimating) _pulseCtrl.repeat(reverse: true);
      if (!_waveCtrl.isAnimating) _waveCtrl.repeat();
    } else {
      if (_pulseCtrl.isAnimating || _pulseCtrl.value != 0) { _pulseCtrl.stop(); _pulseCtrl.reset(); }
      if (_waveCtrl.isAnimating || _waveCtrl.value != 0) { _waveCtrl.stop(); _waveCtrl.reset(); }
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pulseCtrl.dispose();
    _waveCtrl.dispose();
    _permissionTimer?.cancel();
    _siriDismissTimer?.cancel();
    _eventSub?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkPermissions();
    }
  }

  Future<void> _checkLaunchIntent() async {
    try {
      final type = await _platform.invokeMethod<String>('getLaunchIntentType');
      if (type == 'assist') {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          _openSiriOverlay();
        });
      }
    } catch (e) {
      debugPrint('getLaunchIntentType error: $e');
    }
  }

  Future<void> _loadHistoryFromDb() async {
    try {
      final rows = await DatabaseHelper.instance.getConversations(limit: 40);
      if (!mounted) return;
      final loaded = <_Exchange>[];
      for (int i = 0; i < rows.length; i++) {
        final r = rows[i];
        final role = r['role'] as String;
        final msg = r['message'] as String;
        if (role == 'assistant') {
          String userMsg = '';
          if (i + 1 < rows.length && rows[i + 1]['role'] == 'user') {
            userMsg = rows[i + 1]['message'] as String;
            i++;
          }
          loaded.add(_Exchange(user: userMsg, assistant: msg));
        } else if (role == 'user') {
          loaded.add(_Exchange(user: msg, assistant: ''));
        }
      }
      setState(() {
        _history.clear();
        _history.addAll(loaded);
      });
    } catch (e) {
      debugPrint('Error loading DB history: $e');
    }
  }

  void _onNativeEvent(dynamic raw) {
    if (raw == _lastRawEvent) return;
    _lastRawEvent = raw as String?;

    try {
      final data = json.decode(raw as String) as Map<String, dynamic>;
      if (data['type'] == 'permissions_updated') {
        _checkPermissions();
        return;
      }

      final stateStr = data['state'] as String? ?? '';
      final transcript = data['transcript'] as String?;
      final response = data['response'] as String? ?? '';
      final engine = data['activeEngine'] as String?;

      AssistantState newState = _assistantState;
      bool newWsConnected = _wsConnected;

      switch (stateStr) {
        case 'LISTENING':    newState = AssistantState.listening; break;
        case 'PROCESSING':   newState = AssistantState.processing; break;
        case 'PLAYING':      newState = AssistantState.playing; break;
        case 'IDLE':         newState = AssistantState.idle; break;
        case 'CONNECTED':    newWsConnected = true; newState = AssistantState.idle; break;
        case 'DISCONNECTED': newWsConnected = false; newState = AssistantState.disconnected; break;
      }

      final hasStateChanged = newState != _assistantState;
      final hasWsChanged = newWsConnected != _wsConnected;
      final hasEngineChanged = engine != null && engine != _activeEngine;

      bool hasTranscriptChanged = false;
      if (newState == AssistantState.listening || newState == AssistantState.idle) {
        hasTranscriptChanged = _pendingTranscript != null;
      } else if (transcript != null) {
        hasTranscriptChanged = transcript != _pendingTranscript;
      }

      if (hasStateChanged || hasWsChanged || hasEngineChanged || hasTranscriptChanged) {
        if (newState == AssistantState.listening) {
          _siriDismissTimer?.cancel();
          _siriDismissTimer = null;
          _showSiriOverlay = true;
        } else if (newState == AssistantState.idle) {
          if (_showSiriOverlay && _siriDismissTimer == null) {
            _siriDismissTimer = Timer(const Duration(milliseconds: 1400), () {
              if (mounted && _assistantState == AssistantState.idle) {
                setState(() {
                  _showSiriOverlay = false;
                });
              }
            });
          }
        }

        setState(() {
          _assistantState = newState;
          _wsConnected = newWsConnected;
          if (engine != null) _activeEngine = engine;

          if (newState == AssistantState.listening || newState == AssistantState.idle) {
            _pendingTranscript = null;
          } else if (transcript != null) {
            _pendingTranscript = transcript;
          }

          if (stateStr == 'PLAYING') {
            final finalTranscript = transcript ?? _pendingTranscript ?? '';
            if (response.isNotEmpty || finalTranscript.isNotEmpty) {
              _history.insert(0, _Exchange(user: finalTranscript, assistant: response));
              if (finalTranscript.isNotEmpty) {
                DatabaseHelper.instance.insertConversation('user', finalTranscript);
              }
              if (response.isNotEmpty) {
                DatabaseHelper.instance.insertConversation('assistant', response);
              }
            }
            _pendingTranscript = null;
          }
        });

        _updateTickers();
      }
    } catch (e) {
      debugPrint('JSON parse error on event: $e');
    }
  }

  Future<void> _checkPermissions() async {
    try {
      final res = await _platform.invokeMethod('checkPermissions');
      final engine = await _platform.invokeMethod<String>('getActiveWakeEngine') ?? 'None';
      final autostart = await _platform.invokeMethod<bool>('getAutostartEnabled') ?? false;

      if (!mounted) return;

      if (res is Map) {
        final perms = Map<String, dynamic>.from(res);
        final newHasMic = perms['mic'] == true || perms['microphone'] == true;
        final newHasCamera = perms['camera'] == true;
        final newHasA11y = perms['accessibility'] == true;
        final newHasOverlay = perms['overlay'] == true;
        final newHasBattery = perms['battery'] == true;
        final newHasPhone = perms['phone'] == true;
        final newHasCallLog = perms['callLog'] == true;
        final newHasContacts = perms['contacts'] == true;
        final newHasReadSms = perms['readSms'] == true;
        final newHasSendSms = perms['sendSms'] == true;
        final newHasNotificationAccess = perms['notificationAccess'] == true;

        if (newHasMic != _hasMic || newHasCamera != _hasCamera ||
            newHasA11y != _hasA11y || newHasOverlay != _hasOverlay ||
            newHasBattery != _hasBattery || newHasPhone != _hasPhone ||
            newHasCallLog != _hasCallLog || newHasContacts != _hasContacts ||
            newHasReadSms != _hasReadSms || newHasSendSms != _hasSendSms ||
            newHasNotificationAccess != _hasNotificationAccess || engine != _activeEngine ||
            autostart != _autostart) {
          setState(() {
            _hasMic     = newHasMic;
            _hasCamera  = newHasCamera;
            _hasA11y    = newHasA11y;
            _hasOverlay = newHasOverlay;
            _hasBattery = newHasBattery;
            _hasPhone = newHasPhone;
            _hasCallLog = newHasCallLog;
            _hasContacts = newHasContacts;
            _hasReadSms = newHasReadSms;
            _hasSendSms = newHasSendSms;
            _hasNotificationAccess = newHasNotificationAccess;
            _activeEngine = engine;
            _autostart    = autostart;
          });
        }
      }
    } catch (e) {
      debugPrint('checkPermissions error: $e');
    }
  }

  bool get _allPermissionsGranted => _hasMic && _hasA11y && _hasOverlay;

  Future<void> _toggleService() async {
    if (_serviceRunning) {
      try {
        await _platform.invokeMethod('triggerAssistant');
      } on PlatformException catch (e) {
        _showSnack('Error: ${e.message}');
      }
      return;
    }

    if (!_allPermissionsGranted) {
      _showSnack('Grant permissions first.');
      return;
    }

    try {
      final started = await _platform.invokeMethod<bool>('startAssistantService') ?? false;
      if (!started) {
        _showSnack('Allow notifications to start Hey Bro.');
        return;
      }
      setState(() {
        _serviceRunning = true;
        _assistantState = AssistantState.idle;
      });
      _updateTickers();
    } on PlatformException catch (e) {
      _showSnack('Failed to start: ${e.message}');
    }
  }

  Future<void> _stopService() async {
    if (!_serviceRunning) return;
    try {
      await _platform.invokeMethod('stopAssistantService');
      setState(() {
        _serviceRunning = false;
        _assistantState = AssistantState.idle;
      });
      _updateTickers();
    } catch (e) {
      debugPrint('stopAssistantService error: $e');
    }
  }

  Future<void> _loadBackendConfig() async {
    try {
      final url = await _safeInvoke<String>('getBackendWs') ?? _geminiLiveWs;
      if (mounted) setState(() => _backendWs = url);
    } catch (e) {
      debugPrint('getBackendWs error: $e');
    }
  }

  Future<void> _openSiriOverlay() async {
    String screenCtx = '';
    try {
      screenCtx = await _platform.invokeMethod<String>('getScreenContext') ?? '';
    } catch (_) {}

    if (!mounted) return;

    _siriDismissTimer?.cancel();
    _siriDismissTimer = null;

    setState(() {
      _screenContext = screenCtx;
      _showSiriOverlay = true;
    });

    if (_assistantState == AssistantState.idle) {
      try {
        await _platform.invokeMethod('triggerAssistant');
      } catch (e) {
        debugPrint('Trigger error: $e');
      }
    }
  }

  SiriState _mapSiriState(AssistantState state) {
    switch (state) {
      case AssistantState.listening: return SiriState.listening;
      case AssistantState.processing: return SiriState.processing;
      case AssistantState.playing: return SiriState.speaking;
      case AssistantState.idle:
      case AssistantState.disconnected: return SiriState.idle;
    }
  }

  Future<T?> _safeInvoke<T>(String method, [dynamic arguments]) async {
    try {
      return await _platform.invokeMethod<T>(method, arguments);
    } catch (e) {
      debugPrint('Platform invoke "$method" error: $e');
      return null;
    }
  }

  Future<void> _syncAliasesToNative() async {
    try {
      final list = await DatabaseHelper.instance.getAliases();
      if (mounted) {
        setState(() {
          _customShortcuts = list;
        });
      }
      final mapped = list.map((item) => {
        'phrase': (item['trigger_phrase'] as String?) ?? '',
        'action': (item['target_value'] as String?) ?? '',
      }).toList();
      await _safeInvoke('syncCustomAliases', {'aliases': mapped});
    } catch (e) {
      debugPrint('Error syncing aliases to native: $e');
    }
  }

  Future<void> _openApiKeyDialog() async {
    String currentGeminiKey = '';
    String currentGroqKey = '';
    try {
      currentGeminiKey = await _safeInvoke<String>('getGeminiApiKey') ?? '';
      currentGroqKey = await _safeInvoke<String>('getGroqApiKey') ?? '';
    } catch (_) {}

    if (currentGeminiKey.trim().isEmpty) {
      final envKey = dotenv.env['GEMINI_API_KEY'] ?? '';
      if (envKey.trim().isNotEmpty && !envKey.contains('your_gemini')) {
        currentGeminiKey = envKey;
      }
    }
    if (currentGroqKey.trim().isEmpty) {
      final envKey = dotenv.env['GROQ_API_KEY'] ?? '';
      if (envKey.trim().isNotEmpty && !envKey.contains('your_groq')) {
        currentGroqKey = envKey;
      }
    }

    if (!mounted) return;
    final geminiCtrl = TextEditingController(text: currentGeminiKey);
    final groqCtrl = TextEditingController(text: currentGroqKey);

    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF141520),
        title: const Text('API & Cloud Fallback Keys', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '1. Google Gemini (Primary Voice Engine)',
                style: TextStyle(color: Color(0xFF00E5CC), fontSize: 13, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              const Text(
                'Live bidirectional audio (gemini-3.8-live) + zero-server REST fallback.',
                style: TextStyle(color: Colors.white54, fontSize: 11),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: geminiCtrl,
                obscureText: true,
                style: const TextStyle(color: Colors.white, fontSize: 13),
                decoration: InputDecoration(
                  filled: true,
                  fillColor: Colors.white.withValues(alpha: 0.08),
                  hintText: 'Enter your Gemini API key',
                  hintStyle: const TextStyle(color: Colors.white24),
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide.none),
                ),
              ),
              const SizedBox(height: 18),
              const Text(
                '2. Groq Cloud (Ultra-Fast Edge Fallback)',
                style: TextStyle(color: Color(0xFFFF9500), fontSize: 13, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              const Text(
                'Direct edge reasoning (<200ms) with Llama 3.3 70B & 3.1 8B when Gemini has network jitter.',
                style: TextStyle(color: Colors.white54, fontSize: 11),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: groqCtrl,
                obscureText: true,
                style: const TextStyle(color: Colors.white, fontSize: 13),
                decoration: InputDecoration(
                  filled: true,
                  fillColor: Colors.white.withValues(alpha: 0.08),
                  hintText: 'Enter your Groq API key',
                  hintStyle: const TextStyle(color: Colors.white24),
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide.none),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () async {
              final newGemini = geminiCtrl.text.trim();
              final newGroq = groqCtrl.text.trim();
              if (newGemini.isNotEmpty) {
                await _safeInvoke('setGeminiApiKey', {'apiKey': newGemini});
              }
              if (newGroq.isNotEmpty) {
                await _safeInvoke('setGroqApiKey', {'apiKey': newGroq});
              }
              if (mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('API Keys and Groq configuration updated')),
                );
              }
              if (ctx.mounted) Navigator.pop(ctx);
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  }

  Future<void> _openAliasesDialog() async {
    final aliases = await DatabaseHelper.instance.getAliases();
    debugPrint('IN _openAliasesDialog: mounted=$mounted, aliases=${aliases.length}');
    if (!mounted) return;

    final phraseCtrl = TextEditingController();
    final actionCtrl = TextEditingController();
    String? selectedAction;
    bool filterOnlyCustom = false;
    bool customInputMode = false;

    const commonPresets = [
      {'label': '🔦 Torch On', 'phrase': 'turn on torch', 'action': 'flashlightOn'},
      {'label': '🔦 Torch Off', 'phrase': 'turn off torch', 'action': 'flashlightOff'},
      {'label': '🤳 Selfie', 'phrase': 'take a selfie', 'action': 'takeSelfie'},
      {'label': '📸 Screenshot', 'phrase': 'take screenshot', 'action': 'screenshot'},
      {'label': '📷 Open Camera', 'phrase': 'open camera', 'action': 'openCamera'},
      {'label': '🔇 Mute', 'phrase': 'silence phone', 'action': 'mute'},
      {'label': '🔊 Vol Up', 'phrase': 'volume up', 'action': 'volumeUp'},
      {'label': '🔉 Vol Down', 'phrase': 'volume down', 'action': 'volumeDown'},
      {'label': '🎵 Play Music', 'phrase': 'play music', 'action': 'mediaPlayPause'},
      {'label': '⏭️ Next Song', 'phrase': 'next song', 'action': 'mediaNext'},
      {'label': '💬 WhatsApp', 'phrase': 'open whatsapp', 'action': 'openApp:com.whatsapp'},
      {'label': '▶️ YouTube', 'phrase': 'open youtube', 'action': 'openApp:com.google.android.youtube'},
      {'label': '🎵 Spotify', 'phrase': 'open spotify', 'action': 'openApp:com.spotify.music'},
      {'label': '🌐 Chrome', 'phrase': 'open chrome', 'action': 'openApp:com.android.chrome'},
      {'label': '📸 Instagram', 'phrase': 'open instagram', 'action': 'openApp:com.instagram.android'},
      {'label': '✈️ Telegram', 'phrase': 'open telegram', 'action': 'openApp:org.telegram.messenger'},
      {'label': '🔢 Calculator', 'phrase': 'open calculator', 'action': 'openApp:com.google.android.calculator'},
      {'label': '📅 Calendar', 'phrase': 'open calendar', 'action': 'openApp:com.google.android.calendar'},
      {'label': '⏰ Clock', 'phrase': 'open clock', 'action': 'openApp:com.google.android.deskclock'},
      {'label': '📧 Gmail', 'phrase': 'open gmail', 'action': 'openApp:com.google.android.gm'},
      {'label': '🖼️ Photos', 'phrase': 'open gallery', 'action': 'openApp:com.google.android.apps.photos'},
      {'label': '📶 WiFi', 'phrase': 'turn on wifi', 'action': 'toggleWifi'},
      {'label': '📡 Bluetooth', 'phrase': 'turn on bluetooth', 'action': 'toggleBluetooth'},
      {'label': '📶 Hotspot', 'phrase': 'turn on hotspot', 'action': 'toggleHotspot'},
      {'label': '✈️ Airplane', 'phrase': 'airplane mode', 'action': 'toggleAirplaneMode'},
      {'label': '🔒 Lock Screen', 'phrase': 'lock screen', 'action': 'lockScreen'},
      {'label': '🌙 DND', 'phrase': 'do not disturb', 'action': 'doNotDisturb'},
      {'label': '🔋 Battery Saver', 'phrase': 'battery saver', 'action': 'batterySaver'},
      {'label': '🗺️ Maps', 'phrase': 'open maps', 'action': 'openMaps'},
      {'label': '⚙️ Settings', 'phrase': 'open settings', 'action': 'openSettings'},
    ];

    const actionOptions = {
      'Turn On Flashlight': 'flashlightOn',
      'Turn Off Flashlight': 'flashlightOff',
      'Take Selfie': 'takeSelfie',
      'Take Photo': 'takePhoto',
      'Open Camera': 'openCamera',
      'Take Screenshot': 'screenshot',
      'Mute / Silence Phone': 'mute',
      'Volume Up': 'volumeUp',
      'Volume Down': 'volumeDown',
      'Play / Pause Music': 'mediaPlayPause',
      'Next Track': 'mediaNext',
      'Previous Track': 'mediaPrevious',
      'Toggle Wi-Fi': 'toggleWifi',
      'Toggle Bluetooth': 'toggleBluetooth',
      'Toggle Hotspot': 'toggleHotspot',
      'Toggle Airplane Mode': 'toggleAirplaneMode',
      'Lock Screen': 'lockScreen',
      'Battery Saver': 'batterySaver',
      'Do Not Disturb': 'doNotDisturb',
      'Show Notifications': 'notifications',
      'Quick Settings': 'quickSettings',
      'Recent Apps': 'recents',
      'Go Home': 'globalHome',
      'Go Back': 'globalBack',
      'Open WhatsApp': 'openApp:com.whatsapp',
      'Open YouTube': 'openApp:com.google.android.youtube',
      'Open Spotify': 'openApp:com.spotify.music',
      'Open Google Maps': 'openMaps',
      'Open Settings': 'openSettings',
      'Open Chrome': 'openApp:com.android.chrome',
      'Open Instagram': 'openApp:com.instagram.android',
      'Open Telegram': 'openApp:org.telegram.messenger',
      'Open Calculator': 'openApp:com.google.android.calculator',
      'Open Calendar': 'openApp:com.google.android.calendar',
      'Open Clock': 'openApp:com.google.android.deskclock',
      'Open Gmail': 'openApp:com.google.android.gm',
      'Open Photos': 'openApp:com.google.android.apps.photos',
      'Custom Action / App Package...': '__CUSTOM__',
    };

    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF12131D),
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setModalState) {
            final customCount = aliases.where((a) => a['target_type'] == 'custom').length;
            final displayedAliases = filterOnlyCustom
                ? aliases.where((a) => a['target_type'] == 'custom').toList()
                : aliases;

            return Padding(
              padding: EdgeInsets.only(
                left: 20, right: 20, top: 20,
                bottom: MediaQuery.of(ctx).viewInsets.bottom + 20,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text('Voice Shortcuts (SQLite)', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
                      IconButton(icon: const Icon(Icons.close, color: Colors.white54), onPressed: () => Navigator.pop(ctx)),
                    ],
                  ),
                  const Text('Custom shortcuts execute locally in <50ms without network round-trip', style: TextStyle(color: Colors.white54, fontSize: 12)),
                  const SizedBox(height: 12),

                  // Quick Preset Chips
                  SizedBox(
                    height: 36,
                    child: ListView.separated(
                      scrollDirection: Axis.horizontal,
                      itemCount: commonPresets.length,
                      separatorBuilder: (_, i) => const SizedBox(width: 8),
                      itemBuilder: (_, idx) {
                        final p = commonPresets[idx];
                        return ActionChip(
                          label: Text(p['label']!, style: const TextStyle(fontSize: 11, color: Colors.white)),
                          backgroundColor: Colors.white.withValues(alpha: 0.08),
                          side: BorderSide.none,
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                          onPressed: () {
                            setModalState(() {
                              phraseCtrl.text = p['phrase']!;
                              actionCtrl.text = p['action']!;
                              selectedAction = p['action'];
                              customInputMode = false;
                            });
                          },
                        );
                      },
                    ),
                  ),
                  const SizedBox(height: 12),

                  // Input fields
                  Row(
                    children: [
                      Expanded(
                        flex: 5,
                        child: TextField(
                          controller: phraseCtrl,
                          style: const TextStyle(color: Colors.white, fontSize: 13),
                          decoration: InputDecoration(
                            hintText: "Phrase (e.g. 'my songs')",
                            hintStyle: const TextStyle(color: Colors.white30, fontSize: 12),
                            filled: true,
                            fillColor: Colors.white.withValues(alpha: 0.06),
                            border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide.none),
                            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        flex: 6,
                        child: customInputMode
                            ? TextField(
                                controller: actionCtrl,
                                style: const TextStyle(color: Colors.white, fontSize: 13),
                                decoration: InputDecoration(
                                  hintText: "openApp:pkg or action",
                                  hintStyle: const TextStyle(color: Colors.white30, fontSize: 12),
                                  filled: true,
                                  fillColor: Colors.white.withValues(alpha: 0.06),
                                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide.none),
                                  contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                                  suffixIcon: IconButton(
                                    icon: const Icon(Icons.arrow_drop_down, color: Colors.white54),
                                    onPressed: () => setModalState(() => customInputMode = false),
                                  ),
                                ),
                              )
                            : DropdownButtonFormField<String>(
                                initialValue: actionOptions.containsValue(selectedAction) ? selectedAction : null,
                                dropdownColor: const Color(0xFF1B1C2A),
                                isExpanded: true,
                                style: const TextStyle(color: Colors.white, fontSize: 13),
                                decoration: InputDecoration(
                                  hintText: 'Select action',
                                  hintStyle: const TextStyle(color: Colors.white30, fontSize: 12),
                                  filled: true,
                                  fillColor: Colors.white.withValues(alpha: 0.06),
                                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide.none),
                                  contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                                ),
                                items: actionOptions.entries.map((e) => DropdownMenuItem(
                                  value: e.value,
                                  child: Text(e.key, style: const TextStyle(fontSize: 12), overflow: TextOverflow.ellipsis),
                                )).toList(),
                                onChanged: (val) {
                                  if (val == '__CUSTOM__') {
                                    setModalState(() {
                                      customInputMode = true;
                                      selectedAction = null;
                                      actionCtrl.clear();
                                    });
                                  } else if (val != null) {
                                    setModalState(() {
                                      selectedAction = val;
                                      actionCtrl.text = val;
                                      customInputMode = false;
                                    });
                                  }
                                },
                              ),
                      ),
                      const SizedBox(width: 8),
                      IconButton.filled(
                        icon: const Icon(Icons.add),
                        style: IconButton.styleFrom(
                          backgroundColor: const Color(0xFF00E5CC),
                          foregroundColor: Colors.black,
                        ),
                        onPressed: () async {
                          final p = phraseCtrl.text.trim();
                          final a = actionCtrl.text.trim().isNotEmpty ? actionCtrl.text.trim() : selectedAction;
                          if (p.isEmpty) {
                            if (mounted) _showSnack('Please enter a spoken phrase');
                            return;
                          }
                          if (a == null || a.isEmpty || a == '__CUSTOM__') {
                            if (mounted) _showSnack('Please select or type an action');
                            return;
                          }

                          await DatabaseHelper.instance.insertAlias(p, 'custom', a);
                          await _safeInvoke('addCustomAlias', {'phrase': p, 'action': a});
                          phraseCtrl.clear();
                          actionCtrl.clear();
                          setModalState(() {
                            selectedAction = null;
                            customInputMode = false;
                          });
                          final updated = await DatabaseHelper.instance.getAliases();
                          setModalState(() {
                            aliases.clear();
                            aliases.addAll(updated);
                          });
                          await _syncAliasesToNative();
                          if (mounted) _showSnack('Added custom shortcut: "$p" → "$a"');
                        },
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),

                  // Filter / Tabs
                  Row(
                    children: [
                      FilterChip(
                        label: Text('All (${aliases.length})', style: const TextStyle(fontSize: 11)),
                        selected: !filterOnlyCustom,
                        onSelected: (_) => setModalState(() => filterOnlyCustom = false),
                        selectedColor: const Color(0xFF00E5CC).withValues(alpha: 0.2),
                        checkmarkColor: const Color(0xFF00E5CC),
                        side: BorderSide(color: !filterOnlyCustom ? const Color(0xFF00E5CC) : Colors.white24),
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        label: Text('⭐ My Custom ($customCount)', style: TextStyle(fontSize: 11, color: customCount > 0 ? const Color(0xFF00E5CC) : Colors.white70)),
                        selected: filterOnlyCustom,
                        onSelected: (_) => setModalState(() => filterOnlyCustom = true),
                        selectedColor: const Color(0xFF00E5CC).withValues(alpha: 0.2),
                        checkmarkColor: const Color(0xFF00E5CC),
                        side: BorderSide(color: filterOnlyCustom ? const Color(0xFF00E5CC) : Colors.white24),
                      ),
                      const Spacer(),
                      TextButton.icon(
                        icon: const Icon(Icons.sync_rounded, size: 14),
                        label: const Text('Sync Native', style: TextStyle(fontSize: 11)),
                        onPressed: () async {
                          await _syncAliasesToNative();
                          if (mounted) _showSnack('Synchronized ${aliases.length} shortcuts with native engine');
                        },
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),

                  ConstrainedBox(
                    constraints: BoxConstraints(
                      maxHeight: (MediaQuery.of(ctx).size.height * 0.35).clamp(200.0, 360.0),
                    ),
                    child: displayedAliases.isEmpty
                        ? Center(
                            child: Padding(
                              padding: const EdgeInsets.symmetric(vertical: 24),
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  const Icon(Icons.flash_off_rounded, color: Colors.white24, size: 36),
                                  const SizedBox(height: 8),
                                  Text(
                                    filterOnlyCustom
                                        ? 'No custom shortcuts created yet.'
                                        : 'No shortcuts available.',
                                    style: const TextStyle(color: Colors.white60, fontSize: 13),
                                  ),
                                  const SizedBox(height: 4),
                                  const Text(
                                    'Add one using the inputs above to run actions in <50ms!',
                                    style: TextStyle(color: Colors.white30, fontSize: 11),
                                  ),
                                ],
                              ),
                            ),
                          )
                        : ListView.builder(
                            shrinkWrap: true,
                            itemCount: displayedAliases.length,
                            itemBuilder: (ctx, i) {
                              final item = displayedAliases[i];
                              final isCustom = item['target_type'] == 'custom';
                              final phrase = (item['trigger_phrase'] as String?) ?? '';
                              final target = (item['target_value'] as String?) ?? '';

                              return ListTile(
                                dense: true,
                                contentPadding: EdgeInsets.zero,
                                leading: Icon(
                                  isCustom ? Icons.stars_rounded : Icons.bolt,
                                  color: isCustom ? const Color(0xFF00E5CC) : Colors.white38,
                                  size: isCustom ? 20 : 18,
                                ),
                                title: Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        phrase,
                                        style: TextStyle(
                                          color: isCustom ? Colors.white : Colors.white70,
                                          fontWeight: isCustom ? FontWeight.bold : FontWeight.w500,
                                        ),
                                      ),
                                    ),
                                    if (isCustom)
                                      Container(
                                        margin: const EdgeInsets.only(right: 6),
                                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                        decoration: BoxDecoration(
                                          color: const Color(0xFF00E5CC).withValues(alpha: 0.15),
                                          borderRadius: BorderRadius.circular(4),
                                          border: Border.all(color: const Color(0xFF00E5CC).withValues(alpha: 0.4)),
                                        ),
                                        child: const Text(
                                          'CUSTOM',
                                          style: TextStyle(color: Color(0xFF00E5CC), fontSize: 9, fontWeight: FontWeight.bold),
                                        ),
                                      ),
                                  ],
                                ),
                                subtitle: Text(
                                  "→ $target",
                                  style: TextStyle(
                                    color: isCustom ? const Color(0xFF00E5CC).withValues(alpha: 0.8) : Colors.white54,
                                    fontSize: 12,
                                  ),
                                ),
                                trailing: IconButton(
                                  icon: Icon(
                                    Icons.delete_outline,
                                    size: 18,
                                    color: isCustom ? Colors.redAccent.shade100 : Colors.white30,
                                  ),
                                  tooltip: isCustom ? 'Delete custom shortcut' : 'Remove shortcut',
                                  onPressed: () async {
                                    final id = item['id'] as int;
                                    await DatabaseHelper.instance.deleteAlias(id);
                                    await _safeInvoke('deleteCustomAlias', {'phrase': phrase});
                                    final updated = await DatabaseHelper.instance.getAliases();
                                    setModalState(() {
                                      aliases.clear();
                                      aliases.addAll(updated);
                                    });
                                    await _syncAliasesToNative();
                                    if (mounted) _showSnack('Removed shortcut "$phrase"');
                                  },
                                ),
                              );
                            },
                          ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  void _showSnack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 2)));
  }

  void _showMicSettingsSnack() {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text('Microphone permission required. Open settings?'),
        action: SnackBarAction(
          label: 'Settings',
          onPressed: () => _safeInvoke('openAppSettings'),
        ),
        duration: const Duration(seconds: 4),
      ),
    );
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  UI Build
  // ─────────────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF07070E),
      body: Stack(
        children: [
          SafeArea(
            child: CustomScrollView(
          physics: const BouncingScrollPhysics(),
          slivers: [
            SliverAppBar(
              backgroundColor: Colors.transparent,
              elevation: 0,
              pinned: false,
              floating: true,
              title: Row(
                children: [
                  _GlowDot(active: _serviceRunning && _assistantState != AssistantState.idle),
                  const SizedBox(width: 10),
                  const Text('Hey Bro', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 22, color: Colors.white)),
                ],
              ),
              actions: [
                IconButton(
                  tooltip: 'Open Siri Floating Orb',
                  icon: const Icon(Icons.assistant_outlined, color: Color(0xFF00E5CC)),
                  onPressed: _openSiriOverlay,
                ),
                IconButton(
                  tooltip: 'Custom Shortcuts',
                  icon: const Icon(Icons.bolt_rounded, color: Colors.amber),
                  onPressed: _openAliasesDialog,
                ),
                IconButton(
                  tooltip: 'Gemini API Key',
                  icon: const Icon(Icons.vpn_key_outlined),
                  onPressed: _openApiKeyDialog,
                ),
                if (_serviceRunning)
                  IconButton(
                    tooltip: 'Stop Background Service',
                    icon: const Icon(Icons.stop_circle_outlined, color: Color(0xFFEF5350)),
                    onPressed: _stopService,
                  ),
              ],
            ),

            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              sliver: SliverList(
                delegate: SliverChildListDelegate([
                  const SizedBox(height: 4),
                  _StateChip(state: _assistantState),
                  const SizedBox(height: 28),

                  Center(child: RepaintBoundary(child: _buildOrb())),

                  const SizedBox(height: 12),
                  Center(
                    child: Text(
                      _serviceRunning ? 'Say "Hey Bro" or tap orb' : 'Tap orb to start',
                      style: TextStyle(fontSize: 12, color: Colors.white.withValues(alpha: 0.35)),
                    ),
                  ),

                  if (_pendingTranscript != null && _pendingTranscript!.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Center(
                      child: Text(
                        _pendingTranscript!,
                        textAlign: TextAlign.center,
                        style: TextStyle(fontSize: 14, color: Colors.white.withValues(alpha: 0.85), fontStyle: FontStyle.italic),
                      ),
                    ),
                  ],

                  if (_serviceRunning) ...[
                    const SizedBox(height: 6),
                    Center(child: Text(_activeEngine, style: const TextStyle(fontSize: 11, color: Colors.white24))),
                    Center(
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(_wsConnected ? Icons.cloud_done_rounded : Icons.cloud_off_rounded, size: 12, color: _wsConnected ? const Color(0xFF00E5CC) : Colors.white24),
                          const SizedBox(width: 4),
                          Text(_backendWs.contains('googleapis') ? 'Gemini 3.8 Live' : 'Custom Backend', style: const TextStyle(fontSize: 11, color: Colors.white24)),
                        ],
                      ),
                    ),
                  ],

                  const SizedBox(height: 32),

                  if (!_serviceRunning) ...[
                    const _SectionHeader(title: 'What I can do', icon: Icons.auto_awesome_rounded),
                    const SizedBox(height: 8),
                    const _CommandGrid(),
                    const SizedBox(height: 28),
                  ],

                  if (_history.isNotEmpty) ...[
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const _SectionHeader(title: 'Memory (SQLite)', icon: Icons.history_rounded),
                        TextButton(
                          onPressed: () async {
                            await DatabaseHelper.instance.clearConversations();
                            setState(() => _history.clear());
                          },
                          child: const Text('Clear', style: TextStyle(fontSize: 12, color: Colors.white38)),
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    ..._history.map((e) => _ExchangeCard(exchange: e)),
                    const SizedBox(height: 28),
                  ],

                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Expanded(
                        child: _SectionHeader(title: 'Voice Shortcuts', icon: Icons.flash_on_rounded),
                      ),
                      TextButton.icon(
                        icon: const Icon(Icons.tune_rounded, size: 14, color: Color(0xFF00E5CC)),
                        label: const Text('Manage', style: TextStyle(fontSize: 12, color: Color(0xFF00E5CC))),
                        onPressed: _openAliasesDialog,
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  _buildShortcutsDashboard(),
                  const SizedBox(height: 28),

                  const _SectionHeader(title: 'Permissions', icon: Icons.security_rounded),
                  const SizedBox(height: 10),

                  _PermTile(
                    title: 'Microphone',
                    icon: Icons.mic_rounded,
                    granted: _hasMic,
                    onFix: () async {
                      final granted = await _safeInvoke<bool>('requestMicPermission') ?? false;
                      await _checkPermissions();
                      if (!granted && !_hasMic) {
                        _showMicSettingsSnack();
                      }
                    },
                  ),
                  _PermTile(title: 'Accessibility Service', icon: Icons.accessibility_new_rounded, granted: _hasA11y, onFix: () => _safeInvoke('openAccessibilitySettings')),
                  _PermTile(title: 'Draw Over Other Apps', icon: Icons.layers_rounded, granted: _hasOverlay, onFix: () => _safeInvoke('requestOverlayPermission')),
                  _PermTile(title: 'Notifications Access', icon: Icons.notifications_rounded, granted: _hasNotificationAccess, onFix: () => _safeInvoke('openNotificationListenerSettings')),
                  _PermTile(title: 'Battery Optimization', icon: Icons.battery_charging_full_rounded, granted: _hasBattery, onFix: () => _safeInvoke('requestBatteryExemption')),

                  const SizedBox(height: 40),
                ]),
              ),
            ),
          ],
        ),
      ),
      if (_showSiriOverlay)
        Positioned.fill(
          child: SiriOrbOverlay(
            state: _mapSiriState(_assistantState),
            transcript: _pendingTranscript ?? '',
            responseText: _history.isNotEmpty ? _history.first.assistant : '',
            screenContext: _screenContext,
            onMicTap: () async {
              try {
                await _platform.invokeMethod('triggerAssistant');
              } catch (e) {
                debugPrint('Trigger error: $e');
              }
            },
            onDismiss: () {
              _siriDismissTimer?.cancel();
              _siriDismissTimer = null;
              setState(() {
                _showSiriOverlay = false;
              });
            },
            onInterrupt: () async {
              try {
                await _platform.invokeMethod('triggerAssistant');
              } catch (_) {}
            },
          ),
        ),
    ],
  ),
);
  }

  Widget _buildShortcutsDashboard() {
    final customList = _customShortcuts.where((a) => a['target_type'] == 'custom').toList();
    if (customList.isNotEmpty) {
      return Column(
        children: customList.map((item) {
          final phrase = (item['trigger_phrase'] as String?) ?? '';
          final action = (item['target_value'] as String?) ?? '';
          final id = item['id'] as int;
          return Container(
            margin: const EdgeInsets.only(bottom: 8),
            decoration: BoxDecoration(
              color: const Color(0xFF111118),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: const Color(0xFF00E5CC).withValues(alpha: 0.2)),
            ),
            child: ListTile(
              dense: true,
              leading: const Icon(Icons.stars_rounded, color: Color(0xFF00E5CC), size: 20),
              title: Text(
                phrase,
                style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13.5, color: Colors.white),
              ),
              subtitle: Text(
                '→ $action (<50ms)',
                style: TextStyle(color: const Color(0xFF00E5CC).withValues(alpha: 0.8), fontSize: 11.5),
              ),
              trailing: IconButton(
                icon: Icon(Icons.delete_outline_rounded, size: 18, color: Colors.redAccent.shade100),
                tooltip: 'Delete shortcut',
                onPressed: () async {
                  await DatabaseHelper.instance.deleteAlias(id);
                  await _safeInvoke('deleteCustomAlias', {'phrase': phrase});
                  await _syncAliasesToNative();
                  if (mounted) _showSnack('Removed shortcut "$phrase"');
                },
              ),
            ),
          );
        }).toList(),
      );
    }

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFF111118),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: const [
          Row(
            children: [
              Icon(Icons.flash_on_rounded, size: 16, color: Color(0xFF00E5CC)),
              SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Deterministic Sub-50ms Fast-Path Active',
                  style: TextStyle(fontWeight: FontWeight.w600, fontSize: 12.5, color: Colors.white70),
                ),
              ),
            ],
          ),
          SizedBox(height: 6),
          Text(
            'Say "torch", "take screenshot", "selfie", or tap Manage to add your own custom phrases.',
            style: TextStyle(fontSize: 11.5, color: Colors.white38),
          ),
        ],
      ),
    );
  }

  Widget _buildOrb() {
    final color = _assistantState.color;
    final isListening = _assistantState == AssistantState.listening;
    final isPlaying   = _assistantState == AssistantState.playing;
    final animActive  = isListening || isPlaying;

    return GestureDetector(
      onTap: _toggleService,
      child: SizedBox(
        width: 170,
        height: 170,
        child: Stack(
          alignment: Alignment.center,
          children: [
            if (animActive) ...[
              AnimatedBuilder(
                animation: _waveCtrl,
                builder: (ctx, child) => CustomPaint(
                  size: const Size(170, 170),
                  painter: _WaveRingPainter(progress: _waveCtrl.value, color: color),
                ),
              ),
              AnimatedBuilder(
                animation: _waveCtrl,
                builder: (ctx, child) => CustomPaint(
                  size: const Size(170, 170),
                  painter: _WaveRingPainter(progress: (_waveCtrl.value + 0.5) % 1.0, color: color),
                ),
              ),
            ],
            AnimatedBuilder(
              animation: _pulseAnim,
              builder: (ctx, child) => Transform.scale(
                scale: animActive ? _pulseAnim.value : 1.0,
                child: Container(
                  width: 120,
                  height: 120,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: RadialGradient(
                      colors: [
                        color.withValues(alpha: 0.35),
                        color.withValues(alpha: 0.1),
                        Colors.transparent,
                      ],
                    ),
                  ),
                ),
              ),
            ),
            Container(
              width: 84,
              height: 84,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [color, color.withValues(alpha: 0.65)],
                ),
                boxShadow: [
                  BoxShadow(color: color.withValues(alpha: 0.45), blurRadius: 28, spreadRadius: 4),
                ],
              ),
              child: Icon(_assistantState.icon, size: 36, color: Colors.black87),
            ),
          ],
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
//  UI Component Widgets
// ─────────────────────────────────────────────────────────────────────────────

class _WaveRingPainter extends CustomPainter {
  final double progress;
  final Color  color;
  _WaveRingPainter({required this.progress, required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = 42.0 + progress * 40.0;
    final alpha  = (1.0 - progress).clamp(0.0, 1.0) * 0.5;

    final paint = Paint()
      ..color       = color.withValues(alpha: alpha)
      ..style       = PaintingStyle.stroke
      ..strokeWidth = 2.0;

    canvas.drawCircle(center, radius, paint);
  }

  @override
  bool shouldRepaint(_WaveRingPainter old) => (progress - old.progress).abs() > 0.03 || old.color != color;
}

class _CommandGrid extends StatelessWidget {
  const _CommandGrid();
  @override
  Widget build(BuildContext context) {
    return GridView.count(
      crossAxisCount: 2,
      mainAxisSpacing: 10,
      crossAxisSpacing: 10,
      childAspectRatio: 2.8,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      children: const [
        _CommandChip('Set alarm', Icons.alarm_rounded, Color(0xFFFFB300)),
        _CommandChip('Call Mom', Icons.phone_rounded, Color(0xFF00E5CC)),
        _CommandChip('Open WhatsApp', Icons.chat_rounded, Color(0xFF25D366)),
        _CommandChip('Play music', Icons.music_note_rounded, Color(0xFFAB47BC)),
      ],
    );
  }
}

class _CommandChip extends StatelessWidget {
  final String label; final IconData icon; final Color color;
  const _CommandChip(this.label, this.icon, this.color);
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.07), borderRadius: BorderRadius.circular(12), border: Border.all(color: color.withValues(alpha: 0.25))),
      child: Row(children: [Icon(icon, size: 16, color: color), const SizedBox(width: 8), Expanded(child: Text(label, style: TextStyle(fontSize: 11.5, color: color.withValues(alpha: 0.9)), overflow: TextOverflow.ellipsis))]),
    );
  }
}

class _GlowDot extends StatefulWidget {
  final bool active;
  const _GlowDot({required this.active});
  @override
  State<_GlowDot> createState() => _GlowDotState();
}

class _GlowDotState extends State<_GlowDot> with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl = AnimationController(vsync: this, duration: const Duration(seconds: 2));
  @override
  void initState() { super.initState(); _sync(); }
  @override
  void didUpdateWidget(_GlowDot old) { super.didUpdateWidget(old); if (old.active != widget.active) _sync(); }

  void _sync() { if (widget.active) { if (!_ctrl.isAnimating) _ctrl.repeat(reverse: true); } else { _ctrl.stop(); _ctrl.value = 0; } }

  @override
  void dispose() { _ctrl.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) {
    return TickerMode(
      enabled: widget.active,
      child: RepaintBoundary(
        child: AnimatedBuilder(
          animation: _ctrl,
          builder: (ctx, child) => Container(
            width: 10, height: 10,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: widget.active ? const Color(0xFF00E5CC) : Colors.white24,
              boxShadow: widget.active ? [BoxShadow(color: const Color(0xFF00E5CC).withValues(alpha: 0.4 + 0.4 * _ctrl.value), blurRadius: 8, spreadRadius: 2)] : [],
            ),
          ),
        ),
      ),
    );
  }
}

class _StateChip extends StatelessWidget {
  final AssistantState state;
  const _StateChip({required this.state});
  @override
  Widget build(BuildContext context) {
    final color = state.color;
    return Center(
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 300),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
        decoration: BoxDecoration(color: color.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(100), border: Border.all(color: color.withValues(alpha: 0.35), width: 1.5)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(state.icon, color: color, size: 16), const SizedBox(width: 8), Text(state.label, style: TextStyle(color: color, fontWeight: FontWeight.w700, fontSize: 13))]),
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  final String title; final IconData icon;
  const _SectionHeader({required this.title, required this.icon});
  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 17, color: Colors.white38),
        const SizedBox(width: 8),
        Flexible(
          child: Text(
            title,
            style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: Colors.white54),
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ],
    );
  }
}

class _PermTile extends StatelessWidget {
  final String title; final IconData icon; final bool granted; final VoidCallback onFix;
  const _PermTile({required this.title, required this.icon, required this.granted, required this.onFix});
  @override
  Widget build(BuildContext context) {
    final statusColor = granted ? const Color(0xFF00E5CC) : Colors.orange;
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(color: const Color(0xFF111118), borderRadius: BorderRadius.circular(14), border: Border.all(color: statusColor.withValues(alpha: 0.2))),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(14),
        clipBehavior: Clip.antiAlias,
        child: ListTile(
          leading: Icon(icon, color: statusColor, size: 18),
          title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
          trailing: granted ? Icon(Icons.check_circle_rounded, color: statusColor, size: 22) : FilledButton.tonal(onPressed: onFix, child: const Text('Grant')),
        ),
      ),
    );
  }
}

class _Exchange { final String user; final String assistant; const _Exchange({required this.user, required this.assistant}); }
class _ExchangeCard extends StatelessWidget {
  final _Exchange exchange;
  const _ExchangeCard({required this.exchange});
  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(color: const Color(0xFF111118), borderRadius: BorderRadius.circular(14), border: Border.all(color: Colors.white.withValues(alpha: 0.06))),
      padding: const EdgeInsets.all(12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (exchange.user.isNotEmpty) _bubble(text: exchange.user, icon: Icons.person_rounded, color: const Color(0xFF00E5CC), label: 'You'),
        if (exchange.user.isNotEmpty && exchange.assistant.isNotEmpty) const SizedBox(height: 8),
        if (exchange.assistant.isNotEmpty) _bubble(text: exchange.assistant, icon: Icons.smart_toy_rounded, color: const Color(0xFF2979FF), label: 'Hey Bro'),
      ]),
    );
  }
  Widget _bubble({required String text, required IconData icon, required Color color, required String label}) {
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Icon(icon, size: 14, color: color), const SizedBox(width: 10),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(label, style: TextStyle(fontSize: 10, color: color, fontWeight: FontWeight.w700)), const SizedBox(height: 2), Text(text, style: TextStyle(fontSize: 13, color: Colors.white.withValues(alpha: 0.75), height: 1.4))])),
    ]);
  }
}
