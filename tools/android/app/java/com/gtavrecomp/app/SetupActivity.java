package com.gtavrecomp.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Modern GTA V Setup Screen.
 * Displays clean 3-disc installation options (Disc 1, Disc 2, Title Update 27) over the iconic
 * GTA V beach artwork. The game pack (gtav-android-pack.zip) is embedded directly into the APK assets
 * and extracted automatically.
 *
 * Selected filenames appear in place of placeholder demonstrations only once selected.
 * Real-time installation logs with [mm:ss] timestamps appear directly under the Install button.
 */
public class SetupActivity extends Activity {
    private static final String TAG = "GtavSetup";
    static {
        System.loadLibrary("gtav_setup");
    }

    private native String nativeInstall(String home, String expected, String disc1, String disc2, String tu);
    private static native boolean nativeIsDataInstalled(String home);

    private static final String[] TITLES = {
        "Disc 1 (Install)",
        "Disc 2 (Play)",
        "Title Update 27",
    };
    private static final String[] ICONS = {
        "\uD83D\uDCC4 ", // document icon
        "\uD83D\uDCBF ", // disc icon
        "\uD83D\uDCE6 ", // update package icon
    };

    private final Uri[] picked = new Uri[3];
    private final LinearLayout[] fileContainers = new LinearLayout[3];
    private final TextView[] fileNames = new TextView[3];
    private final Button[] pickButtons = new Button[3];
    private Button installBtn;
    private ProgressBar progressBar;
    private TextView logText;
    private ScrollView logScroll;

    private volatile boolean busy;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private SharedPreferences prefs;

    private final StringBuilder logHistory = new StringBuilder();
    private long startTimeMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("setup", MODE_PRIVATE);
        startTimeMs = SystemClock.elapsedRealtime();

        if (!GameActivity.isReleaseLayout(this)) {
            startGame();
            return;
        }
        addSetupShortcut();

