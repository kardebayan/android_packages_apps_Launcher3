/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher2;

import java.io.Writer;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.lang.Float;

import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScript;

import android.renderscript.RenderScript;
import android.renderscript.ProgramVertex;
import android.renderscript.Element;
import android.renderscript.Allocation;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.Sampler;

import android.content.Context;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.graphics.PixelFormat;


public class AllAppsView extends RSSurfaceView
        implements View.OnClickListener, View.OnLongClickListener, DragSource {
    private static final String TAG = "Launcher.AllAppsView";

    private Launcher mLauncher;
    private DragController mDragController;

    private RenderScript mRS;
    private RolloRS mRollo;
    private ArrayList<ApplicationInfo> mAllAppsList;

    private ViewConfiguration mConfig;
    private int mPageCount;
    private boolean mStartedScrolling;
    private VelocityTracker mVelocity;
    private int mLastScrollX;
    private int mLastMotionX;
    private int mMotionDownRawX;
    private int mMotionDownRawY;
    private TouchHandler mTouchHandler;
    private int mScrollHandleTop;

    static class Defines {
        private static float farSize(float sizeAt0) {
            return sizeAt0 * (Defines.RADIUS - Defines.CAMERA_Z) / -Defines.CAMERA_Z;
        }

        public static final int ALLOC_PARAMS = 0;
        public static final int ALLOC_STATE = 1;
        public static final int ALLOC_ICON_IDS = 2;
        public static final int ALLOC_LABEL_IDS = 3;
        public static final int ALLOC_X_BORDERS = 4;
        public static final int ALLOC_Y_BORDERS = 5;

        public static final int COLUMNS_PER_PAGE = 4;
        public static final int ROWS_PER_PAGE = 4;
        
        public static final float RADIUS = 4.0f;

        public static final int SCREEN_WIDTH_PX = 480;
        public static final int SCREEN_HEIGHT_PX = 854;

        public static final int ICON_WIDTH_PX = 64;
        public static final int ICON_TEXTURE_WIDTH_PX = 128;

        public static final int ICON_HEIGHT_PX = 64;
        public static final int ICON_TEXTURE_HEIGHT_PX = 128;
        public static final float ICON_TOP_OFFSET = 0.2f;

        public static final float CAMERA_Z = -2;
        public static final float FAR_ICON_SIZE
                = farSize(2 * ICON_WIDTH_PX / (float)SCREEN_WIDTH_PX);
    }

    public AllAppsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        mConfig = ViewConfiguration.get(context);
        setOnClickListener(this);
        setOnLongClickListener(this);
    }

    public AllAppsView(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs);
    }

    public void setLauncher(Launcher launcher) {
        mLauncher = launcher;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);

        long startTime = SystemClock.uptimeMillis();

        mRS = createRenderScript(true);
        mRollo = new RolloRS();
        mRollo.init(getResources(), w, h);
        if (mAllAppsList != null) {
            mRollo.setApps(mAllAppsList);
            Log.d(TAG, "surfaceChanged... calling mRollo.setApps");
        }

        Resources res = getContext().getResources();
        int barHeight = (int)res.getDimension(R.dimen.button_bar_height);
        mScrollHandleTop = h - barHeight;

        long endTime = SystemClock.uptimeMillis();
        Log.d(TAG, "surfaceChanged took " + (endTime-startTime) + "ms");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        // this method doesn't work when 'extends View' include 'extends ScrollView'.
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev)
    {
        super.onTouchEvent(ev);

        if (mRollo.mState.visible == 0) {
            return false;
        }

        mTouchHandler = mFlingHandler;
        /*
        int action = ev.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            if (ev.getY() > mScrollHandleTop) {
                mTouchHandler = mScrollHandler;
            } else {
                mTouchHandler = mFlingHandler;
            }
        }
        */
        mTouchHandler.onTouchEvent(ev);

        return true;
    }

    private abstract class TouchHandler {
        abstract boolean onTouchEvent(MotionEvent ev);
    };

    private TouchHandler mFlingHandler = new TouchHandler() {
        @Override
        public boolean onTouchEvent(MotionEvent ev)
        {
            int x = (int)ev.getX();
            int deltaX;
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mMotionDownRawX = (int)ev.getRawX();
                    mMotionDownRawY = (int)ev.getRawY();
                    mLastMotionX = x;
                    mRollo.mState.read();
                    mRollo.mState.startScrollX = mRollo.mState.scrollX = mLastScrollX
                            = mRollo.mState.currentScrollX;
                    if (mRollo.mState.flingVelocityX != 0) {
                        mRollo.clearSelectedIcon();
                    } else {
                        mRollo.selectIcon(x, (int)ev.getY(), mRollo.mState.startScrollX,
                                (-mRollo.mState.startScrollX / Defines.SCREEN_WIDTH_PX));
                    }
                    mRollo.mState.flingVelocityX = 0;
                    mRollo.mState.adjustedDeceleration = 0;
                    mRollo.mState.save();
                    mVelocity = VelocityTracker.obtain();
                    mVelocity.addMovement(ev);
                    mStartedScrolling = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_OUTSIDE:
                    int slop = Math.abs(x - mLastMotionX);
                    if (!mStartedScrolling && slop < mConfig.getScaledTouchSlop()) {
                        // don't update mLastMotionX so slop is right and when we do start scrolling
                        // below, we get the right delta.
                    } else {
                        mStartedScrolling = true;
                        mRollo.clearSelectedIcon();
                        deltaX = x - mLastMotionX;
                        mVelocity.addMovement(ev);
                        mRollo.mState.currentScrollX = mLastScrollX;
                        mLastScrollX += deltaX;
                        mRollo.mState.scrollX = mLastScrollX;
                        mRollo.mState.save();
                        mLastMotionX = x;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mVelocity.computeCurrentVelocity(1000 /* px/sec */,
                            mConfig.getScaledMaximumFlingVelocity());
                    mRollo.mState.flingTimeMs = (int)SystemClock.uptimeMillis(); // TODO: use long
                    mRollo.mState.flingVelocityX = (int)mVelocity.getXVelocity();
                    mRollo.clearSelectedIcon();
                    mRollo.mState.save();
                    mLastMotionX = -10000;
                    mVelocity.recycle();
                    mVelocity = null;
                    break;
            }
            return true;
        }
    };

    public void onClick(View v) {
        int index = mRollo.mState.selectedIconIndex;
        if (mRollo.mState.flingVelocityX == 0 && index >= 0 && index < mAllAppsList.size()) {
            ApplicationInfo app = mAllAppsList.get(index);
            mLauncher.startActivitySafely(app.intent);
        }
    }

    public boolean onLongClick(View v) {
        int index = mRollo.mState.selectedIconIndex;
        Log.d(TAG, "long click! velocity=" + mRollo.mState.flingVelocityX + " index=" + index);
        if (mRollo.mState.flingVelocityX == 0 && index >= 0 && index < mAllAppsList.size()) {
            ApplicationInfo app = mAllAppsList.get(index);

            // We don't really have an accurate location to use.  This will do.
            int screenX = mMotionDownRawX - (Defines.ICON_WIDTH_PX / 2);
            int screenY = mMotionDownRawY - Defines.ICON_HEIGHT_PX;

            int left = (Defines.ICON_TEXTURE_WIDTH_PX - Defines.ICON_WIDTH_PX) / 2;
            int top = (Defines.ICON_TEXTURE_HEIGHT_PX - Defines.ICON_HEIGHT_PX) / 2;
            mDragController.startDrag(app.iconBitmap, screenX, screenY,
                    left, top, Defines.ICON_WIDTH_PX, Defines.ICON_HEIGHT_PX,
                    this, app, DragController.DRAG_ACTION_COPY);

            mLauncher.closeAllAppsDialog(true);
        }
        return true;
    }

    public void setDragController(DragController dragger) {
        mDragController = dragger;
    }

    public void onDropCompleted(View target, boolean success) {
    }

    public void show() {
        mRollo.mState.read();
        mRollo.mState.visible = 1;
        mRollo.mState.save();
    }

    public void hide(boolean animate) {
        mRollo.mState.read();
        mRollo.mState.visible = 0;
        mRollo.mState.save();
    }

    /*
    private TouchHandler mScrollHandler = new TouchHandler() {
        @Override
        public boolean onTouchEvent(MotionEvent ev)
        {
            int x = (int)ev.getX();
            int w = getWidth();

            float percent = x / (float)w;

            mRollo.mState.read();

            mRollo.mState.scrollX = mLastScrollX = -(int)(mPageCount * w * percent);
            mRollo.mState.flingVelocityX = 0;
            mRollo.mState.adjustedDeceleration = 0;
            mRollo.mState.save();

            return true;
        }
    };
    */

    @Override
    public boolean onTrackballEvent(MotionEvent ev)
    {
        float x = ev.getX();
        float y = ev.getY();
        //Float tx = new Float(x);
        //Float ty = new Float(y);
        //Log.e("rs", "tbe " + tx.toString() + ", " + ty.toString());


        return true;
    }

    public void setApps(ArrayList<ApplicationInfo> list) {
        mAllAppsList = list;
        if (mRollo != null) {
            mRollo.setApps(list);
        }
        mPageCount = countPages(list.size());
        Log.d(TAG, "setApps mRollo=" + mRollo + " list=" + list);
    }

    private void invokeIcon(int index) {
        Log.d(TAG, "launch it!!!! index=" + index);
    }

    private static int countPages(int iconCount) {
        int iconsPerPage = Defines.COLUMNS_PER_PAGE * Defines.ROWS_PER_PAGE;
        int pages = iconCount / iconsPerPage;
        if (pages*iconsPerPage != iconCount) {
            pages++;
        }
        return pages;
    }

    public class RolloRS {

        // Allocations ======

        private int mWidth;
        private int mHeight;

        private Resources mRes;
        private Script mScript;
        private Sampler mSampler;
        private Sampler mSamplerText;
        private ProgramStore mPSBackground;
        private ProgramStore mPSText;
        private ProgramFragment mPFDebug;
        private ProgramFragment mPFImages;
        private ProgramFragment mPFText;
        private ProgramVertex mPV;
        private ProgramVertex.MatrixAllocation mPVAlloc;
        private ProgramVertex mPVOrtho;
        private ProgramVertex.MatrixAllocation mPVOrthoAlloc;

        private Allocation mScrollHandle;

        private Allocation[] mIcons;
        private int[] mIconIds;
        private Allocation mAllocIconID;

        private Allocation[] mLabels;
        private int[] mLabelIds;
        private Allocation mAllocLabelID;
        private Allocation mSelectedIcon;

        private int[] mTouchYBorders;
        private Allocation mAllocTouchYBorders;
        private int[] mTouchXBorders;
        private Allocation mAllocTouchXBorders;

        private Bitmap mSelectionBitmap;

        Params mParams;
        State mState;

        class Params extends IntAllocation {
            Params(RenderScript rs) {
                super(rs);
            }
            @AllocationIndex(0) public int bubbleWidth;
            @AllocationIndex(1) public int bubbleHeight;
            @AllocationIndex(2) public int bubbleBitmapWidth;
            @AllocationIndex(3) public int bubbleBitmapHeight;
            @AllocationIndex(4) public int scrollHandleId;
            @AllocationIndex(5) public int scrollHandleTextureWidth;
            @AllocationIndex(6) public int scrollHandleTextureHeight;
        }

        class State extends IntAllocation {
            State(RenderScript rs) {
                super(rs);
            }
            @AllocationIndex(0) public int iconCount;
            @AllocationIndex(1) public int scrollX;
            @AllocationIndex(2) public int flingTimeMs;
            @AllocationIndex(3) public int flingVelocityX;
            @AllocationIndex(4) public int adjustedDeceleration;
            @AllocationIndex(5) public int currentScrollX;
            @AllocationIndex(6) public int flingDuration;
            @AllocationIndex(7) public int flingEndPos;
            @AllocationIndex(8) public int startScrollX;
            @AllocationIndex(9) public int selectedIconIndex = -1;
            @AllocationIndex(10) public int selectedIconTexture;
            @AllocationIndex(11) public int visible;
        }

        public RolloRS() {
        }

        public void init(Resources res, int width, int height) {
            mRes = res;
            mWidth = width;
            mHeight = height;
            initGl();
            initData();
            initTouchState();
            initRs();
        }

        private void initGl() {
            Sampler.Builder sb = new Sampler.Builder(mRS);
            sb.setMin(Sampler.Value.LINEAR);//_MIP_LINEAR);
            sb.setMag(Sampler.Value.LINEAR);
            sb.setWrapS(Sampler.Value.CLAMP);
            sb.setWrapT(Sampler.Value.CLAMP);
            mSampler = sb.create();

            sb.setMin(Sampler.Value.NEAREST);
            sb.setMag(Sampler.Value.NEAREST);
            mSamplerText = sb.create();

            ProgramFragment.Builder dbg = new ProgramFragment.Builder(mRS, null, null);
            mPFDebug = dbg.create();
            mPFDebug.setName("PFDebug");

            ProgramFragment.Builder bf = new ProgramFragment.Builder(mRS, null, null);
            bf.setTexEnable(true, 0);
            bf.setTexEnvMode(ProgramFragment.EnvMode.MODULATE, 0);
            mPFImages = bf.create();
            mPFImages.setName("PF");
            mPFImages.bindSampler(mSampler, 0);

            bf.setTexEnvMode(ProgramFragment.EnvMode.MODULATE, 0);
            //mPFText = bf.create();
            mPFText = (new ProgramFragment.Builder(mRS, null, null)).create();
            mPFText.setName("PFText");
            mPFText.bindSampler(mSamplerText, 0);

            ProgramStore.Builder bs = new ProgramStore.Builder(mRS, null, null);
            bs.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            bs.setDitherEnable(false);
            bs.setDepthMask(true);
            bs.setBlendFunc(ProgramStore.BlendSrcFunc.SRC_ALPHA,
                            ProgramStore.BlendDstFunc.ONE_MINUS_SRC_ALPHA);
            mPSBackground = bs.create();
            mPSBackground.setName("PFS");

            bs.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            bs.setDepthMask(false);
            bs.setBlendFunc(ProgramStore.BlendSrcFunc.SRC_ALPHA,
                            ProgramStore.BlendDstFunc.ONE_MINUS_SRC_ALPHA);
            mPSText = bs.create();
            mPSText.setName("PFSText");

            mPVAlloc = new ProgramVertex.MatrixAllocation(mRS);
            mPVAlloc.setupProjectionNormalized(mWidth, mHeight);

            ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS, null, null);
            mPV = pvb.create();
            mPV.setName("PV");
            mPV.bindAllocation(mPVAlloc);

            mPVOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
            mPVOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

            pvb.setTextureMatrixEnable(true);
            mPVOrtho = pvb.create();
            mPVOrtho.setName("PVOrtho");
            mPVOrtho.bindAllocation(mPVOrthoAlloc);

            mRS.contextBindProgramVertex(mPV);

            mTouchXBorders = new int[Defines.COLUMNS_PER_PAGE+1];
            mAllocTouchXBorders = Allocation.createSized(mRS, Element.USER_I32,
                    mTouchXBorders.length);
            mAllocTouchXBorders.data(mTouchXBorders);

            mTouchYBorders = new int[Defines.ROWS_PER_PAGE+1];
            mAllocTouchYBorders = Allocation.createSized(mRS, Element.USER_I32,
                    mTouchYBorders.length);
            mAllocTouchYBorders.data(mTouchYBorders);

            Log.e("rs", "Done loading named");
        }
        
        private void initData() {
            mParams = new Params(mRS);
            mState = new State(mRS);

            final Utilities.BubbleText bubble = new Utilities.BubbleText(getContext());

            mParams.bubbleWidth = bubble.getBubbleWidth();
            mParams.bubbleHeight = bubble.getMaxBubbleHeight();
            mParams.bubbleBitmapWidth = bubble.getBitmapWidth();
            mParams.bubbleBitmapHeight = bubble.getBitmapHeight();

            mScrollHandle = Allocation.createFromBitmapResource(mRS, mRes,
                    R.drawable.all_apps_button_pow2, Element.RGBA_8888, false);
            mScrollHandle.uploadToTexture(0);
            mParams.scrollHandleId = mScrollHandle.getID();
            Log.d(TAG, "mParams.scrollHandleId=" + mParams.scrollHandleId);
            mParams.scrollHandleTextureWidth = 128;
            mParams.scrollHandleTextureHeight = 128;


            mParams.save();
            mState.save();

            mSelectionBitmap = Bitmap.createBitmap(Defines.ICON_WIDTH_PX, Defines.ICON_HEIGHT_PX,
                    Bitmap.Config.ARGB_8888);
            Bitmap selectionBitmap = mSelectionBitmap;
            Paint paint = new Paint();
            float radius = 12 * getContext().getResources().getDisplayMetrics().density;
            //paint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER));
            Canvas canvas = new Canvas(selectionBitmap);
            canvas.drawColor(0xffff0000);

            mSelectedIcon = Allocation.createFromBitmap(mRS, selectionBitmap,
                    Element.RGBA_8888, false);
            mSelectedIcon.uploadToTexture(0);

            mState.selectedIconTexture = mSelectedIcon.getID();

            Log.d(TAG, "initData calling mRollo.setApps");
            setApps(null);
        }

        private void initRs() {
            ScriptC.Builder sb = new ScriptC.Builder(mRS);
            sb.setScript(mRes, R.raw.rollo);
            sb.setRoot(true);
            sb.addDefines(Defines.class);
            mScript = sb.create();
            mScript.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);

            mScript.bindAllocation(mParams.getAllocation(), Defines.ALLOC_PARAMS);
            mScript.bindAllocation(mState.getAllocation(), Defines.ALLOC_STATE);
            mScript.bindAllocation(mAllocIconID, Defines.ALLOC_ICON_IDS);
            mScript.bindAllocation(mAllocLabelID, Defines.ALLOC_LABEL_IDS);
            mScript.bindAllocation(mAllocTouchXBorders, Defines.ALLOC_X_BORDERS);
            mScript.bindAllocation(mAllocTouchYBorders, Defines.ALLOC_Y_BORDERS);

            mRS.contextBindRootScript(mScript);
        }

        private void setApps(ArrayList<ApplicationInfo> list) {
            final int count = list != null ? list.size() : 0;
            mIcons = new Allocation[count];
            mIconIds = new int[count];
            mAllocIconID = Allocation.createSized(mRS, Element.USER_I32, count);

            mLabels = new Allocation[count];
            mLabelIds = new int[count];
            mAllocLabelID = Allocation.createSized(mRS, Element.USER_I32, count);

            Element ie8888 = Element.RGBA_8888;

            Utilities.BubbleText bubble = new Utilities.BubbleText(getContext());

            for (int i=0; i<count; i++) {
                final ApplicationInfo item = list.get(i);

                mIcons[i] = Allocation.createFromBitmap(mRS, item.iconBitmap,
                        Element.RGBA_8888, false);
                mLabels[i] = Allocation.createFromBitmap(mRS, item.titleBitmap,
                        Element.RGBA_8888, false);

                mIcons[i].uploadToTexture(0);
                mLabels[i].uploadToTexture(0);

                mIconIds[i] = mIcons[i].getID();
                mLabelIds[i] = mLabels[i].getID();
            }

            mAllocIconID.data(mIconIds);
            mAllocLabelID.data(mLabelIds);

            mState.iconCount = count;

            Log.d("AllAppsView", "mScript=" + mScript + " mAllocIconID=" + mAllocIconID);

            if (mScript != null) { // wtf
                mScript.bindAllocation(mAllocIconID, Defines.ALLOC_ICON_IDS);
                mScript.bindAllocation(mAllocLabelID, Defines.ALLOC_LABEL_IDS);
            }

            mState.save();
        }

        void initTouchState() {
            int width = getWidth();
            int height = getHeight();

            int iconsSize;
            if (width < height) {
                iconsSize = width;
            } else {
                iconsSize = height;
            }
            int cellHeight = iconsSize / Defines.ROWS_PER_PAGE;
            int cellWidth = iconsSize / Defines.COLUMNS_PER_PAGE;

            int centerY = (height / 2) - (int)(cellHeight * 0.35f);
            mTouchYBorders[0] = centerY - (int)(2.4f * cellHeight);
            mTouchYBorders[1] = centerY - (int)(1.15f * cellHeight);
            mTouchYBorders[2] = centerY;
            mTouchYBorders[3] = centerY + (int)(1.15f * cellHeight);;
            mTouchYBorders[4] = centerY + (int)(2.4f * cellHeight);

            mAllocTouchYBorders.data(mTouchYBorders);
            
            int centerX = (width / 2);
            mTouchXBorders[0] = centerX - (2 * cellWidth);
            mTouchXBorders[1] = centerX - (int)(0.83f * cellWidth);;
            mTouchXBorders[2] = centerX;
            mTouchXBorders[3] = centerX + (int)(0.83f * cellWidth);;
            mTouchXBorders[4] = centerX + (2 * cellWidth);

            mAllocTouchXBorders.data(mTouchXBorders);
        }

        int chooseTappedIcon(int x, int y, int scrollX, int currentPage) {
            int col = -1;
            int row = -1;

            for (int i=0; i<Defines.COLUMNS_PER_PAGE; i++) {
                if (x >= mTouchXBorders[i] && x < mTouchXBorders[i+1]) {
                    col = i;
                    break;
                }
            }
            for (int i=0; i<Defines.ROWS_PER_PAGE; i++) {
                if (y >= mTouchYBorders[i] && y < mTouchYBorders[i+1]) {
                    row = i;
                    break;
                }
            }

            if (row < 0 || col < 0) {
                return -1;
            }

            return (currentPage * Defines.ROWS_PER_PAGE * Defines.COLUMNS_PER_PAGE) 
                    + (row * Defines.ROWS_PER_PAGE) + col;
        }

        /**
         * You need to call save() on mState on your own after calling this.
         */
        void selectIcon(int x, int y, int scrollX, int currentPage) {
            int iconCount = mAllAppsList.size();
            int index = chooseTappedIcon(x, y, scrollX, currentPage);
            if (index < 0 || index >= iconCount) {
                mState.selectedIconIndex = -1;
                return;
            } else {
                mState.selectedIconIndex = index;
            }
        }

        /**
         * You need to call save() on mState on your own after calling this.
         */
        void clearSelectedIcon() {
            mState.selectedIconIndex = -1;
        }
    }
}


