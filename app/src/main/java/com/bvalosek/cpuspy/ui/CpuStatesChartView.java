package com.bvalosek.cpuspy.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.bvalosek.cpuspy.CpuStateMonitor.CpuState;
import com.bvalosek.cpuspy.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Custom chart view displaying CPU states with horizontal bars and time grid.
 * Matches the layout style of CPU Spy Reloaded:
 * - Left column: state names (Deep sleep, frequencies)
 * - Top axis: time markers and vertical grid lines
 * - Bars: compact horizontal bars with duration and percentage
 */
public class CpuStatesChartView extends View {

    private static final int COLOR_DEEP_SLEEP = 0xFF7E57C2; // Purple

    private static final int[] PALETTE = new int[] {
        0xFFE91E63, // Pink (low freq, heavy use)
        0xFF00BCD4, // Cyan
        0xFF009688, // Teal
        0xFF4CAF50, // Green
        0xFF8BC34A, // Light Green
        0xFFFFC107, // Amber
        0xFFFF9800, // Orange
        0xFFFF5722, // Deep Orange
        0xFFF44336, // Red
        0xFF2196F3, // Blue
        0xFF3F51B5, // Indigo
        0xFF9C27B0, // Purple
        0xFF00ACC1, // Dark Cyan
        0xFF43A047, // Dark Green
        0xFFFB8C00, // Dark Orange
        0xFFE53935  // Dark Red
    };

    public static class ChartItem {
        public String label;
        public long duration;
        public float percentage;
        public int color;
        public String durationText;
    }

    private final List<ChartItem> mItems = new ArrayList<>();
    private long mMaxDuration = 1;

    private Paint mLabelPaint;
    private Paint mAxisPaint;
    private Paint mGridPaint;
    private Paint mBarPaint;
    private Paint mBarTextPaint;

    private int mPrimaryColor = 0xFFFFFFFF;
    private int mSecondaryColor = 0xFFB0B0B0;
    private int mDividerColor = 0x22FFFFFF;

    private float mDensity = 1.0f;
    private float mRowHeight;
    private float mBarHeight;
    private float mTopAxisHeight;
    private float mCornerRadius;

    private final RectF mBarRect = new RectF();

    public CpuStatesChartView(Context context) {
        super(context);
        init(context);
    }

    public CpuStatesChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CpuStatesChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        mDensity = context.getResources().getDisplayMetrics().density;

        mRowHeight = 22f * mDensity;
        mBarHeight = 12f * mDensity;
        mTopAxisHeight = 24f * mDensity;
        mCornerRadius = 3f * mDensity;

        resolveColors(context);

        mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mLabelPaint.setTextSize(12f * mDensity);
        mLabelPaint.setTextAlign(Paint.Align.RIGHT);

        mAxisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mAxisPaint.setTextSize(10f * mDensity);
        mAxisPaint.setTextAlign(Paint.Align.CENTER);

        mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mGridPaint.setStrokeWidth(1f * mDensity);

        mBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBarPaint.setStyle(Paint.Style.FILL);

        mBarTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBarTextPaint.setTextSize(11f * mDensity);
    }

    private void resolveColors(Context context) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(R.attr.primaryTextColor, tv, true)) {
            mPrimaryColor = tv.data;
        }
        if (context.getTheme().resolveAttribute(R.attr.secondaryTextColor, tv, true)) {
            mSecondaryColor = tv.data;
        }
        if (context.getTheme().resolveAttribute(R.attr.dividerColor, tv, true)) {
            mDividerColor = tv.data;
        }
    }

    public void setData(List<CpuState> states, long totalTime, Context context) {
        mItems.clear();
        mMaxDuration = 1;
        resolveColors(context);

        boolean isRu = Locale.getDefault().getLanguage().startsWith("ru");

        int colorIdx = 0;
        for (CpuState state : states) {
            ChartItem item = new ChartItem();
            if (state.freq == 0) {
                item.label = context.getString(R.string.state_deep_sleep);
                item.color = COLOR_DEEP_SLEEP;
            } else {
                item.label = (state.freq / 1000) + " " + context.getString(R.string.unit_mhz);
                item.color = PALETTE[colorIdx % PALETTE.length];
                colorIdx++;
            }

            item.duration = state.duration;
            if (item.duration > mMaxDuration) {
                mMaxDuration = item.duration;
            }

            item.percentage = totalTime > 0 ? (float) state.duration * 100f / (float) totalTime : 0f;
            item.durationText = formatDuration(state.duration / 100, (int) item.percentage, isRu);

            mItems.add(item);
        }

        requestLayout();
        invalidate();
    }

    private String formatDuration(long sec, int pct, boolean isRu) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;

        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h).append(isRu ? "ч" : "h");
            if (m > 0) {
                sb.append(m).append(isRu ? "м" : "m");
            }
        } else if (m > 0) {
            sb.append(m).append(isRu ? "м" : "m");
            if (s > 0) {
                sb.append(s).append(isRu ? "с" : "s");
            }
        } else {
            sb.append(s).append(isRu ? "с" : "s");
        }

        if (pct > 0) {
            sb.append(" ").append(pct).append("%");
        }

        return sb.toString();
    }

    private String formatAxisTime(long sec, boolean isRu) {
        if (sec >= 86400) {
            float days = (float) sec / 86400f;
            return String.format(Locale.getDefault(), "%.1f%s", days, isRu ? "д" : "d");
        } else if (sec >= 3600) {
            return (sec / 3600) + (isRu ? "ч" : "h");
        } else if (sec >= 60) {
            return (sec / 60) + (isRu ? "м" : "m");
        } else {
            return sec + (isRu ? "с" : "s");
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int itemCount = mItems.size();
        int height = (int) (mTopAxisHeight + itemCount * mRowHeight + 8f * mDensity);

        if (height < (int) (40f * mDensity)) {
            height = (int) (40f * mDensity);
        }

        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mItems.isEmpty()) return;

        int width = getWidth();
        int height = getHeight();
        boolean isRu = Locale.getDefault().getLanguage().startsWith("ru");

        // Calculate label column width
        float maxLabelWidth = 0;
        for (ChartItem item : mItems) {
            float w = mLabelPaint.measureText(item.label);
            if (w > maxLabelWidth) {
                maxLabelWidth = w;
            }
        }

        float chartLeft = maxLabelWidth + 14f * mDensity;
        float chartRight = width - 10f * mDensity;
        float chartWidth = chartRight - chartLeft;
        if (chartWidth <= 10f * mDensity) return;

        // Draw top axis and vertical grid lines
        mGridPaint.setColor(mDividerColor);
        mAxisPaint.setColor(mSecondaryColor);

        int divisions = 5;
        long maxDurationSec = mMaxDuration / 100;

        for (int i = 1; i <= divisions; i++) {
            float x = chartLeft + (float) i / (float) divisions * chartWidth;
            long sec = (maxDurationSec * i) / divisions;

            // Vertical grid line
            canvas.drawLine(x, mTopAxisHeight - 2f * mDensity, x, height - 4f * mDensity, mGridPaint);

            // Time axis label
            String axisLabel = formatAxisTime(sec, isRu);
            canvas.drawText(axisLabel, x, mTopAxisHeight - 6f * mDensity, mAxisPaint);
        }

        // Draw rows
        Paint.FontMetrics labelFm = mLabelPaint.getFontMetrics();
        float labelBaselineOffset = (labelFm.descent - labelFm.ascent) / 2f - labelFm.descent;

        Paint.FontMetrics textFm = mBarTextPaint.getFontMetrics();
        float textBaselineOffset = (textFm.descent - textFm.ascent) / 2f - textFm.descent;

        for (int i = 0; i < mItems.size(); i++) {
            ChartItem item = mItems.get(i);
            float yCenter = mTopAxisHeight + i * mRowHeight + mRowHeight / 2f;

            // 1. Draw state label (left-aligned column, right-aligned text)
            mLabelPaint.setColor(mPrimaryColor);
            canvas.drawText(item.label, chartLeft - 8f * mDensity, yCenter + labelBaselineOffset, mLabelPaint);

            // 2. Draw horizontal bar
            float barW = (float) item.duration / (float) mMaxDuration * chartWidth;
            if (barW < 2f * mDensity && item.duration > 0) {
                barW = 2f * mDensity;
            }

            mBarPaint.setColor(item.color);
            mBarRect.set(chartLeft, yCenter - mBarHeight / 2f, chartLeft + barW, yCenter + mBarHeight / 2f);
            canvas.drawRoundRect(mBarRect, mCornerRadius, mCornerRadius, mBarPaint);

            // 3. Draw duration and percentage text
            float textWidth = mBarTextPaint.measureText(item.durationText);
            if (barW >= textWidth + 14f * mDensity) {
                // Inside the bar
                mBarTextPaint.setColor(0xFFFFFFFF);
                mBarTextPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(item.durationText, chartLeft + barW - 4f * mDensity, yCenter + textBaselineOffset, mBarTextPaint);
            } else {
                // Outside the bar
                mBarTextPaint.setColor(mSecondaryColor);
                mBarTextPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(item.durationText, chartLeft + barW + 5f * mDensity, yCenter + textBaselineOffset, mBarTextPaint);
            }
        }
    }
}