        boolean again = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SETUP, false);
        if (!again && packCurrent() == null && isDataInstalled()) {
            startGame();
            return;
        }

        for (int i = 0; i < 3; i++) {
            String s = prefs.getString("disc_uri_" + i, null);
            if (s != null) {
                try {
                    picked[i] = Uri.parse(s);
                } catch (Exception ignored) {
                }
            }
        }

        setContentView(buildUi());
        refresh();

        // Check if game pack is already installed or needs automatic extraction from APK assets
        if (packCurrent() != null && (hasBundledPack() || findLocalPack() != null)) {
            autoExtractPack();
        } else if (packCurrent() == null) {
            appendLog("Game pack verified.");
            if (isDataInstalled()) {
                appendLog("Game files verified. Ready to play!");
            } else {
                appendLog("Choose game discs below to begin installation.");
            }
        }
    }

    private static final String EXTRA_SETUP = "com.gtavrecomp.app.SETUP";

    private void addSetupShortcut() {
        try {
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return;
            Intent i = new Intent(this, SetupActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(EXTRA_SETUP, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(this, "setup")
                .setShortLabel("Run setup again")
                .setLongLabel("Run setup again (change discs or pack)")
                .setIntent(i)
                .build();
            sm.setDynamicShortcuts(java.util.Collections.singletonList(info));
        } catch (Exception e) {
            Log.w(TAG, "shortcut: " + e);
        }
    }

    // --- State & Directories --------------------------------------------------------------------

    private File home() {
        File ext = getExternalFilesDir(null);
        if (ext != null) return ext;
        return getFilesDir();
    }

    private File packDir() {
        return new File(getFilesDir(), "pack");
    }

    private boolean hasBundledPack() {
        try (InputStream is = getAssets().open("gtav-android-pack.zip")) {
            return is != null;
        } catch (Exception e) {
            return false;
        }
    }

    private File findLocalPack() {
        File[] candidates = {
            new File("/storage/emulated/0/gtav/gtav-android-pack-poco-x7-pro.zip"),
            new File("/storage/emulated/0/gtav/gtav-android-pack.zip"),
            new File("/sdcard/gtav/gtav-android-pack.zip")
        };
        for (File f : candidates) {
            if (f.exists() && f.canRead() && f.length() > 1000000) return f;
        }
        return null;
    }

    private boolean isDataInstalled() {
        if (nativeIsDataInstalled(home().getAbsolutePath())) return true;
        File[] candidates = {
            new File("/storage/emulated/0/gtav/data"),
            new File("/storage/emulated/0/gtav/data_backup/data"),
            new File("/sdcard/gtav/data"),
            new File(home(), "data")
        };
        for (File dir : candidates) {
            if (new File(dir, "data_installed.txt").exists() &&
                (new File(dir, "game/default.xex").exists() || new File(dir, "game").exists())) {
                return true;
            }
        }
        return false;
    }

    private String packCurrent() {
        File lib = new File(getFilesDir(), "libgtav_game.so");
        File nativeLib = new File(getApplicationInfo().nativeLibraryDir, "libgtav_game.so");
        File info = new File(packDir(), "pack.txt");
        if ((!lib.exists() && !nativeLib.exists()) || !info.exists()) return "No game pack yet.";
        String mine = sha256(new File(getApplicationInfo().nativeLibraryDir, "libgtav_fast.so"));
        Log.i(TAG, "Checking game pack. Installed libgtav_fast.so SHA-256: " + mine);
        if (!matchShell(info, mine)) {
            Log.w(TAG, "Game pack mismatch. Native hash=" + mine);
            return "The game pack was made for another version of this app.";
        }
        return null;
    }

    private static boolean matchShell(File info, String mine) {
        if (mine == null) return false;
        try (BufferedReader r = new BufferedReader(new FileReader(info))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("shell ")) {
                    String shell = line.substring(6).trim();
                    if (shell.equalsIgnoreCase("any") || shell.equalsIgnoreCase(mine)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "pack.txt: " + e);
        }
        return false;
    }

    private static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0;) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // --- UI Construction ------------------------------------------------------------------------

    private View buildUi() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(16, 20, 24));

        // 1. Background Wallpaper (GTA V beach selfie artwork)
        ImageView bgView = new ImageView(this);
        bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bgBmp = null;
        try (InputStream is = getAssets().open("setup_bg.jpg")) {
            bgBmp = BitmapFactory.decodeStream(is);
        } catch (Exception ignored) {
        }
        if (bgBmp == null) {
            File localBg = new File("/storage/emulated/0/gtav/setup_bg.jpg");
            if (localBg.exists()) {
                try (InputStream is = new FileInputStream(localBg)) {
                    bgBmp = BitmapFactory.decodeStream(is);
                } catch (Exception ignored) {
                }
            }
        }
        if (bgBmp != null) {
            bgView.setImageBitmap(bgBmp);
        } else {
            bgView.setBackgroundColor(Color.rgb(20, 30, 42));
        }
        root.addView(bgView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 2. Left side scrollable control panel (~48% width to keep right-side artwork clearly visible)
        int panelWidth = (int) Math.min(screenWidth * 0.50f, 540 * density);
        ScrollView panelScroll = new ScrollView(this);
        panelScroll.setVerticalScrollBarEnabled(false);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (22 * density);
        int padV = (int) (18 * density);
        panel.setPadding(padH, padV, (int) (14 * density), padV);

        // 3 Disc Slots: Disc 1 (Install), Disc 2 (Play), Title Update 27
        for (int i = 0; i < 3; i++) {
            final int slot = i;

            // Card container with rounded corners and semi-transparent slate background
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Color.argb(215, 38, 48, 58)); // Dark slate grey translucent
            cardBg.setCornerRadius(18 * density);
            cardBg.setStroke((int) (1 * density), Color.argb(60, 255, 255, 255));
            card.setBackground(cardBg);
            int cardPadH = (int) (16 * density);
            int cardPadV = (int) (12 * density);
            card.setPadding(cardPadH, cardPadV, cardPadH, cardPadV);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = (int) (10 * density);
            card.setLayoutParams(cardLp);

            // Row with left info (Title + Filename) and right Choose button
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            // Left info container
            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            infoCol.setLayoutParams(infoLp);

            // Card Title (salmon / pinkish-white)
            TextView title = new TextView(this);
            title.setText(TITLES[i]);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTextColor(Color.rgb(255, 165, 165)); // Pinkish coral #FFA5A5
            title.setTypeface(Typeface.DEFAULT_BOLD);
            infoCol.addView(title);

            // Selected file row (icon + chosen filename). Hidden until file is picked!
            LinearLayout fileRow = new LinearLayout(this);
            fileRow.setOrientation(LinearLayout.HORIZONTAL);
            fileRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams fileRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            fileRowLp.topMargin = (int) (4 * density);
            fileRow.setLayoutParams(fileRowLp);
            fileRow.setVisibility(View.GONE); // Only shown when user picks a file!

            TextView fileName = new TextView(this);
            fileName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            fileName.setTextColor(Color.rgb(255, 225, 225));
            fileRow.addView(fileName);

            fileContainers[i] = fileRow;
            fileNames[i] = fileName;
            infoCol.addView(fileRow);
            row.addView(infoCol);

            // Right "Choose..." button (dark rose pill button)
            Button btn = new Button(this);
            btn.setText("Choose...");
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setTextColor(Color.rgb(255, 175, 175));
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setAllCaps(false);

            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(Color.argb(220, 80, 52, 60)); // Dark reddish-rose pill
            btnBg.setCornerRadius(20 * density);
            btn.setBackground(btnBg);
            btn.setPadding((int) (16 * density), (int) (6 * density), (int) (16 * density), (int) (6 * density));
            btn.setOnClickListener(v -> pick(slot));
            pickButtons[i] = btn;
            row.addView(btn);

            card.addView(row);
            panel.addView(card);
        }

        // Install Button (Wide coral/salmon red button matching the card width)
        installBtn = new Button(this);
        installBtn.setText("⬇  Install");
        installBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        installBtn.setTextColor(Color.WHITE);
        installBtn.setTypeface(Typeface.DEFAULT_BOLD);
        installBtn.setAllCaps(false);

        GradientDrawable installBg = new GradientDrawable();
        installBg.setColor(Color.rgb(226, 88, 77)); // Vibrant coral red #E2584D
        installBg.setCornerRadius(24 * density);
        installBtn.setBackground(installBg);

        LinearLayout.LayoutParams installLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int) (50 * density));
        installLp.topMargin = (int) (6 * density);
        installLp.bottomMargin = (int) (10 * density);
        installBtn.setLayoutParams(installLp);
        installBtn.setOnClickListener(v -> onInstall());
        panel.addView(installBtn);

        // Progress bar (sleek horizontal line right under button)
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int) (6 * density));
        progLp.bottomMargin = (int) (10 * density);
        progressBar.setLayoutParams(progLp);
        panel.addView(progressBar);

        // Logs Area - Always located directly below the Install button
        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams logScrollLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int) (190 * density));
        logScroll.setLayoutParams(logScrollLp);
        logScroll.setVerticalScrollBarEnabled(true);

        logText = new TextView(this);
        logText.setTextColor(Color.WHITE);
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setLineSpacing(0, 1.25f);
        // Crisp drop-shadow for high legibility over the background artwork
        logText.setShadowLayer(4.0f, 1.5f, 1.5f, Color.argb(230, 0, 0, 0));
        logText.setText("");
        logScroll.addView(logText);
        panel.addView(logScroll);

        panelScroll.addView(panel);

        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.MATCH_PARENT);
        scrollLp.gravity = Gravity.START | Gravity.TOP;
        root.addView(panelScroll, scrollLp);

        return root;
    }

    private void refresh() {
        for (int i = 0; i < 3; i++) {
            String name = picked[i] != null ? displayName(picked[i]) : null;
            if (name != null && !name.isEmpty()) {
                fileContainers[i].setVisibility(View.VISIBLE);
                fileNames[i].setText(ICONS[i] + name);
            } else {
                fileContainers[i].setVisibility(View.GONE);
            }
            pickButtons[i].setEnabled(!busy);
        }

        if (busy) {
            installBtn.setText("Cancel");
            installBtn.setEnabled(true);
        } else if (isDataInstalled()) {
            installBtn.setText("▶  Launch Game");
            installBtn.setEnabled(true);
        } else {
            installBtn.setText("⬇  Install");
            installBtn.setEnabled(true);
        }
    }

    private String displayName(Uri uri) {
        if (uri == null) return null;
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private void pick(int slot) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, slot);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || request < 0 || request > 2) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Log.w(TAG, "persist " + uri + ": " + e);
        }
        picked[request] = uri;
        prefs.edit().putString("disc_uri_" + request, uri.toString()).apply();
        refresh();
        appendLog("Selected " + TITLES[request] + ": " + displayName(uri));
    }

    // --- Installation & Pack Extraction ---------------------------------------------------------

    private void autoExtractPack() {
        busy = true;
        refresh();
        appendLog("Game pack detected in APK assets.");
        appendLog("Extracting bundled game pack...");
        new Thread(() -> {
            String err = importBundledPack();
            runOnUiThread(() -> {
                busy = false;
                refresh();
                if (err == null) {
                    appendLog("Game pack successfully installed.");
                    if (isDataInstalled()) {
                        appendLog("Game files verified. Ready to launch!");
                        startGame();
                    } else {
                        appendLog("Choose game discs to finish installation.");
                    }
                } else {
                    appendLog("Pack error: " + err);
                    message("Pack Error", err);
                }
            });
        }, "gtav-pack-auto-extract").start();
    }

    private void onInstall() {
        if (busy) {
            cancelled.set(true);
            appendLog("Cancelling installation...");
            return;
        }

        // If game data is already installed, launch immediately
        if (isDataInstalled() && packCurrent() == null) {
            appendLog("Launching GTA V...");
            startGame();
            return;
        }

        cancelled.set(false);
        busy = true;
        refresh();
        appendLog("Check file integrity...");

        new Thread(() -> {
            String error = runInstall();
            runOnUiThread(() -> {
                busy = false;
                refresh();
                if (error == null) {
                    appendLog("Installation complete! Launching GTA V...");
                    startGame();
                } else {
                    if (cancelled.get()) {
                        appendLog("Installation cancelled.");
                    } else {
                        appendLog("Installation stopped: " + error);
                        message("Setup Error", error);
                    }
                }
            });
        }, "gtav-setup").start();
    }

    private String runInstall() {
        try {
            if (packCurrent() != null) {
                appendLog("Checking game pack...");
                if (hasBundledPack() || findLocalPack() != null) {
                    String e = importBundledPack();
                    if (e != null) return e;
                } else {
                    return packCurrent();
                }
                String now = packCurrent();
                if (now != null) return now;
            }

            if (isDataInstalled()) {
                appendLog("Game data already installed.");
                return null;
            }

            for (int i = 0; i < 3; i++) {
                if (picked[i] == null) {
                    return "Please choose " + TITLES[i] + ".";
                }
            }

            appendLog("Verifying Disc 1 image...");
            appendLog("Extracting packages...");
            ParcelFileDescriptor[] fds = new ParcelFileDescriptor[3];
            try {
                String[] paths = new String[3];
                for (int i = 0; i < 3; i++) {
                    fds[i] = getContentResolver().openFileDescriptor(picked[i], "r");
                    if (fds[i] == null) return "Could not open " + displayName(picked[i]) + ".";
                    paths[i] = "/proc/self/fd/" + fds[i].getFd();
                }

                File expectedFile = new File(packDir(), "expected.txt");
                if (!expectedFile.exists()) {
                    return "Missing expected.txt from game pack.";
                }

                appendLog("Beginning Disc 1 extraction...");
                appendLog("Checking destination directories...");
                appendLog("Copying base game archives...");
                return nativeInstall(home().getAbsolutePath(), expectedFile.getAbsolutePath(),
                                     paths[0], paths[1], paths[2]);
            } finally {
                for (ParcelFileDescriptor f : fds) {
                    if (f != null) try { f.close(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "install", e);
            return e.toString();
        }
    }

    private String importBundledPack() {
        if (hasBundledPack()) {
            appendLog("Reading bundled game pack from APK assets...");
            try (InputStream raw = getAssets().open("gtav-android-pack.zip")) {
                return importPackStream(raw);
            } catch (Exception e) {
                return "Could not read bundled pack from APK: " + e.getMessage();
            }
        }
        File local = findLocalPack();
        if (local != null) {
            appendLog("Opening local game pack from " + local.getName() + "...");
            try (InputStream raw = new FileInputStream(local)) {
                return importPackStream(raw);
            } catch (Exception e) {
                return "Could not read local pack: " + e.getMessage();
            }
        }
        return "No bundled or local game pack found.";
    }

    private String importPackStream(InputStream raw) {
        appendLog("Unpacking game pack...");
        File stage = new File(getFilesDir(), "pack.new");
        deleteTree(stage);
        stage.mkdirs();
        File spirvDir = new File(home(), "spirv");
        spirvDir.mkdirs();
        boolean haveLib = false;
        int shaders = 0;
        byte[] buf = new byte[1 << 20];
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (cancelled.get()) return "Cancelled.";
                String name = e.getName();
                if (e.isDirectory() || name.endsWith("/") || name.contains("..") || name.startsWith("/")) continue;
                File out;
                if (name.equals("libgtav_game.so")) {
                    out = new File(stage, name);
                    haveLib = true;
                } else if (name.equals("expected.txt") || name.equals("pack.txt")) {
                    out = new File(stage, name);
                } else if (name.startsWith("spirv/")) {
                    String sub = name.substring(6);
                    if (sub.isEmpty() || sub.endsWith("/")) continue;
                    out = new File(spirvDir, sub);
                    shaders++;
                    if (shaders % 1000 == 0) {
                        appendLog("Extracted " + shaders + " shaders...");
                    }
                } else {
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buf)) > 0;) o.write(buf, 0, n);
                }
            }
        } catch (Exception ex) {
            return "Could not read the game pack: " + ex;
        }

        File nativeLib = new File(getApplicationInfo().nativeLibraryDir, "libgtav_game.so");
        if (nativeLib.exists() && nativeLib.length() > 10000000) {
            haveLib = true;
        }

        if (!haveLib || !new File(stage, "pack.txt").exists() || !new File(stage, "expected.txt").exists())
            return "This is not a valid game pack (missing libgtav_game.so, pack.txt or expected.txt).";

        File staged = new File(stage, "libgtav_game.so");
        if (staged.exists()) {
            File lib = new File(getFilesDir(), "libgtav_game.so");
            lib.delete();
            if (!staged.renameTo(lib)) {
                try {
                    copyFile(staged, lib);
                    staged.delete();
                } catch (Exception e) {
                    return "Could not put the game library in place: " + e;
                }
            }
            lib.setReadable(true, false);
            lib.setExecutable(true, false);
            lib.setReadOnly();
        }
        deleteTree(packDir());
        if (!stage.renameTo(packDir())) {
            try {
                copyTree(stage, packDir());
                deleteTree(stage);
            } catch (Exception e) {
                return "Could not put the game pack in place: " + e;
            }
        }
        appendLog("Game pack installed (" + shaders + " shaders ready).");
        return null;
    }

    private static void copyFile(File src, File dst) throws Exception {
        dst.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 18];
            for (int n; (n = in.read(buf)) > 0;) out.write(buf, 0, n);
        }
    }

    private static void copyTree(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] files = src.listFiles();
            if (files != null) {
                for (File f : files) copyTree(f, new File(dst, f.getName()));
            }
        } else {
            copyFile(src, dst);
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void startGame() {
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }

    private void message(String title, String text) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show();
    }

    // --- Logging & Callbacks from Native Installer ----------------------------------------------

    private void appendLog(String message) {
        long elapsedSec = (SystemClock.elapsedRealtime() - startTimeMs) / 1000;
        long mm = elapsedSec / 60;
        long ss = elapsedSec % 60;
        String line = String.format("[%02d:%02d] %s", mm, ss, message);

        runOnUiThread(() -> {
            logHistory.append(line).append("\n");
            String all = logHistory.toString();
            String[] lines = all.split("\n");
            int from = Math.max(0, lines.length - 14);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                if (i > from) sb.append("\n");
                sb.append(lines[i]);
            }
            if (logText != null) {
                logText.setText(sb.toString());
            }
            if (logScroll != null) {
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    void onStage(String s) {
        appendLog(s);
    }

    void onLog(String s) {
        appendLog(s);
    }

    void onProgress(double f) {
        runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setProgress((int) (f * 1000));
            }
        });
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    boolean onAsk(String question) {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] yes = {false};
        runOnUiThread(() -> new AlertDialog.Builder(this).setMessage(question).setCancelable(false)
            .setPositiveButton("Yes", (d, w) -> { yes[0] = true; done.countDown(); })
            .setNegativeButton("No", (d, w) -> done.countDown()).show());
        try {
            done.await();
        } catch (InterruptedException e) {
            return false;
        }
        return yes[0];
    }
}
