package com.growshopsluis.lightmeter;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Range;
import android.util.Rational;

import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 41;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 42;
    private static final double[] PPFD_FACTORS = {0.015, 0.025, 0.0185, 0.012, 0.013};
    private static final int[] PRESET_EV = {0, -1, 1};

    private TextureView cameraPreview;
    private TextView statusText;
    private TextView luxValue;
    private TextView fcValue;
    private TextView ppfdValue;
    private TextView lumenValue;
    private TextView assessmentTitle;
    private TextView assessmentDetail;
    private EditText areaInput;
    private EditText calibrationInput;
    private EditText lampNameInput;
    private TextView cameraProfileText;
    private View sourceVisual;
    private View sourceGlow;
    private View meterTarget;
    private ImageView sourceVisualImage;
    private Button previewToggleButton;
    private Spinner presetSpinner;
    private LinearLayout historyContainer;
    private MeasurementStore measurementStore;
    private View settingsView;
    private AlertDialog settingsDialog;
    private ObjectAnimator settingsAnimator;
    private String currentCameraLabel = "";

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;
    private String cameraId;
    private String cameraProfileKey = "camera_default";
    private int selectedFacing = CameraCharacteristics.LENS_FACING_FRONT;
    private int selectedPreset;
    private boolean loadingCameraProfile;
    private volatile boolean cameraOpening;
    private volatile int cameraGeneration;
    private Range<Integer> compensationRange;
    private Rational compensationStep;
    private float fallbackAperture = 1.8f;
    private volatile long exposureTimeNs;
    private volatile int iso;
    private volatile float aperture;
    private volatile double currentLux;
    private volatile double calibrationFactor = 1.0;
    private double ppfdFactor = PPFD_FACTORS[0];
    private int selectedLampType;
    private boolean cameraPreviewVisible;
    private long lastUiUpdate;
    private boolean updatePromptShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams windowAttributes = getWindow().getAttributes();
        windowAttributes.screenBrightness = 1f;
        getWindow().setAttributes(windowAttributes);

        cameraPreview = findViewById(R.id.cameraPreview);
        statusText = findViewById(R.id.statusText);
        luxValue = findViewById(R.id.luxValue);
        fcValue = findViewById(R.id.fcValue);
        ppfdValue = findViewById(R.id.ppfdValue);
        assessmentTitle = findViewById(R.id.assessmentTitle);
        assessmentDetail = findViewById(R.id.assessmentDetail);
        sourceVisual = findViewById(R.id.sourceVisual);
        sourceGlow = findViewById(R.id.sourceGlow);
        meterTarget = findViewById(R.id.meterTarget);
        sourceVisualImage = findViewById(R.id.sourceVisualImage);
        previewToggleButton = findViewById(R.id.previewToggleButton);
        historyContainer = findViewById(R.id.historyContainer);
        TextView versionText = findViewById(R.id.versionText);
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            versionText.setText(getString(R.string.version_label, versionName));
        } catch (PackageManager.NameNotFoundException ignored) {
            versionText.setVisibility(View.GONE);
        }
        measurementStore = new MeasurementStore(this);
        setupLampTypeButtons();

        settingsView = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        lumenValue = settingsView.findViewById(R.id.lumenValue);
        areaInput = settingsView.findViewById(R.id.areaInput);
        calibrationInput = settingsView.findViewById(R.id.calibrationInput);
        lampNameInput = settingsView.findViewById(R.id.lampNameInput);
        cameraProfileText = settingsView.findViewById(R.id.cameraProfileText);
        presetSpinner = settingsView.findViewById(R.id.presetSpinner);

        calibrationInput.setText(formatDecimal(calibrationFactor, 2));
        lampNameInput.setText(getPreferences(MODE_PRIVATE).getString("lamp_name", ""));
        setupInputs();
        meterTarget.setVisibility(View.GONE);
        previewToggleButton.setOnClickListener(view -> toggleCameraPreview());
        findViewById(R.id.lightGuideButton).setOnClickListener(view -> showLightGuide());
        settingsView.findViewById(R.id.saveMeasurementButton)
                .setOnClickListener(view -> saveMeasurement());
        findViewById(R.id.clearHistoryButton).setOnClickListener(view -> confirmClearHistory());
        settingsDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(settingsView)
                .setPositiveButton(R.string.close, null)
                .create();
        View settingsButton = findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(view -> settingsDialog.show());
        settingsAnimator = ObjectAnimator.ofFloat(settingsButton, View.ROTATION, 0f, 360f);
        settingsAnimator.setDuration(7_000);
        settingsAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        settingsAnimator.setInterpolator(new LinearInterpolator());
        settingsAnimator.start();
        UpdateWorker.cleanupInstalledUpdate(this);
        UpdateWorker.schedule(this);
        showHistory();
        cameraPreview.setSurfaceTextureListener(surfaceTextureListener);
    }

    private void setupInputs() {
        Spinner cameraSpinner = settingsView.findViewById(R.id.cameraSpinner);
        ArrayAdapter<String> cameraAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.front_camera), getString(R.string.back_camera)});
        cameraSpinner.setAdapter(cameraAdapter);
        cameraSpinner.setSelection(0, false);
        cameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                int facing = position == 0
                        ? CameraCharacteristics.LENS_FACING_FRONT
                        : CameraCharacteristics.LENS_FACING_BACK;
                if (facing == selectedFacing) return;
                selectedFacing = facing;
                restartCamera();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.preset_auto), getString(R.string.preset_bright),
                        getString(R.string.preset_low_light)});
        presetSpinner.setAdapter(presetAdapter);
        presetSpinner.setSelection(0, false);
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (loadingCameraProfile || position == selectedPreset) return;
                selectedPreset = position;
                getPreferences(MODE_PRIVATE).edit()
                        .putInt(cameraProfileKey + "_preset", selectedPreset)
                        .apply();
                restartCamera();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (calibrationInput.hasFocus()) {
                    calibrationFactor = parsePositive(calibrationInput, 1.0);
                }
                updateReadings();
            }
            @Override public void afterTextChanged(Editable s) {
                if (calibrationInput.hasFocus()) {
                    getPreferences(MODE_PRIVATE).edit()
                            .putFloat(cameraProfileKey + "_calibration",
                                    (float) parsePositive(calibrationInput, 1.0))
                            .apply();
                }
            }
        };
        areaInput.addTextChangedListener(watcher);
        calibrationInput.addTextChangedListener(watcher);
    }

    private void setupLampTypeButtons() {
        int[] buttonIds = {R.id.whiteLedButton, R.id.blurpleLedButton, R.id.sunlightButton,
                R.id.hpsButton, R.id.fluorescentButton};
        selectedLampType = Math.max(0, Math.min(PPFD_FACTORS.length - 1,
                getPreferences(MODE_PRIVATE).getInt("lamp_type", 0)));
        ppfdFactor = PPFD_FACTORS[selectedLampType];
        for (int i = 0; i < buttonIds.length; i++) {
            int index = i;
            View button = findViewById(buttonIds[i]);
            button.setSelected(index == selectedLampType);
            button.setOnClickListener(view -> {
                selectedLampType = index;
                ppfdFactor = PPFD_FACTORS[index];
                getPreferences(MODE_PRIVATE).edit().putInt("lamp_type", index).apply();
                for (int id : buttonIds) findViewById(id).setSelected(id == buttonIds[index]);
                updateReadings();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        offerDownloadedUpdate();
        startCameraThread();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            requestUpdateNotificationPermission();
        }
        if (cameraPreview.isAvailable()) {
            openCamera();
        }
    }

    private void requestUpdateNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notification_permission_requested", false)) {
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean("notification_permission_requested", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void offerDownloadedUpdate() {
        String version = UpdateWorker.pendingUpdateVersion(this);
        if (updatePromptShown || version == null) return;
        updatePromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_ready_title, version))
                .setMessage(R.string.update_ready_text)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.install_update, (dialog, which) ->
                        startActivity(new Intent(this, UpdateInstallActivity.class)))
                .show();
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("LightMeterCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        if (cameraDevice != null || cameraOpening || cameraHandler == null) return;

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = chooseCamera(manager, selectedFacing);
            if (cameraId == null) {
                showStatus(getString(R.string.no_camera));
                return;
            }
            showStatus(getString(R.string.opening_camera));
            cameraOpening = true;
            int generation = ++cameraGeneration;
            manager.openCamera(cameraId, createCameraStateCallback(generation), cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            cameraOpening = false;
            showStatus(getString(R.string.camera_unavailable));
        }
    }

    private String chooseCamera(CameraManager manager, int preferredFacing) throws CameraAccessException {
        String first = null;
        for (String id : manager.getCameraIdList()) {
            if (first == null) first = id;
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == preferredFacing) {
                applyCameraCharacteristics(id, characteristics, facing);
                return id;
            }
        }
        if (first != null) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(first);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            applyCameraCharacteristics(first, characteristics, facing);
        }
        return first;
    }

    private void applyCameraCharacteristics(
            String id, CameraCharacteristics characteristics, Integer facing) {
        float[] apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        if (apertures != null && apertures.length > 0) fallbackAperture = apertures[0];
        compensationRange = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        compensationStep = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        boolean isFront = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        cameraPreview.setScaleX(isFront ? -1f : 1f);

        cameraProfileKey = "camera_" + Build.MANUFACTURER + "_" + Build.MODEL + "_" + id;
        calibrationFactor = getPreferences(MODE_PRIVATE)
                .getFloat(cameraProfileKey + "_calibration", 1f);
        selectedPreset = getPreferences(MODE_PRIVATE)
                .getInt(cameraProfileKey + "_preset", 0);
        selectedPreset = Math.max(0, Math.min(PRESET_EV.length - 1, selectedPreset));
        loadingCameraProfile = true;
        calibrationInput.clearFocus();
        calibrationInput.setText(formatDecimal(calibrationFactor, 2));
        presetSpinner.setSelection(selectedPreset);
        loadingCameraProfile = false;
        cameraProfileText.setText(getString(R.string.detected_camera,
                Build.MANUFACTURER, Build.MODEL,
                getString(isFront ? R.string.front_camera : R.string.back_camera), id));
        currentCameraLabel = getString(isFront ? R.string.front_camera : R.string.back_camera)
                + " " + id;
    }

    private CameraDevice.StateCallback createCameraStateCallback(int generation) {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                if (generation != cameraGeneration) {
                    camera.close();
                    return;
                }
                cameraOpening = false;
                cameraDevice = camera;
                createCameraSession(camera);
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                camera.close();
                if (generation != cameraGeneration) return;
                if (camera == cameraDevice) cameraDevice = null;
                cameraOpening = false;
                showStatus(getString(R.string.camera_disconnected));
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                camera.close();
                if (generation != cameraGeneration) return;
                if (camera == cameraDevice) cameraDevice = null;
                cameraOpening = false;
                showStatus(getString(R.string.camera_error, error));
            }
        };
    }

    private void createCameraSession(CameraDevice camera) {
        SurfaceTexture texture = cameraPreview.getSurfaceTexture();
        if (cameraDevice != camera || texture == null) return;
        texture.setDefaultBufferSize(640, 480);
        Surface previewSurface = new Surface(texture);
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
        imageReader.setOnImageAvailableListener(this::analyzeImage, cameraHandler);

        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder = request;
            request.addTarget(previewSurface);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            Integer exposureCompensation = presetExposureCompensation();
            if (exposureCompensation != null) {
                request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation);
            }
            camera.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice != camera) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(request.build(), captureCallback, cameraHandler);
                                showStatus(getString(R.string.measuring));
                            } catch (CameraAccessException e) {
                                showStatus(getString(R.string.measurement_start_failed));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            showStatus(getString(R.string.camera_config_failed));
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            showStatus(getString(R.string.camera_config_failed));
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(
                        CameraCaptureSession session,
                        CaptureRequest request,
                        TotalCaptureResult result) {
                    Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    Float reportedAperture = result.get(CaptureResult.LENS_APERTURE);
                    if (exposure != null) exposureTimeNs = exposure;
                    if (sensitivity != null) iso = sensitivity;
                    aperture = reportedAperture != null ? reportedAperture : fallbackAperture;
                }
            };

    private void analyzeImage(ImageReader reader) {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null || exposureTimeNs == 0 || iso == 0) return;
            Image.Plane yPlane = image.getPlanes()[0];
            ByteBuffer buffer = yPlane.getBuffer();
            int bufferBase = buffer.position();
            int rowStride = yPlane.getRowStride();
            int pixelStride = yPlane.getPixelStride();
            int width = image.getWidth();
            int height = image.getHeight();
            int left = width / 4;
            int right = width * 3 / 4;
            int top = height / 4;
            int bottom = height * 3 / 4;
            long total = 0;
            int samples = 0;
            for (int y = top; y < bottom; y += 4) {
                for (int x = left; x < right; x += 4) {
                    total += buffer.get(bufferBase + y * rowStride + x * pixelStride) & 0xff;
                    samples++;
                }
            }
            double normalizedLuma = samples == 0 ? 0 : total / (samples * 255.0);
            double frameLux = LightCalculations.estimateLux(
                    exposureTimeNs, iso, aperture, normalizedLuma, calibrationFactor);
            currentLux = frameLux;
            if (SystemClock.elapsedRealtime() - lastUiUpdate > 350) {
                lastUiUpdate = SystemClock.elapsedRealtime();
                runOnUiThread(this::updateReadings);
            }
        }
    }

    private void updateReadings() {
        NumberFormat integerFormat = NumberFormat.getIntegerInstance(Locale.getDefault());
        luxValue.setText(getString(R.string.lux_value, integerFormat.format(currentLux)));
        NumberFormat decimalFormat = NumberFormat.getNumberInstance(Locale.getDefault());
        decimalFormat.setMaximumFractionDigits(1);
        fcValue.setText(getString(R.string.fc_value,
                decimalFormat.format(LightCalculations.luxToFootCandles(currentLux))));
        double ppfd = LightCalculations.estimatePpfd(currentLux, ppfdFactor);
        ppfdValue.setText(integerFormat.format(ppfd));
        updateAssessment(ppfd);
        updateLightVisual(currentLux);
        double area = parsePositive(areaInput, 0);
        if (area > 0) {
            lumenValue.setText(getString(R.string.lumen_value, integerFormat.format(
                    LightCalculations.estimateLumens(currentLux, area))));
        } else {
            lumenValue.setText(R.string.lumen_empty);
        }
    }

    private void updateAssessment(double ppfd) {
        int title;
        int detail;
        if (ppfd < 100) {
            title = R.string.assessment_too_low;
            detail = R.string.assessment_too_low_detail;
        } else if (ppfd < 300) {
            title = R.string.assessment_seedlings;
            detail = R.string.assessment_seedlings_detail;
        } else if (ppfd < 600) {
            title = R.string.assessment_vegetative;
            detail = R.string.assessment_vegetative_detail;
        } else if (ppfd < 1_000) {
            title = R.string.assessment_flowering;
            detail = R.string.assessment_flowering_detail;
        } else if (ppfd <= 1_500) {
            title = R.string.assessment_intensive;
            detail = R.string.assessment_intensive_detail;
        } else {
            title = R.string.assessment_too_high;
            detail = R.string.assessment_too_high_detail;
        }
        assessmentTitle.setText(title);
        assessmentDetail.setText(detail);
    }

    private double parsePositive(EditText input, double fallback) {
        String value = input.getText().toString().trim().replace(',', '.');
        if (value.isEmpty()) return fallback;
        try {
            double parsed = Double.parseDouble(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String formatDecimal(double value, int decimals) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        return format.format(value);
    }

    private void showStatus(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private void closeCamera() {
        cameraGeneration++;
        cameraOpening = false;
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        previewRequestBuilder = null;
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void restartCamera() {
        closeCamera();
        exposureTimeNs = 0;
        iso = 0;
        currentLux = 0;
        updateReadings();
        if (cameraPreview.isAvailable()) openCamera();
    }

    private void saveMeasurement() {
        if (currentLux <= 0 || cameraId == null) {
            Toast.makeText(this, R.string.measurement_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        String lampName = lampNameInput.getText().toString().trim();
        if (lampName.isEmpty()) lampName = getString(R.string.unknown_lamp);
        getPreferences(MODE_PRIVATE).edit().putString("lamp_name", lampName).apply();
        double area = parsePositive(areaInput, 0);
        Double lumens = area > 0 ? LightCalculations.estimateLumens(currentLux, area) : null;
        String lightSource = currentLightSourceLabel();
        String preset = String.valueOf(presetSpinner.getSelectedItem());
        measurementStore.add(new MeasurementStore.Measurement(
                System.currentTimeMillis(), lampName, currentCameraLabel,
                lightSource, preset, currentLux,
                LightCalculations.estimatePpfd(currentLux, ppfdFactor), lumens));
        Toast.makeText(this, R.string.measurement_saved, Toast.LENGTH_SHORT).show();
        showHistory();
    }

    private void showHistory() {
        historyContainer.removeAllViews();
        List<MeasurementStore.Measurement> measurements = measurementStore.recent(100);
        if (measurements.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.history_empty);
            empty.setTextColor(getColor(R.color.muted));
            empty.setTextSize(14);
            historyContainer.addView(empty);
            return;
        }

        NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.getDefault());
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        int margin = Math.round(6 * getResources().getDisplayMetrics().density);
        for (MeasurementStore.Measurement measurement : measurements) {
            String lumenSuffix = measurement.lumens == null ? "" : getString(
                    R.string.history_lumen_suffix, numbers.format(measurement.lumens));
            TextView row = new TextView(this);
            row.setText(getString(R.string.history_row,
                    dateFormat.format(new Date(measurement.measuredAt)), measurement.lampName,
                    measurement.cameraName, measurement.lightSource + " / " + measurement.preset,
                    numbers.format(measurement.lux),
                    decimalFormat(LightCalculations.luxToFootCandles(measurement.lux)),
                    numbers.format(measurement.ppfd), lumenSuffix));
            row.setTextColor(getColor(R.color.ink));
            row.setTextSize(14);
            row.setLineSpacing(0, 1.15f);
            row.setPadding(padding, padding, padding, padding);
            row.setBackgroundResource(R.drawable.history_item_background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = margin;
            historyContainer.addView(row, params);
        }
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_history_title)
                .setMessage(R.string.clear_history_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    measurementStore.clear();
                    showHistory();
                })
                .show();
    }

    private String decimalFormat(double value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setMaximumFractionDigits(1);
        return format.format(value);
    }

    private void showLightGuide() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.light_guide_title)
                .setMessage(R.string.light_guide_current)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private Integer presetExposureCompensation() {
        if (compensationRange == null || compensationStep == null
                || compensationStep.floatValue() <= 0) {
            return null;
        }
        int value = Math.round(PRESET_EV[selectedPreset] / compensationStep.floatValue());
        return Math.max(compensationRange.getLower(), Math.min(compensationRange.getUpper(), value));
    }

    private String currentLightSourceLabel() {
        int[] labels = {R.string.source_white_led, R.string.source_blurple_led,
                R.string.source_sunlight, R.string.source_hps, R.string.source_fluorescent};
        return getString(labels[selectedLampType]);
    }

    private void toggleCameraPreview() {
        cameraPreviewVisible = !cameraPreviewVisible;
        sourceVisual.setVisibility(cameraPreviewVisible ? View.GONE : View.VISIBLE);
        meterTarget.setVisibility(cameraPreviewVisible ? View.VISIBLE : View.GONE);
        previewToggleButton.setText(cameraPreviewVisible
                ? R.string.show_light : R.string.show_camera);
    }

    private void updateLightVisual(double lux) {
        float intensity = (float) Math.min(1.0,
                Math.log10(1.0 + Math.max(0.0, lux)) / Math.log10(100_001.0));
        int coreAlpha = Math.round(35 + 200 * intensity);
        int edgeAlpha = Math.round(8 + 90 * intensity);
        GradientDrawable glow = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(coreAlpha, 255, 248, 184),
                        Color.argb(edgeAlpha, 216, 255, 71), Color.TRANSPARENT});
        glow.setShape(GradientDrawable.OVAL);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(110 * getResources().getDisplayMetrics().density);
        sourceGlow.setBackground(glow);
        float scale = 0.86f + 0.14f * intensity;
        sourceVisualImage.animate()
                .alpha(0.42f + 0.58f * intensity)
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(300)
                .start();
    }

    @Override
    protected void onDestroy() {
        settingsAnimator.cancel();
        settingsDialog.dismiss();
        measurementStore.close();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
            requestUpdateNotificationPermission();
        } else {
            showStatus(getString(R.string.camera_permission_required));
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCamera();
                }

                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
            };
}
