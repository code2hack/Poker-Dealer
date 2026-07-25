package com.code2hack.prototype.hotspot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

public final class SessionMetrics {
    public enum State {
        STOPPED,
        LISTENING,
        CONNECTING,
        HANDSHAKING,
        CONNECTED,
        BACKING_OFF,
        ERROR
    }

    private final String role;
    private final LongSupplier clock;
    private final long startedNanos;
    private final Map<Long, Long> pendingProbes = new HashMap<>();
    private final List<Long> rttNanos = new ArrayList<>();

    private State state = State.STOPPED;
    private String endpoint = "-";
    private String lastEvent = "created";
    private String lastError = "-";
    private long connectionAttempts;
    private long connections;
    private long disconnects;
    private long reconnects;
    private long handshakes;
    private long framesSent;
    private long framesReceived;
    private long probesSent;
    private long probesAcked;
    private long probesLost;
    private long duplicateFrames;
    private long outOfOrderFrames;
    private long invalidFrames;
    private boolean hasInboundSession;
    private long lastInboundSessionId;
    private long lastInboundSequence = -1;
    private long lastFrameNanos;
    private long maxReceiveGapNanos;

    public SessionMetrics(String role) {
        this(role, System::nanoTime);
    }

    public SessionMetrics(String role, LongSupplier clock) {
        this.role = role;
        this.clock = clock;
        this.startedNanos = clock.getAsLong();
    }

    public synchronized void transition(State next, String event) {
        state = next;
        lastEvent = event;
    }

    public synchronized void markConnectionAttempt(String target) {
        connectionAttempts++;
        endpoint = target;
        transition(State.CONNECTING, "connection attempt " + connectionAttempts);
    }

    public synchronized void markListening(String target) {
        endpoint = target;
        transition(State.LISTENING, "listening");
    }

    public synchronized void markConnected(String peer) {
        connections++;
        if (connections > 1) {
            reconnects++;
        }
        endpoint = peer;
        lastError = "-";
        transition(State.HANDSHAKING, "socket connected");
    }

    public synchronized void markHandshake(String detail) {
        handshakes++;
        transition(State.CONNECTED, detail);
    }

    public synchronized void markDisconnected(String reason) {
        disconnects++;
        lastError = reason == null ? "-" : reason;
        lastEvent = "disconnected";
    }

    public synchronized void markError(String error) {
        lastError = error;
        transition(State.ERROR, "error");
    }

    public synchronized void onFrameSent() {
        framesSent++;
    }

    public synchronized void onFrameReceived() {
        framesReceived++;
        long now = clock.getAsLong();
        if (lastFrameNanos != 0) {
            maxReceiveGapNanos = Math.max(maxReceiveGapNanos, now - lastFrameNanos);
        }
        lastFrameNanos = now;
    }

    public synchronized void onInboundSequence(long sessionId, long sequence) {
        if (!hasInboundSession || lastInboundSessionId != sessionId) {
            hasInboundSession = true;
            lastInboundSessionId = sessionId;
            lastInboundSequence = -1;
        }
        if (lastInboundSequence >= 0) {
            if (sequence == lastInboundSequence) {
                duplicateFrames++;
            } else if (sequence < lastInboundSequence) {
                outOfOrderFrames++;
            } else if (sequence > lastInboundSequence + 1) {
                probesLost += sequence - lastInboundSequence - 1;
            }
        }
        lastInboundSequence = Math.max(lastInboundSequence, sequence);
    }

    public synchronized void onProbeSent(long sequence, long nowNanos) {
        probesSent++;
        pendingProbes.put(sequence, nowNanos);
    }

    public synchronized void onProbeAcked(long sequence, long nowNanos) {
        Long sent = pendingProbes.remove(sequence);
        if (sent == null) {
            duplicateFrames++;
            return;
        }
        probesAcked++;
        if (rttNanos.size() < 20_000) {
            rttNanos.add(Math.max(0, nowNanos - sent));
        }
    }

    public synchronized void expireProbes(long nowNanos, long timeoutNanos) {
        List<Long> expired = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : pendingProbes.entrySet()) {
            if (nowNanos - entry.getValue() >= timeoutNanos) {
                expired.add(entry.getKey());
            }
        }
        for (Long sequence : expired) {
            pendingProbes.remove(sequence);
            probesLost++;
        }
    }

    public synchronized int pendingProbeCount() {
        return pendingProbes.size();
    }

    public synchronized void onInvalidFrame(String error) {
        invalidFrames++;
        lastError = error;
    }

    public synchronized void resetCounters() {
        pendingProbes.clear();
        rttNanos.clear();
        connectionAttempts = 0;
        connections = 0;
        disconnects = 0;
        reconnects = 0;
        handshakes = 0;
        framesSent = 0;
        framesReceived = 0;
        probesSent = 0;
        probesAcked = 0;
        probesLost = 0;
        duplicateFrames = 0;
        outOfOrderFrames = 0;
        invalidFrames = 0;
        hasInboundSession = false;
        lastInboundSessionId = 0;
        lastInboundSequence = -1;
        lastFrameNanos = 0;
        maxReceiveGapNanos = 0;
        lastEvent = "counters reset";
        lastError = "-";
    }

    public synchronized String render(boolean wakeLockHeld) {
        List<Long> sorted = new ArrayList<>(rttNanos);
        Collections.sort(sorted);
        long now = clock.getAsLong();
        double uptimeSeconds = (now - startedNanos) / 1_000_000_000.0;
        long p50 = percentile(sorted, 0.50);
        long p95 = percentile(sorted, 0.95);
        long p99 = percentile(sorted, 0.99);
        long max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);

        return String.format(
                Locale.US,
                "PROTOTYPE — %s%n"
                        + "state: %s%n"
                        + "endpoint: %s%n"
                        + "uptime_s: %.1f%n"
                        + "wake_lock: %s%n"
                        + "last_event: %s%n"
                        + "last_error: %s%n"
                        + "%n"
                        + "attempts: %d%n"
                        + "connections: %d%n"
                        + "reconnects: %d%n"
                        + "disconnects: %d%n"
                        + "handshakes: %d%n"
                        + "%n"
                        + "frames_sent: %d%n"
                        + "frames_received: %d%n"
                        + "probes_sent: %d%n"
                        + "probes_acked: %d%n"
                        + "probes_outstanding: %d%n"
                        + "probes_lost_or_gapped: %d%n"
                        + "duplicates: %d%n"
                        + "out_of_order: %d%n"
                        + "invalid_frames: %d%n"
                        + "%n"
                        + "rtt_samples: %d%n"
                        + "rtt_p50_ms: %.3f%n"
                        + "rtt_p95_ms: %.3f%n"
                        + "rtt_p99_ms: %.3f%n"
                        + "rtt_max_ms: %.3f%n"
                        + "max_receive_gap_ms: %.1f%n",
                role,
                state,
                endpoint,
                uptimeSeconds,
                wakeLockHeld,
                lastEvent,
                lastError,
                connectionAttempts,
                connections,
                reconnects,
                disconnects,
                handshakes,
                framesSent,
                framesReceived,
                probesSent,
                probesAcked,
                pendingProbes.size(),
                probesLost,
                duplicateFrames,
                outOfOrderFrames,
                invalidFrames,
                sorted.size(),
                nanosToMillis(p50),
                nanosToMillis(p95),
                nanosToMillis(p99),
                nanosToMillis(max),
                nanosToMillis(maxReceiveGapNanos)
        );
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private static double nanosToMillis(long value) {
        return value / 1_000_000.0;
    }
}
