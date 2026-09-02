package com.rch.mdm.sample;

import static android.view.View.VISIBLE;

import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.hmdm.HeadwindMDM;
import com.rch.mdm.MdmConfig;
import com.rch.mdm.MdmException;
import com.rch.mdm.MdmService;

/**
 * Sample app for {@code mdmlib}.
 *
 * <p>The MDM server URL and the device numbers are hard-coded (same values as the
 * original {@code change_dev_number.py}). Only the username and password are asked
 * for at runtime, through a dialog.</p>
 */
public class MainActivity extends AppCompatActivity implements HeadwindMDM.EventHandler{

    // --- Hard-coded configuration (from change_dev_number.py) ---
   

    private TextView logView;
    private ProgressBar progress;
    private Button changeButton;

    private Button getButton;

    /** Created once we know the credentials; reused for the lifetime of the activity. */
    private MdmService mdmService;


    HeadwindMDM headwindMDM;

    TextView deviceInfo;

    boolean hmdmConnected =false;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceInfo = findViewById(R.id.deviceInfo);

        headwindMDM= HeadwindMDM.getInstance();

        if (!headwindMDM.isConnected()) {
            if (!headwindMDM.connect(this, MainActivity.this)) {
            }
        }



        logView = findViewById(R.id.log);
        progress = findViewById(R.id.progress);
        changeButton = findViewById(R.id.changeButton);
        changeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                askCredentialsThenRun();
            }
        });

        getButton = findViewById(R.id.getButton);
        getButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                headwindMDM.disconnect(MainActivity.this);
                headwindMDM.connect(MainActivity.this, MainActivity.this);
            }
        });



    }

    @Override
    protected void onDestroy() {
        if (mdmService != null) {
            mdmService.shutdown();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------

    private void askCredentialsThenRun() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_credentials, null);
        final EditText usernameField = dialogView.findViewById(R.id.username);
        final EditText passwordField = dialogView.findViewById(R.id.password);
        final EditText deviceNumberField = dialogView.findViewById(R.id.deviceNumber);

        new AlertDialog.Builder(this)
                .setTitle("MDM credentials")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String username = usernameField.getText().toString().trim();
                    String password = passwordField.getText().toString();
                    String deviceNumber = deviceNumberField.getText().toString();
                    if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password) || TextUtils.isEmpty(deviceNumber)) {
                        Toast.makeText(this, "Username,password and new device number are required",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runChangeDeviceNumber(username, password,deviceNumber);
                })
                .show();
    }

    private void runChangeDeviceNumber(String username, String password, String deviceNumber) {
        // A fresh service each run keeps this sample simple (credentials may change
        // between runs). shutdown() the previous one so its thread is released.
        if (mdmService != null) {
            mdmService.shutdown();
        }
        mdmService = new MdmService(new MdmConfig(headwindMDM.getServerUrl(), username, password));

        setBusy(true);
        log("Authenticating as \"" + username + "\" and renaming "
                + headwindMDM.getDeviceId() + " -> " + deviceNumber + " ...");

        mdmService.changeDeviceNumber(headwindMDM.getDeviceId(), deviceNumber,
                new MdmService.Callback<Long>() {
                    @Override public void onSuccess(Long deviceId) {
                        setBusy(false);
                        log("OK - device id " + deviceId
                                + " renamed to " + deviceNumber
                                + " and configUpdated push sent.");
                    }

                    @Override public void onError(@NonNull MdmException e) {
                        setBusy(false);
                        StringBuilder sb = new StringBuilder("FAILED: ").append(e.getMessage());
                        if (e.getStatusCode() > 0) {
                            sb.append("\nHTTP ").append(e.getStatusCode());
                        }
                        if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                            sb.append("\n").append(e.getResponseBody());
                        }
                        log(sb.toString());
                    }
                });
    }

    // ------------------------------------------------------------------

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? VISIBLE : View.GONE);
        changeButton.setEnabled(!busy);
    }

    private void log(String message) {
        String stamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        String line = "[" + stamp + "] " + message + "\n";
        logView.append(line);
    }

    @Override
    public void onHeadwindMDMConnected() {
        changeButton.setVisibility(VISIBLE);
        getButton.setVisibility(VISIBLE);
        hmdmConnected=true;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                deviceInfo.setText(
                        "Server: " + headwindMDM.getServerHost() + "\n"
                                + "Current device number: " + headwindMDM.getDeviceId() );
            }
        });

    }

    @Override
    public void onHeadwindMDMDisconnected() {

    }

    @Override
    public void onHeadwindMDMConfigChanged() {

    }
}
