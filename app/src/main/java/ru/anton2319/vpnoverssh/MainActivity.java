package ru.anton2319.vpnoverssh;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

import ru.anton2319.vpnoverssh.data.SSHConnectionProfile;
import ru.anton2319.vpnoverssh.data.singleton.SocksPersistent;
import ru.anton2319.vpnoverssh.data.singleton.StatusInfo;
import ru.anton2319.vpnoverssh.data.utils.SSHConnectionProfileAdapter;
import ru.anton2319.vpnoverssh.data.utils.SSHConnectionProfileManager;
import ru.anton2319.vpnoverssh.services.SocksProxyService;
import ru.anton2319.vpnoverssh.services.SshService;

// MainActivity handles UI interaction for SSH/VPN connections, profile selection,
// and payload configuration. Includes service lifecycle management.
public class MainActivity extends AppCompatActivity {
    List<SSHConnectionProfile> sshConnectionProfileList;
    SSHConnectionProfile selectedProfile;
    private static final String TAG = "MainActivity";
    private String privateKey;
    Intent vpnIntent, sshIntent;
    MutableLiveData<String> connectButtonData = new MutableLiveData<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize Intents and Views
        if(StatusInfo.getInstance().getVpnIntent() == null) StatusInfo.getInstance().setVpnIntent(new Intent(this, SocksProxyService.class));
        if(StatusInfo.getInstance().getSshIntent() == null) StatusInfo.getInstance().setSshIntent(new Intent(this, SshService.class));
        vpnIntent = StatusInfo.getInstance().getVpnIntent();
        sshIntent = StatusInfo.getInstance().getSshIntent();

        Context context = this;
        SSHConnectionProfileManager sshConnectionProfileManager = new SSHConnectionProfileManager(this);
        sshConnectionProfileList = sshConnectionProfileManager.loadProfiles();
        SSHConnectionProfileAdapter adapter = new SSHConnectionProfileAdapter(this, sshConnectionProfileList);
        
        Spinner spinner = findViewById(R.id.spinner);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedProfile = SSHConnectionProfile.fromLinkedTreeMap(parent.getItemAtPosition(position));
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Setup Buttons
        findViewById(R.id.editProfileButton).setOnClickListener(v -> {
            if(selectedProfile != null && selectedProfile.uuid != null) {
                Intent intent = new Intent(context, NewConnectionActivity.class);
                intent.putExtra("uuid", selectedProfile.uuid.toString());
                startActivity(intent);
            }
        });
        findViewById(R.id.addProfileButton).setOnClickListener(v -> startActivity(new Intent(context, NewConnectionActivity.class)));
        
        Button connectButton = findViewById(R.id.ssh_connect_button);
        connectButton.setOnClickListener(view -> {
            if(selectedProfile != null) {
                EditText editPayload = findViewById(R.id.editPayload);
                getSharedPreferences("vpn_settings", MODE_PRIVATE).edit().putString("custom_payload", editPayload != null ? editPayload.getText().toString() : "").apply();
                startVpn(selectedProfile.getUsername(), selectedProfile.getPassword(), selectedProfile.getPrivateKey(), selectedProfile.getServerIP(), selectedProfile.getServerPort());
            } else startActivity(new Intent(context, NewConnectionActivity.class));
        });

        connectButtonData.observe(this, connectButton::setText);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh profiles and payload
        SSHConnectionProfileAdapter adapter = new SSHConnectionProfileAdapter(this, new SSHConnectionProfileManager(this).loadProfiles());
        ((Spinner) findViewById(R.id.spinner)).setAdapter(adapter);
        EditText editPayload = findViewById(R.id.editPayload);
        if (editPayload != null) editPayload.setText(getSharedPreferences("vpn_settings", MODE_PRIVATE).getString("custom_payload", ""));
        connectButtonData.postValue(StatusInfo.getInstance().isActive() ? "disconnect" : "connect");
    }

    // Menu and Service Methods
    @Override
    public boolean onCreateOptionsMenu(Menu menu) { getMenuInflater().inflate(R.menu.menu_main, menu); return true; }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) startActivity(new Intent(this, SettingsActivity.class));
        return super.onOptionsItemSelected(item);
    }

    private void startVpn(String username, String password, String privateKey, String hostname, int port) {
        if(!StatusInfo.getInstance().isActive()) {
            Intent intentPrepare = VpnService.prepare(this);
            if (intentPrepare != null) { startActivityForResult(intentPrepare, 0); return; }
            StatusInfo.getInstance().setActive(true);
            connectButtonData.postValue("disconnect");
            try {
                sshIntent.putExtra("user", username);
                sshIntent.putExtra("password", password);
                if (privateKey != null && !privateKey.isEmpty()) sshIntent.putExtra("privateKey", privateKey);
                sshIntent.putExtra("hostname", hostname);
                sshIntent.putExtra("port", String.valueOf(port > 0 ? port : 22));
                sshIntent.putExtra("payload", getSharedPreferences("vpn_settings", MODE_PRIVATE).getString("custom_payload", ""));
                startService(sshIntent);
                vpnIntent.putExtra("socksPort", 1080);
                startService(vpnIntent);
            } catch (Exception e) { StatusInfo.getInstance().setActive(false); e.printStackTrace(); }
        } else {
            StatusInfo.getInstance().setActive(false);
            connectButtonData.postValue("connect");
            if(SocksPersistent.getInstance().getVpnThread() != null) SocksPersistent.getInstance().getVpnThread().interrupt();
            stopService(sshIntent);
        }
    }
}
