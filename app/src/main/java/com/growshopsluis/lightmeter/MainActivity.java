package com.growshopsluis.lightmeter;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
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
    private static final double[] PPFD_FACTORS = {0.015, 0.015, 0.025, 0.0185, 0.012, 0.013};
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
    private TextView lampDetectionText;
    private View sourceVisual;
    private View meterTarget;
    private ImageView sourceVisualImage;
    private TextView sourceVisualTitle;
    private Button previewToggleButton;
    private Spinner presetSpinner;
    private Spinner lightSourceSpinner;
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
    private boolean automaticLampDetection = true;
    private LampClassifier.Type lampCandidate;
    private int lampCandidateFrames;
    private LampClassifier.Type detectedLampType;
    private boolean cameraPreviewVisible;
    private final double[] lumaHistory = new double[24];
    private int lumaHistorySize;
    private int lumaHistoryIndex;
    private long lastUiUpdate;

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
        meterTarget = findViewById(R.id.meterTarget);
        sourceVisualImage = findViewById(R.id.sourceVisualImage);
        sourceVisualTitle = findViewById(R.id.sourceVisualTitle);
        previewToggleButton = findViewById(R.id.previewToggleButton);
        historyContainer = findViewById(R.id.historyContainer);
        measurementStore = new MeasurementStore(this);

        settingsView = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        lumenValue = settingsView.findViewById(R.id.lumenValue);
        areaInput = settingsView.findViewById(R.id.areaInput);
        calibrationInput = settingsView.findViewById(R.id.calibrationInput);
        lampNameInput = settingsView.findViewById(R.id.lampNameInput);
        cameraProfileText = settingsView.findViewById(R.id.cameraProfileText);
        lampDetectionText = settingsView.findViewById(R.id.lampDetectionText);
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

        lightSourceSpinner = settingsView.findViewById(R.id.lightSourceSpinner);
        String[] lightSources = {
                getString(R.string.source_auto), getString(R.string.source_white_led),
                getString(R.string.source_blurple_led), getString(R.string.source_sunlight),
                getString(R.string.source_hps), getString(R.string.source_fluorescent)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, lightSources);
        lightSourceSpinner.setAdapter(adapter);
        lightSourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                automaticLampDetection = position == 0;
                ppfdFactor = PPFD_FACTORS[position];
                resetLampDetection();
                if (automaticLampDetection) {
                    lampDetectionText.setText(R.string.detecting_lamp);
                    showAnalyzingVisual();
                } else {
                    lampDetectionText.setText(getString(
                            R.string.manual_lamp_source, ppfdFactor));
                    showManualLampVisual(position);
                    applyAutomaticWhiteBalance(null);
                }
                updateReadings();
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

    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        if (cameraPreview.isAvailable()) {
            openCamera();
        }
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
            analyzeLampType(image, normalizedLuma, frameLux);
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
        resetLampDetection();
        if (automaticLampDetection) lampDetectionText.setText(R.string.detecting_lamp);
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
                .setMessage(R.string.light_guide_text)
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

    private void analyzeLampType(Image image, double luma, double frameLux) {
        if (!automaticLampDetection || image.getPlanes().length < 3) {
            return;
        }
        lumaHistory[lumaHistoryIndex] = luma;
        lumaHistoryIndex = (lumaHistoryIndex + 1) % lumaHistory.length;
        lumaHistorySize = Math.min(lumaHistorySize + 1, lumaHistory.length);
        if (lumaHistorySize < 16) return;
        double u = averageChromaPlane(image.getPlanes()[1], image.getWidth(), image.getHeight());
        double v = averageChromaPlane(image.getPlanes()[2], image.getWidth(), image.getHeight());
        double centeredU = u - 0.5;
        double centeredV = v - 0.5;
        double red = clampColor(luma + 1.402 * centeredV);
        double green = clampColor(luma - 0.344 * centeredU - 0.714 * centeredV);
        double blue = clampColor(luma + 1.772 * centeredU);
        LampClassifier.Result result = LampClassifier.classify(
                red, green, blue, frameLux, calculateFlickerRatio());
        if (result.type == lampCandidate) lampCandidateFrames++;
        else {
            lampCandidate = result.type;
            lampCandidateFrames = 1;
        }
        if (lampCandidateFrames < 10 || detectedLampType == result.type) return;

        detectedLampType = result.type;
        ppfdFactor = result.type.ppfdFactor;
        applyAutomaticWhiteBalance(result.type);
        runOnUiThread(() -> {
            lampDetectionText.setText(getString(R.string.detected_lamp,
                    lampTypeName(result.type), result.confidencePercent, result.type.ppfdFactor));
            showLampVisual(result.type);
            updateReadings();
        });
    }

    private double averageChromaPlane(Image.Plane plane, int imageWidth, int imageHeight) {
        ByteBuffer buffer = plane.getBuffer();
        int base = buffer.position();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int planeWidth = imageWidth / 2;
        int planeHeight = imageHeight / 2;
        long total = 0;
        int samples = 0;
        for (int y = planeHeight / 4; y < planeHeight * 3 / 4; y += 2) {
            for (int x = planeWidth / 4; x < planeWidth * 3 / 4; x += 2) {
                int index = base + y * rowStride + x * pixelStride;
                if (index >= buffer.limit()) continue;
                total += buffer.get(index) & 0xff;
                samples++;
            }
        }
        return samples == 0 ? 0.5 : total / (samples * 255.0);
    }

    private void applyAutomaticWhiteBalance(LampClassifier.Type type) {
        CaptureRequest.Builder request = previewRequestBuilder;
        CameraCaptureSession session = captureSession;
        if (request == null || session == null || cameraHandler == null) return;
        int mode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
        if (type == LampClassifier.Type.HPS) mode = CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
        else if (type == LampClassifier.Type.FLUORESCENT) {
            mode = CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
        } else if (type == LampClassifier.Type.WHITE_LED) {
            mode = CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
        }
        request.set(CaptureRequest.CONTROL_AWB_MODE, mode);
        try {
            session.setRepeatingRequest(request.build(), captureCallback, cameraHandler);
        } catch (CameraAccessException | IllegalStateException ignored) {
            // A camera switch can close the session while a detection result is being applied.
        }
    }

    private void resetLampDetection() {
        lampCandidate = null;
        lampCandidateFrames = 0;
        detectedLampType = null;
        lumaHistorySize = 0;
        lumaHistoryIndex = 0;
        if (automaticLampDetection) runOnUiThread(this::showAnalyzingVisual);
    }

    private double calculateFlickerRatio() {
        if (lumaHistorySize == 0) return 0;
        double average = 0;
        for (int i = 0; i < lumaHistorySize; i++) average += lumaHistory[i];
        average /= lumaHistorySize;
        if (average <= 0) return 0;
        double variance = 0;
        for (int i = 0; i < lumaHistorySize; i++) {
            double difference = lumaHistory[i] - average;
            variance += difference * difference;
        }
        return Math.sqrt(variance / lumaHistorySize) / average;
    }

    private String currentLightSourceLabel() {
        if (automaticLampDetection && detectedLampType != null) {
            return getString(R.string.source_auto) + ": " + lampTypeName(detectedLampType);
        }
        return String.valueOf(lightSourceSpinner.getSelectedItem());
    }

    private String lampTypeName(LampClassifier.Type type) {
        switch (type) {
            case BLURPLE_LED: return getString(R.string.source_blurple_led);
            case HPS: return getString(R.string.source_hps);
            case FLUORESCENT: return getString(R.string.source_fluorescent);
            default: return getString(R.string.source_white_led);
        }
    }

    private double clampColor(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private void toggleCameraPreview() {
        cameraPreviewVisible = !cameraPreviewVisible;
        sourceVisual.setVisibility(cameraPreviewVisible ? View.GONE : View.VISIBLE);
        meterTarget.setVisibility(cameraPreviewVisible ? View.VISIBLE : View.GONE);
        previewToggleButton.setText(cameraPreviewVisible
                ? R.string.show_source : R.string.show_camera);
    }

    private void showAnalyzingVisual() {
        sourceVisualImage.setImageResource(R.drawable.source_analyzing);
        sourceVisualTitle.setText(R.string.source_analyzing_title);
        sourceVisualImage.setContentDescription(getString(R.string.source_analyzing_title));
    }

    private void showManualLampVisual(int position) {
        switch (position) {
            case 2:
                showSourceVisual(R.drawable.source_blurple_led, R.string.source_blurple_led);
                break;
            case 3:
                showSourceVisual(R.drawable.source_sunlight, R.string.source_sunlight);
                break;
            case 4:
                showSourceVisual(R.drawable.source_hps, R.string.source_hps);
                break;
            case 5:
                showSourceVisual(R.drawable.source_fluorescent, R.string.source_fluorescent);
                break;
            default:
                showSourceVisual(R.drawable.source_white_led, R.string.source_white_led);
        }
    }

    private void showLampVisual(LampClassifier.Type type) {
        switch (type) {
            case BLURPLE_LED:
                showSourceVisual(R.drawable.source_blurple_led, R.string.source_blurple_led);
                break;
            case HPS:
                showSourceVisual(R.drawable.source_hps, R.string.source_hps);
                break;
            case FLUORESCENT:
                showSourceVisual(R.drawable.source_fluorescent, R.string.source_fluorescent);
                break;
            default:
                showSourceVisual(R.drawable.source_white_led, R.string.source_white_led);
        }
    }

    private void showSourceVisual(int drawable, int title) {
        sourceVisualImage.setImageResource(drawable);
        sourceVisualTitle.setText(title);
        sourceVisualImage.setContentDescription(getString(title));
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
