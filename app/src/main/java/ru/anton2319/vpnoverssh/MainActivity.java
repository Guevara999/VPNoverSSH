package ru.anton2319.vpnoverssh;

// ... (imports)

public class MainActivity extends AppCompatActivity {
    // ... (fields)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ... (intent setup)
        setContentView(R.layout.activity_main);
        // ... (profile loading)

        Button connectButton = findViewById(R.id.ssh_connect_button);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(selectedProfile != null) {
                    // EXTRACT PAYLOAD STRING: Grabs user configurations
                    EditText editPayload = findViewById(R.id.editPayload);
                    String payloadStr = editPayload != null ? editPayload.getText().toString() : "";
                    
                    // SAVE TO STORAGE: Keeps text across background executions and reboots
                    getSharedPreferences("vpn_settings", MODE_PRIVATE)
                        .edit()
                        .putString("custom_payload", payloadStr)
                        .apply();

                    startVpn(/*...*/);
                }
                // ...
            }
        });
        // ...
    }

    @Override
    public void onResume() {
        super.onResume();
        // ... (spinner update)
        
        // RESTORE PAYLOAD STRING VALUE ON RESUME
        EditText editPayload = findViewById(R.id.editPayload);
        if (editPayload != null) {
            String savedPayload = getSharedPreferences("vpn_settings", MODE_PRIVATE).getString("custom_payload", "");
            editPayload.setText(savedPayload);
        }
        // ... (status update)
    }

    private void startVpn(/*...*/) {
        if(!StatusInfo.getInstance().isActive()) {
            // ... (permission checks)
            try {
                // ... (sshIntent extras)
                
                // ROUTE EXTRA PAYLOAD INJECTOR INTENT DATA: Pack string data
                String savedPayload = getSharedPreferences("vpn_settings", MODE_PRIVATE).getString("custom_payload", "");
                sshIntent.putExtra("payload", savedPayload);

                startService(sshIntent);
                // ... (proxy setup)
            } catch (Exception e) { /*...*/ }
        } else { /*...*/ }
    }
    // ... (helper methods)
}
