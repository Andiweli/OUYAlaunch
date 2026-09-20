package com.ast.ouyalaunch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.appcompat.widget.AppCompatRadioButton;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
    interface GenreSelectedListener { void onGenreSelected(int genreIndex); }

    /** Compact destination chooser for the tile "Move Genre" action. */
    static AlertDialog showMoveGenre(Context context, CategorySettings settings, String currentGenre,
            GenreSelectedListener listener) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final CategoryDialogSizing sizing = CategoryDialogSizing.forDisplay(metrics.widthPixels,
                metrics.heightPixels, metrics.density, metrics.scaledDensity);

        // Favorites is a virtual tab, not a stable AppEntry.genre destination. Hidden
        // categories and the tile's current category are intentionally not offered.
        java.util.ArrayList<Integer> destinations = new java.util.ArrayList<>();
        for (int i = 1; i < MainActivity.GENRES.size(); i++) {
            if (settings.enabled(i) && !MainActivity.GENRES.get(i).equals(currentGenre)) destinations.add(i);
        }
        if (destinations.isEmpty()) {
            Toast.makeText(context, R.string.no_move_category, Toast.LENGTH_SHORT).show();
            return null;
        }

        LinearLayout contents = new LinearLayout(context);
        contents.setOrientation(LinearLayout.VERTICAL);
        contents.setPadding(sizing.px(16), sizing.px(6), sizing.px(16), sizing.px(4));

        final AppCompatRadioButton[] rows = new AppCompatRadioButton[destinations.size()];
        for (int i = 0; i < destinations.size(); i++) {
            int genreIndex = destinations.get(i);
            AppCompatRadioButton row = new AppCompatRadioButton(context);
            row.setId(0x00f20000 + i);
            Drawable radioGraphic = androidx.core.widget.CompoundButtonCompat.getButtonDrawable(row);
            if (radioGraphic != null) {
                row.setButtonDrawable(new CompactCheckboxDrawable(radioGraphic.mutate(),
                        sizing.px(CategoryDialogSizing.CHECKBOX_SIZE_DP)));
            }
            androidx.core.widget.CompoundButtonCompat.setButtonTintList(row, checkboxColors());
            row.setText(settings.name(genreIndex));
            row.setTag(Integer.valueOf(genreIndex));
            row.setChecked(false);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            row.setPadding(0, sizing.px(3), sizing.px(8), sizing.px(3));
            row.setCompoundDrawablePadding(sizing.px(6));
            compactTextControl(row, sizing);
            int rowHeight = controlHeight(row, sizing);
            contents.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
            rows[i] = row;
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalFadingEdgeEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(contents, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setCustomTitle(createMoveGenreTitle(context, sizing))
                .setView(scroll, 0, 0, 0, 0)
                .setNegativeButton(R.string.cancel, null)
                .create();

        for (AppCompatRadioButton row : rows) {
            row.setOnClickListener(view -> {
                Object value = view.getTag();
                if (value instanceof Integer && listener != null) {
                    listener.onGenreSelected(((Integer) value).intValue());
                }
                dialog.dismiss();
            });
            row.setOnKeyListener((view, key, event) -> {
                if (key != KeyEvent.KEYCODE_BUTTON_A && key != KeyEvent.KEYCODE_DPAD_CENTER
                        && key != KeyEvent.KEYCODE_ENTER) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) view.performClick();
                return true;
            });
        }

        dialog.setOnKeyListener((d, key, event) -> {
            // OUYA A is Android BUTTON_B and always cancels this chooser.
            if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_MENU
                    || key == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) dialog.dismiss();
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> {
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            compactActionButton(cancel, sizing);
            for (int i = 0; i < rows.length; i++) {
                if (i > 0) rows[i].setNextFocusUpId(rows[i - 1].getId());
                rows[i].setNextFocusDownId(i + 1 < rows.length ? rows[i + 1].getId() : cancel.getId());
            }
            cancel.setNextFocusUpId(rows[rows.length - 1].getId());
            confirmWithController(cancel);
            if (dialog.getWindow() != null) {
                dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                dialog.getWindow().setGravity(Gravity.CENTER);
                // The destination chooser is intentionally about one third narrower
                // than the previous 440 dp cap while keeping the editor typography.
                int width = Math.min(sizing.windowWidth, sizing.px(294));
                dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            rows[0].requestFocus();
        });
        dialog.show();
        return dialog;
    }

    static AlertDialog show(Context context, CategorySettings settings, SavedListener listener) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final CategoryDialogSizing sizing = CategoryDialogSizing.forDisplay(metrics.widthPixels,
                metrics.heightPixels, metrics.density, metrics.scaledDensity);
        final int count = MainActivity.GENRES.size();
        final CheckBox[] checks = new CheckBox[count];
        final EditText[] names = new EditText[count];
        LinearLayout contents = new LinearLayout(context);
        contents.setOrientation(LinearLayout.VERTICAL);
        contents.setPadding(sizing.px(16), sizing.px(6), sizing.px(16), sizing.px(4));
        TextView hint = new TextView(context);
        hint.setText(R.string.categories_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizing.textPx(12));
        hint.setIncludeFontPadding(false);
        hint.setGravity(Gravity.LEFT);
        hint.setPadding(0, 0, 0, sizing.px(8));
        contents.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < count; i++) {
            final int rowIndex = i;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBaselineAligned(false);
            AppCompatCheckBox check = new AppCompatCheckBox(context);
            Drawable checkboxGraphic = androidx.core.widget.CompoundButtonCompat.getButtonDrawable(check);
            if (checkboxGraphic != null) {
                // Resize the actual theme drawable, not the whole view: hit/focus bounds
                // and the label stay aligned, including on Android 4.1 / OUYA.
                check.setButtonDrawable(new CompactCheckboxDrawable(checkboxGraphic.mutate(),
                        sizing.px(CategoryDialogSizing.CHECKBOX_SIZE_DP)));
            }
            androidx.core.widget.CompoundButtonCompat.setButtonTintList(check, checkboxColors());
            check.setId(0x00f10000 + i * 2);
            check.setText(i == 0 ? context.getString(R.string.tab_favorites)
                    : context.getString(R.string.category_slot, i));
            compactTextControl(check, sizing);
            check.setSingleLine(true);
            check.setEllipsize(TextUtils.TruncateAt.END);
            check.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            check.setPadding(0, sizing.px(3), sizing.px(8), sizing.px(3));
            check.setCompoundDrawablePadding(sizing.px(6));
            check.setChecked(settings.enabled(i));
            EditText name = new EditText(context);
            name.setId(0x00f10001 + i * 2);
            name.setText(settings.name(i));
            name.setHint(MainActivity.GENRES.get(i));
            name.setSingleLine(true);
            name.setSelectAllOnFocus(false);
            name.setSelection(name.length());
            compactTextControl(name, sizing);
            name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            name.setPadding(sizing.px(6), sizing.px(3), sizing.px(6), sizing.px(3));
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
            // Explicit, font-aware control heights also override intrinsic background
            // minimums from old Holo / high-density themes. Never scale the whole view.
            int rowHeight = Math.max(controlHeight(check, sizing), controlHeight(name, sizing));
            row.addView(check, new LinearLayout.LayoutParams(sizing.px(CategoryDialogSizing.LABEL_WIDTH_DP),
                    rowHeight));
            row.addView(name, new LinearLayout.LayoutParams(0, rowHeight, 1));
            contents.addView(row);
        }
        ScrollView scroll = new EditorScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalFadingEdgeEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(contents, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setCustomTitle(createTitle(context, sizing))
                .setView(scroll, 0, 0, 0, 0)
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
            compactActionButtons(save, cancel, sizing);
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
                dialog.getWindow().setGravity(Gravity.CENTER);
                // Let AlertDialog measure its title, all seven rows and the action bar.
                // A fixed height clipped the fifth row on dense displays. When the IME
                // reduces available height, the existing ScrollView remains scrollable.
                dialog.getWindow().setLayout(sizing.windowWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
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

    private static View createMoveGenreTitle(Context context, CategoryDialogSizing sizing) {
        LinearLayout title = new LinearLayout(context);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setGravity(Gravity.LEFT);
        title.setPadding(sizing.px(16), sizing.px(12), sizing.px(16), sizing.px(6));

        TextView heading = new TextView(context);
        heading.setTextAppearance(context, androidx.appcompat.R.style.TextAppearance_AppCompat_Title);
        heading.setText(R.string.move_genre_title);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizing.textPx(18));
        heading.setIncludeFontPadding(false);
        heading.setSingleLine(true);
        heading.setGravity(Gravity.LEFT);
        title.addView(heading, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(context);
        subtitle.setTextAppearance(context, androidx.appcompat.R.style.TextAppearance_AppCompat_Body1);
        subtitle.setText(R.string.move_genre_subtitle);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizing.textPx(10));
        subtitle.setIncludeFontPadding(false);
        subtitle.setSingleLine(true);
        subtitle.setGravity(Gravity.LEFT);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = sizing.px(3);
        title.addView(subtitle, subtitleParams);
        return title;
    }

    private static View createTitle(Context context, CategoryDialogSizing sizing) {
        LinearLayout title = new LinearLayout(context);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setGravity(Gravity.LEFT);
        title.setPadding(sizing.px(16), sizing.px(12), sizing.px(16), 0);

        LinearLayout brandRow = new LinearLayout(context);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setBaselineAligned(true);
        brandRow.setGravity(Gravity.LEFT | Gravity.TOP);

        TextView brand = new TextView(context);
        brand.setTextAppearance(context, androidx.appcompat.R.style.TextAppearance_AppCompat_Title);
        brand.setText(R.string.app_name);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizing.textPx(18));
        brand.setIncludeFontPadding(false);
        brand.setSingleLine(true);
        brand.setGravity(Gravity.LEFT);

        TextView author = new TextView(context);
        author.setTextAppearance(context, androidx.appcompat.R.style.TextAppearance_AppCompat_Body1);
        author.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizing.textPx(10));
        author.setIncludeFontPadding(false);
        author.setSingleLine(true);
        author.setEllipsize(TextUtils.TruncateAt.END);
        author.setText(R.string.app_author);
        author.setGravity(Gravity.LEFT);

        TextView version = new TextView(context);
        version.setTextAppearance(context, androidx.appcompat.R.style.TextAppearance_AppCompat_Body1);
        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizing.textPx(10));
        version.setIncludeFontPadding(false);
        version.setSingleLine(true);
        version.setText(context.getString(R.string.app_version, BuildConfig.VERSION_NAME));
        version.setGravity(Gravity.RIGHT);

        brandRow.addView(brand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams authorParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        authorParams.leftMargin = sizing.px(8);
        authorParams.rightMargin = sizing.px(8);
        brandRow.addView(author, authorParams);

        brandRow.addView(version, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        title.addView(brandRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
    private static void compactTextControl(TextView control, CategoryDialogSizing sizing) {
        control.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizing.textPx(CategoryDialogSizing.ROW_TEXT_SP));
        control.setIncludeFontPadding(false);
        // TextView's minHeight and View's minimumHeight are independent theme values.
        control.setMinHeight(sizing.px(CategoryDialogSizing.ROW_MIN_HEIGHT_DP));
        control.setMinimumHeight(sizing.px(CategoryDialogSizing.ROW_MIN_HEIGHT_DP));
        control.setMinWidth(0);
        control.setMinimumWidth(0);
    }

    private static int controlHeight(TextView control, CategoryDialogSizing sizing) {
        return Math.max(sizing.px(CategoryDialogSizing.ROW_MIN_HEIGHT_DP),
                (int) Math.ceil(control.getPaint().getFontSpacing())
                        + control.getPaddingTop() + control.getPaddingBottom() + sizing.px(2));
    }

    private static void compactActionButton(Button action, CategoryDialogSizing sizing) {
        if (action == null) return;
        compactTextControl(action, sizing);
        action.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizing.textPx(13));
        action.setMinWidth(sizing.px(72));
        action.setMinimumWidth(sizing.px(72));
        action.setPadding(sizing.px(12), sizing.px(3), sizing.px(12), sizing.px(3));
        // Respect larger accessibility fonts without restoring the theme's very
        // tall button height. Leave enough room for the measured glyphs/padding.
        ViewGroup.LayoutParams params = action.getLayoutParams();
        if (params != null) {
            params.height = controlHeight(action, sizing);
            action.setLayoutParams(params);
        }
    }

    private static void compactActionButtons(Button save, Button cancel, CategoryDialogSizing sizing) {
        compactActionButton(save, sizing);
        compactActionButton(cancel, sizing);
        if (save.getParent() == cancel.getParent() && save.getParent() instanceof ViewGroup) {
            ViewGroup bar = (ViewGroup) save.getParent();
            bar.setMinimumHeight(0);
            bar.setPadding(sizing.px(12), 0, sizing.px(12), sizing.px(6));
        }
    }

    /** InsetDrawable forwards the theme checkbox's state, drawing and invalidations. */
    static final class CompactCheckboxDrawable extends InsetDrawable {
        private final int extent;

        CompactCheckboxDrawable(Drawable drawable, int extent) {
            super(drawable, 0);
            this.extent = extent;
        }

        @Override public int getIntrinsicWidth() { return extent; }
        @Override public int getIntrinsicHeight() { return extent; }
        @Override public int getMinimumWidth() { return extent; }
        @Override public int getMinimumHeight() { return extent; }
    }
    private CategoryDialog() { }
}
