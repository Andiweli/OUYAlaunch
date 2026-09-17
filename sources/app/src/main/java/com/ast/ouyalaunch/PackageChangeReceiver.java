package com.ast.ouyalaunch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Legacy compatibility class; no longer registered. Scans remain exclusively manual. */
public final class PackageChangeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // Intentionally empty: package changes must not wake the launcher or write preferences.
    }
}
