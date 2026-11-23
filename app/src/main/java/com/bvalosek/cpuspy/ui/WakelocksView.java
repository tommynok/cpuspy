package com.bvalosek.cpuspy.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.bvalosek.cpuspy.WakelockMonitor.WakelockEntry;
import com.bvalosek.cpuspy.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom View rendering the wakelocks list.
 * - App icon (resolved via PackageManager, cached)
 * - Display name (wakelock tag)
 * - Total time
 * - Active indicator dot
 */
public class WakelocksView extends View {

    private static final int ACTIVE_COLOR = 0xFF4CAF50;  // Green dot = currently held
    private static final int INACTIVE_COLOR = 0x44AAAAAA;

    private final List<WakelockEntry> mEntries = new ArrayList<>();

    private Paint mNamePaint;
    private Paint mTimePaint;
    private Paint mDividerPaint;
    private Paint mDotPaint;
    private Paint mLabelPaint;

    private int mPrimaryColor   = 0xFFFFFFFF;
    private int mSecondaryColor = 0xFFB0B0B0;
    private int mDividerColor   = 0x22FFFFFF;
    private int mPercentColor   = 0xFF90CAF9;

    private float mDensity;
    private float mRowHeight;
    private float mIconSize;
    private float mPadding;

    // Icon cache: packageName → Drawable (null = no icon found, skip)
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
        mRowHeight = 44f * mDensity;
        mIconSize  = 28f * mDensity;
        mPadding   = 10f * mDensity;

        resolveColors(context);

        mNamePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mNamePaint.setTextSize(13f * mDensity);

        mTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTimePaint.setTextSize(12f * mDensity);
        mTimePaint.setTextAlign(Paint.Align.RIGHT);

        mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mLabelPaint.setTextSize(10f * mDensity);

        mDividerPaint = new Paint();
        mDividerPaint.setStrokeWidth(1f);

        mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mDotPaint.setStyle(Paint.Style.FILL);
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

    /** Pre-resolve icons for all entries that have a packageName */
    private void resolveIcons(Context context) {
        PackageManager pm = context.getPackageManager();
        for (WakelockEntry e : mEntries) {
            if (e.packageName == null) continue;
            if (mIconCache.containsKey(e.packageName)) continue;
            try {
                Drawable icon = pm.getApplicationIcon(e.packageName);
                mIconCache.put(e.packageName, icon);
            } catch (PackageManager.NameNotFoundException ex) {
                mIconCache.put(e.packageName, null); // mark as "not found"
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int)(mEntries.size() * mRowHeight + 8f * mDensity);
        if (height < (int)(40f * mDensity)) height = (int)(40f * mDensity);
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                             resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mEntries.isEmpty()) return;

        int width = getWidth();
        boolean isRu = Locale.getDefault().getLanguage().startsWith("ru");

        // Right column: time text
        float timeColWidth = mTimePaint.measureText("66ч 11м") + mPadding;
        float timeX = width - mPadding;

        for (int i = 0; i < mEntries.size(); i++) {
            WakelockEntry e = mEntries.get(i);
            float top    = i * mRowHeight;
            float yCenter = top + mRowHeight / 2f;

            // --- Divider ---
            if (i > 0) {
                mDividerPaint.setColor(mDividerColor);
                canvas.drawLine(mPadding, top, width - mPadding, top, mDividerPaint);
            }

            float x = mPadding;

            // --- Active dot ---
            float dotRadius = 4f * mDensity;
            mDotPaint.setColor(e.isActive ? ACTIVE_COLOR : INACTIVE_COLOR);
            canvas.drawCircle(x + dotRadius, yCenter, dotRadius, mDotPaint);
            x += dotRadius * 2 + 6f * mDensity;

            // --- App icon ---
            Drawable icon = e.packageName != null ? mIconCache.get(e.packageName) : null;
            if (icon != null) {
                int iconLeft  = (int) x;
                int iconTop   = (int)(yCenter - mIconSize / 2f);
                int iconRight = (int)(x + mIconSize);
                int iconBot   = (int)(yCenter + mIconSize / 2f);
                mIconRect.set(iconLeft, iconTop, iconRight, iconBot);
                icon.setBounds(mIconRect);
                icon.draw(canvas);
                x += mIconSize + 8f * mDensity;
            } else {
                x += 4f * mDensity; // small indent even without icon
            }

            // --- Name (display name) + package hint ---
            float nameAreaRight = timeX - timeColWidth;

            mNamePaint.setColor(mPrimaryColor);
            String dispName = e.displayName();
            // Clip if too long
            dispName = clipText(mNamePaint, dispName, nameAreaRight - x);
            canvas.drawText(dispName, x, yCenter - 1f * mDensity, mNamePaint);

            if (e.packageName != null) {
                mLabelPaint.setColor(mSecondaryColor);
                Paint.FontMetrics fm = mNamePaint.getFontMetrics();
                float subY = yCenter + (-fm.ascent) * 0.55f;
                String pkgShort = e.packageName.length() > 25
                        ? "…" + e.packageName.substring(e.packageName.length() - 22)
                        : e.packageName;
                canvas.drawText(pkgShort, x, subY, mLabelPaint);
            }

            // --- Time (right column) ---
            mTimePaint.setColor(mPercentColor);
            String timeStr = e.totalTimeMs > 0 ? formatMs(e.totalTimeMs, isRu)
                    : (e.isActive ? (isRu ? "активен" : "active") : "—");
            canvas.drawText(timeStr, timeX, yCenter + mTimePaint.getTextSize() * 0.35f, mTimePaint);
        }
    }

    private String clipText(Paint paint, String text, float maxWidth) {
        if (paint.measureText(text) <= maxWidth) return text;
        while (text.length() > 3 && paint.measureText(text + "…") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }

    private String formatMs(long ms, boolean ru) {
        long sec = ms / 1000;
        long h   = sec / 3600;
        long m   = (sec % 3600) / 60;
        long s   = sec % 60;
        if (h > 0) return h + (ru ? "ч" : "h") + " " + m + (ru ? "м" : "m");
        if (m > 0) return m + (ru ? "м" : "m") + " " + s + (ru ? "с" : "s");
        return s + (ru ? "с" : "s");
    }
}
