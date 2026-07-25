package com.code2hack.prototype.hotspot.poker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.code2hack.prototype.hotspot.core.Frame;
import com.code2hack.prototype.hotspot.core.FrameCodec;
import com.code2hack.prototype.hotspot.core.SessionMetrics;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class PokerTransportService extends Service {
    public static final int PORT = 39_817;
    private static final String TAG = "HotspotPokerProto";
    private static final String CHANNEL_ID = "hotspot-poker-prototype";
    private static final int NOTIFICATION_ID = 39_818;
    private static final long PROBE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int PEER_READ_TIMEOUT_MILLIS = 15_000;
    private static final int MAX_BURST_COUNT = 20_000;

    private final LocalBinder binder = new LocalBinder();
    private final SessionMetrics metrics =
            new SessionMetrics("RG-glasses TCP server", SystemClock::elapsedRealtimeNanos);
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService burstExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong sequence = new AtomicLong();
    private final long processSessionId = new SecureRandom().nextLong();

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile Peer peer;
    private volatile boolean wakeLockEnabled;
    private PowerManager.WakeLock wakeLock;

    public final class LocalBinder extends Binder {
        public PokerTransportService service() {
            return PokerTransportService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        running = true;
        startServer();
        scheduler.scheduleAtFixedRate(this::scheduledTick, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    protected void dump(FileDescriptor fileDescriptor, PrintWriter writer, String[] args) {
        writer.print(snapshot());
    }

    @Override
    public void onDestroy() {
        running = false;
        closePeer("service destroyed");
        closeServer();
        setWakeLockEnabled(false);
        scheduler.shutdownNow();
        burstExecutor.shutdownNow();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    public String snapshot() {
        return metrics.render(isWakeLockHeld())
                + "process_session: " + Long.toUnsignedString(processSessionId, 16) + "\n"
                + "transport_port: " + PORT + "\n";
    }

    public void runBurst(int count) {
        int bounded = requireBurstCount(count);
        burstExecutor.execute(() -> {
            int sent = 0;
            while (sent < bounded && running) {
                while (metrics.pendingProbeCount() >= 64 && running) {
                    sleepQuietly(1);
                }
                if (sendProbe()) {
                    sent++;
                    if (sent % 100 == 0) {
                        Thread.yield();
                    }
                } else {
                    sleepQuietly(25);
                }
            }
        });
    }

    public void dropConnection() {
        closePeer("manual socket drop");
    }

    public void resetCounters() {
        metrics.resetCounters();
    }

    public synchronized void setWakeLockEnabled(boolean enabled) {
        wakeLockEnabled = enabled;
        if (enabled) {
            if (wakeLock == null) {
                PowerManager power = getSystemService(PowerManager.class);
                wakeLock = power.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "PokerDealerPrototype:GlassesTransport"
                );
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } else if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    public boolean isWakeLockHeld() {
        return wakeLockEnabled && wakeLock != null && wakeLock.isHeld();
    }

    private void startServer() {
        networkExecutor.execute(() -> {
            while (running) {
                try (ServerSocket listening = new ServerSocket()) {
                    serverSocket = listening;
                    listening.setReuseAddress(true);
                    listening.bind(new InetSocketAddress("0.0.0.0", PORT));
                    metrics.markListening("0.0.0.0:" + PORT);
                    Log.i(TAG, "listening on 0.0.0.0:" + PORT);

                    while (running) {
                        Socket socket = listening.accept();
                        if (socket.getInetAddress().isLoopbackAddress()) {
                            socket.close();
                            continue;
                        }
                        handlePeer(socket);
                    }
                } catch (IOException error) {
                    if (running) {
                        metrics.markError(describe(error));
                        Log.w(TAG, "server failed", error);
                        sleepQuietly(1_000);
                    }
                } finally {
                    serverSocket = null;
                }
            }
        });
    }

    private void handlePeer(Socket socket) {
        closePeer("replaced by new client");
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            // A half-open/stale client cannot monopolize accept forever. If no
            // frame arrives for 15 seconds, this read loop exits and accept resumes.
            socket.setSoTimeout(PEER_READ_TIMEOUT_MILLIS);
            Peer connected = new Peer(socket);
            peer = connected;
            metrics.markConnected(connected.endpoint());
            Log.i(TAG, "connected " + connected.endpoint());
            connected.send(Frame.text(
                    Frame.HELLO,
                    processSessionId,
                    0,
                    SystemClock.elapsedRealtimeNanos(),
                    "poker"
            ));

            while (running && connected == peer && !socket.isClosed()) {
                Frame frame = FrameCodec.read(connected.input);
                metrics.onFrameReceived();
                handleFrame(connected, frame);
            }
        } catch (EOFException eof) {
            metrics.markDisconnected("peer closed");
            Log.i(TAG, "peer closed");
        } catch (IOException error) {
            if (running) {
                metrics.markDisconnected(describe(error));
                Log.w(TAG, "connection failed", error);
            }
        } finally {
            if (peer != null && peer.socket == socket) {
                peer = null;
            }
            closeQuietly(socket);
            if (running) {
                metrics.markListening("0.0.0.0:" + PORT);
            }
        }
    }

    private void handleFrame(Peer connected, Frame frame) throws IOException {
        switch (frame.type) {
            case Frame.HELLO:
                connected.send(Frame.text(
                        Frame.HELLO_ACK,
                        processSessionId,
                        frame.sequence,
                        frame.sentNanos,
                        "ack:" + frame.payloadText()
                ));
                break;
            case Frame.HELLO_ACK:
                metrics.markHandshake("hello/ack complete");
                break;
            case Frame.CLIENT_PROBE:
                metrics.onInboundSequence(frame.sessionId, frame.sequence);
                connected.send(new Frame(
                        Frame.CLIENT_PROBE_ACK,
                        processSessionId,
                        frame.sequence,
                        frame.sentNanos,
                        frame.payload
                ));
                break;
            case Frame.SERVER_PROBE_ACK:
                metrics.onProbeAcked(frame.sequence, SystemClock.elapsedRealtimeNanos());
                break;
            case Frame.REQUEST_SERVER_BURST:
                int count = parseBurstCount(frame.payloadText());
                connected.send(Frame.text(
                        Frame.CONTROL_ACK,
                        processSessionId,
                        frame.sequence,
                        SystemClock.elapsedRealtimeNanos(),
                        "server burst " + count + " queued"
                ));
                metrics.transition(
                        SessionMetrics.State.CONNECTED,
                        "Fold6 requested server burst " + count
                );
                runBurst(count);
                break;
            default:
                metrics.onInvalidFrame("unexpected type " + frame.type);
        }
    }

    private void scheduledTick() {
        metrics.expireProbes(SystemClock.elapsedRealtimeNanos(), PROBE_TIMEOUT_NANOS);
        sendProbe();
    }

    private boolean sendProbe() {
        Peer connected = peer;
        if (connected == null) {
            return false;
        }
        long next = sequence.incrementAndGet();
        long now = SystemClock.elapsedRealtimeNanos();
        metrics.onProbeSent(next, now);
        try {
            connected.send(Frame.text(
                    Frame.SERVER_PROBE,
                    processSessionId,
                    next,
                    now,
                    "poker-probe-" + next
            ));
            return true;
        } catch (IOException error) {
            metrics.markDisconnected(describe(error));
            closePeer("send failed");
            return false;
        }
    }

    private static int parseBurstCount(String value) throws IOException {
        final int count;
        try {
            count = Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            throw new IOException("Invalid server burst count");
        }
        if (count < 1 || count > MAX_BURST_COUNT) {
            throw new IOException(
                    "Server burst count must be between 1 and " + MAX_BURST_COUNT
            );
        }
        return count;
    }

    private static int requireBurstCount(int count) {
        if (count < 1 || count > MAX_BURST_COUNT) {
            throw new IllegalArgumentException(
                    "burst count must be between 1 and " + MAX_BURST_COUNT
            );
        }
        return count;
    }

    private void closePeer(String reason) {
        Peer connected = peer;
        peer = null;
        if (connected != null) {
            metrics.markDisconnected(reason);
            closeQuietly(connected.socket);
        }
    }

    private void closeServer() {
        ServerSocket listening = serverSocket;
        serverSocket = null;
        if (listening != null) {
            try {
                listening.close();
            } catch (IOException ignored) {
                // Throwaway prototype.
            }
        }
    }

    private void createNotificationChannel() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "Hotspot transport prototype",
                NotificationManager.IMPORTANCE_LOW
        ));
    }

    private Notification buildNotification() {
        Intent activity = new Intent(this, PokerPrototypeActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                activity,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Poker hotspot prototype")
                .setContentText("TCP server listening on port " + PORT)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private final class Peer {
        private final Socket socket;
        private final BufferedInputStream input;
        private final BufferedOutputStream output;

        private Peer(Socket socket) throws IOException {
            this.socket = socket;
            input = new BufferedInputStream(socket.getInputStream(), 65_536);
            output = new BufferedOutputStream(socket.getOutputStream(), 65_536);
        }

        private synchronized void send(Frame frame) throws IOException {
            FrameCodec.write(output, frame);
            metrics.onFrameSent();
        }

        private String endpoint() {
            return socket.getLocalSocketAddress() + " <- " + socket.getRemoteSocketAddress();
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Throwaway prototype.
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
