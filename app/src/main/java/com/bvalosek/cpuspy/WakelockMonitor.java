package com.bvalosek.cpuspy;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bvalosek.cpuspy.porter.PorterClient;
import rikka.shizuku.Shizuku;

/**
 * Reads wakelock data using elevated execution:
 *   1. Root (su): dumpsys batterystats --checkin + dumpsys power
 *   2. Porter: executes shell commands via elevated Porter service
 *   3. Shizuku: executes shell commands via Shizuku binder (uid 2000 has android.permission.DUMP)
 *   4. Fallback: /sys/class/wakeup/ (only if elevated methods yielded no data)
 */
public class WakelockMonitor {

    private static final String TAG = "WakelockMonitor";

    // Native system UIDs that don't have an APK in PackageManager
    // UID -> [packageName, appNameEn, appNameRu]
    private static final Map<Integer, String[]> SYSTEM_UID_MAP = new HashMap<>();
    static {
        SYSTEM_UID_MAP.put(0,    new String[]{"system.root", "Root Services", "Службы Root"});
        SYSTEM_UID_MAP.put(1000, new String[]{"android", "Android System", "Система Android"});
        SYSTEM_UID_MAP.put(1001, new String[]{"com.android.phone", "Phone / Telephony", "Телефон / Модем"});
        SYSTEM_UID_MAP.put(1002, new String[]{"com.android.bluetooth", "Bluetooth", "Bluetooth"});
        SYSTEM_UID_MAP.put(1010, new String[]{"system.wifi", "Wi-Fi Service", "Служба Wi-Fi"});
        SYSTEM_UID_MAP.put(1013, new String[]{"android.media", "Media Server", "Медиасервер"});
        SYSTEM_UID_MAP.put(1014, new String[]{"system.dhcp", "DHCP Client", "Служба DHCP"});
        SYSTEM_UID_MAP.put(1021, new String[]{"system.gps", "GPS / Location", "Служба GPS / Геолокация"});
        SYSTEM_UID_MAP.put(1027, new String[]{"com.android.nfc", "NFC Service", "Служба NFC"});
        SYSTEM_UID_MAP.put(1036, new String[]{"system.logd", "Log Daemon (logd)", "Служба логирования (logd)"});
        SYSTEM_UID_MAP.put(1040, new String[]{"system.cameraserver", "Camera Server (cameraserver)", "Служба камеры (cameraserver)"});
        SYSTEM_UID_MAP.put(1041, new String[]{"system.audioserver", "Audio System (audioserver)", "Аудиосистема (audioserver)"});
        SYSTEM_UID_MAP.put(1046, new String[]{"system.mediacodec", "Media Codecs (mediacodec)", "Медиакодеки (mediacodec)"});
        SYSTEM_UID_MAP.put(1047, new String[]{"system.mediaextractor", "Media Extractor", "Медиаэкстрактор"});
        SYSTEM_UID_MAP.put(1068, new String[]{"system.netd", "Network Daemon (netd)", "Сетевой демон (netd)"});
        SYSTEM_UID_MAP.put(1073, new String[]{"system.statsd", "Stats Daemon (statsd)", "Служба метрик (statsd)"});
        SYSTEM_UID_MAP.put(2000, new String[]{"com.android.shell", "Shell / ADB", "Консоль (Shell / ADB)"});
    }

    public static class WakelockEntry {
        public String  name;          // tag or driver name
        public String  packageName;   // e.g. "com.google.android.gms", "android", null for kernel
        public String  appName;       // human-readable label ("Google Play services", "Settings")
        public long    totalTimeMs;   // total running time (ms)
        public long    activeSinceMs; // if currently active (ms)
        public long    hitCount;      // wake count (e.g. 164)
        public boolean isActive;
        public boolean isApp = true;  // true = user/system app, false = kernel/hardware driver

        /** Display name suitable for UI */
        public String displayName() {
            String res;
            if (appName != null && !appName.isEmpty()) {
                res = appName;
            } else if (name != null) {
                int colon = name.indexOf(':');
                if (colon > 0 && packageName != null) {
                    res = name.substring(colon + 1);
                } else {
                    res = name;
                }
            } else {
                res = "";
            }
            return cleanStr(res);
        }
    }

    private List<WakelockEntry> _entries = new ArrayList<>();

    public List<WakelockEntry> getEntries() {
        return _entries;
    }

