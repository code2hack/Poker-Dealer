package com.code2hack.prototype.hotspot.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;

public final class FrameCodec {
    private static final int MAGIC = 0x50444850; // PDHP
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 40;
    private static final int MAX_BODY_BYTES = 65_536;

    private FrameCodec() {
    }

    public static Frame read(InputStream input) throws IOException {
        DataInputStream source = new DataInputStream(input);
        final int bodyLength;
        try {
            bodyLength = source.readInt();
        } catch (EOFException eof) {
            throw eof;
        }
        if (bodyLength < HEADER_BYTES || bodyLength > MAX_BODY_BYTES) {
            throw new IOException("Invalid frame body length: " + bodyLength);
        }

        byte[] body = new byte[bodyLength];
        source.readFully(body);
        DataInputStream frame = new DataInputStream(new ByteArrayInputStream(body));

        if (frame.readInt() != MAGIC) {
            throw new IOException("Invalid frame magic");
        }
        int version = frame.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("Unsupported frame version: " + version);
        }
        byte type = frame.readByte();
        frame.readUnsignedShort(); // reserved flags
        long sessionId = frame.readLong();
        long sequence = frame.readLong();
        long sentNanos = frame.readLong();
        int payloadLength = frame.readInt();
        long expectedCrc = Integer.toUnsignedLong(frame.readInt());
        if (payloadLength != bodyLength - HEADER_BYTES) {
            throw new IOException("Payload length mismatch: " + payloadLength);
        }
        byte[] payload = new byte[payloadLength];
        frame.readFully(payload);
        if (payloadCrc(payload) != expectedCrc) {
            throw new IOException("Payload CRC mismatch");
        }
        return new Frame(type, sessionId, sequence, sentNanos, payload);
    }

    public static void write(OutputStream output, Frame value) throws IOException {
        if (value.payload.length > MAX_BODY_BYTES - HEADER_BYTES) {
            throw new IOException("Payload too large: " + value.payload.length);
        }

        ByteArrayOutputStream bodyBytes =
                new ByteArrayOutputStream(HEADER_BYTES + value.payload.length);
        DataOutputStream body = new DataOutputStream(bodyBytes);
        body.writeInt(MAGIC);
        body.writeByte(VERSION);
        body.writeByte(value.type);
        body.writeShort(0);
        body.writeLong(value.sessionId);
        body.writeLong(value.sequence);
        body.writeLong(value.sentNanos);
        body.writeInt(value.payload.length);
        body.writeInt((int) payloadCrc(value.payload));
        body.write(value.payload);
        body.flush();

        DataOutputStream destination = new DataOutputStream(output);
        destination.writeInt(bodyBytes.size());
        bodyBytes.writeTo(destination);
        destination.flush();
    }

    private static long payloadCrc(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return crc.getValue();
    }
}
