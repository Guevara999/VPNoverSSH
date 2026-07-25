package ru.anton2319.vpnoverssh.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.DynamicPortForwarder;
import com.trilead.ssh2.HTTPProxyData;

import java.io.IOException;
import java.net.Socket;
import java.util.Optional;

import ru.anton2319.vpnoverssh.PayloadEngine;
import ru.anton2319.vpnoverssh.data.singleton.PortForward;

public class SshService extends Service {

    private static final String TAG = "SshService";
    Thread sshThread;
    Connection conn;
    DynamicPortForwarder forwarder;
    SharedPreferences sharedPreferences;

    private static final String CHANNEL_ID = "ssh_service_channel";
    private static final int NOTIF_ID = 1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Ensure notification channel & foreground service for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "SSH Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VPNoverSSH")
                .setContentText("SSH tunnel running")
                .setSmallIcon(android.R.drawable.stat_sys_tether)
                .setOngoing(true)
                .build();

        startForeground(NOTIF_ID, notif);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sshThread = PortForward.getInstance().getSshThread();
        if (sshThread != null) {
            sshThread.interrupt();
        }
        sshThread = newSshThread(intent);
        PortForward.getInstance().setSshThread(sshThread);
        sshThread.start();

        return START_STICKY;
    }

    public void initiateSSH(Intent intent) throws IOException, RuntimeException {
        Log.d(TAG, "Starting trilead.ssh2 service");

        String user = intent.getStringExtra("user");
        String host = intent.getStringExtra("hostname");
        String password = intent.getStringExtra("password");
        int port = Integer.parseInt(Optional.ofNullable(intent.getStringExtra("port")).orElse("22"));
        String privateKey = intent.getStringExtra("privateKey");

        String rawPayload = intent.getStringExtra("payload");
        String remoteProxyString = intent.getStringExtra("remote_proxy");

        // Safely evaluate whether the remote proxy configuration string is present
        if (rawPayload != null && !rawPayload.trim().isEmpty() && remoteProxyString != null && remoteProxyString.contains(":")) {
            Log.d(TAG, "Parsing Remote Proxy and Executing Custom Payload Engine");

            String proxyHost = "";
            int proxyPort = 8080;

            try {
                // Safeguard parser loop to completely prevent array index boundary crashes
                String[] proxyParts = remoteProxyString.split(":");
                if (proxyParts.length >= 2) {
                    proxyHost = proxyParts[0].trim();
                    proxyPort = Integer.parseInt(proxyParts[1].trim());
                } else {
                    throw new IllegalArgumentException("Invalid Remote Proxy Format. Use host:port");
                }

                String preparedPayload = PayloadEngine.formatPayloadString(rawPayload, host, String.valueOf(port));

                // Establish tunnel socket routing directly to the proxy destination server address
                try (Socket payloadSocket = new Socket(proxyHost, proxyPort)) {
                    PayloadEngine.transmitPayload(payloadSocket, preparedPayload);
                }

                conn = new Connection(host, port);
                conn.setProxyData(new HTTPProxyData(proxyHost, proxyPort));
            } catch (Exception e) {
                throw new IOException("Remote Proxy Payload Injection Pipeline Failed: " + e.getMessage(), e);
            }
        } else {
            // Direct protocol fallback if parameters are empty
            conn = new Connection(host, port);
        }

        conn.connect();
        PortForward.getInstance().setConn(conn);

        int attempts = 1;
        boolean isAuthenticated = false;

        // Normalize privateKey (empty -> null)
        if (privateKey != null && privateKey.isEmpty()) privateKey = null;

        while (attempts-- > 0) {
            if (password ==            if (privateKey != null && password != null) {
                isAuthenticated = conn.authenticateWithPublicKey(user, privateKey.toCharArray(), "");
                if (isAuthenticated) {
                    break;
                }
                isAuthenticated = conn.authenticateWithPassword(user, password);
                if (!isAuthenticated) {
                    throw new RuntimeException("Cannot authenticate with the provided credentials");
                }
            } else {
                if (privateKey != null) {
                    isAuthenticated = conn.authenticateWithPublicKey(user, privateKey.toCharArray(), "");
                }
                if (password != null) {
                    isAuthenticated = conn.authenticateWithPassword(user, password);
                }
            }

            if(isAuthenticated) {
                break;
            }
        }

        if (!isAuthenticated) {
            throw new RuntimeException("Cannot authenticate with the provided credentials");
        }

        int forwarderPort = Integer.parseInt(sharedPreferences.getString("forwarder_port", "1080"));
        forwarder = conn.createDynamicPortForwarder(forwarderPort);
        PortForward.getInstance().setForwarder(forwarder);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Shutting down gracefully");
        sshThread = PortForward.getInstance().getSshThread();
        if (sshThread != null) {
            sshThread.interrupt();
        }

        // Close resources if still open
        Connection c = PortForward.getInstance().getConn();
        DynamicPortForwarder f = PortForward.getInstance().getForwarder();
        try {
            if (f != null) f.close();
        } catch (Exception e) {
            Log.w(TAG, "Error closing forwarder: " + e.getMessage());
        }
        try {
            if (c != null) c.close();
        } catch (Exception e) {
            Log.w(TAG, "Error closing connection: " + e.getMessage());
        }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public Thread newSshThread(Intent intent) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    System.setProperty("user.home", getFilesDir().getAbsolutePath());
                    initiateSSH(intent);

                    // Wait without busy-looping. Sleep until interrupted.
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(Long.MAX_VALUE);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw ie;
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    e.printStackTrace();
                    Log.d(TAG, "trilead.ssh2 failed, and there is no fallback yet ¯\\_(ツ)_/¯");
                } catch (InterruptedException e) {
                    // Interrupted -> clean up
                    conn = PortForward.getInstance().getConn();
                    forwarder = PortForward.getInstance().getForwarder();
                    if (conn != null) conn.close();
                    if (forwarder != null) forwarder.close();
                    stopSelf();
                }
            }
        });
    }
}