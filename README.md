# OUYAlaunch <br/> Lightweight launcher for OUYA Android console

> Fast, OUYA-themed Android launcher for the **OUYA** micro-gaming-console with favorites, genre tabs, and automatic icon caching.

[![Latest release](https://img.shields.io/github/v/release/andiweli/OUYAlaunch?label=latest%20APK)](https://github.com/andiweli/OUYAlaunch/releases/latest)
[![Platform](https://img.shields.io/badge/platform-OUYA%20(Android%204.1.2)-orange)](https://github.com/andiweli/OUYAlaunch)
[![minSdk](https://img.shields.io/badge/minSdk-16-brightgreen)](https://github.com/andiweli/OUYAlaunch)
[![Language](https://img.shields.io/badge/language-Java-blue)](https://github.com/andiweli/OUYAlaunch)
[![Support via PayPal](https://img.shields.io/badge/Support%20via-PayPal-0070BA?logo=paypal\&logoColor=white)](https://paypal.me/andiweli)


**OUYAlaunch** is a custom home-screen replacement for the **OUYA** Android micro-console.  
It focuses on a clean, OUYA-styled UI, fast navigation with the OUYA controller and a lightweight code base that runs smoothly on the original hardware.

<img width="100%" alt="image" src="https://github.com/user-attachments/assets/ffa3af32-f796-4e6e-acfc-8233935abb7f" />

---

## ✨ Features

- 🎨 **OUYA-styled, clean UI**
  - Color scheme and layout designed specifically for the OUYA.
  - Custom buttons and logos drawn for a cohesive console look.

- ⚡ **Fast and lightweight**
  - Optimized for the OUYA’s Android 4.1.2 base.
  - Caches app icons for quick grid rendering.

- 🕹️ **Controller-only navigation**
  - Designed to be fully controllable with the OUYA gamepad (no mouse/keyboard required).
  - All management actions are mapped to the OUYA face buttons and shoulder triggers.

- 📁 **Automatic app discovery via `ouya_icon.png`**
  - On first launch, OUYAlaunch scans **all installed apps** for an `ouya_icon.png` somewhere in their `res/` folders.
  - Icons are cached so the launcher grid stays responsive even with many installed games.

- ⭐ **Favorites and genre tabs**
  - Apps are organized into multiple tabs (Favorites + 6 genres).
  - By default, all newly detected apps land in the second tab **“Casual”**, so you can re-organize them at your own pace.

- 🏷️ **Configurable genre names**
  - Rename all genre tabs via OUYA-button config overlay.
  - You can de/select your needed tabs too!

---

## 🖼 Screenshot

![OUYAlaunch – favorites & genre tabs](https://github.com/user-attachments/assets/3970fbbf-0d41-4a02-a4bc-386e7c488619)

![OUYAlaunch - edit genre tabs](https://github.com/user-attachments/assets/b32a6002-e6b5-4e94-a778-e9e555dd2dfa)

---

## 🚀 How it works (first launch & app scan)

On the **first launch**:

1. OUYAlaunch scans all installed apps on the console.
2. For each package it looks for an `ouya_icon.png` anywhere inside the app’s `res/` folder.
3. Matching icons are **cached** so the launcher can draw the app grid quickly.
4. Every found app is initially placed into the **“Casual”** tab (second tab) — you can then move each app into the genre that fits best.

This makes OUYAlaunch a good drop-in replacement for the stock OUYA launcher while still respecting the familiar OUYA icon convention.

---

## 🎮 Controller mapping

OUYAlaunch does not have a classic “settings” menu.  
Instead, you control everything with the OUYA controller buttons:

- **O** – Launch the selected app  
- **U** – Move the selected app to the **next genre tab**  
- **Y** – Delete an app (hold for ~2 seconds)  
- **A** – Mark / unmark an app as **Favorite**

Navigation and global actions:

- **L1 / R1** – Switch between tabs (Favorites + genre tabs)  
- **L2 + R2 (pressed together)** – Rescan all installed apps (rebuilds the cache)  
- **D-Pad / Left stick / Right stick** – Move selection in the app grid  

Everything in the launcher can be reached using only the OUYA controller.

---

## 🧩 Renaming genre tabs (JSON config)

Since version **1.5+**, you can rename the six genre tabs using a JSON file created by the launcher on first scan.

The config file is stored at:

    sdcard/android/data/com.ast.ouyalaunch

Inside that folder you will find a JSON file that looks like this:

    {
      "Favorites": "Favorites",
      "Genre1": "Casual",
      "Genre2": "Action",
      "Genre3": "Racing",
      "Genre4": "Simulation",
      "Genre5": "RPG",
      "Genre6": "Apps"
    }

- Do **not** rename the keys (`"Genre1"` ... `"Genre6"`).  
- Only change the **values** on the right side (e.g. `"Racing"`, `"Apps"` etc.).  
- You can edit the file directly on the OUYA with a file manager such as *Total Commander* or on a PC and then copy it back.

The launcher will read these values and update the tab titles the next time it runs.

---

## 📦 Installation (ready-to-use APK)

If you just want to use OUYAlaunch on your OUYA without touching the source code:

1. Go to the **Releases** section:  
   👉 **[Download the latest OUYAlaunch APK](https://github.com/andiweli/OUYAlaunch/releases/latest)**
2. Copy the APK to your OUYA (via USB drive, network share, or browser).  
3. Install the APK on your OUYA.  
4. When you press the **OUYA / Home** button, select OUYAlaunch as your launcher (and mark it as default if you want it to fully replace the stock launcher).

You can check the detailed version history in the included `changelog.txt` file or directly on GitHub.

---

## 🛠 Build from source

If you want to modify OUYAlaunch or build it yourself:

- **Minimum Android version:** SDK 16 (Android 4.1.x – matching the OUYA firmware)  
- **IDE:** Android Studio **4.0.1** or newer  
- **Language:** Java  

### Steps

1. Clone the repository:

       git clone https://github.com/andiweli/OUYAlaunch.git
       cd OUYAlaunch

2. Open the project in **Android Studio 4.0.1+**.  
3. Let Gradle sync and resolve dependencies.  
4. Build the APK (menu: Build → Make Project or Build Bundle(s) / APK(s)).  
5. Deploy the resulting APK to your OUYA.

The project is optimized for SDK 16 so it stays compatible with the original OUYA system software.

---

## ❤️ Support

If you enjoy this project and would like to support my work, you can make a small contribution via PayPal.

Your support helps me spend more time maintaining existing projects, fixing bugs, improving compatibility, and working on new features.

[![Support via PayPal](https://img.shields.io/badge/Support%20via-PayPal-0070BA?logo=paypal\&logoColor=white)](https://paypal.me/andiweli)

Thank you for your support!

---

## ℹ️ About

**OUYAlaunch**  
Lightweight and fast launcher for the **OUYA Android micro-gaming-console**.  
Created as a side project to give the OUYA a clean, console-style launcher focused on games and controller-only navigation.

If you find OUYAlaunch useful, consider giving the repo a ⭐ on GitHub so more OUYA owners can discover it.

---

## 🔎 Keywords / topics

_ouya • android • launcher • home screen • couch gaming • micro-console • favorites • genres • ouya_icon • custom launcher_
