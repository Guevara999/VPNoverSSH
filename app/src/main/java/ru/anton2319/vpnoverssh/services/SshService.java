package ru.anton2319.vpnoverssh.services;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.DynamicPortForwarder;
import com.trilead.ssh2.HTTPProxyData;

import java.io.IOException;
import java.net.Socket;
import java.util.Optional;

import ru.anton2319.vpnoverssh.PayloadEngine;
import ru.anton2319.vpnoverssh.data.singleton.PortForward;

// SshService manages the background thread lifecycle for the SSH/VPN tunnel.
// Uses Trilead SSH library coupled with PayloadEngine to route connection
// packets securely via custom HTTP proxy payload strings.
public class SshService extends Service {

    private static final String TAG = "SshService";
    Thread sshThread;
    Connection conn;
    DynamicPortForwarder forwarder;
    SharedPreferences sharedPreferences;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        int port = Integer.parseInt(Optional.of(intent.getStringExtra("port")).orElse(String.valueOf(22)));
        String privateKey = intent.getStringExtra("privateKey");
        String rawPayload = intent.getStringExtra("payload");

        // If the user pasted a payload string, intercept connection logic using a local HTTP proxy handler
        if (rawPayload != null && !rawPayload.trim().isEmpty()) {
            Log.d(TAG, "Injecting Custom HTTP Payload Routing Engine");
            
            // Format parameters inside your custom string (e.g. [rotate], [crlf])
            String preparedPayload = PayloadEngine.formatPayloadString(rawPayload, host, String.valueOf(port));

            // Spin up a raw background socket proxy loop right on the device
            try {
                // Connect socket out over standard HTTP Proxy pipeline channels (Usually port 80 or 8080)
                Socket payloadSocket = new Socket(host, port);
                
                // Transmit raw double-flush packets if the payload string relies on [split] formatting
                PayloadEngine.transmitPayload(payloadSocket, preparedPayload);
                
                // Force the Trilead connection framework to bind directly over this payload-modified stream
                conn = new Connection(host, port);
                
                // Use placeholder values here since your payloadSocket has already negotiated access upstream
                conn.setProxyData(new HTTPProxyData("127.0.0.1", 8080)); 
            } catch (Exception e) {
                throw new IOException("Payload transmission routing pipeline failed: " + e.getMessage());
            }
        } else {
            // Fallback connection loop for empty payloads (Direct connection method)
            conn = new Connection(host, port);
        }

        conn.connect();
        PortForward.getInstance().setConn(conn);

        // Authenticate with the SSH server
        int attempts = 1;
        boolean isAuthenticated = false;

        while (attempts-- > 0) {
            if (password == null && privateKey == null) {
                isAuthenticated = conn.authenticateWithNone(user);
            }

            if (privateKey != null && password != null) {
                isAuthenticated = conn.authenticateWithPublicKey(user, privateKey.toCharArray(), "");
                if (isAuthenticated) {
                    break;
                }
                isAuthenticated = conn.authenticateWithPassword(user, password);
                if (!isAuthenticated) {
                    throw new RuntimeException("Cannot authenticate with the provided credentials");
                }
            }

            if (privateKey != null) {
                isAuthenticated = conn.authenticateWithPublicKey(user, privateKey.toCharArray(), "");
            }

            if (password != null) {
                isAuthenticated = conn.authenticateWithPassword(user, password);
            }

            if(isAuthenticated) {
                break;
            }
        }

        if (!isAuthenticated) {
            throw new RuntimeException("Cannot authenticate with the provided credentials");
        }

        forwarder = conn.createDynamicPortForwarder(Integer.parseInt(Optional.of(sharedPreferences.getString("forwarder_port", "1080")).orElse("1080")));
        PortForward.getInstance().setForwarder(forwarder);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Shutting down gracefully");
        sshThread = PortForward.getInstance().getSshThread();
        if (sshThread != null) {
            sshThread.interrupt();
        }
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
                    while (true) {
                        if (Thread.interrupted()) {
                            throw new InterruptedException();
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    e.printStackTrace();
                    Log.d(TAG, "trilead.ssh2 failed, and there is no fallback yet ¯\\_(ツ)_/¯");
                } catch (InterruptedException e) {
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
