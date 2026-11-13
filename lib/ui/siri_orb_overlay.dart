import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

enum SiriState { idle, listening, processing, speaking }

class SiriOrbOverlay extends StatefulWidget {
  final SiriState state;
  final String transcript;
  final String responseText;
  final String screenContext;
  final VoidCallback onMicTap;
  final VoidCallback onDismiss;
  final VoidCallback onInterrupt;

  const SiriOrbOverlay({
    super.key,
    required this.state,
    required this.transcript,
    required this.responseText,
    required this.screenContext,
    required this.onMicTap,
    required this.onDismiss,
    required this.onInterrupt,
  });

  @override
  State<SiriOrbOverlay> createState() => _SiriOrbOverlayState();
}

class _SiriOrbOverlayState extends State<SiriOrbOverlay>
    with TickerProviderStateMixin {
  late AnimationController _pulseController;
  late AnimationController _rotationController;
  late AnimationController _waveController;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    )..repeat(reverse: true);

    _rotationController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 8),
    )..repeat();

    _waveController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1800),
    )..repeat();
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _rotationController.dispose();
    _waveController.dispose();
    super.dispose();
  }

  Color get _glowColor {
    switch (widget.state) {
      case SiriState.listening:
        return const Color(0xFF00E5FF);
      case SiriState.processing:
        return const Color(0xFFFFB300);
      case SiriState.speaking:
        return const Color(0xFF7C4DFF);
      case SiriState.idle:
        return const Color(0xFF00B0FF);
    }
  }

  String get _stateLabel {
    switch (widget.state) {
      case SiriState.listening:
        return "Listening to 'Hey Bro'…";
      case SiriState.processing:
        return "Reasoning with Gemini…";
      case SiriState.speaking:
        return "Speaking… (Tap to interrupt)";
      case SiriState.idle:
        return "Tap orb or say 'Hey Bro'";
    }
  }

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;

    return Material(
      color: Colors.transparent,
      child: Stack(
        children: [
          // Semi-transparent backdrop tap to dismiss
          GestureDetector(
            onTap: widget.onDismiss,
            child: Container(
              color: Colors.black.withValues(alpha: 0.45),
            ),
          ),

          // Siri Floating Bottom Card
          Positioned(
            left: 12,
            right: 12,
            bottom: bottomInset + 16,
            child: Container(
              decoration: BoxDecoration(
                color: const Color(0xFF0D0E15).withValues(alpha: 0.94),
                borderRadius: BorderRadius.circular(32),
                border: Border.all(
                  color: _glowColor.withValues(alpha: 0.35),
                  width: 1.5,
                ),
                boxShadow: [
                  BoxShadow(
                    color: _glowColor.withValues(alpha: 0.22),
                    blurRadius: 40,
                    spreadRadius: 4,
                  ),
                ],
              ),
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Header drag indicator & close
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 10, vertical: 4),
                        decoration: BoxDecoration(
                          color: _glowColor.withValues(alpha: 0.15),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(
                              width: 7,
                              height: 7,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                color: _glowColor,
                              ),
                            ),
                            const SizedBox(width: 6),
                            Text(
                              _stateLabel,
                              style: TextStyle(
                                color: _glowColor,
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ],
                        ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.close_rounded,
                            color: Colors.white54, size: 20),
                        onPressed: widget.onDismiss,
                        visualDensity: VisualDensity.compact,
                      ),
                    ],
                  ),

                  // Screen Context attachment pill (if present)
                  if (widget.screenContext.isNotBlank())
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 10, vertical: 5),
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: 0.06),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Row(
                          children: [
                            const Icon(Icons.screen_search_desktop_outlined,
                                size: 14, color: Colors.white70),
                            const SizedBox(width: 6),
                            Expanded(
                              child: Text(
                                "Screen text context active",
                                style: TextStyle(
                                  color: Colors.white.withValues(alpha: 0.7),
                                  fontSize: 11,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),

                  const SizedBox(height: 16),

                  // Transcripts / Responses
                  if (widget.transcript.isNotBlank() ||
                      widget.responseText.isNotBlank())
                    Container(
                      constraints: const BoxConstraints(maxHeight: 120),
                      padding: const EdgeInsets.symmetric(
                          horizontal: 14, vertical: 10),
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.05),
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: SingleChildScrollView(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (widget.transcript.isNotBlank())
                              Text(
                                "“${widget.transcript}”",
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 15,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                            if (widget.responseText.isNotBlank()) ...[
                              const SizedBox(height: 6),
                              Text(
                                widget.responseText,
                                style: TextStyle(
                                  color: _glowColor,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),

                  const SizedBox(height: 20),

                  // Reactive Siri Orb Waveform Widget
                  GestureDetector(
                    onTap: () {
                      HapticFeedback.lightImpact();
                      if (widget.state == SiriState.speaking) {
                        widget.onInterrupt();
                      } else {
                        widget.onMicTap();
                      }
                    },
                    child: AnimatedBuilder(
                      animation: Listenable.merge(
                          [_pulseController, _rotationController, _waveController]),
                      builder: (context, _) {
                        return CustomPaint(
                          size: const Size(110, 110),
                          painter: SiriOrbPainter(
                            pulse: _pulseController.value,
                            rotation: _rotationController.value * 2 * math.pi,
                            wave: _waveController.value,
                            primaryColor: _glowColor,
                            isSpeaking: widget.state == SiriState.speaking,
                            isListening: widget.state == SiriState.listening,
                          ),
                        );
                      },
                    ),
                  ),

                  const SizedBox(height: 12),
                  Text(
                    widget.state == SiriState.speaking
                        ? "Tap orb to stop"
                        : "Hey Bro",
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.6),
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                      letterSpacing: 0.5,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

extension on String {
  bool isNotBlank() => trim().isNotEmpty;
}

class SiriOrbPainter extends CustomPainter {
  final double pulse;
  final double rotation;
  final double wave;
  final Color primaryColor;
  final bool isSpeaking;
  final bool isListening;

  SiriOrbPainter({
    required this.pulse,
    required this.rotation,
    required this.wave,
    required this.primaryColor,
    required this.isSpeaking,
    required this.isListening,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final baseRadius = size.width * 0.32;
    final scale = (isListening || isSpeaking) ? 1.0 + (pulse * 0.18) : 1.0 + (pulse * 0.05);

    // Outer glow blur
    final glowPaint = Paint()
      ..color = primaryColor.withValues(alpha: (isSpeaking || isListening) ? 0.35 : 0.18)
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 28);
    canvas.drawCircle(center, baseRadius * scale * 1.35, glowPaint);

    // Multi-color rotating nebula layers
    final colors = [
      primaryColor,
      const Color(0xFFE040FB),
      const Color(0xFF00E5FF),
      primaryColor,
    ];

    final sweepGradient = SweepGradient(
      colors: colors,
      transform: GradientRotation(rotation),
    );

    final orbPaint = Paint()
      ..shader = sweepGradient.createShader(Rect.fromCircle(center: center, radius: baseRadius * scale))
      ..style = PaintingStyle.fill;

    canvas.drawCircle(center, baseRadius * scale, orbPaint);

    // Inner highlight reflection
    final innerHighlight = Paint()
      ..shader = RadialGradient(
        colors: [
          Colors.white.withValues(alpha: 0.65),
          Colors.transparent,
        ],
      ).createShader(Rect.fromCircle(center: center - const Offset(6, 6), radius: baseRadius * 0.6));

    canvas.drawCircle(center - const Offset(6, 6), baseRadius * 0.55 * scale, innerHighlight);

    // Siri wavy ring lines
    if (isListening || isSpeaking) {
      final ringPaint = Paint()
        ..color = Colors.white.withValues(alpha: 0.4)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.0;

      final path = Path();
      const points = 60;
      for (int i = 0; i <= points; i++) {
        final angle = (i / points) * 2 * math.pi;
        final waveOffset = math.sin(angle * 4 + wave * 2 * math.pi) * (isSpeaking ? 7 : 4);
        final r = (baseRadius * scale * 1.15) + waveOffset;
        final x = center.dx + r * math.cos(angle);
        final y = center.dy + r * math.sin(angle);
        if (i == 0) {
          path.moveTo(x, y);
        } else {
          path.lineTo(x, y);
        }
      }
      path.close();
      canvas.drawPath(path, ringPaint);
    }
  }

  @override
  bool shouldRepaint(covariant SiriOrbPainter oldDelegate) => true;
}
