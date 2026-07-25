package com.code2hack.prototype.hotspot.dealer;

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
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class DealerTransportService extends Service {
    public static final int PORT = 39_817;
    public static final int CONTROL_PORT = 39_818;
    public static final String DEFAULT_GLASSES_HOST = "10.84.179.154";
    private static final String TAG = "HotspotDealerProto";
    private static final String CHANNEL_ID = "hotspot-dealer-prototype";
    private static final int NOTIFICATION_ID = 39_817;
    private static final long PROBE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_BURST_COUNT = 20_000;

    private final LocalBinder binder = new LocalBinder();
    private final SessionMetrics metrics =
            new SessionMetrics("Fold6 TCP client", SystemClock::elapsedRealtimeNanos);
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService burstExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong controlSequence = new AtomicLong();
    private final long processSessionId = new SecureRandom().nextLong();

    private volatile boolean running;
    private volatile Peer peer;
    private volatile Socket connectingSocket;
    private volatile ServerSocket controlServerSocket;
    private volatile String hostOverride = "";
    private volatile String resolution = "default -> " + DEFAULT_GLASSES_HOST;
    private volatile boolean wakeLockEnabled;
    private PowerManager.WakeLock wakeLock;

    public final class LocalBinder extends Binder {
        public DealerTransportService service() {
            return DealerTransportService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        running = true;
        startClientLoop();
        startControlServer();
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
        closeConnectingSocket();
        closeControlServer();
        setWakeLockEnabled(false);
        scheduler.shutdownNow();
        burstExecutor.shutdownNow();
        controlExecutor.shutdownNow();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    public String snapshot() {
        return metrics.render(isWakeLockHeld())
                + "process_session: " + Long.toUnsignedString(processSessionId, 16) + "\n"
                + "host_resolution: " + resolution + "\n"
                + "transport_port: " + PORT + "\n"
                + "loopback_control: 127.0.0.1:" + CONTROL_PORT + "\n";
    }

    public String hostOverride() {
        return hostOverride;
    }

    public String effectiveHost() {
        String override = hostOverride;
        return override.isEmpty() ? DEFAULT_GLASSES_HOST : override;
    }

    public void setHostOverride(String value) {
        hostOverride = value == null ? "" : value.trim();
        closePeer("host override changed");
        closeConnectingSocket();
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

    public void requestPeerBurst(int count) {
        int bounded = requireBurstCount(count);
        Peer connected = peer;
        if (connected == null) {
            throw new IllegalStateException("No glasses peer is connected");
        }
        long request = controlSequence.incrementAndGet();
        try {
            connected.send(Frame.text(
                    Frame.REQUEST_SERVER_BURST,
                    processSessionId,
                    request,
                    SystemClock.elapsedRealtimeNanos(),
                    Integer.toString(bounded)
            ));
            metrics.transition(
                    SessionMetrics.State.CONNECTED,
                    "requested glasses burst " + bounded
            );
        } catch (IOException error) {
            metrics.markDisconnected(describe(error));
            closePeer("peer-burst request failed");
            throw new IllegalStateException(describe(error), error);
        }
    }

    public void dropConnection() {
        closePeer("manual socket drop");
        closeConnectingSocket();
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
                        "PokerDealerPrototype:Fold6Transport"
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

    private void startClientLoop() {
        networkExecutor.execute(() -> {
            long backoffMillis = 250;
            while (running) {
                Socket socket = null;
                try {
                    String host = effectiveHost();
                    resolution = (hostOverride.isEmpty() ? "default" : "manual")
                            + " -> " + host + " (kernel-routed)";
                    metrics.markConnectionAttempt(host + ":" + PORT);

                    // Deliberately unbound. The Fold6 hotspot is not exposed as a
                    // TRANSPORT_WIFI Network; Linux routing selects its tether interface.
                    socket = new Socket();
                    connectingSocket = socket;
                    socket.connect(new InetSocketAddress(host, PORT), 3_000);
                    connectingSocket = null;
                    socket.setTcpNoDelay(true);
                    socket.setKeepAlive(true);
                    socket.setSoTimeout(15_000);

                    Peer connected = new Peer(socket);
                    peer = connected;
                    metrics.markConnected(connected.endpoint() + " via " + resolution);
                    Log.i(TAG, "connected " + connected.endpoint() + " via " + resolution);
                    connected.send(Frame.text(
                            Frame.HELLO,
                            processSessionId,
                            0,
                            SystemClock.elapsedRealtimeNanos(),
                            "dealer"
                    ));
                    backoffMillis = 250;

                    while (running && connected == peer && !socket.isClosed()) {
                        Frame frame = FrameCodec.read(connected.input);
                        metrics.onFrameReceived();
                        handleFrame(connected, frame);
                    }
                } catch (EOFException eof) {
                    metrics.markDisconnected("peer closed");
                    Log.i(TAG, "peer closed");
                } catch (Exception error) {
                    if (running) {
                        metrics.markDisconnected(describe(error));
                        Log.w(TAG, "connection failed", error);
                    }
                } finally {
                    connectingSocket = null;
                    if (peer != null && peer.socket == socket) {
                        peer = null;
                    }
                    closeQuietly(socket);
                }

                if (running) {
                    metrics.transition(
                            SessionMetrics.State.BACKING_OFF,
                            "retry in " + backoffMillis + " ms"
                    );
                    sleepQuietly(backoffMillis);
                    backoffMillis = Math.min(backoffMillis * 2, 5_000);
                }
            }
        });
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
            case Frame.SERVER_PROBE:
                metrics.onInboundSequence(frame.sessionId, frame.sequence);
                connected.send(new Frame(
                        Frame.SERVER_PROBE_ACK,
                        processSessionId,
                        frame.sequence,
                        frame.sentNanos,
                        frame.payload
                ));
                break;
            case Frame.CLIENT_PROBE_ACK:
                metrics.onProbeAcked(frame.sequence, SystemClock.elapsedRealtimeNanos());
                break;
            case Frame.CONTROL_ACK:
                metrics.transition(
                        SessionMetrics.State.CONNECTED,
                        "glasses control ack: " + frame.payloadText()
                );
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
                    Frame.CLIENT_PROBE,
                    processSessionId,
                    next,
                    now,
                    "dealer-probe-" + next
            ));
            return true;
        } catch (IOException error) {
            metrics.markDisconnected(describe(error));
            closePeer("send failed");
            return false;
        }
    }

    private void startControlServer() {
        controlExecutor.execute(() -> {
            try (ServerSocket listening = new ServerSocket()) {
                controlServerSocket = listening;
                listening.setReuseAddress(true);
                listening.bind(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"),
                        CONTROL_PORT
                ));
                Log.i(TAG, "test control listening on 127.0.0.1:" + CONTROL_PORT);
                while (running) {
                    try (Socket control = listening.accept()) {
                        handleControlConnection(control);
                    } catch (IOException error) {
                        if (running) {
                            Log.w(TAG, "control connection failed", error);
                        }
                    }
                }
            } catch (IOException error) {
                if (running) {
                    metrics.markError("loopback control: " + describe(error));
                    Log.e(TAG, "control server failed", error);
                }
            } finally {
                controlServerSocket = null;
            }
        });
    }

    private void handleControlConnection(Socket socket) throws IOException {
        socket.setSoTimeout(5_000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(),
                StandardCharsets.UTF_8
        ));
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        String command = reader.readLine();
        if (command == null) {
            writer.println("ERROR empty command");
            return;
        }
        try {
            writer.print(executeControlCommand(command));
        } catch (RuntimeException error) {
            writer.println("ERROR " + describe(error));
        }
        writer.flush();
    }

    private String executeControlCommand(String commandLine) {
        String normalized = commandLine.trim();
        String upper = normalized.toUpperCase(Locale.US);
        if (upper.equals("SNAPSHOT")) {
            return snapshot() + "END\n";
        }
        if (upper.equals("DROP")) {
            dropConnection();
            return "OK connection dropped; reconnect remains enabled\n";
        }
        if (upper.equals("RESET")) {
            resetCounters();
            return "OK counters reset\n";
        }
        if (upper.equals("WAKELOCK ON")) {
            setWakeLockEnabled(true);
            return "OK wake lock on\n";
        }
        if (upper.equals("WAKELOCK OFF")) {
            setWakeLockEnabled(false);
            return "OK wake lock off\n";
        }
        if (upper.startsWith("BURST ")) {
            int count = parseBurstCount(normalized.substring("BURST ".length()));
            runBurst(count);
            return "OK dealer burst " + count + " queued\n";
        }
        if (upper.startsWith("PEER_BURST ")) {
            int count = parseBurstCount(normalized.substring("PEER_BURST ".length()));
            requestPeerBurst(count);
            return "OK glasses burst " + count + " requested\n";
        }
        return "ERROR unknown command\n";
    }

    private static int parseBurstCount(String value) {
        try {
            return requireBurstCount(Integer.parseInt(value.trim()));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid burst count");
        }
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

    private void closeConnectingSocket() {
        Socket connecting = connectingSocket;
        connectingSocket = null;
        closeQuietly(connecting);
    }

    private void closeControlServer() {
        ServerSocket listening = controlServerSocket;
        controlServerSocket = null;
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
        Intent activity = new Intent(this, DealerPrototypeActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                activity,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Dealer hotspot prototype")
                .setContentText("TCP client reconnecting to glasses")
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
            return socket.getLocalSocketAddress() + " -> " + socket.getRemoteSocketAddress();
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
