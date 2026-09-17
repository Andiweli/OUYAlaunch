
package com.ast.ouyalaunch;

public class AppEntry {
    public String packageName;
    public String title;
    public String genre; // one of Casual, Action & Shooter, Racing, Simulation, RPG
    public boolean favorite;
    public String iconPath; // cached icon file path

    public long packageUpdatedAt;
    public long apkLength;
    public int iconCacheVersion;

    public AppEntry() {}

    public AppEntry(AppEntry other) {
        this(other.packageName, other.title, other.genre, other.favorite, other.iconPath);
        packageUpdatedAt = other.packageUpdatedAt;
        apkLength = other.apkLength;
        iconCacheVersion = other.iconCacheVersion;
    }

    public AppEntry(String pkg, String title, String genre, boolean favorite, String iconPath) {
        this.packageName = pkg;
        this.title = title;
        this.genre = genre;
        this.favorite = favorite;
        this.iconPath = iconPath;
    }
}
