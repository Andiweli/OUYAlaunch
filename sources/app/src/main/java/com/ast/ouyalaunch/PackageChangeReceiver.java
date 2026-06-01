package com.ast.ouyalaunch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class PackageChangeReceiver extends BroadcastReceiver {
    private static final String STATE_PREFS = "ouyalaunch_state";
    private static final String KEY_PACKAGE_RESCAN_PENDING = "package_rescan_pending";
    private static final String KEY_LAST_PACKAGE_EVENT_AT = "last_package_event_at";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        String action = intent.getAction();
        if (Intent.ACTION_PACKAGE_ADDED.equals(action) || Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : "";

            // Do not trigger or queue automatic rescans. The launcher cache is built once
            // on first install/start and is updated only by the manual L2+R2 rescan.
            // Also clear any stale pending flag left by older builds so returning from a
            // game cannot start a scan.
            context.getApplicationContext()
                    .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PACKAGE_RESCAN_PENDING, false)
                    .putLong(KEY_LAST_PACKAGE_EVENT_AT, System.currentTimeMillis())
                    .apply();

            Log.i("PackageChangeReceiver", "package change ignored; manual scan only: " + action + " " + pkg);
        }
    }
}
