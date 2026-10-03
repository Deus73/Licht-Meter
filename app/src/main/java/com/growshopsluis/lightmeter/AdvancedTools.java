package com.growshopsluis.lightmeter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AdvancedTools {
    public interface Readings {
        double lux();
        double ppfd();
        double photoperiodHours();
        boolean stable();
        double calibrationFactor();
        void applyCalibration(double factor);
    }

    private final Activity activity;
    private final Readings readings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AlertDialog advancedDialog;
    private AlertDialog logDialog;
    private Runnable liveUpdater;

    public AdvancedTools(Activity activity, Readings readings) {
        this.activity = activity;
        this.readings = readings;
    }

    public void show() {
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_advanced_tools, null);
        TextView liveSummary = view.findViewById(R.id.advancedLiveSummary);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.advanced_title)
                .setView(view)
                .setPositiveButton(R.string.close, null)
                .create();
        view.findViewById(R.id.dliToolButton).setOnClickListener(v -> showDliPlanner());
        view.findViewById(R.id.workplaceToolButton).setOnClickListener(v -> showWorkplaceCheck());
        view.findViewById(R.id.heatmapToolButton).setOnClickListener(v -> showHeatmap());
        view.findViewById(R.id.logToolButton).setOnClickListener(v -> showLogSession());
        view.findViewById(R.id.luxGuideToolButton).setOnClickListener(v -> showLuxGuide());
        view.findViewById(R.id.calibrationToolButton).setOnClickListener(v -> showCalibration());
        if (advancedDialog != null) advancedDialog.dismiss();
        advancedDialog = dialog;
        liveUpdater = new Runnable() {
            @Override public void run() {
                liveSummary.setText(activity.getString(R.string.advanced_live_summary,
                        NumberFormat.getIntegerInstance().format(readings.lux()),
                        NumberFormat.getIntegerInstance().format(readings.ppfd()),
                        activity.getString(readings.stable()
                                ? R.string.status_stable : R.string.status_settling)));
                handler.postDelayed(this, 500);
            }
        };
        dialog.setOnDismissListener(ignored -> {
            handler.removeCallbacks(liveUpdater);
            if (advancedDialog == dialog) advancedDialog = null;
        });
        dialog.show();
        handler.post(liveUpdater);
    }

    public void onPause() {
        if (logDialog != null) logDialog.dismiss();
        if (advancedDialog != null) advancedDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
    }

    private void showDliPlanner() {
        NumberFormat number = oneDecimal();
        double ppfd = readings.ppfd();
        String table = activity.getString(R.string.dli_planner_result,
                number.format(ppfd),
                number.format(LightCalculations.estimateProjectedDli(ppfd, 8)),
                number.format(LightCalculations.estimateProjectedDli(ppfd, 12)),
                number.format(LightCalculations.estimateProjectedDli(ppfd, 16)),
                number.format(LightCalculations.estimateProjectedDli(ppfd, 18)),
                number.format(readings.photoperiodHours()),
                number.format(LightCalculations.estimateProjectedDli(
                        ppfd, readings.photoperiodHours())));
        showMessage(R.string.dli_planner_title, table);
    }

    private void showWorkplaceCheck() {
        double lux = readings.lux();
        int level;
        if (lux < 100) level = R.string.workplace_very_low;
        else if (lux < 300) level = R.string.workplace_orientation;
        else if (lux < 500) level = R.string.workplace_basic;
        else if (lux < 750) level = R.string.workplace_office;
        else level = R.string.workplace_precision;
        String message = activity.getString(R.string.workplace_result,
                NumberFormat.getIntegerInstance().format(lux), activity.getString(level));
        showMessage(R.string.workplace_title, message);
    }

    private void showHeatmap() {
        int padding = dp(14);
        LinearLayout content = verticalLayout(padding);
        TextView instructions = text(activity.getString(R.string.heatmap_instructions), 14);
        content.addView(instructions);

        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(3);
        grid.setRowCount(3);
        double[] luxValues = new double[9];
        double[] ppfdValues = new double[9];
        Button[] cells = new Button[9];
        TextView summary = text(activity.getString(R.string.heatmap_empty), 14);
        for (int i = 0; i < cells.length; i++) {
            int index = i;
            Button cell = new Button(activity);
            cell.setText(activity.getString(R.string.heatmap_point_empty, i + 1));
            cell.setTextSize(12);
            cell.setMinHeight(dp(68));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 3, 1f), GridLayout.spec(i % 3, 1f));
            params.width = 0;
            params.height = dp(72);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(cell, params);
            cells[i] = cell;
            cell.setOnClickListener(v -> {
                if (!requireStable()) return;
                luxValues[index] = readings.lux();
                ppfdValues[index] = readings.ppfd();
                refreshHeatmap(cells, luxValues);
                summary.setText(heatmapSummary(luxValues));
            });
        }
        content.addView(grid);
        summary.setPadding(0, dp(8), 0, dp(8));
        content.addView(summary);
        Button share = toolButton(R.string.share_csv);
        Button reset = toolButton(R.string.reset);
        content.addView(share);
        content.addView(reset);

        share.setOnClickListener(v -> {
            if (count(luxValues) == 0) {
                Toast.makeText(activity, R.string.heatmap_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            shareCsv(activity.getString(R.string.heatmap_csv_subject), heatmapCsv(luxValues, ppfdValues));
        });
        reset.setOnClickListener(v -> {
            java.util.Arrays.fill(luxValues, 0);
            java.util.Arrays.fill(ppfdValues, 0);
            refreshHeatmap(cells, luxValues);
            summary.setText(R.string.heatmap_empty);
        });
        new AlertDialog.Builder(activity)
                .setTitle(R.string.heatmap_title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void refreshHeatmap(Button[] cells, double[] values) {
        double maximum = 0;
        for (double value : values) maximum = Math.max(maximum, value);
        for (int i = 0; i < cells.length; i++) {
            if (values[i] <= 0) {
                cells[i].setText(activity.getString(R.string.heatmap_point_empty, i + 1));
                cells[i].setBackgroundColor(Color.rgb(225, 231, 227));
                cells[i].setTextColor(Color.rgb(6, 19, 13));
            } else {
                float ratio = maximum == 0 ? 0 : (float) (values[i] / maximum);
                float hue = 10 + 110 * ratio;
                cells[i].setBackgroundColor(Color.HSVToColor(new float[]{hue, .72f, .68f}));
                cells[i].setTextColor(Color.WHITE);
                cells[i].setText(activity.getString(R.string.heatmap_point_value, i + 1,
                        NumberFormat.getIntegerInstance().format(values[i])));
            }
        }
    }

    private String heatmapSummary(double[] values) {
        int count = count(values);
        if (count == 0) return activity.getString(R.string.heatmap_empty);
        double min = Double.MAX_VALUE;
        double max = 0;
        double total = 0;
        for (double value : values) {
            if (value <= 0) continue;
            min = Math.min(min, value);
            max = Math.max(max, value);
            total += value;
        }
        double average = total / count;
        return activity.getString(R.string.heatmap_summary, count,
                NumberFormat.getIntegerInstance().format(min),
                NumberFormat.getIntegerInstance().format(average),
                NumberFormat.getIntegerInstance().format(max),
                oneDecimal().format(min / average * 100));
    }

    private String heatmapCsv(double[] lux, double[] ppfd) {
        StringBuilder csv = new StringBuilder("point,lux,ppfd\n");
        for (int i = 0; i < lux.length; i++) {
            if (lux[i] <= 0) continue;
            csv.append(i + 1).append(',').append(formatCsv(lux[i])).append(',')
                    .append(formatCsv(ppfd[i])).append('\n');
        }
        return csv.toString();
    }

    private void showLogSession() {
        List<LogEntry> entries = new ArrayList<>();
        LinearLayout content = verticalLayout(dp(14));
        TextView status = text(activity.getString(R.string.log_empty), 14);
        Button toggle = toolButton(R.string.log_start);
        Button share = toolButton(R.string.share_csv);
        content.addView(status);
        content.addView(toggle);
        content.addView(share);
        boolean[] running = {false};
        long[] startedAt = {0};
        Runnable[] sampler = new Runnable[1];
        sampler[0] = () -> {
            if (!running[0]) return;
            if (readings.stable()) {
                entries.add(new LogEntry(System.currentTimeMillis(), readings.lux(), readings.ppfd()));
                status.setText(logSummary(entries, startedAt[0]));
            }
            handler.postDelayed(sampler[0], 1_000);
        };
        toggle.setOnClickListener(v -> {
            running[0] = !running[0];
            if (running[0]) {
                entries.clear();
                startedAt[0] = System.currentTimeMillis();
                toggle.setText(R.string.log_stop);
                status.setText(R.string.log_waiting);
                handler.post(sampler[0]);
            } else {
                toggle.setText(R.string.log_start);
                handler.removeCallbacks(sampler[0]);
            }
        });
        share.setOnClickListener(v -> {
            if (entries.isEmpty()) {
                Toast.makeText(activity, R.string.log_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            shareCsv(activity.getString(R.string.log_csv_subject), logCsv(entries));
        });
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.log_title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.setOnDismissListener(d -> {
            running[0] = false;
            handler.removeCallbacks(sampler[0]);
            if (logDialog == dialog) logDialog = null;
        });
        logDialog = dialog;
        dialog.show();
    }

    private String logSummary(List<LogEntry> entries, long startedAt) {
        double min = Double.MAX_VALUE;
        double max = 0;
        double total = 0;
        for (LogEntry entry : entries) {
            min = Math.min(min, entry.lux);
            max = Math.max(max, entry.lux);
            total += entry.lux;
        }
        return activity.getString(R.string.log_summary, entries.size(),
                (System.currentTimeMillis() - startedAt) / 1_000,
                NumberFormat.getIntegerInstance().format(min),
                NumberFormat.getIntegerInstance().format(total / entries.size()),
                NumberFormat.getIntegerInstance().format(max));
    }

    private String logCsv(List<LogEntry> entries) {
        StringBuilder csv = new StringBuilder("timestamp,lux,ppfd\n");
        for (LogEntry entry : entries) {
            csv.append(entry.timestamp).append(',').append(formatCsv(entry.lux)).append(',')
                    .append(formatCsv(entry.ppfd)).append('\n');
        }
        return csv.toString();
    }

    private void showLuxGuide() {
        showMessage(R.string.lux_guide_title, activity.getString(R.string.lux_guide_text));
    }

    private void showCalibration() {
        LinearLayout content = verticalLayout(dp(18));
        content.addView(text(activity.getString(R.string.calibration_current,
                NumberFormat.getIntegerInstance().format(readings.lux())), 14));
        EditText reference = new EditText(activity);
        reference.setHint(R.string.calibration_reference_hint);
        reference.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        content.addView(reference);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.calibration_tool_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apply, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (!requireStable()) return;
                    double referenceLux;
                    try {
                        referenceLux = Double.parseDouble(reference.getText().toString()
                                .trim().replace(',', '.'));
                    } catch (NumberFormatException exception) {
                        reference.setError(activity.getString(R.string.invalid_value));
                        return;
                    }
                    if (!Double.isFinite(referenceLux) || referenceLux <= 0
                            || !Double.isFinite(readings.lux()) || readings.lux() <= 0) {
                        reference.setError(activity.getString(R.string.invalid_value));
                        return;
                    }
                    double factor = readings.calibrationFactor() * referenceLux / readings.lux();
                    if (!Double.isFinite(factor) || factor <= 0 || factor > Float.MAX_VALUE) {
                        reference.setError(activity.getString(R.string.invalid_value));
                        return;
                    }
                    readings.applyCalibration(factor);
                    Toast.makeText(activity, activity.getString(
                            R.string.calibration_applied, oneDecimal().format(factor)),
                            Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private boolean requireStable() {
        if (readings.stable()) return true;
        Toast.makeText(activity, R.string.advanced_wait_stable, Toast.LENGTH_SHORT).show();
        return false;
    }

    private void showMessage(int title, String message) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void shareCsv(String subject, String csv) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, csv);
        activity.startActivity(Intent.createChooser(share, activity.getString(R.string.share_csv)));
    }

    private LinearLayout verticalLayout(int padding) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);
        return layout;
    }

    private TextView text(String value, float size) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(activity.getColor(R.color.ink));
        text.setLineSpacing(0, 1.15f);
        return text;
    }

    private Button toolButton(int text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(activity.getColorStateList(R.color.button_background));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private NumberFormat oneDecimal() {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format;
    }

    private String formatCsv(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private int count(double[] values) {
        int count = 0;
        for (double value : values) if (value > 0) count++;
        return count;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class LogEntry {
        final long timestamp;
        final double lux;
        final double ppfd;

        LogEntry(long timestamp, double lux, double ppfd) {
            this.timestamp = timestamp;
            this.lux = lux;
            this.ppfd = ppfd;
        }
    }
}
