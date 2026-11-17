package com.gtavrecomp.app;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintStream;
import java.util.ArrayList;

import org.libsdl.app.SDLActivity;

/**
 * SDL activity that runs the recompiled game (libmain.so, SDL_main). Game data lives in the app's
 * external files directory (adb-writable without storage permissions):
 *   files/game/disc2_files, files/game/update, files/userdata; the log goes to files/logs/gtav.log.
 * files/args.txt adds runtime flags, one per line (lines starting with # are ignored); a line
 * env:NAME=VALUE sets an environment variable for the game instead.
 */
public class GameActivity extends SDLActivity {
    private static final String TAG = "GtavRecomp";

    private static void writeCrashLog(String msg, Throwable t) {
        String[] paths = {
            "/storage/emulated/0/gtav/crash.log",
            "/sdcard/gtav_crash.log"
        };
        for (String p : paths) {
            try {
                File f = new File(p);
                f.getParentFile().mkdirs();
                try (PrintStream ps = new PrintStream(new FileOutputStream(f, true))) {
                    ps.println("=== CRASH LOG ===");
                    if (msg != null) ps.println(msg);
                    if (t != null) t.printStackTrace(ps);
                    ps.println();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception in " + thread.getName(), throwable);
            writeCrashLog("Uncaught exception in thread " + thread.getName(), throwable);
        });

        // SDL picks the orientation from the window it creates (the runtime's is resizable, so
        // portrait was allowed): the game is landscape only.
        try {
            android.system.Os.setenv("SDL_ORIENTATIONS", "LandscapeLeft LandscapeRight", true);
            // No accelerometer-as-joystick and no HIDAPI: both keep SDL's event loop polling (a
            // whole big core spent in joystick detection); pads come through Android's input events.
            android.system.Os.setenv("SDL_ACCELEROMETER_AS_JOYSTICK", "0", true);
            android.system.Os.setenv("SDL_JOYSTICK_HIDAPI", "0", true);
            // On Mali and mobile GPUs, fetch resolves in-pass can corrupt/hang rendering: force off by default
            android.system.Os.setenv("GTAV_NATIVE_FETCH_RESOLVE", "0", true);
        } catch (Exception e) {
            Log.w(TAG, "setenv: " + e);
        }
        // args.txt env: lines before super.onCreate loads libmain.so: its static initializers read
        // some variables (GTAV_NATIVE_FETCH_RESOLVE), and getArguments runs only after the load.
        // First read bundled args.txt from APK assets:
        try (java.io.InputStream is = getAssets().open("args.txt");
             BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("env:") && line.indexOf('=') > 4) {
                    int eq = line.indexOf('=');
                    android.system.Os.setenv(line.substring(4, eq), line.substring(eq + 1), true);
                }
            }
        } catch (Exception ignored) {
        }
        File[] candidateArgs = {
            new File(getExternalFilesDir(null), "args.txt"),
            new File("/storage/emulated/0/gtav/args.txt"),
            new File("/sdcard/gtav/args.txt")
        };
        for (File extra : candidateArgs) {
            if (extra.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(extra))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("env:") && line.indexOf('=') > 4) {
                            int eq = line.indexOf('=');
                            android.system.Os.setenv(line.substring(4, eq), line.substring(eq + 1), true);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "args.txt env (" + extra + "): " + e);
                }
            }
        }
        if (isReleaseLayout(this) &&
            !new File(getFilesDir(), "libgtav_game.so").exists() &&
            !new File(getApplicationInfo().nativeLibraryDir, "libgtav_game.so").exists()) {
            Log.w(TAG, "libgtav_game.so missing, redirecting to SetupActivity");
            startActivity(new android.content.Intent(this, SetupActivity.class));
            finish();
            return;
        }

        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected String[] getLibraries() {
        // SDL3 is linked statically into librexruntime.so; loading libmain.so pulls in its deps.
        return new String[] { "main" };
    }

    /**
     * The public APK: no game code (libmain.so is a shim; libgtav_fast.so holds ours and the
     * player's libgtav_game.so comes from their PC's Build for Android, see SetupActivity).
     */
    static boolean isReleaseLayout(android.content.Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libgtav_fast.so").exists();
    }

    /** The release defaults (tools/android/args.txt): files/args.txt lines come after and win. */
    private static final String[] RELEASE_ARGS = {
        "--log_level=info", "--gtav_native=on",
        "--clock_no_scaling=true", "--gtav_fps_limit=0", "--vsync=false",
        "--gtav_native_vsync=false", "--gtav_native_fps=true",
        "--gpu_skip_rendering=true", "--gpu_skip_rendering_lag_ms=4",
        "--vulkan_log_debug_messages=false",
        "--window_presenter=false", "--gtav_native_texture_budget_mb=1536",
        "--gtav_native_rect_gs=false", "--gtav_native_fetch_resolves=false",
        "--vulkan_require_vertex_pipeline_stores_and_atomics=false",
        "--vulkan_require_geometry_shader=false",
        "--vulkan_require_fill_mode_non_solid=false",
        "--vulkan_sparse_shared_memory=false",
        "--gtav_native_point_float32=true",
        "--gtav_native_draw_clears=true",
        "--gtav_native_hold_lost_frames=false",
        "--gtav_native_async_pipelines=true",
        "--gtav_native_pipeline_wait_ms=0.0",
        "--gtav_native_render_scale=1.0",
        "--gtav_native_max_anisotropy=2",
        "--gtav_native_record_thread=true",
        "--gtav_native_convert_threads=4",
        "--gtav_native_texture_recheck_frames=20",
        "--gtav_native_texture_sample_frames=4",
        "--gtav_native_exact_barriers=false",
        "--gtav_native_load_clears=false",
        "--gtav_native_defer_clears=true",
        "--gtav_native_scissor=true",
        "--gtav_native_resolve_discard=true",
        "--gtav_native_fence_wait=true",
        "--gtav_native_fence_copy=true",
        "--gtav_native_fence_lag=2",
        "--gtav_native_decode_bc=true",
        "--gtav_android_main_prime=true",
        "--xma_flush_streams=true",
        "--gtav_audio_unsync=true",
        "--audio_maxqframes=32", "--audio_device_frames=512", "--audio_force_stereo=true",
        "--vulkan_allow_present_mode_immediate=true",
        "--vulkan_allow_present_mode_mailbox=true",
        "--vulkan_allow_present_mode_fifo_relaxed=true",
        "--gtav_touch_controls=true",
        "--gtav_touch_scale=1.1",
        "--gtav_touch_inset=0.0",
        "--gtav_native_save_missing_shaders=true",
        "--gtav_native_compile=false",
    };

    @Override
    protected String[] getArguments() {
        File files = getExternalFilesDir(null);
        String root = files.getAbsolutePath();
        new File(files, "logs").mkdirs();
        ArrayList<String> args = new ArrayList<>();

        File dataRoot = new File(files, "data");
        if (!new File(dataRoot, "data_installed.txt").exists()) {
            File[] dataCandidates = {
                new File("/storage/emulated/0/gtav/data"),
                new File("/storage/emulated/0/gtav/data_backup/data"),
                new File("/sdcard/gtav/data"),
                new File("/sdcard/gtav/data_backup/data")
            };
            for (File d : dataCandidates) {
                if (new File(d, "data_installed.txt").exists()) {
                    dataRoot = d;
                    break;
                }
            }
        }

        if (new File(dataRoot, "data_installed.txt").exists()) {
            // Installed by the setup screen (install.cpp RunDataInstall layout).
            File userData = new File(dataRoot, "user");
            if (!userData.exists() || !new File(userData, "0000000000000000").exists()) {
                File[] userCandidates = {
                    new File("/storage/emulated/0/gtav/data/user"),
                    new File("/storage/emulated/0/gtav/data_backup/data/user"),
                    new File("/sdcard/gtav/data/user"),
                    new File(files, "data/user")
                };
                for (File u : userCandidates) {
                    if (u.exists() && (new File(u, "0000000000000000").exists() || new File(u, "545408A7").exists())) {
                        userData = u;
                        break;
                    }
                }
            }
            userData.mkdirs();
            args.add("--game_data_root=" + dataRoot.getAbsolutePath() + "/game");
            args.add("--update_data_root=" + dataRoot.getAbsolutePath() + "/update");
            args.add("--user_data_root=" + userData.getAbsolutePath());
        } else {
            // Developer layout (pushed with adb).
            new File(files, "userdata").mkdirs();
            args.add("--game_data_root=" + root + "/game/disc2_files");
            args.add("--update_data_root=" + root + "/game/update");
            args.add("--user_data_root=" + root + "/userdata");
        }
        for (String a : RELEASE_ARGS) args.add(a);

        // Bundled args.txt inside APK assets:
        try (java.io.InputStream is = getAssets().open("args.txt");
             BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("env:")) {
                    args.add(line);
                }
            }
        } catch (Exception ignored) {
        }
        args.add("--gpu_plugin=xenos");

        // Prefer writing log directly to /storage/emulated/0/gtav/logs/gtav.log if available
        File gtavDir = new File("/storage/emulated/0/gtav");
        String logFile = root + "/logs/gtav.log";
        if (gtavDir.exists()) {
            File logDir = new File(gtavDir, "logs");
            logDir.mkdirs();
            if (logDir.canWrite()) {
                logFile = new File(logDir, "gtav.log").getAbsolutePath();
            }
            File spirvDir = new File(gtavDir, "spirv");
            if (spirvDir.exists()) {
                args.add("--gtav_native_spirv_dir=" + spirvDir.getAbsolutePath());
            }
            File dumpDir = new File(gtavDir, "shader_dump");
            if (dumpDir.exists()) {
                args.add("--gtav_native_shader_dump=" + dumpDir.getAbsolutePath());
            }
        }
        // If not added from /storage/emulated/0/gtav/spirv, check app files spirv directory
        File internalSpirv = new File(files, "spirv");
        if (!args.toString().contains("--gtav_native_spirv_dir=") && internalSpirv.exists()) {
            args.add("--gtav_native_spirv_dir=" + internalSpirv.getAbsolutePath());
        }
        args.add("--log_file=" + logFile);
        args.add("--log_max_file_size_mb=256");
        args.add("--log_max_files=2");

        File extra = new File(files, "args.txt");
        File externalArgs = new File(gtavDir, "args.txt");
        if (externalArgs.exists()) {
            extra = externalArgs;
        }
        if (extra.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(extra))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("env:")) {
                        args.add(line);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "args.txt: " + e);
            }
        }
        // One value per flag, the last one winning (files/args.txt over the defaults): the
        // command-line parser (CLI11) rejects a repeated option and then applies none of them.
        java.util.LinkedHashMap<String, String> byName = new java.util.LinkedHashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            String name = eq < 0 ? a : a.substring(0, eq);
            if (name.startsWith("--no-")) name = "--" + name.substring(5);
            byName.remove(name);
            byName.put(name, a);
        }
        args = new ArrayList<>(byName.values());
        String fullCmd = String.join(" ", args);
        Log.i(TAG, "arguments: " + fullCmd);

        // Record launch arguments
        try {
            File launchLog = new File("/storage/emulated/0/gtav/launch.log");
            launchLog.getParentFile().mkdirs();
            try (PrintStream ps = new PrintStream(new FileOutputStream(launchLog, false))) {
                ps.println("=== Launch Arguments ===");
                ps.println(fullCmd);
            }
        } catch (Exception ignored) {
        }

        return args.toArray(new String[0]);
    }
}
