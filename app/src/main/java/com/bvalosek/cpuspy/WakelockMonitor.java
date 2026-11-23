package com.bvalosek.cpuspy;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads wakelock data from:
 *   1. Root (su): dumpsys power + /sys/class/wakeup/ or /sys/kernel/debug/wakeup_sources
 *   2. Non-root fallback: direct dumpsys power (Shizuku/ADB) or direct /sys/class/wakeup/
 */
public class WakelockMonitor {

    private static final String TAG = "WakelockMonitor";

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

    /**
     * Check if root binaries exist on the device (fast check)
     */
    public static boolean isRootAvailable() {
        String[] paths = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
            "/data/adb/ap/bin/su"
        };
        for (String p : paths) {
            try {
                if (new File(p).exists()) return true;
            } catch (Exception ignored) {}
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            int exit = p.waitFor();
            if (exit == 0) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Update entries from all available sources.
     * Call from a background thread (e.g. AsyncTask).
     */
    public void updateEntries() {
        Map<String, WakelockEntry> entryMap = new HashMap<>();

        // 1. Try Root session (KernelSU / Magisk / APatch)
        boolean rootSuccess = readViaRoot(entryMap);

        // 2. Fallback if root didn't yield results (Shizuku, ADB shell, or readable sysfs)
        if (!rootSuccess || entryMap.isEmpty()) {
            readDirectDumpsys(entryMap);
            readDirectSysfs(entryMap);
        }

        List<WakelockEntry> result = new ArrayList<>(entryMap.values());

        // Sort: active first, then by totalTimeMs desc, then by hitCount desc
        Collections.sort(result, new Comparator<WakelockEntry>() {
            @Override
            public int compare(WakelockEntry a, WakelockEntry b) {
                if (a.isActive != b.isActive) return a.isActive ? -1 : 1;
                if (a.totalTimeMs != b.totalTimeMs) return Long.compare(b.totalTimeMs, a.totalTimeMs);
                return Long.compare(b.hitCount, a.hitCount);
            }
        });

        _entries = result;
    }

    // -------------------------------------------------------------------------
    // Method 1: Interactive Root Shell (works with KernelSU, Magisk, APatch)
    // Runs dumpsys power + reads kernel wakeup sources in ONE su session.
    // -------------------------------------------------------------------------
    private boolean readViaRoot(Map<String, WakelockEntry> map) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
            writer.write("echo '===DUMPSYS_POWER==='\n");
            writer.write("dumpsys power 2>/dev/null\n");
            writer.write("echo '===WAKEUP_SOURCES==='\n");
            writer.write("if [ -f /sys/kernel/debug/wakeup_sources ]; then cat /sys/kernel/debug/wakeup_sources; ");
            writer.write("elif [ -d /sys/class/wakeup ]; then for d in /sys/class/wakeup/wakeup*; do ");
            writer.write("if [ -d \"$d\" ]; then echo \"$(cat $d/name 2>/dev/null) $(cat $d/active_count 2>/dev/null) 0 0 0 $(cat $d/active_time_ms 2>/dev/null) $(cat $d/total_time_ms 2>/dev/null)\"; fi; done; fi\n");
            writer.write("echo '===END==='\n");
            writer.write("exit\n");
            writer.flush();
            writer.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int section = 0; // 1 = dumpsys, 2 = wakeup_sources
            boolean inWakeLocks = false;

            while ((line = reader.readLine()) != null) {
                if (line.contains("===DUMPSYS_POWER===")) {
                    section = 1;
                    continue;
                } else if (line.contains("===WAKEUP_SOURCES===")) {
                    section = 2;
                    continue;
                } else if (line.contains("===END===")) {
                    break;
                }

                if (section == 1) {
                    if (line.contains("Wake Locks:")) {
                        inWakeLocks = true;
                        continue;
                    }
                    if (inWakeLocks) {
                        if (line.trim().isEmpty() || line.startsWith("  Suspend Blockers")) {
                            inWakeLocks = false;
                        } else {
                            parseDumpsysLine(line, map);
                        }
                    }
                } else if (section == 2) {
                    parseWakeupSourceLine(line, map);
                }
            }

            p.waitFor();
            reader.close();
            return !map.isEmpty();
        } catch (Exception e) {
            Log.d(TAG, "Root execution failed: " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Method 2: Direct dumpsys power (for Shizuku / ADB shell)
    // -------------------------------------------------------------------------
    private void readDirectDumpsys(Map<String, WakelockEntry> map) {
        try {
            ProcessBuilder pb = new ProcessBuilder("dumpsys", "power");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            boolean inWakeLocks = false;
            while ((line = br.readLine()) != null) {
                if (line.contains("Wake Locks:")) {
                    inWakeLocks = true;
                    continue;
                }
                if (inWakeLocks) {
                    if (line.trim().isEmpty() || line.startsWith("  Suspend Blockers")) break;
                    parseDumpsysLine(line, map);
                }
            }
            p.waitFor();
            br.close();
        } catch (Exception e) {
            Log.d(TAG, "direct dumpsys failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Method 3: Direct sysfs read (/sys/class/wakeup/)
    // -------------------------------------------------------------------------
    private void readDirectSysfs(Map<String, WakelockEntry> map) {
        try {
            File dir = new File("/sys/class/wakeup");
            if (dir.exists() && dir.isDirectory()) {
                File[] wakeups = dir.listFiles();
                if (wakeups != null) {
                    for (File w : wakeups) {
                        if (!w.isDirectory()) continue;
                        String name = readFileFirstLine(new File(w, "name"));
                        if (name == null || name.isEmpty()) continue;
                        long activeCount = safeLong(readFileFirstLine(new File(w, "active_count")));
                        long activeTime  = safeLong(readFileFirstLine(new File(w, "active_time_ms")));
                        long totalTime   = safeLong(readFileFirstLine(new File(w, "total_time_ms")));

                        if (totalTime == 0 && activeTime == 0 && activeCount == 0) continue;

                        putOrMerge(map, name, activeTime > 0, totalTime, activeTime, activeCount, extractPackage(name));
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "direct sysfs read failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Parsing Helpers
    // -------------------------------------------------------------------------
    private void parseDumpsysLine(String line, Map<String, WakelockEntry> map) {
        try {
            line = line.trim();
            if (line.isEmpty()) return;

            String name = null;
            int q1 = line.indexOf('\'');
            int q2 = line.lastIndexOf('\'');
            if (q1 >= 0 && q2 > q1) {
                name = line.substring(q1 + 1, q2);
            } else {
                return;
            }

            String pkg = null;
            int pkgIdx = line.indexOf("packageName=");
            if (pkgIdx >= 0) {
                String rest = line.substring(pkgIdx + "packageName=".length());
                int space = rest.indexOf(' ');
                pkg = space > 0 ? rest.substring(0, space) : rest.trim();
            } else if (name.startsWith("*alarm*:")) {
                // e.g. *alarm*:com.google.android.gms/.chimera.GmsIntentOperationService
                String sub = name.substring("*alarm*:".length());
                int slash = sub.indexOf('/');
                pkg = slash > 0 ? sub.substring(0, slash) : sub;
            } else {
                pkg = extractPackage(name);
            }

            putOrMerge(map, name, true, 0, 0, 1, pkg);
        } catch (Exception ignored) {}
    }

    private void parseWakeupSourceLine(String line, Map<String, WakelockEntry> map) {
        try {
            String[] f = line.trim().split("\\s+");
            if (f.length < 7) return;

            String name       = f[0];
            if ("name".equalsIgnoreCase(name)) return; // skip header line

            long hitCount     = safeLong(f[1]);
            long activeSinceMs = safeLong(f[5]);
            long totalTimeMs  = safeLong(f[6]);

            // Skip entries that were never active
            if (hitCount == 0 && totalTimeMs == 0 && activeSinceMs == 0) return;

            boolean isActive = activeSinceMs > 0;
            String pkg = extractPackage(name);

            putOrMerge(map, name, isActive, totalTimeMs, activeSinceMs, hitCount, pkg);
        } catch (Exception ignored) {}
    }

    private void putOrMerge(Map<String, WakelockEntry> map, String name, boolean isActive,
                            long totalTimeMs, long activeSinceMs, long hitCount, String pkg) {
        WakelockEntry existing = map.get(name);
        if (existing == null) {
            WakelockEntry e = new WakelockEntry();
            e.name          = name;
            e.isActive      = isActive;
            e.totalTimeMs   = totalTimeMs;
            e.activeSinceMs = activeSinceMs;
            e.hitCount      = hitCount;
            e.packageName   = pkg;
            map.put(name, e);
        } else {
            if (isActive) existing.isActive = true;
            if (totalTimeMs > existing.totalTimeMs) existing.totalTimeMs = totalTimeMs;
            if (activeSinceMs > existing.activeSinceMs) existing.activeSinceMs = activeSinceMs;
            if (hitCount > existing.hitCount) existing.hitCount = hitCount;
            if (existing.packageName == null && pkg != null) existing.packageName = pkg;
        }
    }

    private static String extractPackage(String name) {
        if (name == null) return null;
        int colon = name.indexOf(':');
        String candidate = colon > 0 ? name.substring(0, colon) : name;
        if (candidate.contains(".") && candidate.length() > 4
                && Character.isLowerCase(candidate.charAt(0))) {
            return candidate;
        }
        return null;
    }

    private static String readFileFirstLine(File file) {
        if (!file.exists() || !file.canRead()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static long safeLong(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }
}
