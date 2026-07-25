package com.code2hack.prototype.hotspot.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Frame {
    public static final byte HELLO = 1;
    public static final byte HELLO_ACK = 2;
    public static final byte CLIENT_PROBE = 3;
    public static final byte CLIENT_PROBE_ACK = 4;
    public static final byte SERVER_PROBE = 5;
    public static final byte SERVER_PROBE_ACK = 6;
    public static final byte REQUEST_SERVER_BURST = 7;
    public static final byte CONTROL_ACK = 8;

    public final byte type;
    public final long sessionId;
    public final long sequence;
    public final long sentNanos;
    public final byte[] payload;

    public Frame(
            byte type,
            long sessionId,
            long sequence,
            long sentNanos,
            byte[] payload
    ) {
        this.type = type;
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.sentNanos = sentNanos;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    public static Frame text(
            byte type,
            long sessionId,
            long sequence,
            long sentNanos,
            String payload
    ) {
        return new Frame(
                type,
                sessionId,
                sequence,
                sentNanos,
                payload.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String payloadText() {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
