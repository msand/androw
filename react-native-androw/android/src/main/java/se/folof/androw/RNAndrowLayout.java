package se.folof.androw;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;

import com.facebook.react.bridge.Dynamic;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.uimanager.MeasureSpecAssertions;
import com.facebook.react.views.view.ReactViewGroup;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.ScriptIntrinsicColorMatrix;
import android.util.Log;

public class RNAndrowLayout extends ReactViewGroup {

    private int mColor;
    private float mRadius;
    private float mOpacity;
    private float shadowY;
    private float shadowX;

    private boolean shadowDirty;
    private boolean contentDirty;
    private boolean hasPositiveArea;

    private boolean hasShadowColor;
    private boolean hasShadowRadius;
    private boolean hasShadowOpacity;

    private final int[] offsetXY = {0, 0};
    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final Bitmap.Config ARGB_8888 = Bitmap.Config.ARGB_8888;
    private static final ColorSpace SRGB = ColorSpace.get(ColorSpace.Named.SRGB);
    private static final BlurMaskFilter.Blur NORMAL = BlurMaskFilter.Blur.NORMAL;
    private static final PorterDuff.Mode SRC_ATOP = PorterDuff.Mode.SRC_ATOP;

    private Bitmap shadowBitmap = Bitmap.createBitmap(null, 1, 1, ARGB_8888, true, SRGB);
    private Bitmap originalBitmap = Bitmap.createBitmap(null, 1, 1, ARGB_8888, true, SRGB);
    private Canvas originalCanvas = new Canvas(originalBitmap);
    private boolean originalBitmapHasContent;

    private RenderScript rs;
    private boolean useRenderScript;
    private ScriptIntrinsicBlur intrinsicBlur;
    private ScriptIntrinsicColorMatrix intrinsicLuminance;
    private static final int safetyOffset = 25; // ScriptIntrinsicBlur max radius 25.0F
    private static final int safetyMargin = safetyOffset * 2;
    private Allocation inputAllocation;

    public RNAndrowLayout(Context context) {
        super(context);
        rs = RenderScript.create(context);
        intrinsicBlur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        intrinsicLuminance = ScriptIntrinsicColorMatrix.create(rs);
        intrinsicLuminance.setGreyscale();
    }

    public Bitmap blur(Bitmap input) {
        Bitmap output = Bitmap.createBitmap(input.getWidth(), input.getHeight(), input.getConfig());
        Allocation outputAllocation = Allocation.createFromBitmap(rs, output);
        intrinsicLuminance.forEach(inputAllocation, outputAllocation);
        intrinsicBlur.setInput(outputAllocation);
        intrinsicBlur.forEach(outputAllocation);
        outputAllocation.copyTo(output);
        return output;
    }

    public void setUseRenderScript(boolean flag) {
        if (useRenderScript == flag) {
            return;
        }
        useRenderScript = flag;
        shadowDirty = true;
        super.invalidate();
    }

    public void setShadowOffset(ReadableMap offsetMap) {
        boolean hasMap = offsetMap != null;

        if (hasMap && offsetMap.hasKey("width")) {
            shadowX = (float) offsetMap.getDouble("width");
        } else {
            shadowX = 0f;
        }

        if (hasMap && offsetMap.hasKey("height")) {
            shadowY = (float) offsetMap.getDouble("height");
        } else {
            shadowY = 0f;
        }

        super.invalidate();
    }

    public void setShadowColor(Integer color) {
        hasShadowColor = color != null;
        if (hasShadowColor && mColor != color) {
            shadowPaint.setColorFilter(new PorterDuffColorFilter(color, SRC_ATOP));
            mColor = color;
        }
        super.invalidate();
    }

    public void setShadowOpacity(Dynamic nullableOpacity) {
        hasShadowOpacity = nullableOpacity != null && !nullableOpacity.isNull();
        float opacity = hasShadowOpacity ? (float) nullableOpacity.asDouble() : 0f;
        hasShadowOpacity &= opacity > 0f;
        if (hasShadowOpacity && mOpacity != opacity) {
            shadowPaint.setAlpha(Math.round(255 * opacity));
            mOpacity = opacity;
        }
        super.invalidate();
    }

    public void setShadowRadius(Dynamic nullableRadius) {
        hasShadowRadius = nullableRadius != null && !nullableRadius.isNull();
        float radius = hasShadowRadius ? (float) nullableRadius.asDouble() : 0f;
        hasShadowRadius &= radius > 0f;
        if (hasShadowRadius && mRadius != radius) {
            blurPaint.setMaskFilter(new BlurMaskFilter(radius, NORMAL));
            intrinsicBlur.setRadius(radius);
            shadowDirty = true;
            mRadius = radius;
        }
        super.invalidate();
    }

    public void invalidate() {
        contentDirty = true;
        shadowDirty = true;
        super.invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        MeasureSpecAssertions.assertExplicitMeasureSpec(widthMeasureSpec, heightMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec) + safetyMargin;
        int width = MeasureSpec.getSize(widthMeasureSpec) + safetyMargin;
        setMeasuredDimension(width, height);
        hasPositiveArea = width > safetyMargin && height > safetyMargin;
        if (hasPositiveArea) {
            if (originalBitmap.getWidth() == width && originalBitmap.getHeight() == height) {
                return;
            }
            originalBitmap.recycle();
            originalBitmapHasContent = false;
            originalBitmap = Bitmap.createBitmap(null, width, height, ARGB_8888, true, SRGB);
            originalCanvas.setBitmap(originalBitmap);
            originalCanvas.setMatrix(null);
            originalCanvas.translate(safetyOffset, safetyOffset);
        }
        invalidate();
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        if (hasPositiveArea) {
            float safety = -safetyOffset;

            if (contentDirty) {
                if (originalBitmapHasContent) {
                    originalBitmap.eraseColor(Color.TRANSPARENT);
                }
                super.dispatchDraw(originalCanvas);
                inputAllocation = Allocation.createFromBitmap(rs, originalBitmap);
                originalBitmapHasContent = true;
                contentDirty = false;
            }

            if (hasShadowRadius && hasShadowColor && hasShadowOpacity) {
                if (shadowDirty) {
                    shadowBitmap.recycle();
                    long start = System.nanoTime();
                    shadowBitmap = useRenderScript ? blur(originalBitmap) : originalBitmap.extractAlpha(blurPaint, offsetXY);
                    Log.i("Androw blur nano seconds", Long.toString(System.nanoTime() - start) + useRenderScript);
                    shadowDirty = false;
                }
                canvas.drawBitmap(
                        shadowBitmap,
                        (useRenderScript ? 0f : offsetXY[0]) + shadowX + safety,
                        (useRenderScript ? 0f : offsetXY[1]) + shadowY + safety,
                        shadowPaint
                );
            }

            canvas.drawBitmap(originalBitmap, safety, safety, null);
        } else {
            super.dispatchDraw(canvas);
        }
    }

}
