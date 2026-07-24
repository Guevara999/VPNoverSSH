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
        String remoteProxyString = intent.getStringExtra("remote_proxy");

        if (rawPayload != null && !rawPayload.trim().isEmpty() && remoteProxyString != null && remoteProxyString.contains(":")) {
            Log.d(TAG, "Parsing Remote Proxy and Executing Custom Payload Engine");
            
            String[] proxyParts = remoteProxyString.split(":");
            // FIXED: Target specific element array index locations [0] and [1] before running .trim()
            String proxyHost = proxyParts[0].trim();
            int proxyPort = Integer.parseInt(proxyParts[1].trim());

            String preparedPayload = PayloadEngine.formatPayloadString(rawPayload, host, String.valueOf(port));

            try {
                Socket payloadSocket = new Socket(proxyHost, proxyPort);
                PayloadEngine.transmitPayload(payloadSocket, preparedPayload);
                
                conn = new Connection(host, port);
                conn.setProxyData(new HTTPProxyData(proxyHost, proxyPort)); 
            } catch (Exception e) {
                throw new IOException("Remote Proxy Payload Injection Pipeline Failed: " + e.getMessage());
            }
        } else {
            conn = new Connection(host, port);
        }

        conn.connect();
        PortForward.getInstance().setConn(conn);

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
