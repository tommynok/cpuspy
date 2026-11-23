package com.bvalosek.cpuspy.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import com.bvalosek.cpuspy.R;
import com.bvalosek.cpuspy.WakelockMonitor.WakelockEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom View rendering the wakelocks list (Apps & Kernel).
 * Matches user-friendly style (CPU Spy Reloaded):
 * - Large App icon (40dp)
 * - App title (e.g. "Google Play services")
 * - Package name (e.g. "com.google.android.gms")
 * - Stats (e.g. "164 wake ups, running 20s" / "164 пробуждений, работает 20с")
 * - Tap to open App Settings
 */
public class WakelocksView extends View {

    private static final int ACTIVE_COLOR = 0xFF4CAF50;  // Green dot = currently active

    private final List<WakelockEntry> mEntries = new ArrayList<>();

    private Paint mTitlePaint;
    private Paint mPkgPaint;
    private Paint mStatsPaint;
    private Paint mDividerPaint;
    private Paint mDotPaint;
    private Paint mKernelIconBgPaint;
    private Paint mKernelIconTextPaint;

    private int mPrimaryColor   = 0xFFFFFFFF;
    private int mSecondaryColor = 0xFFB0B0B0;
    private int mDividerColor   = 0x22FFFFFF;
    private int mPercentColor   = 0xFF90CAF9;

    private float mDensity;
    private float mRowHeight;
    private float mIconSize;
    private float mPadding;

    // Cache: packageName -> Drawable icon
    private final Map<String, Drawable> mIconCache = new HashMap<>();
    private final Rect mIconRect = new Rect();

    public WakelocksView(Context context) {
        super(context);
        init(context);
    }

    public WakelocksView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mRowHeight = 64f * mDensity;
        mIconSize  = 40f * mDensity;
        mPadding   = 16f * mDensity;

        resolveColors(context);

        mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTitlePaint.setTextSize(14.5f * mDensity);
        mTitlePaint.setFakeBoldText(true);

        mPkgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPkgPaint.setTextSize(11f * mDensity);

        mStatsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mStatsPaint.setTextSize(12f * mDensity);

        mDividerPaint = new Paint();
        mDividerPaint.setStrokeWidth(1f);

        mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mDotPaint.setStyle(Paint.Style.FILL);

        mKernelIconBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mKernelIconBgPaint.setStyle(Paint.Style.FILL);

        mKernelIconTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mKernelIconTextPaint.setTextSize(14f * mDensity);
        mKernelIconTextPaint.setFakeBoldText(true);
        mKernelIconTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void resolveColors(Context context) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(R.attr.primaryTextColor, tv, true))
            mPrimaryColor = tv.data;
        if (context.getTheme().resolveAttribute(R.attr.secondaryTextColor, tv, true))
            mSecondaryColor = tv.data;
        if (context.getTheme().resolveAttribute(R.attr.dividerColor, tv, true))
            mDividerColor = tv.data;
        if (context.getTheme().resolveAttribute(R.attr.percentageTextColor, tv, true))
            mPercentColor = tv.data;
    }

    public void setEntries(List<WakelockEntry> entries, Context context) {
        mEntries.clear();
        mEntries.addAll(entries);
        resolveColors(context);
        resolveIcons(context);
        requestLayout();
        invalidate();
    }

    /** Pre-resolve icons for all app entries */
    private void resolveIcons(Context context) {
        PackageManager pm = context.getPackageManager();
        for (WakelockEntry e : mEntries) {
            if (e.packageName == null) continue;
            if (mIconCache.containsKey(e.packageName)) continue;
            try {
                Drawable icon = pm.getApplicationIcon(e.packageName);
                mIconCache.put(e.packageName, icon);
            } catch (PackageManager.NameNotFoundException ex) {
                // If package not found directly, try android framework icon
                if ("android".equals(e.packageName)) {
                    try {
                        mIconCache.put("android", pm.getApplicationIcon("com.android.settings"));
                    } catch (Exception ignored) {
                        mIconCache.put(e.packageName, null);
                    }
                } else {
                    mIconCache.put(e.packageName, null);
                }
            } catch (Exception ex) {
                mIconCache.put(e.packageName, null);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int)(mEntries.size() * mRowHeight + 12f * mDensity);
        if (height < (int)(50f * mDensity)) height = (int)(50f * mDensity);
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                             resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mEntries.isEmpty()) return;

        int width = getWidth();
        boolean isRu = Locale.getDefault().getLanguage().startsWith("ru");

        float textX = mPadding + mIconSize + 14f * mDensity;
        float maxTextWidth = width - textX - mPadding;

        mTitlePaint.setColor(mPrimaryColor);
        mPkgPaint.setColor(mSecondaryColor);
        mDividerPaint.setColor(mDividerColor);

        for (int i = 0; i < mEntries.size(); i++) {
            WakelockEntry e = mEntries.get(i);
            float top = i * mRowHeight;
            float yCenter = top + mRowHeight / 2f;

            // --- 1. App or Kernel Icon ---
            float iconLeft = mPadding;
            float iconTop  = yCenter - mIconSize / 2f;
            float iconRight = iconLeft + mIconSize;
            float iconBot   = iconTop + mIconSize;

            Drawable icon = e.packageName != null ? mIconCache.get(e.packageName) : null;
            if (icon != null) {
                mIconRect.set((int) iconLeft, (int) iconTop, (int) iconRight, (int) iconBot);
                icon.setBounds(mIconRect);
                icon.draw(canvas);
            } else {
                // Draw generic badge for kernel / drivers / uninstalled apps
                mKernelIconBgPaint.setColor(e.isApp ? 0x22888888 : 0x3342A5F5);
                float radius = mIconSize / 2f;
                float cx = iconLeft + radius;
                float cy = iconTop + radius;
                canvas.drawCircle(cx, cy, radius, mKernelIconBgPaint);

                mKernelIconTextPaint.setColor(e.isApp ? mSecondaryColor : mPercentColor);
                String letter = e.isApp ? (e.displayName().isEmpty() ? "?" : e.displayName().substring(0, 1).toUpperCase()) : "K";
                canvas.drawText(letter, cx, cy + 5f * mDensity, mKernelIconTextPaint);
            }

            // --- 2. Title (App Name) ---
            String title = e.displayName();
            float titleY = top + 20f * mDensity;
            String clippedTitle = clipText(mTitlePaint, title, maxTextWidth - 20f * mDensity);
            canvas.drawText(clippedTitle, textX, titleY, mTitlePaint);

            // Active indicator (green dot right after title)
            if (e.isActive) {
                float titleWidth = mTitlePaint.measureText(clippedTitle);
                float dotX = textX + titleWidth + 8f * mDensity;
                float dotY = titleY - 4.5f * mDensity;
                mDotPaint.setColor(ACTIVE_COLOR);
                canvas.drawCircle(dotX, dotY, 4f * mDensity, mDotPaint);
            }

            // --- 3. Subtitle (Package name or Driver label) ---
            float pkgY = top + 36f * mDensity;
            String pkgStr;
            if (e.isApp) {
                pkgStr = (e.packageName != null) ? e.packageName : e.name;
            } else {
                pkgStr = isRu ? "Ядро / Аппаратный драйвер" : "Kernel / Hardware driver";
            }
            canvas.drawText(clipText(mPkgPaint, pkgStr, maxTextWidth), textX, pkgY, mPkgPaint);

            // --- 4. Stats: "164 wake ups, running 20s" ---
            float statsY = top + 52f * mDensity;
            String statsStr = formatWakeStats(e.hitCount, e.totalTimeMs, isRu);
            mStatsPaint.setColor(mSecondaryColor);
            canvas.drawText(clipText(mStatsPaint, statsStr, maxTextWidth), textX, statsY, mStatsPaint);

            // --- 5. Divider ---
            if (i < mEntries.size() - 1) {
                canvas.drawLine(textX, top + mRowHeight, width, top + mRowHeight, mDividerPaint);
            }
        }
    }

    /** Tap row -> opens Android App Settings for that application */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            int index = (int)(event.getY() / mRowHeight);
            if (index >= 0 && index < mEntries.size()) {
                WakelockEntry e = mEntries.get(index);
                if (e.isApp && e.packageName != null && !"android".equals(e.packageName) && !e.packageName.startsWith("uid:")) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + e.packageName));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        getContext().startActivity(intent);
                        return true;
                    } catch (Exception ignored) {}
                }
            }
        }
        return super.onTouchEvent(event);
    }

    private String clipText(Paint paint, String text, float maxWidth) {
        if (text == null) return "";
        if (paint.measureText(text) <= maxWidth) return text;
        while (text.length() > 3 && paint.measureText(text + "…") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }

    private String formatWakeStats(long wakeups, long timeMs, boolean isRu) {
        String timeStr = formatDuration(timeMs, isRu);
        if (wakeups > 0 && timeMs > 0) {
            return isRu
                ? (wakeups + " пробуждений, работает " + timeStr)
                : (wakeups + " wake ups, running " + timeStr);
        } else if (wakeups > 0) {
            return isRu ? (wakeups + " пробуждений") : (wakeups + " wake ups");
        } else if (timeMs > 0) {
            return isRu ? ("работает " + timeStr) : ("running " + timeStr);
        } else {
            return isRu ? "нет активности" : "no activity";
        }
    }

    private String formatDuration(long ms, boolean isRu) {
        if (ms <= 0) return isRu ? "0с" : "0s";
        double sec = ms / 1000.0;
        if (sec < 10.0) {
            return String.format(Locale.US, "%.1f%s", sec, isRu ? "с" : "s");
        }
        long s = (long) sec;
        long m = s / 60;
        s = s % 60;
        long h = m / 60;
        m = m % 60;
        if (h > 0) {
            return h + (isRu ? "ч " : "h, ") + m + (isRu ? "м" : "m");
        }
        if (m > 0) {
            return m + (isRu ? "м, " : "m, ") + s + (isRu ? "с" : "s");
        }
        return s + (isRu ? "с" : "s");
    }
}
