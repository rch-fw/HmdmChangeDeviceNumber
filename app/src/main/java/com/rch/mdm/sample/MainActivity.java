package com.rch.mdm.sample;

import static android.view.View.VISIBLE;

import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

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

    private Button secondaryDisplayButton;

    /** Dialog currently shown on the secondary display, if any. */
    private Presentation secondaryDialog;

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

        secondaryDisplayButton = findViewById(R.id.secondaryDisplayButton);
        secondaryDisplayButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleSecondaryDialog();
            }
        });

    }

    @Override
    protected void onStop() {
        // A Presentation is tied to this activity's window; it must not outlive it.
        dismissSecondaryDialog();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mdmService != null) {
            mdmService.shutdown();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------

    private void toggleSecondaryDialog() {
        if (secondaryDialog != null) {
            dismissSecondaryDialog();
        } else {
            showDialogOnSecondaryDisplay();
        }
    }

    private void showDialogOnSecondaryDisplay() {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
            Toast.makeText(this, "No secondary display found", Toast.LENGTH_SHORT).show();
            return;
        }

        Display display = displays[0];
        secondaryDialog = new Presentation(this, display);
        // Also fires when the system dismisses the dialog (e.g. the display is removed),
        // so the button label always reflects the real state.
        secondaryDialog.setOnDismissListener(dialog -> {
            if (secondaryDialog == dialog) {
                secondaryDialog = null;
                updateSecondaryDisplayButton();
            }
        });
        secondaryDialog.setContentView(R.layout.dialog_secondary_display);
        TextView message = secondaryDialog.findViewById(R.id.secondaryMessage);
        message.setText("Opened from the main display on \"" + display.getName() + "\".");
        secondaryDialog.findViewById(R.id.secondaryOkButton).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dismissSecondaryDialog();
                    }
                });

        try {
            secondaryDialog.show();
            log("Dialog shown on secondary display \"" + display.getName() + "\"");
            updateSecondaryDisplayButton();
        } catch (WindowManager.InvalidDisplayException e) {
            // The display was removed between the lookup and show().
            secondaryDialog = null;
            log("FAILED to show dialog on secondary display: " + e.getMessage());
        }
    }

    private void dismissSecondaryDialog() {
        if (secondaryDialog != null) {
            Presentation dialog = secondaryDialog;
            secondaryDialog = null;
            dialog.dismiss();
            updateSecondaryDisplayButton();
        }
    }

    private void updateSecondaryDisplayButton() {
        secondaryDisplayButton.setText(secondaryDialog != null
                ? "Hide dialog on secondary display"
                : "Show dialog on secondary display");
    }

    // ------------------------------------------------------------------

    private void askCredentialsThenRun() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_credentials, null);
        final EditText usernameField = dialogView.findViewById(R.id.username);
        final EditText passwordField = dialogView.findViewById(R.id.password);
        final EditText deviceNumberField = dialogView.findViewById(R.id.deviceNumber);
        final EditText groupsField = dialogView.findViewById(R.id.groups);

        new AlertDialog.Builder(this)
                .setTitle("MDM credentials")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String username = usernameField.getText().toString().trim();
                    String password = passwordField.getText().toString();
                    String deviceNumber = deviceNumberField.getText().toString().trim();
                    List<String> groups = parseGroups(groupsField.getText().toString());
                    if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)
                            || (TextUtils.isEmpty(deviceNumber) && groups == null)) {
                        Toast.makeText(this, "Username, password and a new device number or groups are required",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runChangeDevice(username, password,
                            TextUtils.isEmpty(deviceNumber) ? null : deviceNumber, groups);
                })
                .show();
    }

    /** Comma-separated group names; {@code null} (groups unchanged) when the field is blank. */
    private static List<String> parseGroups(String text) {
        if (text.trim().isEmpty()) {
            return null;
        }
        List<String> groups = new ArrayList<>();
        for (String name : text.split(",")) {
            if (!name.trim().isEmpty()) {
                groups.add(name.trim());
            }
        }
        return groups;
    }

    private void runChangeDevice(String username, String password, String deviceNumber, List<String> groups) {
        // A fresh service each run keeps this sample simple (credentials may change
        // between runs). shutdown() the previous one so its thread is released.
        if (mdmService != null) {
            mdmService.shutdown();
        }
        mdmService = new MdmService(new MdmConfig(headwindMDM.getServerUrl(), username, password));

        setBusy(true);
        log("Authenticating as \"" + username + "\" and updating " + headwindMDM.getDeviceId()
                + (deviceNumber != null ? ": number -> " + deviceNumber : "")
                + (groups != null ? ": groups -> " + groups : "") + " ...");

        mdmService.changeDevice(headwindMDM.getDeviceId(), deviceNumber, groups,
                new MdmService.Callback<Long>() {
                    @Override public void onSuccess(Long deviceId) {
                        setBusy(false);
                        log("OK - device id " + deviceId + " updated"
                                + (deviceNumber != null ? "; renamed to " + deviceNumber + " and configUpdated push sent" : "")
                                + (groups != null ? "; groups set to " + groups : "") + ".");
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
