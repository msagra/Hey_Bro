class NativeEventOrder {
  String? _sessionId;
  int _lastSequence = 0;
  final Set<String> _retiredSessions = {};

  bool accepts({
    required Object? sessionId,
    required Object? sequenceId,
    bool stateSync = false,
  }) {
    if (sessionId == null && sequenceId == null) return true;
    if (sessionId is! String ||
        sessionId.isEmpty ||
        sequenceId is! num ||
        !sequenceId.isFinite ||
        sequenceId < 1 ||
        sequenceId % 1 != 0) {
      return false;
    }

    final sequence = sequenceId.toInt();
    if (_sessionId == null) {
      _sessionId = sessionId;
      _lastSequence = sequence;
      return true;
    }

    if (sessionId == _sessionId) {
      if (sequence <= _lastSequence) return false;
      _lastSequence = sequence;
      return true;
    }

    if (!stateSync || _retiredSessions.contains(sessionId)) return false;
    _retiredSessions.add(_sessionId!);
    _sessionId = sessionId;
    _lastSequence = sequence;
    return true;
  }
}
