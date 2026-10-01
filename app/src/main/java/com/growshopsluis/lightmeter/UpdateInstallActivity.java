package com.growshopsluis.lightmeter;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;

public class UpdateInstallActivity extends Activity {
    private boolean waitingForPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        waitingForPermission = savedInstanceState != null
                && savedInstanceState.getBoolean("waiting_for_permission");
    }

    @Override
    protected void onResume() {
        super.onResume();
        File apk = UpdateWorker.updateFile(this);
        if (!apk.isFile()) {
            finish();
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            if (waitingForPermission) {
                finish();
                return;
            }
            waitingForPermission = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", apk);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(install);
        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("waiting_for_permission", waitingForPermission);
        super.onSaveInstanceState(state);
    }
}
