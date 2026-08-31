import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;

void main() {
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
    with TickerProviderStateMixin {

  static const _platform     = MethodChannel('com.example.hey_bro/assistant');
  static const _eventChannel = EventChannel('com.example.hey_bro/events');

  // ── Service State ──────────────────────────────────────────────────────────
  bool           _serviceRunning = false;
  AssistantState _assistantState = AssistantState.idle;
  String         _activeEngine   = 'None';
  bool           _autostart      = false;

  // ── Backend / server config ────────────────────────────────────────────────
  static const _cloudWs   = 'wss://hey-bro-1zxk.onrender.com/ws/audio';
  static const _localWs   = 'ws://192.168.10.150:8000/ws/audio';
  static const _offlineWs = ''; // offline sentinel

  String _backendWs   = _cloudWs;
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

  // ── Conversation ───────────────────────────────────────────────────────────
  final List<_Exchange> _history = [];
  String? _pendingTranscript;

  // ── Animations ────────────────────────────────────────────────────────────
  late final AnimationController _pulseCtrl;
  late final AnimationController _waveCtrl;
  late final Animation<double>   _pulseAnim;

  // ── Timers / Subscriptions ─────────────────────────────────────────────────
  Timer?              _permissionTimer;
  StreamSubscription? _eventSub;
  String?             _lastRawEvent;

  // ─────────────────────────────────────────────────────────────────────────
  @override
  void initState() {
    super.initState();

    _pulseCtrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200));
    _pulseAnim = Tween<double>(begin: 1.0, end: 1.12).animate(
      CurvedAnimation(parent: _pulseCtrl, curve: Curves.easeInOut),
    );

    _waveCtrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1800));

    _checkPermissions();
    _permissionTimer = Timer.periodic(const Duration(seconds: 5), (_) => _checkPermissions());
    _loadBackendConfig();

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
      // PHASE 1: strictly stop and reset when idle/disconnected
      if (_pulseCtrl.isAnimating || _pulseCtrl.value != 0) { _pulseCtrl.stop(); _pulseCtrl.reset(); }
      if (_waveCtrl.isAnimating || _waveCtrl.value != 0) { _waveCtrl.stop(); _waveCtrl.reset(); }
    }
  }

  @override
  void dispose() {
    _pulseCtrl.dispose();
    _waveCtrl.dispose();
    _permissionTimer?.cancel();
    _eventSub?.cancel();
    super.dispose();
  }

  // Debug helpers
  Future<Map<String, dynamic>> _getSttDelays() async {
    try {
      final res = await _platform.invokeMethod('getSttDelays');
      return Map<String, dynamic>.from(res as Map);
    } catch (e) {
      return {'ackMs': 250, 'startMs': 2200, 'postTtsMs': 100};
    }
  }

  Future<void> _adjustStartDelay(int delta) async {
    final current = await _getSttDelays();
    final ack = (current['ackMs'] as num).toInt();
    final start = (current['startMs'] as num).toInt();
    final post = (current['postTtsMs'] as num).toInt();
    final newStart = (start + delta).clamp(200, 10000);
    await _platform.invokeMethod('setSttDelays', {'ackMs': ack, 'startMs': newStart, 'postTtsMs': post});
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('STT delay: ${newStart}ms'), duration: const Duration(seconds: 1)));
  }

  Widget _buildDebugFab() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        FloatingActionButton.small(
          heroTag: 'inc',
          onPressed: () => _adjustStartDelay(200),
          child: const Icon(Icons.add),
        ),
        const SizedBox(height: 8),
        FloatingActionButton.small(
          heroTag: 'dec',
          onPressed: () => _adjustStartDelay(-200),
          child: const Icon(Icons.remove),
        ),
      ],
    );
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Event handling
  // ─────────────────────────────────────────────────────────────────────────

  void _onNativeEvent(dynamic raw) {
    if (raw == _lastRawEvent) return;
    _lastRawEvent = raw as String?;

    try {
      final data = json.decode(raw as String) as Map<String, dynamic>;
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
        case 'DISCONNECTED': newWsConnected = false; newState = _backendWs.isEmpty ? AssistantState.idle : AssistantState.disconnected; break;
      }

      // PHASE 1: Throttled rebuilding – only if meaningful state changed
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
            if (finalTranscript.isNotEmpty || response.isNotEmpty) {
              _history.insert(0, _Exchange(user: finalTranscript, assistant: response));
              if (_history.length > 20) _history.removeLast();
            }
          }
        });
        _updateTickers();
      }
    } catch (e) {
      debugPrint('Event parse error: $e');
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Permissions
  // ─────────────────────────────────────────────────────────────────────────

  Future<void> _checkPermissions() async {
    try {
      final Map<Object?, Object?> perms =
          await _platform.invokeMethod('checkPermissions');
      final String engine =
          await _platform.invokeMethod('getActiveWakeEngine');
      final bool autostart =
          await _platform.invokeMethod('getAutostartEnabled');
      if (mounted) {
        final newHasMic = perms['microphone'] == true;
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

  // ─────────────────────────────────────────────────────────────────────────
  //  Service control
  // ─────────────────────────────────────────────────────────────────────────

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
    } on PlatformException catch (e) {
      _showSnack('Error: ${e.message}');
    }
  }

  Future<void> _toggleAutostart(bool value) async {
    try {
      await _platform.invokeMethod('setAutostartEnabled', {'enabled': value});
      setState(() => _autostart = value);
    } catch (e) {
      debugPrint('setAutostart error: $e');
    }
  }

  Future<void> _loadBackendConfig() async {
    try {
      final url = await _platform.invokeMethod('getBackendWs') as String? ?? _cloudWs;
      if (mounted) setState(() => _backendWs = url);
    } catch (e) {
      debugPrint('getBackendWs error: $e');
    }
  }

  String get _backendLabel {
    if (_backendWs == _cloudWs) return 'Cloud Server';
    if (_backendWs == _localWs) return 'Local Server';
    return 'Offline Engine';
  }

  Future<void> _pickServer() async {
    final selected = await showDialog<String>(
      context: context,
      builder: (BuildContext ctx) {
        return AlertDialog(
          title: const Text('Select Backend'),
          content: Column(
            mainAxisSize: Map<String, dynamic>.from({}).isEmpty ? MainAxisSize.min : MainAxisSize.min,
            children: [
              _serverOption(ctx, label: 'Cloud Server', url: _cloudWs, icon: Icons.cloud_done_rounded),
              const SizedBox(height: 8),
              _serverOption(ctx, label: 'Local Server', url: _localWs, icon: Icons.lan_rounded),
              const SizedBox(height: 8),
              _serverOption(ctx, label: 'Offline Engine', url: _offlineWs, icon: Icons.phonelink_erase_rounded),
            ],
          ),
          actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel'))],
        );
      },
    );
    if (selected != null) {
      try {
        await _platform.invokeMethod('setBackendWs', {'url': selected});
        if (mounted) {
          setState(() {
            _backendWs = selected;
            _wsConnected = false;
          });
          _showSnack('Backend: $_backendLabel');
        }
      } catch (e) {
        _showSnack('Server switch failed');
      }
    }
  }

  Widget _serverOption(BuildContext ctx, {required String label, required String url, required IconData icon}) {
    final selected = _backendWs == url;
    final accent = selected ? const Color(0xFF00E5CC) : Colors.white38;
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(12),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => Navigator.pop(ctx, url),
        child: ListTile(
          selected: selected,
          leading: Icon(icon, color: accent),
          title: Text(label, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
        ),
      ),
    );
  }

  void _showSnack(String msg) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating),
      );
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Build
  // ─────────────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF07070E),
      floatingActionButton: _buildDebugFab(),
      body: SafeArea(
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
                  tooltip: 'Open chat',
                  icon: const Icon(Icons.chat_bubble_outline_rounded),
                  onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ChatScreen(backendWs: _backendWs))),
                ),
                if (_serviceRunning)
                  IconButton(
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

                  // PHASE 1: Isolated repaint for the orb center
                  Center(child: RepaintBoundary(child: _buildOrb())),

                  const SizedBox(height: 12),
                  Center(
                    child: Text(
                      _serviceRunning ? 'Say "Hey Bro" anytime' : 'Tap orb to start',
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
                          Text(_backendLabel, style: const TextStyle(fontSize: 11, color: Colors.white24)),
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
                    const _SectionHeader(title: 'Recent', icon: Icons.chat_bubble_outline_rounded),
                    const SizedBox(height: 10),
                    ..._history.take(6).map((e) => _ExchangeCard(exchange: e)),
                    const SizedBox(height: 24),
                  ],

                  const _SectionHeader(title: 'Permissions', icon: Icons.security_rounded),
                  const SizedBox(height: 10),
                  _PermTile(title: 'Microphone', icon: Icons.mic_rounded, granted: _hasMic, onFix: () => _platform.invokeMethod('requestMicPermission')),
                  _PermTile(title: 'Accessibility', icon: Icons.accessibility_new_rounded, granted: _hasA11y, onFix: () => _platform.invokeMethod('requestAccessibilityPermission')),
                  _PermTile(title: 'Overlay', icon: Icons.picture_in_picture_rounded, granted: _hasOverlay, onFix: () => _platform.invokeMethod('requestOverlayPermission')),
                  _PermTile(
                    title: 'Calls, contacts and SMS',
                    icon: Icons.phone_android_rounded,
                    granted: _hasPhone && _hasContacts && _hasReadSms && _hasSendSms,
                    onFix: () async {
                      await _platform.invokeMethod('requestCommunicationPermissions');
                      _checkPermissions();
                    },
                  ),
                  _PermTile(
                    title: 'Notification access',
                    icon: Icons.notifications_active_rounded,
                    granted: _hasNotificationAccess,
                    onFix: () async {
                      await _platform.invokeMethod('requestNotificationAccess');
                      _checkPermissions();
                    },
                  ),

                  const SizedBox(height: 16),
                  const _SectionHeader(title: 'Backend', icon: Icons.dns_rounded),
                  const SizedBox(height: 10),
                  _BackendTile(label: _backendLabel, connected: _wsConnected, onTap: _pickServer),

                  Container(
                    margin: const EdgeInsets.only(bottom: 10),
                    decoration: BoxDecoration(color: const Color(0xFF111118), borderRadius: BorderRadius.circular(14), border: Border.all(color: Colors.white10)),
                    child: Material(
                      color: Colors.transparent,
                      borderRadius: BorderRadius.circular(14),
                      clipBehavior: Clip.antiAlias,
                      child: SwitchListTile(
                        title: const Text('Auto-start on boot', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
                        value: _autostart,
                        activeThumbColor: const Color(0xFF00E5CC),
                        onChanged: _toggleAutostart,
                      ),
                    ),
                  ),

                  const SizedBox(height: 40),
                ]),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildOrb() {
    final color    = _assistantState.color;
    final isActive = _serviceRunning;
    final isListening = isActive && _assistantState == AssistantState.listening;

    return GestureDetector(
      onTap: _toggleService,
      onLongPress: _stopService,
      child: SizedBox(
        width: 200,
        height: 200,
        child: Stack(
          alignment: Alignment.center,
          children: [
            // PHASE 1: Isolated RepaintBoundary for wave rings
            TickerMode(
              enabled: isListening,
              child: RepaintBoundary(
                child: AnimatedBuilder(
                  animation: _waveCtrl,
                  builder: (context, _) {
                    return CustomPaint(
                      size: const Size(200, 200),
                      painter: _WaveRingPainter(_waveCtrl.value, color),
                    );
                  },
                ),
              ),
            ),

            // PHASE 1: Isolated RepaintBoundary for pulsing orb
            TickerMode(
              enabled: isActive && (_assistantState == AssistantState.listening || _assistantState == AssistantState.playing),
              child: RepaintBoundary(
                child: AnimatedBuilder(
                  animation: _pulseAnim,
                  builder: (context, child) {
                    final scale = isListening ? _pulseAnim.value : 1.0;
                    return Transform.scale(scale: scale, child: child);
                  },
                  child: Container(
                    width: 160,
                    height: 160,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: RadialGradient(
                        colors: isActive
                            ? [color.withValues(alpha: 0.8), color.withValues(alpha: 0.1)]
                            : [const Color(0xFF1C1C26), const Color(0xFF111118)],
                        stops: const [0.0, 1.0],
                        radius: 0.9,
                      ),
                      boxShadow: isActive
                          ? [BoxShadow(color: color.withValues(alpha: 0.3), blurRadius: 30, spreadRadius: 5)]
                          : [],
                    ),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(isActive ? _assistantState.icon : Icons.mic_none_rounded, size: 60, color: isActive ? Colors.white : Colors.white24),
                        const SizedBox(height: 6),
                        Text(isActive ? _assistantState.label : 'Tap to Start', style: const TextStyle(fontSize: 12, color: Colors.white, fontWeight: FontWeight.w600)),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class ChatScreen extends StatefulWidget {
  final String backendWs;
  const ChatScreen({super.key, required this.backendWs});

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final _input = TextEditingController();
  final _scroll = ScrollController();
  final List<Map<String, String>> _messages = [];
  String _provider = 'groq';
  String _model = 'llama-3.3-70b-versatile';
  bool _sending = false;

  static const _models = <String, List<String>>{
    'groq': ['llama-3.3-70b-versatile', 'llama-3.1-8b-instant'],
    'gemini': ['gemini-1.5-flash', 'gemini-1.5-pro'],
    'deepseek': ['deepseek-chat', 'deepseek-reasoner'],
  };

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  String get _chatUrl {
    final base = widget.backendWs
        .replaceFirst(RegExp(r'^wss://'), 'https://')
        .replaceFirst(RegExp(r'^ws://'), 'http://');
    return base.replaceFirst('/ws/audio', '/chat');
  }

  Future<void> _send() async {
    final message = _input.text.trim();
    if (message.isEmpty || _sending) return;
    _input.clear();
    setState(() {
      _sending = true;
      _messages.add({'role': 'user', 'text': message});
    });
    try {
      final history = _messages
          .where((item) => item['role'] == 'user' || item['role'] == 'assistant')
          .toList()
          .map((item) => {'user': item['role'] == 'user' ? item['text']! : '', 'assistant': item['role'] == 'assistant' ? item['text']! : ''})
          .toList();
      final response = await http.post(
        Uri.parse(_chatUrl),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'message': message, 'provider': _provider, 'model': _model, 'history': history}),
      ).timeout(const Duration(seconds: 65));
      final data = jsonDecode(response.body) as Map<String, dynamic>;
      if (response.statusCode < 200 || response.statusCode >= 300 || data['error'] != null) {
        throw Exception(data['error'] ?? 'Chat request failed (${response.statusCode})');
      }
      if (data['type'] == 'ACTION' && data['action'] is String) {
        final params = Map<String, dynamic>.from((data['params'] as Map?) ?? const {});
        final actionPayload = <String, dynamic>{'action': data['action'], ...params};
        await const MethodChannel('com.example.hey_bro/assistant').invokeMethod(
          'executeAssistantAction',
          {'payload': jsonEncode(actionPayload)},
        );
      }
      setState(() => _messages.add({'role': 'assistant', 'text': '${data['reply']}'}));
    } catch (error) {
      setState(() => _messages.add({'role': 'assistant', 'text': 'Chat unavailable: $error'}));
    } finally {
      if (mounted) {
        setState(() => _sending = false);
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (_scroll.hasClients) _scroll.animateTo(_scroll.position.maxScrollExtent, duration: const Duration(milliseconds: 250), curve: Curves.easeOut);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final choices = _models[_provider]!;
    if (!choices.contains(_model)) _model = choices.first;
    return Scaffold(
      appBar: AppBar(title: const Text('Hey Bro Chat')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: Row(children: [
              Expanded(child: DropdownButtonFormField<String>(isExpanded: true, initialValue: _provider, decoration: const InputDecoration(labelText: 'Provider'), items: const [DropdownMenuItem(value: 'groq', child: Text('Groq')), DropdownMenuItem(value: 'gemini', child: Text('Gemini')), DropdownMenuItem(value: 'deepseek', child: Text('DeepSeek'))], onChanged: _sending ? null : (value) => setState(() { _provider = value!; _model = _models[value]!.first; }))),
              const SizedBox(width: 12),
              Expanded(child: DropdownButtonFormField<String>(isExpanded: true, initialValue: _model, decoration: const InputDecoration(labelText: 'Model'), items: choices.map((model) => DropdownMenuItem(value: model, child: Text(model, overflow: TextOverflow.ellipsis))).toList(), onChanged: _sending ? null : (value) => setState(() => _model = value!))),
            ]),
          ),
          Expanded(child: ListView.builder(controller: _scroll, padding: const EdgeInsets.all(16), itemCount: _messages.length, itemBuilder: (_, index) {
            final item = _messages[index];
            final isUser = item['role'] == 'user';
            return Align(alignment: isUser ? Alignment.centerRight : Alignment.centerLeft, child: Container(margin: const EdgeInsets.only(bottom: 10), padding: const EdgeInsets.all(12), constraints: const BoxConstraints(maxWidth: 330), decoration: BoxDecoration(color: isUser ? const Color(0xFF00695C) : const Color(0xFF1C1C26), borderRadius: BorderRadius.circular(14)), child: Text(item['text'] ?? '')));
          }),),
          SafeArea(child: Padding(padding: const EdgeInsets.fromLTRB(12, 4, 12, 12), child: Row(children: [Expanded(child: TextField(controller: _input, enabled: !_sending, textInputAction: TextInputAction.send, onSubmitted: (_) => _send(), decoration: const InputDecoration(hintText: 'Ask anything'))), const SizedBox(width: 8), IconButton(tooltip: 'Send message', onPressed: _sending ? null : _send, icon: _sending ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.send_rounded))]))),
        ],
      ),
    );
  }
}

class _WaveRingPainter extends CustomPainter {
  final double progress;
  final Color color;
  final Paint _paint = Paint()..style = PaintingStyle.stroke..strokeWidth = 1.5;

  _WaveRingPainter(this.progress, this.color);

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    for (int i = 0; i < 3; i++) {
      final phase   = (progress + i * 0.33) % 1.0;
      final radius  = 80.0 + phase * 20.0;
      final opacity = (1 - phase) * 0.3;
      _paint.color = color.withValues(alpha: opacity);
      canvas.drawCircle(center, radius, _paint);
    }
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
  
  // PHASE 1: strictly control ticker for the glow dot
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
    return Row(children: [Icon(icon, size: 17, color: Colors.white38), const SizedBox(width: 8), Text(title, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: Colors.white54))]);
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

class _BackendTile extends StatelessWidget {
  final String label; final bool connected; final VoidCallback onTap;
  const _BackendTile({required this.label, required this.connected, required this.onTap});
  @override
  Widget build(BuildContext context) {
    final statusColor = connected ? const Color(0xFF00E5CC) : Colors.white24;
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(color: const Color(0xFF111118), borderRadius: BorderRadius.circular(14), border: Border.all(color: statusColor.withValues(alpha: 0.2))),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(14),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: ListTile(
            leading: Icon(connected ? Icons.cloud_done_rounded : Icons.cloud_off_rounded, color: statusColor, size: 18),
            title: Text(label, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
            trailing: const Icon(Icons.arrow_forward_rounded, size: 20, color: Colors.white24),
          ),
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