    private static volatile Boolean sRootAvailableCache = null;

    /**
     * Checks if elevated privileges (Root, Shizuku, or Porter) are granted and available.
     */
    public static boolean isElevatedAvailable() {
        return isRootGranted() || isPorterGranted() || isShizukuGranted();
    }

    public static boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isShizukuGranted() {
        try {
            return Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isPorterGranted() {
        try {
            return PorterClient.isGranted();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isRootGranted() {
        if (sRootAvailableCache != null) {
            return sRootAvailableCache;
        }
        return isRootAvailable();
    }

    public static void setRootGranted(boolean granted) {
        sRootAvailableCache = granted;
    }

    /**
     * Fast probe to verify root execution without hanging.
     */
    public static boolean probeRootFast() {
        for (String su : new String[]{"su", "/system/bin/su", "/system/xbin/su"}) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{su, "-c", "id"});
                final Process proc = p;
                final boolean[] success = new boolean[1];
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (proc.waitFor() == 0) {
                                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                                String line = r.readLine();
                                if (line != null && line.contains("uid=0")) {
                                    success[0] = true;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                t.start();
                t.join(800);
                if (t.isAlive()) {
                    t.interrupt();
                    proc.destroy();
                } else if (success[0]) {
                    sRootAvailableCache = true;
                    return true;
                }
            } catch (Throwable ignored) {
            } finally {
                if (p != null) {
                    try { p.destroy(); } catch (Throwable ignored) {}
                }
            }
        }
        sRootAvailableCache = false;
        return false;
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

    public void updateEntries() {
        updateEntries(null);
    }

    /**
     * Update entries from all available sources.
     * Call from a background thread (e.g. AsyncTask).
     */
    public void updateEntries(Context context) {
        Map<String, WakelockEntry> appMap = new HashMap<>();
        Map<Integer, String> uidMap = new HashMap<>();

        // Map well-known system UIDs from SYSTEM_UID_MAP
        for (Map.Entry<Integer, String[]> entry : SYSTEM_UID_MAP.entrySet()) {
            uidMap.put(entry.getKey(), entry.getValue()[0]);
        }

        PackageManager pm = null;
        if (context != null) {
            try {
                pm = context.getPackageManager();
            } catch (Exception ignored) {}
        }

        // 1. Fast elevated execution: dumpsys batterystats --checkin + dumpsys power
        readViaElevated(appMap, uidMap, pm);

        // 2. Fallback only if no data was retrieved
        if (appMap.isEmpty()) {
            readDirectSysfs(appMap);
        }

        // 3. Resolve human-readable app names and try resolving raw UIDs
        resolveAppNames(appMap, uidMap, pm);

        // 4. FILTER: discard all entries that have zero activity
        List<WakelockEntry> filtered = new ArrayList<>();
        for (WakelockEntry e : appMap.values()) {
            if (e.hitCount > 0 || e.totalTimeMs > 0 || e.isActive) {
                filtered.add(e);
            }
        }

        // 5. Sort:
        // 1) Apps strictly before kernel / hardware drivers (system are a priori below apps)
        // 2) By wake count (hitCount) descending (prevents 1-count locks from pushing heavy ones down)
        // 3) By running time (totalTimeMs) descending
        // 4) Active right now (isActive) as tie-breaker
        // 5) Alphabetical by display name
        Collections.sort(filtered, new Comparator<WakelockEntry>() {
            @Override
            public int compare(WakelockEntry a, WakelockEntry b) {
                if (a.isApp != b.isApp) return a.isApp ? -1 : 1;
                if (a.hitCount != b.hitCount) return Long.compare(b.hitCount, a.hitCount);
                if (a.totalTimeMs != b.totalTimeMs) return Long.compare(b.totalTimeMs, a.totalTimeMs);
                if (a.isActive != b.isActive) return a.isActive ? -1 : 1;
                return a.displayName().compareToIgnoreCase(b.displayName());
            }
        });

        _entries = filtered;
    }

    // -------------------------------------------------------------------------
    // Method 1: Elevated Execution (Root -> Porter -> Shizuku)
    // Runs dumpsys batterystats --checkin + dumpsys power in ONE session.
    // -------------------------------------------------------------------------
    private void readViaElevated(Map<String, WakelockEntry> map, Map<Integer, String> uidMap, PackageManager pm) {
        String cmd = "echo '===BATTERYSTATS_CHECKIN==='; dumpsys batterystats --checkin 2>/dev/null; echo '===DUMPSYS_POWER==='; dumpsys power 2>/dev/null; echo '===END==='";

        // Provider cascade:
        // 1. Root (su)
        if (isRootGranted() || isRootAvailable()) {
            if (executeAndParse(cmd, 1, map, uidMap, pm)) {
                sRootAvailableCache = true;
                return;
            }
        }

        // 2. Porter
        if (isPorterGranted()) {
            if (executeAndParse(cmd, 2, map, uidMap, pm)) {
                return;
            }
        }

        // 3. Shizuku
        if (isShizukuGranted()) {
            if (executeAndParse(cmd, 3, map, uidMap, pm)) {
                return;
            }
        }
    }

    private boolean executeAndParse(String cmd, int providerType,
                                    Map<String, WakelockEntry> map,
                                    Map<Integer, String> uidMap,
                                    PackageManager pm) {
        Process p = null;
        try {
            if (providerType == 1) {
                for (String su : new String[]{"su", "/system/bin/su", "/system/xbin/su"}) {
                    try {
                        p = Runtime.getRuntime().exec(new String[]{su, "-c", cmd});
                        break;
                    } catch (Throwable ignored) {}
                }
            } else if (providerType == 2) {
                p = PorterClient.runProcess(cmd);
            } else if (providerType == 3) {
                p = runWithShizuku(cmd);
            }

            if (p == null) return false;

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int section = 0; // 1 = checkin, 2 = dumpsys power
            boolean inPowerWakeLocks = false;
            boolean hasReadAnyValidData = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.contains("===BATTERYSTATS_CHECKIN===")) {
                    section = 1;
                    continue;
                } else if (line.contains("===DUMPSYS_POWER===")) {
                    section = 2;
                    continue;
                } else if (line.contains("===END===")) {
                    break;
                }

                if (section == 1) {
                    if (parseCheckinLine(line, map, uidMap, pm)) {
                        hasReadAnyValidData = true;
                    }
                } else if (section == 2) {
                    if (line.contains("Wake Locks:")) {
                        inPowerWakeLocks = true;
                        continue;
                    }
                    if (inPowerWakeLocks) {
                        if (line.isEmpty() || line.startsWith("Suspend Blockers")) {
                            inPowerWakeLocks = false;
                        } else {
                            if (parsePowerActiveLine(line, map, uidMap, pm)) {
                                hasReadAnyValidData = true;
                            }
                        }
                    }
                }
            }

            try { reader.close(); } catch (Throwable ignored) {}
            return hasReadAnyValidData;
        } catch (Throwable t) {
            Log.d(TAG, "executeAndParse provider=" + providerType + " failed: " + t.getMessage());
            return false;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    private static Process runWithShizuku(String cmd) throws Exception {
        java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
            "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new String[] { "sh", "-c", cmd }, null, null);
    }

    // -------------------------------------------------------------------------
    // Parse Checkin CSV lines from dumpsys batterystats
    // Format: version,uid,category,type,args...
    // -------------------------------------------------------------------------
    private boolean parseCheckinLine(String line, Map<String, WakelockEntry> map,
                                     Map<Integer, String> uidMap, PackageManager pm) {
        try {
            if (line == null || line.length() < 6) return false;
            String[] p = line.split(",");
            if (p.length < 5) return false;

            int uid = safeInt(p[1]);
            String type = p[3].trim();

            if ("apk".equals(type) && p.length >= 6) {
                // p[4] = wakeups count, p[5] = package name
                long wakeups = safeLong(p[4]);
                String pkg = cleanStr(p[5]);
                if (!pkg.isEmpty()) {
                    uidMap.put(uid, pkg);
                    // Only add to display map if wakeups > 0!
                    if (wakeups > 0) {
                        WakelockEntry e = map.get(pkg);
                        if (e == null) {
                            e = new WakelockEntry();
                            e.name = pkg;
                            e.packageName = pkg;
                            e.isApp = true;
                            map.put(pkg, e);
                        }
                        e.hitCount += wakeups;
                        return true;
                    }
                }
            } else if ("wl".equals(type)) {
                // p[4] = wakelock name
                String wlName = cleanStr(p[4]);
                // Look for "p" (partial wakelock) tag in remaining tokens
                long partTime = 0;
                long partCount = 0;
                for (int i = 5; i < p.length; i++) {
                    if ("p".equalsIgnoreCase(p[i].trim())) {
                        partTime = safeLong(p[i - 1]);
                        partCount = (i + 1 < p.length) ? safeLong(p[i + 1]) : 0;
                        break;
                    }
                }

                // Only record if this wakelock was actually held
                if (partTime > 0 || partCount > 0) {
                    String pkg = uidMap.get(uid);
                    if (pkg == null && pm != null) {
                        try {
                            String[] pkgs = pm.getPackagesForUid(uid);
                            if (pkgs != null && pkgs.length > 0) {
                                pkg = pkgs[0];
                                uidMap.put(uid, pkg);
                            }
                        } catch (Exception ignored) {}
                    }
                    if (pkg == null) {
                        if (uid == 1000) pkg = "android";
                        else pkg = "uid:" + uid;
                    }

                    WakelockEntry e = map.get(pkg);
                    if (e == null) {
                        e = new WakelockEntry();
                        e.name = pkg;
                        e.packageName = pkg.startsWith("uid:") ? null : pkg;
                        e.isApp = true;
                        map.put(pkg, e);
                    }
                    e.totalTimeMs += partTime;
                    if (e.hitCount == 0) e.hitCount += partCount;
                    return true;
                }
            } else if ("kwl".equals(type) && p.length >= 6) {
                // Kernel wake lock: p[4] = name, p[5] = totalTimeMs, p[6] = count
                String kwlName = cleanStr(p[4]);
                long timeMs = safeLong(p[5]);
                long count = (p.length > 6) ? safeLong(p[6]) : 0;
                if (timeMs > 0 || count > 0) {
                    WakelockEntry e = map.get(kwlName);
                    if (e == null) {
                        e = new WakelockEntry();
                        e.name = kwlName;
                        e.isApp = false;
                        map.put(kwlName, e);
                    }
                    e.totalTimeMs += timeMs;
                    e.hitCount += count;
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // -------------------------------------------------------------------------
    // Parse active wake locks from dumpsys power
    // -------------------------------------------------------------------------
    private boolean parsePowerActiveLine(String line, Map<String, WakelockEntry> map,
                                         Map<Integer, String> uidMap, PackageManager pm) {
        try {
            line = line.trim();
            if (line.isEmpty()) return false;

            int q1 = line.indexOf('\'');
            int q2 = line.lastIndexOf('\'');
            String name = (q1 >= 0 && q2 > q1) ? cleanStr(line.substring(q1 + 1, q2)) : null;
            if (name == null || name.isEmpty()) return false;

            String pkg = null;

            // 1. Check WorkSource{...} (e.g. ws=WorkSource{10123 com.android.vending})
            int wsIdx = line.indexOf("WorkSource{");
            if (wsIdx >= 0) {
                int end = line.indexOf('}', wsIdx);
                if (end > wsIdx) {
                    String wsContent = line.substring(wsIdx + "WorkSource{".length(), end).trim();
                    String[] tokens = wsContent.split("\\s+");
                    for (String t : tokens) {
                        t = cleanStr(t);
                        if (t.contains(".") && t.length() > 4 && Character.isLowerCase(t.charAt(0))) {
                            pkg = t;
                            break;
                        }
                    }
                    if (pkg == null && tokens.length > 0) {
                        int wsUid = safeInt(tokens[0]);
                        if (wsUid > 0) {
                            if (wsUid == 1000) pkg = "android";
                            else if (uidMap != null && uidMap.containsKey(wsUid)) pkg = uidMap.get(wsUid);
                            else if (SYSTEM_UID_MAP.containsKey(wsUid)) pkg = SYSTEM_UID_MAP.get(wsUid)[0];
                            else if (pm != null) {
                                try {
                                    String[] pkgs = pm.getPackagesForUid(wsUid);
                                    if (pkgs != null && pkgs.length > 0) {
                                        pkg = pkgs[0];
                                        if (uidMap != null) uidMap.put(wsUid, pkg);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }

            // 2. Check packageName=
            if (pkg == null) {
                int pkgIdx = line.indexOf("packageName=");
                if (pkgIdx >= 0) {
                    String rest = line.substring(pkgIdx + "packageName=".length());
                    int space = rest.indexOf(' ');
                    String cand = space > 0 ? rest.substring(0, space) : rest.trim();
                    cand = cleanStr(cand);
                    if (cand.contains(".") && Character.isLowerCase(cand.charAt(0))) {
                        pkg = cand;
                    }
                }
            }

            // 3. Extract package from lock name (*job*/..., *alarm*:..., *sync*/..., etc.)
            if (pkg == null) {
                pkg = extractPackage(name);
            }

            // 4. Check (uid=XXXX)
            if (pkg == null) {
                int uidIdx = line.indexOf("(uid=");
                if (uidIdx < 0) uidIdx = line.indexOf("uid=");
                if (uidIdx >= 0) {
                    int start = uidIdx + (line.charAt(uidIdx) == '(' ? 5 : 4);
                    int end = start;
                    while (end < line.length() && Character.isDigit(line.charAt(end))) {
                        end++;
                    }
                    if (end > start) {
                        int u = safeInt(line.substring(start, end));
                        if (u > 0) {
                            if (u == 1000) pkg = "android";
                            else if (uidMap != null && uidMap.containsKey(u)) pkg = uidMap.get(u);
                            else if (SYSTEM_UID_MAP.containsKey(u)) pkg = SYSTEM_UID_MAP.get(u)[0];
                            else if (pm != null) {
                                try {
                                    String[] pkgs = pm.getPackagesForUid(u);
                                    if (pkgs != null && pkgs.length > 0) {
                                        pkg = pkgs[0];
                                        if (uidMap != null) uidMap.put(u, pkg);
                                    }
                                } catch (Exception ignored) {}
                            }
                            if (pkg == null && u >= 10000) {
                                pkg = "uid:" + u;
                            }
                        }
                    }
                }
            }

            if (pkg != null) {
                WakelockEntry e = map.get(pkg);
                if (e != null) {
                    e.isActive = true;
                    if (e.packageName == null && !pkg.startsWith("uid:")) {
                        e.packageName = pkg;
                    }
                    e.isApp = true;
                } else {
                    e = new WakelockEntry();
                    e.name = pkg;
                    e.packageName = pkg.startsWith("uid:") ? null : pkg;
                    e.isActive = true;
                    e.isApp = true;
                    if (e.hitCount == 0) e.hitCount = 1;
                    map.put(pkg, e);
                }
            } else {
                WakelockEntry e = map.get(name);
                if (e != null) {
                    e.isActive = true;
                } else {
                    e = new WakelockEntry();
                    e.name = name;
                    e.isActive = true;
                    e.isApp = false;
                    if (e.hitCount == 0) e.hitCount = 1;
                    map.put(name, e);
                }
            }
            return true;
        } catch (Exception ignored) {}
        return false;
    }

    // -------------------------------------------------------------------------
    // Fallback: Direct sysfs read (/sys/class/wakeup/)
    // -------------------------------------------------------------------------
    private void readDirectSysfs(Map<String, WakelockEntry> map) {
        try {
            File dir = new File("/sys/class/wakeup");
            if (dir.exists() && dir.isDirectory()) {
                File[] wakeups = dir.listFiles();
                if (wakeups != null) {
                    for (File w : wakeups) {
                        if (!w.isDirectory()) continue;
                        String name = cleanStr(readFileFirstLine(new File(w, "name")));
                        if (name.isEmpty()) continue;
                        long activeCount = safeLong(readFileFirstLine(new File(w, "active_count")));
                        long activeTime  = safeLong(readFileFirstLine(new File(w, "active_time_ms")));
                        long totalTime   = safeLong(readFileFirstLine(new File(w, "total_time_ms")));

                        if (totalTime == 0 && activeTime == 0 && activeCount == 0) continue;

                        WakelockEntry e = new WakelockEntry();
                        e.name = name;
                        e.totalTimeMs = totalTime;
                        e.activeSinceMs = activeTime;
                        e.hitCount = activeCount;
                        e.isActive = (activeTime > 0);
                        e.isApp = false;
                        map.put(name, e);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "direct sysfs read failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Resolve user-friendly App Names from package names & UIDs
    // -------------------------------------------------------------------------
    private void resolveAppNames(Map<String, WakelockEntry> map, Map<Integer, String> uidMap, PackageManager pm) {
        boolean isRu = java.util.Locale.getDefault().getLanguage().startsWith("ru");
        for (WakelockEntry e : map.values()) {
            // Check if name or packageName is a raw UID like "uid:1041"
            String uidStr = null;
            if (e.name != null && e.name.startsWith("uid:")) {
                uidStr = e.name.substring(4);
            } else if (e.packageName != null && e.packageName.startsWith("uid:")) {
                uidStr = e.packageName.substring(4);
            }

            if (uidStr != null) {
                int uidVal = safeInt(uidStr);
                if (SYSTEM_UID_MAP.containsKey(uidVal)) {
                    String[] sys = SYSTEM_UID_MAP.get(uidVal);
                    e.packageName = sys[0];
                    e.appName = isRu ? sys[2] : sys[1];
                } else if (uidVal < 10000) {
                    e.packageName = "system.uid." + uidVal;
                    e.appName = isRu ? ("Системная служба (UID " + uidVal + ")") : ("System Service (UID " + uidVal + ")");
                } else {
                    String resolvedPkg = uidMap != null ? uidMap.get(uidVal) : null;
                    if (resolvedPkg == null && pm != null) {
                        try {
                            String[] pkgs = pm.getPackagesForUid(uidVal);
                            if (pkgs != null && pkgs.length > 0) {
                                resolvedPkg = pkgs[0];
                                if (uidMap != null) uidMap.put(uidVal, resolvedPkg);
                            }
                        } catch (Exception ignored) {}
                    }
                    if (resolvedPkg != null) {
                        e.packageName = resolvedPkg;
                    } else {
                        e.packageName = "uid:" + uidVal;
                        e.appName = isRu ? ("Удалённое приложение (UID " + uidVal + ")") : ("Uninstalled App (UID " + uidVal + ")");
                    }
                }
            }

            // Check if packageName matches a known system UID entry
            if (e.packageName != null && e.appName == null) {
                for (String[] sys : SYSTEM_UID_MAP.values()) {
                    if (sys[0].equals(e.packageName)) {
                        e.appName = isRu ? sys[2] : sys[1];
                        break;
                    }
                }
            }

            if (e.appName != null) continue;

            if (e.packageName == null) {
                if (e.isApp) e.appName = cleanStr(e.name);
                continue;
            }

            if ("android".equals(e.packageName)) {
                e.appName = isRu ? "Система Android" : "Android System";
                continue;
            }

            if (pm != null) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(e.packageName, 0);
                    CharSequence label = pm.getApplicationLabel(ai);
                    if (label != null && label.length() > 0) {
                        e.appName = label.toString();
                    }
                } catch (Exception ignored) {}
            }

            if (e.appName == null) {
                e.appName = e.packageName;
            }
        }
    }

    private static String cleanStr(String s) {
        if (s == null) return "";
        return s.replace("\"", "").replace("'", "").trim();
    }

    private static String extractPackage(String name) {
        if (name == null || name.isEmpty()) return null;
        String s = name.trim();

        if (s.startsWith("*job*/")) {
            s = s.substring(6);
        } else if (s.startsWith("*alarm*:")) {
            s = s.substring(8);
        } else if (s.startsWith("*sync*/")) {
            s = s.substring(7);
        } else if (s.startsWith("*vibrator*/")) {
            s = s.substring(11);
        } else if (s.startsWith("*launch*/")) {
            s = s.substring(9);
        } else if (s.startsWith("*walarm*:")) {
            s = s.substring(9);
        } else if (s.startsWith("*")) {
            int slash = s.indexOf('/');
            int colon = s.indexOf(':');
            int cut = -1;
            if (slash > 0 && colon > 0) cut = Math.min(slash, colon);
            else if (slash > 0) cut = slash;
            else if (colon > 0) cut = colon;
            if (cut > 0 && cut < s.length() - 1) {
                s = s.substring(cut + 1);
            }
        }

        // WorkManager tag pattern: @tag@packageName/service
        if (s.startsWith("@")) {
            int secondAt = s.indexOf('@', 1);
            if (secondAt > 0 && secondAt < s.length() - 1) {
                s = s.substring(secondAt + 1);
            } else {
                s = s.substring(1);
            }
        }

        int colon = s.indexOf(':');
        if (colon > 0) s = s.substring(0, colon);

        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);

        s = cleanStr(s);
        if (s.contains(".") && s.length() > 4 && Character.isLowerCase(s.charAt(0))) {
            return s;
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

    private static int safeInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static long safeLong(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }
}
