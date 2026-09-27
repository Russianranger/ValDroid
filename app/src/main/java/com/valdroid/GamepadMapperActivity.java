package com.valdroid;

import android.graphics.Insets;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.valdroid.input.Binding;
import com.valdroid.input.GamepadMapping;
import com.valdroid.input.GamepadProfile;
import com.valdroid.input.GamepadProfile.Input;
import com.valdroid.input.GamepadProfile.StickMode;
import com.valdroid.input.GamepadProfileStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Edits physical-controller outputs separately from optional button-name calibration. */
public class GamepadMapperActivity extends AppCompatActivity {
    private static final Input[] BUTTONS = {
            Input.A, Input.B, Input.X, Input.Y, Input.LB, Input.RB,
            Input.SELECT, Input.START, Input.GUIDE, Input.L3, Input.R3,
            Input.LT, Input.RT, Input.DPAD_UP, Input.DPAD_RIGHT,
            Input.DPAD_DOWN, Input.DPAD_LEFT
    };
    // This order matches GamepadMapping's logical indices. Analog inputs have no calibration.
    private static final Input[] CALIBRATED = {
            Input.A, Input.B, Input.X, Input.Y, Input.LB, Input.RB,
            Input.SELECT, Input.START, Input.GUIDE, Input.L3, Input.R3
    };
    private static final Input[] LEFT_DIRECTIONS = {
            Input.LS_UP, Input.LS_RIGHT, Input.LS_DOWN, Input.LS_LEFT
    };
    private static final Input[] RIGHT_DIRECTIONS = {
            Input.RS_UP, Input.RS_RIGHT, Input.RS_DOWN, Input.RS_LEFT
    };

