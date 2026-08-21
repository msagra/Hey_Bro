import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

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

  // ── Permissions ────────────────────────────────────────────────────────────
  bool _hasMic        = false;
  bool _hasCamera     = false;
  bool _hasA11y       = false;
  bool _hasOverlay    = false;
  bool _hasBattery    = false;

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

  // ─────────────────────────────────────────────────────────────────────────
  @override
  void initState() {
    super.initState();

    _pulseCtrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1000))
      ..repeat(reverse: true);
    _pulseAnim = Tween<double>(begin: 1.0, end: 1.14).animate(
      CurvedAnimation(parent: _pulseCtrl, curve: Curves.easeInOut),
    );

    _waveCtrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1800))
      ..repeat();

    _checkPermissions();
    _permissionTimer = Timer.periodic(const Duration(seconds: 4), (_) => _checkPermissions());

    _eventSub = _eventChannel.receiveBroadcastStream().listen(
      _onNativeEvent,
      onError: (e) => debugPrint('EventChannel error: $e'),
    );
  }

  @override
  void dispose() {
    _pulseCtrl.dispose();
    _waveCtrl.dispose();
    _wsUrlCtrl?.dispose();
    _permissionTimer?.cancel();
    _eventSub?.cancel();
    super.dispose();
  }

  TextEditingController? _wsUrlCtrl;  // kept for compat, not shown in UI

  // Debug helpers to tune STT timing at runtime
  Future<Map<String, dynamic>> _getSttDelays() async {
    try {
      final res = await _platform.invokeMethod('getSttDelays');
      return Map<String, dynamic>.from(res as Map);
    } catch (e) {
      return {'ackMs': 400, 'startMs': 2200, 'postTtsMs': 800};
    }
  }

  Future<void> _setSttDelays(int ackMs, int startMs, int postTtsMs) async {
    try {
      await _platform.invokeMethod('setSttDelays', {'ackMs': ackMs, 'startMs': startMs, 'postTtsMs': postTtsMs});
    } catch (e) {
      debugPrint('setSttDelays error: $e');
    }
  }

  Future<void> _adjustStartDelay(int delta) async {
    final current = await _getSttDelays();
    final ack = (current['ackMs'] as num).toInt();
    final start = (current['startMs'] as num).toInt();
    final post = (current['postTtsMs'] as num).toInt();
    final newStart = (start + delta).clamp(200, 10000);
    await _setSttDelays(ack, newStart, post);
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('STT start delay set to ${newStart}ms')));
  }

  Widget _buildDebugFab() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        FloatingActionButton.small(
          heroTag: 'inc',
          onPressed: () => _adjustStartDelay(200),
          tooltip: 'Increase STT start delay (+200ms)',
          child: const Icon(Icons.add),
        ),
        const SizedBox(height: 8),
        FloatingActionButton.small(
          heroTag: 'dec',
          onPressed: () => _adjustStartDelay(-200),
          tooltip: 'Decrease STT start delay (-200ms)',
          child: const Icon(Icons.remove),
        ),
      ],
    );
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Event handling
  // ─────────────────────────────────────────────────────────────────────────

  void _onNativeEvent(dynamic raw) {
    try {
      final data  = json.decode(raw as String) as Map<String, dynamic>;
      final state = data['state'] as String? ?? '';

      setState(() {
        switch (state) {
          case 'LISTENING':
            _assistantState    = AssistantState.listening;
            _pendingTranscript = null;
            break;
          case 'PROCESSING':
            _assistantState    = AssistantState.processing;
            _pendingTranscript = data['transcript'] as String?;
            break;
          case 'PLAYING':
            _assistantState = AssistantState.playing;
            final transcript = _pendingTranscript ?? (data['transcript'] as String? ?? '');
            final response   = data['response'] as String? ?? '';
            if (transcript.isNotEmpty || response.isNotEmpty) {
              _history.insert(0, _Exchange(user: transcript, assistant: response));
              if (_history.length > 20) _history.removeLast();
            }
            break;
          case 'IDLE':
            _assistantState    = AssistantState.idle;
            _pendingTranscript = null;
            break;
          case 'CONNECTED':
            break;
          case 'DISCONNECTED':
            _assistantState = AssistantState.disconnected;
            break;
        }
      });
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
        setState(() {
          _hasMic     = perms['microphone'] == true;
          _hasCamera  = perms['camera'] == true;
          _hasA11y    = perms['accessibility'] == true;
          _hasOverlay = perms['overlay'] == true;
          _hasBattery = perms['battery'] == true;
          _activeEngine = engine;
          _autostart    = autostart;
        });
      }
    } on PlatformException catch (e) {
      debugPrint('checkPermissions error: ${e.message}');
    }
  }

  bool get _allPermissionsGranted => _hasMic && _hasA11y && _hasOverlay;

  // ─────────────────────────────────────────────────────────────────────────
  //  Service control
  // ─────────────────────────────────────────────────────────────────────────

  Future<void> _toggleService() async {
    if (_serviceRunning) {
      // Tap while running = manual push-to-talk trigger
      try {
        await _platform.invokeMethod('triggerAssistant');
      } on PlatformException catch (e) {
        _showSnack('Error: ${e.message}');
      }
      return;
    }

    if (!_allPermissionsGranted) {
      _showSnack('Grant microphone, accessibility, and overlay permissions first.');
      return;
    }

    try {
      await _platform.invokeMethod('startAssistantService');
      setState(() {
        _serviceRunning = true;
        _assistantState = AssistantState.idle;
      });
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
    } on PlatformException catch (e) {
      _showSnack('Error: ${e.message}');
    }
  }

  Future<void> _toggleAutostart(bool value) async {
    try {
      await _platform.invokeMethod('setAutostartEnabled', {'enabled': value});
      setState(() => _autostart = value);
    } on PlatformException catch (e) {
      debugPrint('setAutostart error: ${e.message}');
    }
  }

  void _showSnack(String msg) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(msg),
          behavior: SnackBarBehavior.floating,
          backgroundColor: const Color(0xFF1C1C26),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
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
            // ── AppBar ────────────────────────────────────────────────────
            SliverAppBar(
              backgroundColor: Colors.transparent,
              elevation: 0,
              floating: true,
              title: Row(
                children: [
                  _GlowDot(active: _serviceRunning),
                  const SizedBox(width: 10),
                  const Text(
                    'Hey Bro',
                    style: TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 22,
                      color: Colors.white,
                      letterSpacing: 0.5,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(
                      color: const Color(0xFF00E5CC).withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(color: const Color(0xFF00E5CC).withValues(alpha: 0.3)),
                    ),
                    child: const Text(
                      'Local AI',
                      style: TextStyle(
                        fontSize: 10,
                        color: Color(0xFF00E5CC),
                        fontWeight: FontWeight.w700,
                        letterSpacing: 0.8,
                      ),
                    ),
                  ),
                ],
              ),
              actions: [
                if (_serviceRunning)
                  IconButton(
                    icon: const Icon(Icons.stop_circle_outlined, color: Color(0xFFEF5350)),
                    tooltip: 'Stop',
                    onPressed: _stopService,
                  ),
              ],
            ),

            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              sliver: SliverList(
                delegate: SliverChildListDelegate([

                  const SizedBox(height: 4),

                  // ── Status chip ────────────────────────────────────────
                  _StateChip(state: _assistantState),
                  const SizedBox(height: 28),

                  // ── Central orb ────────────────────────────────────────
                  Center(child: _buildOrb()),

                  const SizedBox(height: 12),

                  // Hint text
                  Center(
                    child: Text(
                      _serviceRunning
                          ? 'Say "Hey Bro" anytime · tap to speak now'
                          : 'Tap orb to start listening',
                      style: TextStyle(
                        fontSize: 12,
                        color: Colors.white.withValues(alpha: 0.35),
                        letterSpacing: 0.3,
                      ),
                    ),
                  ),

                  if (_serviceRunning && _activeEngine != 'None') ...[
                    const SizedBox(height: 6),
                    Center(
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.memory_rounded, size: 12, color: Colors.white24),
                          const SizedBox(width: 4),
                          Text(
                            _activeEngine,
                            style: const TextStyle(fontSize: 11, color: Colors.white24),
                          ),
                        ],
                      ),
                    ),
                  ],

                  const SizedBox(height: 32),

                  // ── What I can do ──────────────────────────────────────
                  if (!_serviceRunning) ...[
                    _SectionHeader(title: 'What I can do', icon: Icons.auto_awesome_rounded),
                    const SizedBox(height: 12),
                    _SectionHeader(title: 'Lock-screen safe', icon: Icons.lock_open_rounded),
                    const SizedBox(height: 8),
                    _CommandGrid(category: 'lockscreen'),
                    const SizedBox(height: 18),
                    _SectionHeader(title: 'Unlocked only', icon: Icons.lock_rounded),
                    const SizedBox(height: 8),
                    _CommandGrid(category: 'unlocked'),
                    const SizedBox(height: 28),
                  ],

                  // ── Conversation history ───────────────────────────────
                  if (_history.isNotEmpty) ...[
                    _SectionHeader(title: 'Recent', icon: Icons.chat_bubble_outline_rounded),
                    const SizedBox(height: 10),
                    ..._history.take(6).map((e) => _ExchangeCard(exchange: e)),
                    const SizedBox(height: 24),
                  ],

                  // ── Permissions ────────────────────────────────────────
                  _SectionHeader(title: 'Permissions', icon: Icons.security_rounded),
                  const SizedBox(height: 10),
                  _PermTile(
                    title: 'Microphone',
                    subtitle: 'Wake word & voice commands',
                    icon: Icons.mic_rounded,
                    granted: _hasMic,
                    onFix: () => _platform.invokeMethod('requestMicPermission'),
                  ),
                  _PermTile(
                    title: 'Camera',
                    subtitle: 'Take photos on command',
                    icon: Icons.camera_alt_rounded,
                    granted: _hasCamera,
                    onFix: () => _platform.invokeMethod('requestCameraPermission'),
                  ),
                  _PermTile(
                    title: 'Accessibility Service',
                    subtitle: 'Open apps & control device UI',
                    icon: Icons.accessibility_new_rounded,
                    granted: _hasA11y,
                    onFix: () => _platform.invokeMethod('requestAccessibilityPermission'),
                  ),
                  _PermTile(
                    title: 'Display Over Other Apps',
                    subtitle: 'Show listening indicator anywhere',
                    icon: Icons.picture_in_picture_rounded,
                    granted: _hasOverlay,
                    onFix: () => _platform.invokeMethod('requestOverlayPermission'),
                  ),

                  const SizedBox(height: 16),

                  _SectionHeader(title: 'Background', icon: Icons.settings_suggest_rounded),
                  const SizedBox(height: 10),
                  _PermTile(
                    title: 'Battery Optimization',
                    subtitle: 'Keep service alive when screen is off',
                    icon: Icons.battery_charging_full_rounded,
                    granted: _hasBattery,
                    onFix: () => _platform.invokeMethod('requestBatteryExemption'),
                  ),

                  // Auto-start toggle
                  Container(
                    margin: const EdgeInsets.only(bottom: 10),
                    decoration: BoxDecoration(
                      color: const Color(0xFF111118),
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: Colors.white10),
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: Material(
                      type: MaterialType.transparency,
                      child: SwitchListTile(
                        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                        secondary: Container(
                          width: 36, height: 36,
                          decoration: BoxDecoration(
                            color: Colors.purple.withValues(alpha: 0.15),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(Icons.restart_alt_rounded, color: Colors.purple, size: 20),
                        ),
                        title: const Text('Auto-start on boot', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
                        subtitle: const Text('Restart listening after phone reboots', style: TextStyle(fontSize: 12, color: Colors.white38)),
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

  // ─────────────────────────────────────────────────────────────────────────

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
            // Animated ring when listening
            if (isListening)
              AnimatedBuilder(
                animation: _waveCtrl,
                builder: (context2, child2) {
                  return CustomPaint(
                    size: const Size(200, 200),
                    painter: _WaveRingPainter(_waveCtrl.value, color),
                  );
                },
              ),

            // Pulsing orb
            AnimatedBuilder(
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
                        ? [
                            color.withValues(alpha: 0.95),
                            color.withValues(alpha: 0.25),
                            color.withValues(alpha: 0.05),
                          ]
                        : [const Color(0xFF1C1C26), const Color(0xFF111118)],
                    stops: isActive ? const [0.0, 0.6, 1.0] : const [0.0, 1.0],
                    radius: 0.9,
                  ),
                  boxShadow: isActive
                      ? [
                          BoxShadow(color: color.withValues(alpha: 0.5), blurRadius: 40, spreadRadius: 10),
                          BoxShadow(color: color.withValues(alpha: 0.2), blurRadius: 80, spreadRadius: 20),
                        ]
                      : [],
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    AnimatedSwitcher(
                      duration: const Duration(milliseconds: 300),
                      child: Icon(
                        isActive ? _assistantState.icon : Icons.mic_none_rounded,
                        key: ValueKey(_assistantState),
                        size: 60,
                        color: isActive ? Colors.white : Colors.white24,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      isActive ? _assistantState.label : 'Tap to Start',
                      style: TextStyle(
                        fontSize: 12,
                        color: isActive ? Colors.white.withValues(alpha: 0.85) : Colors.white24,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 0.3,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Wave ring painter for listening state
// ─────────────────────────────────────────────────────────────────────────────

class _WaveRingPainter extends CustomPainter {
  final double progress;
  final Color color;
  _WaveRingPainter(this.progress, this.color);

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    for (int i = 0; i < 3; i++) {
      final phase   = (progress + i * 0.33) % 1.0;
      final radius  = 80.0 + phase * 20.0;
      final opacity = (1 - phase) * 0.35;
      final paint   = Paint()
        ..color       = color.withValues(alpha: opacity)
        ..style       = PaintingStyle.stroke
        ..strokeWidth = 1.5;
      canvas.drawCircle(center, radius, paint);
    }
  }

  @override
  bool shouldRepaint(_WaveRingPainter old) =>
      (progress - old.progress).abs() > 0.01 || old.color != color;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Command grid
// ─────────────────────────────────────────────────────────────────────────────

class _CommandGrid extends StatelessWidget {
  final String category;
  const _CommandGrid({required this.category});

  static const _lockscreenCommands = [
    ('Set alarm at 7 AM',       Icons.alarm_rounded,            Color(0xFFFFB300)),
    ('Call Mom',                Icons.phone_rounded,            Color(0xFF00E5CC)),
    ('Turn on flashlight',      Icons.flashlight_on_rounded,    Color(0xFFFFEE58)),
    ('Take a photo',            Icons.camera_alt_rounded,       Color(0xFF2979FF)),
    ('What time is it',         Icons.access_time_rounded,      Color(0xFF7E57C2)),
    ('Start 5 minute timer',    Icons.timer_rounded,            Color(0xFFEF5350)),
    ('Volume up',               Icons.volume_up_rounded,       Color(0xFF66BB6A)),
    ('Open WiFi settings',      Icons.wifi_rounded,             Color(0xFF42A5F5)),
  ];

  static const _unlockedCommands = [
    ('Open WhatsApp',           Icons.chat_rounded,             Color(0xFF25D366)),
    ('Search for news',         Icons.search_rounded,           Color(0xFFFF6D00)),
    ('Play music',              Icons.music_note_rounded,       Color(0xFFAB47BC)),
    ('Take screenshot',         Icons.screenshot_monitor_rounded, Color(0xFF42A5F5)),
    ('Open YouTube',            Icons.smart_display_rounded,    Color(0xFFFF7043)),
    ('Directions to office',    Icons.map_rounded,              Color(0xFF26A69A)),
    ('Open Bluetooth settings', Icons.bluetooth_rounded,        Color(0xFF29B6F6)),
    ('Open app',                Icons.apps_rounded,             Color(0xFF8D6E63)),
  ];

  @override
  Widget build(BuildContext context) {
    final commands = category == 'lockscreen' ? _lockscreenCommands : _unlockedCommands;
    return GridView.count(
      crossAxisCount: 2,
      mainAxisSpacing: 10,
      crossAxisSpacing: 10,
      childAspectRatio: 2.8,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      children: commands.map((cmd) {
        final (label, icon, color) = cmd;
        return _CommandChip(label, icon, color);
      }).toList(),
    );
  }
}

class _CommandChip extends StatelessWidget {
  final String label;
  final IconData icon;
  final Color color;
  const _CommandChip(this.label, this.icon, this.color);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.07),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.25)),
      ),
      child: Row(
        children: [
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '"$label"',
              style: TextStyle(
                fontSize: 11.5,
                color: color.withValues(alpha: 0.9),
                fontWeight: FontWeight.w500,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Small widgets
// ─────────────────────────────────────────────────────────────────────────────

class _GlowDot extends StatefulWidget {
  final bool active;
  const _GlowDot({required this.active});
  @override
  State<_GlowDot> createState() => _GlowDotState();
}

class _GlowDotState extends State<_GlowDot> with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl =
      AnimationController(vsync: this, duration: const Duration(seconds: 2))..repeat(reverse: true);
  @override
  void dispose() { _ctrl.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (ctx, child) => Container(
        width: 10, height: 10,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: widget.active ? const Color(0xFF00E5CC) : Colors.white24,
          boxShadow: widget.active
              ? [BoxShadow(
                  color: const Color(0xFF00E5CC).withValues(alpha: 0.4 + 0.4 * _ctrl.value),
                  blurRadius: 8, spreadRadius: 2,
                )]
              : [],
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
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(100),
          border: Border.all(color: color.withValues(alpha: 0.35), width: 1.5),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(state.icon, color: color, size: 16),
            const SizedBox(width: 8),
            Text(
              state.label,
              style: TextStyle(color: color, fontWeight: FontWeight.w700, fontSize: 13),
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  final String title;
  final IconData icon;
  const _SectionHeader({required this.title, required this.icon});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 17, color: Colors.white38),
        const SizedBox(width: 8),
        Text(
          title,
          style: const TextStyle(
            fontSize: 14, fontWeight: FontWeight.w700, color: Colors.white54, letterSpacing: 0.3,
          ),
        ),
      ],
    );
  }
}

class _PermTile extends StatelessWidget {
  final String   title, subtitle;
  final IconData icon;
  final bool     granted;
  final VoidCallback onFix;
  const _PermTile({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.granted,
    required this.onFix,
  });

  @override
  Widget build(BuildContext context) {
    final statusColor = granted ? const Color(0xFF00E5CC) : Colors.orange;
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: const Color(0xFF111118),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: statusColor.withValues(alpha: 0.2), width: 1),
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        type: MaterialType.transparency,
        child: ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
          leading: Container(
            width: 36, height: 36,
            decoration: BoxDecoration(
              color: statusColor.withValues(alpha: 0.12),
              shape: BoxShape.circle,
            ),
            child: Icon(icon, color: statusColor, size: 18),
          ),
          title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
          subtitle: Text(subtitle, style: const TextStyle(fontSize: 12, color: Colors.white38)),
          trailing: granted
              ? Icon(Icons.check_circle_rounded, color: statusColor, size: 22)
              : FilledButton.tonal(
                  onPressed: onFix,
                  style: FilledButton.styleFrom(
                    backgroundColor: Colors.orange.withValues(alpha: 0.15),
                    foregroundColor: Colors.orange,
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                    textStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
                    minimumSize: const Size(60, 34),
                  ),
                  child: const Text('Grant'),
                ),
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Conversation exchange model & card
// ─────────────────────────────────────────────────────────────────────────────

class _Exchange {
  final String user;
  final String assistant;
  const _Exchange({required this.user, required this.assistant});
}

class _ExchangeCard extends StatelessWidget {
  final _Exchange exchange;
  const _ExchangeCard({required this.exchange});

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: const Color(0xFF111118),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: Colors.white.withValues(alpha: 0.06)),
      ),
      padding: const EdgeInsets.all(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (exchange.user.isNotEmpty)
            _bubble(
              text: exchange.user,
              icon: Icons.person_rounded,
              color: const Color(0xFF00E5CC),
              label: 'You',
            ),
          if (exchange.user.isNotEmpty && exchange.assistant.isNotEmpty)
            const SizedBox(height: 8),
          if (exchange.assistant.isNotEmpty)
            _bubble(
              text: exchange.assistant,
              icon: Icons.smart_toy_rounded,
              color: const Color(0xFF2979FF),
              label: 'Hey Bro',
            ),
        ],
      ),
    );
  }

  Widget _bubble({
    required String text,
    required IconData icon,
    required Color color,
    required String label,
  }) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 26, height: 26,
          decoration: BoxDecoration(color: color.withValues(alpha: 0.15), shape: BoxShape.circle),
          child: Icon(icon, size: 14, color: color),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: TextStyle(fontSize: 10, color: color, fontWeight: FontWeight.w700)),
              const SizedBox(height: 2),
              Text(
                text,
                style: TextStyle(
                  fontSize: 13,
                  color: Colors.white.withValues(alpha: 0.75),
                  height: 1.4,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
