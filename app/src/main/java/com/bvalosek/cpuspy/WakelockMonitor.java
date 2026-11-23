package com.bvalosek.cpuspy;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads wakelock data from the kernel:
 *   1. Root: /sys/kernel/debug/wakeup_sources  (full table)
 *   2. Fallback: dumpsys power via shell        (partial, no root needed)
 */
public class WakelockMonitor {

    private static final String TAG = "WakelockMonitor";
    private static final String WAKEUP_SOURCES = "/sys/kernel/debug/wakeup_sources";

    public static class WakelockEntry {
        public String  name;
        public String  packageName;   // null if kernel/system
        public long    totalTimeMs;   // total time held (ms)
        public long    activeSinceMs; // currently active since (ms), 0 = inactive
        public long    hitCount;      // how many times acquired
        public boolean isActive;

        /** Label suitable for display (strips package prefix) */
        public String displayName() {
            if (name == null) return "";
            int colon = name.indexOf(':');
            if (colon > 0 && packageName != null) {
                return name.substring(colon + 1);
            }
            return name;
        }
    }

    private List<WakelockEntry> _entries = new ArrayList<>();

    public List<WakelockEntry> getEntries() {
        return _entries;
    }

    /** Try root → fallback dumpsys. Call from background thread. */
    public void updateEntries() {
        List<WakelockEntry> result = readWakeupSources();
        if (result == null || result.isEmpty()) {
            result = readDumpsysPower();
        }
        if (result == null) result = new ArrayList<>();

        // Sort: active first, then by totalTimeMs desc
        Collections.sort(result, new Comparator<WakelockEntry>() {
            @Override
            public int compare(WakelockEntry a, WakelockEntry b) {
                if (a.isActive != b.isActive) return a.isActive ? -1 : 1;
                return Long.compare(b.totalTimeMs, a.totalTimeMs);
            }
        });

        _entries = result;
    }

    // -------------------------------------------------------------------------
    // Source 1: /sys/kernel/debug/wakeup_sources  (requires root / debugfs)
    // Format: name  active_count  event_count  wakeup_count  expire_count
    //         active_since(ms)  total_time(ms)  max_time(ms)  last_change  prevent_suspend_time(ms)
    // -------------------------------------------------------------------------
    private List<WakelockEntry> readWakeupSources() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cat " + WAKEUP_SOURCES});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<WakelockEntry> list = new ArrayList<>();
            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; } // header
                WakelockEntry e = parseWakeupSourceLine(line);
                if (e != null) list.add(e);
            }
            int exit = p.waitFor();
            br.close();
            if (exit == 0 && !list.isEmpty()) return list;
        } catch (Exception e) {
            Log.d(TAG, "wakeup_sources via su failed: " + e.getMessage());
        }

        // Try without su (some devices allow read by shell)
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"cat", WAKEUP_SOURCES});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<WakelockEntry> list = new ArrayList<>();
            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                WakelockEntry e = parseWakeupSourceLine(line);
                if (e != null) list.add(e);
            }
            br.close();
            if (!list.isEmpty()) return list;
        } catch (Exception e) {
            Log.d(TAG, "wakeup_sources direct failed: " + e.getMessage());
        }

        return null;
    }

    private WakelockEntry parseWakeupSourceLine(String line) {
        try {
            // Fields are tab-separated
            String[] f = line.trim().split("\\s+");
            if (f.length < 7) return null;
            WakelockEntry e = new WakelockEntry();
            e.name         = f[0];
            e.hitCount     = safeLong(f[1]);
            e.activeSinceMs = safeLong(f[5]);
            e.totalTimeMs  = safeLong(f[6]);
            e.isActive     = e.activeSinceMs > 0;
            e.packageName  = extractPackage(e.name);
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Source 2: dumpsys power — works via shell (Shizuku/Porter/ADB) or even
    // Runtime.exec on some devices
    // -------------------------------------------------------------------------
    private List<WakelockEntry> readDumpsysPower() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "power"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<WakelockEntry> list = new ArrayList<>();
            String line;
            boolean inWakeLocks = false;
            while ((line = br.readLine()) != null) {
                if (line.contains("Wake Locks:")) { inWakeLocks = true; continue; }
                if (inWakeLocks) {
                    if (line.trim().isEmpty() || line.startsWith("  Suspend Blockers")) break;
                    WakelockEntry e = parseDumpsysWakeLockLine(line);
                    if (e != null) list.add(e);
                }
            }
            br.close();
            return list;
        } catch (Exception e) {
            Log.d(TAG, "dumpsys power failed: " + e.getMessage());
        }
        return null;
    }

    private WakelockEntry parseDumpsysWakeLockLine(String line) {
        // Example: "    PARTIAL_WAKE_LOCK              'GCM_HB_ALARM' packageName=com.google.android.gms"
        try {
            line = line.trim();
            if (line.isEmpty()) return null;
            WakelockEntry e = new WakelockEntry();
            // Extract quoted name
            int q1 = line.indexOf('\'');
            int q2 = line.lastIndexOf('\'');
            if (q1 >= 0 && q2 > q1) {
                e.name = line.substring(q1 + 1, q2);
            } else {
                e.name = line;
            }
            // Extract packageName= if present
            int pkgIdx = line.indexOf("packageName=");
            if (pkgIdx >= 0) {
                String rest = line.substring(pkgIdx + "packageName=".length());
                int space = rest.indexOf(' ');
                e.packageName = space > 0 ? rest.substring(0, space) : rest.trim();
            } else {
                e.packageName = extractPackage(e.name);
            }
            e.isActive    = true;
            e.totalTimeMs = 0; // dumpsys doesn't give historical total
            e.hitCount    = 0;
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Extract package name from wakelock name like "com.package.name:WakelockTag" */
    private static String extractPackage(String name) {
        if (name == null) return null;
        int colon = name.indexOf(':');
        String candidate = colon > 0 ? name.substring(0, colon) : name;
        // Heuristic: a valid package contains at least one dot and starts with lowercase letter
        if (candidate.contains(".") && candidate.length() > 4
                && Character.isLowerCase(candidate.charAt(0))) {
            return candidate;
        }
        return null;
    }

    private static long safeLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }
}