    private GamepadProfile draft;
    private String savedProfile;
    private int[] calibration;
    private int[] savedCalibration;
    private int[] wizardMapping;
    private int wizardStep;
    private boolean calibrationExpanded;
    private boolean mappingActive;
    private LinearLayout content;
    private MenuItem saveItem;
    private final Set<Integer> calibrationHeldKeys = new HashSet<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_gamepad_mapper);
        findViewById(R.id.mapper_root).setOnApplyWindowInsetsListener((v, wi) -> {
            Insets bars = wi.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return wi;
        });

        GamepadMapping.load(this);
        draft = GamepadProfileStore.load(this);
        savedProfile = draft.serialize();
        calibration = GamepadMapping.get().clone();
        savedCalibration = calibration.clone();
        if (state != null) {
            draft = GamepadProfile.parse(state.getString("profile", savedProfile));
            savedProfile = state.getString("savedProfile", savedProfile);
            calibration = validCalibration(state.getIntArray("calibration"), calibration);
            savedCalibration = validCalibration(state.getIntArray("savedCalibration"), savedCalibration);
            calibrationExpanded = state.getBoolean("calibrationExpanded");
            wizardStep = state.getInt("wizardStep");
            wizardMapping = state.getIntArray("wizardMapping");
            mappingActive = state.getBoolean("mappingActive") && wizardMapping != null
                    && wizardMapping.length == GamepadMapping.COUNT && wizardStep >= 0
                    && wizardStep < GamepadMapping.WIZARD_ORDER.length;
        }

        MaterialToolbar toolbar = findViewById(R.id.mapper_toolbar);
        toolbar.setNavigationOnClickListener(v -> leaveEditor());
        saveItem = toolbar.getMenu().add(R.string.gm_save);
        saveItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        saveItem.setOnMenuItemClickListener(item -> { save(); return true; });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { leaveEditor(); }
        });
        content = findViewById(R.id.gamepad_mapping_content);
        render();
    }

    private static int[] validCalibration(int[] value, int[] fallback) {
        return value != null && value.length == GamepadMapping.COUNT ? value.clone() : fallback;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putString("profile", draft.serialize());
        out.putString("savedProfile", savedProfile);
        out.putIntArray("calibration", calibration);
        out.putIntArray("savedCalibration", savedCalibration);
        out.putBoolean("calibrationExpanded", calibrationExpanded);
        out.putBoolean("mappingActive", mappingActive);
        out.putInt("wizardStep", wizardStep);
        out.putIntArray("wizardMapping", wizardMapping);
        super.onSaveInstanceState(out);
    }

    private void render() {
        content.removeAllViews();
        text(getString(R.string.gm_scope), false);
        text(getString(isDirty() ? R.string.gm_unsaved : R.string.gm_saved_state), false);
        heading(R.string.gm_layout_heading);
        text(getString(R.string.gm_layout_hint), false);
        button(getString(R.string.gm_layout_gamepad), v -> {
            draft = GamepadProfile.gamepadDefaults(); render();
        });
        button(getString(R.string.gm_layout_keyboard), v -> {
            draft = GamepadProfile.mouseKeyboardDefaults(); render();
        });
        MaterialButton revert = button(getString(R.string.gm_revert), v -> {
            draft = GamepadProfile.parse(savedProfile);
            calibration = savedCalibration.clone();
            mappingActive = false;
            render();
        });
        revert.setEnabled(isDirty() || mappingActive);

        heading(R.string.gm_buttons);
        for (Input input : BUTTONS) bindingRow(input);
        heading(R.string.gm_left_stick);
        text(getString(R.string.gm_stick_hint), false);
        stickRow(true);
        heading(R.string.gm_right_stick);
        stickRow(false);
        showSharedOutputs();
        showCalibration();
        saveItem.setEnabled(!mappingActive);
    }

    private void bindingRow(Input input) {
        button(getString(R.string.gm_binding_row, inputLabel(input), outputLabel(draft.get(input))),
                v -> chooseCategory(input));
    }

    private String outputLabel(Binding binding) {
        return binding == Binding.NONE ? getString(R.string.gm_output_none) : binding.label;
    }

    private String inputLabel(Input input) {
        int[] standard = GamepadMapping.getDefault();
        for (int i = 0; i < CALIBRATED.length; i++) {
            if (CALIBRATED[i] == input && calibration[i] != standard[i]) {
                return getString(R.string.gm_input_from, input.label, physicalLabel(calibration[i]));
            }
        }
        return input.label;
    }

    private void chooseCategory(Input input) {
        String[] categories = { getString(R.string.gm_category_gamepad),
                getString(R.string.gm_category_mouse), getString(R.string.gm_category_keyboard),
                getString(R.string.gm_category_launcher), getString(R.string.gm_disabled) };
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.gm_category_title, input.label))
                .setItems(categories, (dialog, index) -> {
                    if (index == 4) {
                        draft.set(input, Binding.NONE);
                        render();
                    } else {
                        chooseBinding(input, index, categories[index]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseBinding(Input input, int category, String title) {
        List<Binding> bindings = new ArrayList<>();
        for (Binding binding : Binding.pressable()) {
            boolean include = category == 0 && binding.isGamepad()
                    || category == 1 && (binding.kind == Binding.Kind.MOUSE
                    || binding.kind == Binding.Kind.SCROLL || binding.kind == Binding.Kind.MOUSE_MOVE)
                    || category == 2 && binding.kind == Binding.Kind.KEY
                    || category == 3 && binding.kind == Binding.Kind.SPECIAL;
            if (include) bindings.add(binding);
        }
        String[] labels = new String[bindings.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = outputLabel(bindings.get(i));
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.gm_binding_title, input.label, title))
                .setSingleChoiceItems(labels, bindings.indexOf(draft.get(input)), (dialog, index) -> {
                    draft.set(input, bindings.get(index));
                    dialog.dismiss();
                    render();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void stickRow(boolean left) {
        StickMode mode = left ? draft.leftStick : draft.rightStick;
        String name = getString(left ? R.string.gm_left_stick : R.string.gm_right_stick);
        button(getString(R.string.gm_binding_row, name, mode.label), v -> {
            StickMode[] modes = StickMode.values();
            String[] labels = new String[modes.length];
            for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.gm_stick_mode, name))
                    .setSingleChoiceItems(labels, mode.ordinal(), (dialog, index) -> {
                        if (left) draft.leftStick = modes[index];
                        else draft.rightStick = modes[index];
                        dialog.dismiss();
                        render();
                    })
                    .setNegativeButton(android.R.string.cancel, null).show();
        });
        if (mode == StickMode.DIRECTIONS) {
            for (Input input : left ? LEFT_DIRECTIONS : RIGHT_DIRECTIONS) bindingRow(input);
        }
    }

    private void showSharedOutputs() {
        List<Input> active = new ArrayList<>(Arrays.asList(BUTTONS));
        if (draft.leftStick == StickMode.DIRECTIONS) active.addAll(Arrays.asList(LEFT_DIRECTIONS));
        if (draft.rightStick == StickMode.DIRECTIONS) active.addAll(Arrays.asList(RIGHT_DIRECTIONS));
        Map<Binding, List<String>> targets = new LinkedHashMap<>();
        for (Input input : active) {
            Binding binding = draft.get(input);
            if (binding == Binding.NONE) continue;
            List<String> sources = targets.get(binding);
            if (sources == null) { sources = new ArrayList<>(); targets.put(binding, sources); }
            sources.add(input.label);
        }
        List<String> shared = new ArrayList<>();
        for (Map.Entry<Binding, List<String>> entry : targets.entrySet()) {
            if (entry.getValue().size() > 1) {
                shared.add(outputLabel(entry.getKey()) + " (" + android.text.TextUtils.join(", ", entry.getValue()) + ")");
            }
        }
        if (!shared.isEmpty()) {
            text(getString(R.string.gm_duplicate_hint, android.text.TextUtils.join("; ", shared)), false);
        }
    }

    private void showCalibration() {
        int changes = 0;
        int[] standard = GamepadMapping.getDefault();
        for (int i = 0; i < calibration.length; i++) if (calibration[i] != standard[i]) changes++;
        MaterialButton expand = button(getString(R.string.gm_calibration_heading, changes), v -> {
            calibrationExpanded = !calibrationExpanded;
            render();
        });
        expand.setEnabled(!mappingActive);
        if (!calibrationExpanded && !mappingActive) return;
        text(getString(R.string.gm_calibration_hint), false);
        if (mappingActive) {
            int logical = GamepadMapping.WIZARD_ORDER[wizardStep];
            text(getString(R.string.gm_calibration_step, wizardStep + 1,
                    GamepadMapping.WIZARD_ORDER.length, CALIBRATED[logical].label), true);
            button(getString(R.string.gm_calibration_cancel), v -> { mappingActive = false; render(); });
        } else {
            StringBuilder summary = new StringBuilder();
            for (int i = 0; i < calibration.length; i++) {
                if (i > 0) summary.append('\n');
                summary.append(getString(R.string.gm_calibration_row,
                        physicalLabel(calibration[i]), CALIBRATED[i].label));
            }
            text(summary.toString(), false);
            button(getString(R.string.gm_calibration_start), v -> {
                wizardMapping = calibration.clone();
                wizardStep = 0;
                mappingActive = true;
                render();
            });
            button(getString(R.string.gm_calibration_reset), v -> {
                calibration = GamepadMapping.getDefault();
                render();
            });
        }
    }

    private static String physicalLabel(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_L1: return "LB";
            case KeyEvent.KEYCODE_BUTTON_R1: return "RB";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select / Back";
            case KeyEvent.KEYCODE_BUTTON_START: return "Start";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "Guide";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            default: break;
        }
        return KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_BUTTON_", "")
                .replace("KEYCODE_", "").replace('_', ' ');
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Keep the release paired with a captured press, even when that press finished the wizard.
        if (calibrationHeldKeys.contains(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_UP) calibrationHeldKeys.remove(event.getKeyCode());
            return true;
        }
        if (mappingActive && isFromGamepad(event.getSource())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                calibrationHeldKeys.add(event.getKeyCode());
                calibratePress(event.getKeyCode());
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void calibratePress(int keyCode) {
        if (!isCalibratableButton(keyCode)) {
            Toast.makeText(this, R.string.gm_calibration_button_only, Toast.LENGTH_SHORT).show();
            return;
        }
        if (keyCode == wizardMapping[GamepadMapping.L_GUIDE]) {
            Toast.makeText(this, R.string.gm_calibration_guide_reserved, Toast.LENGTH_SHORT).show();
            return;
        }
        for (int i = 0; i < wizardStep; i++) {
            if (wizardMapping[GamepadMapping.WIZARD_ORDER[i]] == keyCode) {
                Toast.makeText(this, R.string.gm_calibration_duplicate, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        wizardMapping[GamepadMapping.WIZARD_ORDER[wizardStep++]] = keyCode;
        if (wizardStep == GamepadMapping.WIZARD_ORDER.length) {
            calibration = wizardMapping.clone();
            mappingActive = false;
            Toast.makeText(this, R.string.gm_calibration_done, Toast.LENGTH_SHORT).show();
        }
        render();
    }

    private static boolean isCalibratableButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_C:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_Z:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return keyCode >= KeyEvent.KEYCODE_BUTTON_1 && keyCode <= KeyEvent.KEYCODE_BUTTON_16;
        }
    }

    private static boolean isFromGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private boolean isDirty() {
        return !draft.serialize().equals(savedProfile) || !Arrays.equals(calibration, savedCalibration);
    }

    private boolean save() {
        if (mappingActive) {
            Toast.makeText(this, R.string.gm_calibration_finish_first, Toast.LENGTH_SHORT).show();
            return false;
        }
        GamepadProfileStore.save(this, draft);
        GamepadMapping.save(this, calibration);
        savedProfile = draft.serialize();
        savedCalibration = calibration.clone();
        Toast.makeText(this, R.string.gm_saved, Toast.LENGTH_SHORT).show();
        render();
        return true;
    }

    private void leaveEditor() {
        if (mappingActive) {
            mappingActive = false;
            render();
            return;
        }
        if (!isDirty()) { finish(); return; }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gm_discard_title)
                .setMessage(R.string.gm_discard_message)
                .setPositiveButton(R.string.gm_save, (dialog, which) -> { if (save()) finish(); })
                .setNegativeButton(R.string.gm_discard, (dialog, which) -> finish())
                .setNeutralButton(R.string.gm_keep_editing, null).show();
    }

    private void heading(int stringId) { text(getString(stringId), true); }

    private TextView text(String value, boolean heading) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(heading ? 20 : 14);
        view.setTextColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface));
        view.setPadding(0, dp(heading ? 20 : 6), 0, dp(8));
        if (heading) view.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private MaterialButton button(String label, View.OnClickListener action) {
        MaterialButton view = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        view.setText(label);
        view.setAllCaps(false);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(52));
        view.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4);
        content.addView(view, params);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
