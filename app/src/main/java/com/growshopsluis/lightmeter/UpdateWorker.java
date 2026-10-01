package com.growshopsluis.lightmeter;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class UpdateWorker extends Worker {
    private static final String RELEASE_API =
            "https://api.github.com/repos/Deus73/Licht-Meter/releases/latest";
    private static final String WORK_NAME = "weekly-app-update-check";
    private static final String CHANNEL_ID = "app_updates";

    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                UpdateWorker.class, 7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        File temporaryFile = null;
        try {
            JSONObject release = new JSONObject(readUrl(RELEASE_API));
            String version = release.optString("tag_name").replaceFirst("^[vV]", "");
            if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return Result.success();

            String apkUrl = findApkUrl(release.optJSONArray("assets"));
            if (apkUrl == null) return Result.success();
            File updateFile = updateFile(getApplicationContext());
            File parent = updateFile.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) return Result.failure();
            temporaryFile = new File(parent, "licht-meter-update.tmp");
            download(apkUrl, temporaryFile);
            if (!isOwnPackage(temporaryFile)) {
                temporaryFile.delete();
                return Result.failure();
            }
            if (updateFile.exists() && !updateFile.delete()) return Result.failure();
            if (!temporaryFile.renameTo(updateFile)) return Result.failure();
            getApplicationContext().getSharedPreferences("updates", Context.MODE_PRIVATE)
                    .edit().putString("downloaded_version", version).apply();
            showInstallNotification(version);
            return Result.success();
        } catch (Exception exception) {
            if (temporaryFile != null) temporaryFile.delete();
            return Result.retry();
        }
    }

    static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftValue = i < leftParts.length ? leadingNumber(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? leadingNumber(rightParts[i]) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private static int leadingNumber(String value) {
        String digits = value.replaceFirst("^(\\d+).*$", "$1");
        if (!digits.matches("\\d+")) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String findApkUrl(JSONArray assets) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null
                    || !asset.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
            String url = asset.optString("browser_download_url");
            if (url.startsWith("https://github.com/Deus73/Licht-Meter/")) return url;
        }
        return null;
    }

    private String readUrl(String address) throws Exception {
        HttpURLConnection connection = openConnection(address);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }

    private void download(String address, File destination) throws Exception {
        HttpURLConnection connection = openConnection(address);
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Licht-Meter/" + BuildConfig.VERSION_NAME);
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            connection.disconnect();
            throw new IllegalStateException("Update server returned " + responseCode);
        }
        return connection;
    }

    @SuppressWarnings("deprecation")
    private boolean isOwnPackage(File apk) {
        PackageInfo info = getApplicationContext().getPackageManager()
                .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        return info != null && getApplicationContext().getPackageName().equals(info.packageName);
    }

    private void showInstallNotification(String version) {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_HIGH));
        Intent intent = new Intent(context, UpdateInstallActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(3103, new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_update)
                .setContentTitle(context.getString(R.string.update_ready_title, version))
                .setContentText(context.getString(R.string.update_ready_text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build());
    }

    static File updateFile(Context context) {
        return new File(new File(context.getFilesDir(), "updates"), "licht-meter-update.apk");
    }

    static String pendingUpdateVersion(Context context) {
        String version = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
                .getString("downloaded_version", null);
        return version != null && updateFile(context).isFile()
                && compareVersions(version, BuildConfig.VERSION_NAME) > 0 ? version : null;
    }

    static void cleanupInstalledUpdate(Context context) {
        String version = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
                .getString("downloaded_version", null);
        if (version == null || compareVersions(version, BuildConfig.VERSION_NAME) > 0) return;
        updateFile(context).delete();
        context.getSharedPreferences("updates", Context.MODE_PRIVATE)
                .edit().remove("downloaded_version").apply();
    }
}
