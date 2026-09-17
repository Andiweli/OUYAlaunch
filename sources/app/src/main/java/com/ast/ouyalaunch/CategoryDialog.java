package com.ast.ouyalaunch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import androidx.appcompat.widget.AppCompatCheckBox;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.lang.ref.WeakReference;

/** Controller/touch/keyboard editor; its display labels never replace stable game category keys. */
final class CategoryDialog {
    interface SavedListener { void onSaved(CategorySettings settings); }

    static AlertDialog show(Context context, CategorySettings settings, SavedListener listener) {
        final int count = MainActivity.GENRES.size();
        final CheckBox[] checks = new CheckBox[count];
        final EditText[] names = new EditText[count];
        LinearLayout contents = new LinearLayout(context);
        contents.setOrientation(LinearLayout.VERTICAL);
        contents.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8));
        TextView hint = new TextView(context);
        hint.setText(R.string.categories_hint);
        hint.setTextSize(16);
        hint.setPadding(0, 0, 0, dp(context, 12));
        contents.addView(hint);
        for (int i = 0; i < count; i++) {
            final int rowIndex = i;
            LinearLayout row = new LinearLayout(context);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            AppCompatCheckBox check = new AppCompatCheckBox(context);
            androidx.core.widget.CompoundButtonCompat.setButtonTintList(check, checkboxColors());
            check.setId(0x00f10000 + i * 2);
            check.setText(i == 0 ? context.getString(R.string.tab_favorites)
                    : context.getString(R.string.category_slot, i));
            check.setTextSize(18);
            check.setChecked(settings.enabled(i));
            check.setMinHeight(dp(context, 52));
            EditText name = new EditText(context);
            name.setId(0x00f10001 + i * 2);
            name.setText(settings.name(i));
            name.setHint(MainActivity.GENRES.get(i));
            name.setSingleLine(true);
            name.setSelectAllOnFocus(false);
            name.setSelection(name.length());
            name.setTextSize(18);
            name.setMinHeight(dp(context, 52));
            name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(CategorySettings.MAX_NAME_LENGTH)});
            name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            check.setNextFocusRightId(name.getId());
            name.setNextFocusLeftId(check.getId());
            checks[i] = check;
            names[i] = name;
            check.setOnCheckedChangeListener((button, enabled) -> {
                int active = 0;
                for (CheckBox item : checks) if (item != null && item.isChecked()) active++;
                if (!enabled && active == 0) {
                    button.setChecked(true);
                    Toast.makeText(context, R.string.category_required, Toast.LENGTH_SHORT).show();
                }
            });
            confirmWithController(check);
            name.setOnKeyListener((view, key, event) -> {
                if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)
                        && !event.isShiftPressed() && !event.isAltPressed() && !event.isCtrlPressed()) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int start = Math.max(0, name.getSelectionStart());
                        int end = Math.max(0, name.getSelectionEnd());
                        boolean left = key == KeyEvent.KEYCODE_DPAD_LEFT;
                        int caret = left ? Math.min(start, end) : Math.max(start, end);
                        if (start == end && (left ? caret > 0 : caret < name.length())) {
                            caret = Character.offsetByCodePoints(name.getText(), caret, left ? -1 : 1);
                        }
                        name.setSelection(caret);
                    }
                    return true;
                }
                if (key != KeyEvent.KEYCODE_BUTTON_A && key != KeyEvent.KEYCODE_DPAD_CENTER) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    name.requestFocus();
                    // Keep the caret/selection when opening the keyboard with the controller.
                    InputMethodManager ime = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (ime != null) ime.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT);
                }
                return true;
            });
            name.setOnEditorActionListener((view, action, event) -> {
                if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
                InputMethodManager ime = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (ime != null) ime.hideSoftInputFromWindow(name.getWindowToken(), 0);
                checks[rowIndex].requestFocus();
                return true;
            });
            row.addView(check, new LinearLayout.LayoutParams(dp(context, 175), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            contents.addView(row);
        }
        ScrollView scroll = new EditorScrollView(context);
        scroll.addView(contents);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setCustomTitle(createTitle(context))
                .setView(scroll)
                .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                .setPositiveButton(R.string.save, null).create();
        final boolean[] saving = {false};
        dialog.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_MENU
                    || key == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (!saving[0] && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) d.dismiss();
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    checks[i].setNextFocusUpId(checks[i - 1].getId());
                    names[i].setNextFocusUpId(names[i - 1].getId());
                }
                checks[i].setNextFocusDownId(i + 1 < count ? checks[i + 1].getId() : save.getId());
                names[i].setNextFocusDownId(i + 1 < count ? names[i + 1].getId() : save.getId());
            }
            save.setNextFocusUpId(checks[count - 1].getId());
            cancel.setNextFocusUpId(checks[count - 1].getId());
            confirmWithController(save);
            confirmWithController(cancel);
            save.setOnClickListener(view -> {
                if (saving[0]) return;
                String[] labels = new String[count];
                boolean[] visible = new boolean[count];
                for (int i = 0; i < count; i++) { labels[i] = names[i].getText().toString(); visible[i] = checks[i].isChecked(); }
                final CategorySettings updated;
                try { updated = new CategorySettings(labels, visible); }
                catch (IllegalArgumentException error) {
                    Toast.makeText(context, R.string.category_required, Toast.LENGTH_SHORT).show();
                    return;
                }
                saving[0] = true;
                save.setEnabled(false);
                cancel.setEnabled(false);
                dialog.setCancelable(false);
                for (int i = 0; i < count; i++) { checks[i].setEnabled(false); names[i].setEnabled(false); }
                Context app = context.getApplicationContext();
                WeakReference<AlertDialog> owner = new WeakReference<>(dialog);
                WeakReference<SavedListener> callback = new WeakReference<>(listener);
                DataStore.execute(() -> {
                    Exception failure = null;
                    try { updated.save(app); } catch (Exception error) { failure = error; }
                    final boolean success = failure == null;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        AlertDialog target = owner.get();
                        if (target == null || !target.isShowing()) return;
                        if (success) {
                            SavedListener saved = callback.get();
                            if (saved != null) saved.onSaved(updated);
                            target.dismiss();
                        } else {
                            saving[0] = false;
                            target.setCancelable(true);
                            save.setEnabled(true);
                            cancel.setEnabled(true);
                            for (int i = 0; i < count; i++) { checks[i].setEnabled(true); names[i].setEnabled(true); }
                            Toast.makeText(target.getContext(), R.string.save_failed, Toast.LENGTH_LONG).show();
                        }
                    });
                });
            });
            if (dialog.getWindow() != null) {
                // Fullscreen windows ignore ADJUST_RESIZE on older Android/OUYA builds.
                dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
                int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
                dialog.getWindow().setLayout(Math.min(dp(context, 700), (int) (screenWidth * .92f)),
                        Math.min(dp(context, 570), (int) (screenHeight * .90f)));
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            checks[0].requestFocus();
        });
        dialog.show();
        return dialog;
    }

    static final class EditorScrollView extends ScrollView {
        EditorScrollView(Context context) { super(context); }

        private void revealFocusedField() {
            post(() -> {
                View focused = findFocus();
                if (focused instanceof EditText) {
                    // Run after the IME has resized and laid out the scroll viewport.
                    focused.requestRectangleOnScreen(new android.graphics.Rect(
                            0, 0, focused.getWidth(), focused.getHeight()), true);
                }
            });
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            revealFocusedField();
        }

        @Override public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            revealFocusedField();
        }
    }

    private static View createTitle(Context context) {
        LinearLayout title = new LinearLayout(context);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setPadding(dp(context, 20), dp(context, 20), dp(context, 20), 0);
        LinearLayout brandRow = new LinearLayout(context);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setBaselineAligned(true);
        brandRow.setGravity(android.view.Gravity.TOP);
        TextView brand = new TextView(context);
        brand.setTextAppearance(context, androidx.appcompat.R.style.TextAppearance_AppCompat_Title);
        brand.setText(R.string.app_name);
        TextView author = new TextView(context);
        author.setTextAppearance(context, androidx.appcompat.R.style.TextAppearance_AppCompat_Body1);
        author.setTextSize(12);
        author.setText(R.string.app_author);
        brandRow.addView(brand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams authorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        authorParams.leftMargin = dp(context, 10);
        brandRow.addView(author, authorParams);
        title.addView(brandRow);
        return title;
    }

    static ColorStateList checkboxColors() {
        int orange = Color.rgb(255, 138, 0);
        return new ColorStateList(new int[][] {
                {-android.R.attr.state_enabled}, {android.R.attr.state_hovered},
                {android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[] {0xff666666, orange, orange, orange, 0xffd8d9dc});
    }

    private static void confirmWithController(View view) {
        view.setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_BUTTON_A && key != KeyEvent.KEYCODE_DPAD_CENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) v.performClick();
            return true;
        });
    }
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    private CategoryDialog() { }
}
