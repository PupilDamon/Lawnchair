/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.launcher3;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.launcher3.DropTarget.DragObject;
import com.android.launcher3.FocusHelper.PagedViewKeyListener;
import com.android.launcher3.compat.AppWidgetManagerCompat;
import com.android.launcher3.util.Thunk;

import java.util.ArrayList;

/**
 * The Apps/Customize page that displays all the applications, widgets, and shortcuts.
 */
public class AppsCustomizePagedView extends PagedViewWithDraggableItems implements
        View.OnClickListener, DragSource,
        PagedViewWidget.ShortPressListener, LauncherTransitionable {
    static final String TAG = "AppsCustomizePagedView";

    private static Rect sTmpRect = new Rect();
    private static final int[] sTempPosArray = new int[2];

    /**
     * The different content types that this paged view can show.
     */
    public enum ContentType {
        Widgets
    }
    private ContentType mContentType = ContentType.Widgets;

    // Refs
    @Thunk Launcher mLauncher;
    private DragController mDragController;
    private final LayoutInflater mLayoutInflater;
    private final PackageManager mPackageManager;

    // Save and Restore
    private int mSaveInstanceStateItemIndex = -1;

    // Content
    private ArrayList<Object> mWidgets;

    // Caching
    private IconCache mIconCache;

    // Dimens
    private int mContentWidth, mContentHeight;
    @Thunk int mWidgetCountX, mWidgetCountY;
    private int mNumWidgetPages;

    private final PagedViewKeyListener mKeyListener = new PagedViewKeyListener();

    private Runnable mInflateWidgetRunnable = null;
    private Runnable mBindWidgetRunnable = null;
    static final int WIDGET_NO_CLEANUP_REQUIRED = -1;
    static final int WIDGET_PRELOAD_PENDING = 0;
    static final int WIDGET_BOUND = 1;
    static final int WIDGET_INFLATED = 2;
    int mWidgetCleanupState = WIDGET_NO_CLEANUP_REQUIRED;
    int mWidgetLoadingId = -1;
    PendingAddWidgetInfo mCreateWidgetInfo = null;
    private boolean mDraggingWidget = false;
    boolean mPageBackgroundsVisible = true;

    private Toast mWidgetInstructionToast;

    // Deferral of loading widget previews during launcher transitions
    private boolean mInTransition;

    WidgetPreviewLoader mWidgetPreviewLoader;

    private boolean mInBulkBind;
    private boolean mNeedToUpdatePageCountsAndInvalidateData;

    public AppsCustomizePagedView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLayoutInflater = LayoutInflater.from(context);
        mPackageManager = context.getPackageManager();
        mWidgets = new ArrayList<>();
        mIconCache = (LauncherAppState.getInstance()).getIconCache();

        // Save the default widget preview background
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AppsCustomizePagedView, 0, 0);
        mWidgetCountX = a.getInt(R.styleable.AppsCustomizePagedView_widgetCountX, 2);
        mWidgetCountY = a.getInt(R.styleable.AppsCustomizePagedView_widgetCountY, 2);
        a.recycle();

        // The padding on the non-matched dimension for the default widget preview icons
        // (top + bottom)
        mFadeInAdjacentScreens = false;

        // Unless otherwise specified this view is important for accessibility.
        if (getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        setSinglePageInViewport();
    }

    @Override
    protected void init() {
        super.init();
        mCenterPagesVertically = false;

        Context context = getContext();
        Resources r = context.getResources();
        setDragSlopeThreshold(r.getInteger(R.integer.config_appsCustomizeDragSlopeThreshold)/100f);
    }

    public void onFinishInflate() {
        super.onFinishInflate();

        LauncherAppState app = LauncherAppState.getInstance();
        DeviceProfile grid = app.getDynamicGrid().getDeviceProfile();
        setPadding(grid.edgeMarginPx, 2 * grid.edgeMarginPx,
                grid.edgeMarginPx, 2 * grid.edgeMarginPx);
    }

    void setWidgetsPageIndicatorPadding(int pageIndicatorHeight) {
        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), pageIndicatorHeight);
    }

    WidgetPreviewLoader getWidgetPreviewLoader() {
        if (mWidgetPreviewLoader == null) {
            mWidgetPreviewLoader = LauncherAppState.getInstance().getWidgetCache();
        }
        return mWidgetPreviewLoader;
    }

    /** Returns the item index of the center item on this page so that we can restore to this
     *  item index when we rotate. */
    private int getMiddleComponentIndexOnCurrentPage() {
        int i = -1;
        if (getPageCount() > 0) {
            int currentPage = getCurrentPage();
            if (mContentType == ContentType.Widgets) {
                PagedViewGridLayout layout = (PagedViewGridLayout) getPageAt(currentPage);
                int numItemsPerPage = mWidgetCountX * mWidgetCountY;
                int childCount = layout.getChildCount();
                if (childCount > 0) {
                    i = (currentPage * numItemsPerPage) + (childCount / 2);
                }
            } else {
                throw new RuntimeException("Invalid ContentType");
            }
        }
        return i;
    }

    /** Get the index of the item to restore to if we need to restore the current page. */
    int getSaveInstanceStateIndex() {
        if (mSaveInstanceStateItemIndex == -1) {
            mSaveInstanceStateItemIndex = getMiddleComponentIndexOnCurrentPage();
        }
        return mSaveInstanceStateItemIndex;
    }

    /** Returns the page in the current orientation which is expected to contain the specified
     *  item index. */
    int getPageForComponent(int index) {
        if (index < 0) return 0;

        int numItemsPerPage = mWidgetCountX * mWidgetCountY;
        return index / numItemsPerPage;
    }

    /** Restores the page for an item at the specified index */
    void restorePageForIndex(int index) {
        if (index < 0) return;
        mSaveInstanceStateItemIndex = index;
    }

    private void updatePageCounts() {
        mNumWidgetPages = (int) Math.ceil(mWidgets.size() /
                (float) (mWidgetCountX * mWidgetCountY));
    }

    protected void onDataReady(int width, int height) {
        updatePageCounts();

        // Force a measure to update recalculate the gaps
        mContentWidth = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        mContentHeight = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();

        final boolean hostIsTransitioning = getTabHost().isInTransition();
        int page = getPageForComponent(mSaveInstanceStateItemIndex);
        invalidatePageData(Math.max(0, page), hostIsTransitioning);
    }

    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        if (!isDataReady()) {
            if (!mWidgets.isEmpty()) {
                post(new Runnable() {
                    // This code triggers requestLayout so must be posted outside of the
                    // layout pass.
                    public void run() {
                        if (Utilities.isViewAttachedToWindow(AppsCustomizePagedView.this)) {
                            setDataIsReady();
                            onDataReady(getMeasuredWidth(), getMeasuredHeight());
                        }
                    }
                });
            }
        }
    }

    public void onPackagesUpdated(ArrayList<Object> widgetsAndShortcuts) {
        LauncherAppState app = LauncherAppState.getInstance();
        DeviceProfile grid = app.getDynamicGrid().getDeviceProfile();

        // Get the list of widgets and shortcuts
        mWidgets.clear();
        for (Object o : widgetsAndShortcuts) {
            if (o instanceof LauncherAppWidgetProviderInfo) {
                LauncherAppWidgetProviderInfo widget = (LauncherAppWidgetProviderInfo) o;
                if (!app.shouldShowAppOrWidgetProvider(widget.provider) && !widget.isCustomWidget) {
                    continue;
                }

                if (widget.minSpanX > 0 && widget.minSpanY > 0) {
                    // Ensure that all widgets we show can be added on a workspace of this size
                    int[] spanXY = Launcher.getSpanForWidget(mLauncher, widget);
                    int[] minSpanXY = Launcher.getMinSpanForWidget(mLauncher, widget);
                    int minSpanX = Math.min(spanXY[0], minSpanXY[0]);
                    int minSpanY = Math.min(spanXY[1], minSpanXY[1]);
                    if (minSpanX <= (int) grid.numColumns &&
                        minSpanY <= (int) grid.numRows) {
                        mWidgets.add(widget);
                    } else {
                        Log.e(TAG, "Widget " + widget.provider + " can not fit on this device (" +
                              widget.minWidth + ", " + widget.minHeight + ")");
                    }
                } else {
                    Log.e(TAG, "Widget " + widget.provider + " has invalid dimensions (" +
                          widget.minWidth + ", " + widget.minHeight + ")");
                }
            } else {
                // just add shortcuts
                mWidgets.add(o);
            }
        }

        updatePageCountsAndInvalidateData();
    }

    public void setBulkBind(boolean bulkBind) {
        if (bulkBind) {
            mInBulkBind = true;
        } else {
            mInBulkBind = false;
            if (mNeedToUpdatePageCountsAndInvalidateData) {
                updatePageCountsAndInvalidateData();
            }
        }
    }

    private void updatePageCountsAndInvalidateData() {
        if (mInBulkBind) {
            mNeedToUpdatePageCountsAndInvalidateData = true;
        } else {
            updatePageCounts();
            invalidateOnDataChange();
            mNeedToUpdatePageCountsAndInvalidateData = false;
        }
    }

    @Override
    public void onClick(View v) {
        // When we have exited all apps or are in transition, disregard clicks
        if (!mLauncher.isWidgetsViewVisible()
                || mLauncher.getWorkspace().isSwitchingState()
                || !(v instanceof PagedViewWidget)) return;

        // Let the user know that they have to long press to add a widget
        if (mWidgetInstructionToast != null) {
            mWidgetInstructionToast.cancel();
        }
        mWidgetInstructionToast = Toast.makeText(getContext(),R.string.long_press_widget_to_add,
            Toast.LENGTH_SHORT);
        mWidgetInstructionToast.show();
    }

    /*
     * PagedViewWithDraggableItems implementation
     */
    @Override
    protected void determineDraggingStart(android.view.MotionEvent ev) {
    }

    static Bundle getDefaultOptionsForWidget(Launcher launcher, PendingAddWidgetInfo info) {
        Bundle options = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            AppWidgetResizeFrame.getWidgetSizeRanges(launcher, info.spanX, info.spanY, sTmpRect);
            Rect padding = AppWidgetHostView.getDefaultPaddingForWidget(launcher,
                    info.componentName, null);

            float density = launcher.getResources().getDisplayMetrics().density;
            int xPaddingDips = (int) ((padding.left + padding.right) / density);
            int yPaddingDips = (int) ((padding.top + padding.bottom) / density);

            options = new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                    sTmpRect.left - xPaddingDips);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                    sTmpRect.top - yPaddingDips);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                    sTmpRect.right - xPaddingDips);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                    sTmpRect.bottom - yPaddingDips);
        }
        return options;
    }

    private void preloadWidget(final PendingAddWidgetInfo info) {
        final LauncherAppWidgetProviderInfo pInfo = info.info;
        final Bundle options = pInfo.isCustomWidget ? null :
                getDefaultOptionsForWidget(mLauncher, info);

        if (pInfo.configure != null) {
            info.bindOptions = options;
            return;
        }

        mWidgetCleanupState = WIDGET_PRELOAD_PENDING;
        mBindWidgetRunnable = new Runnable() {
            @Override
            public void run() {
                if (pInfo.isCustomWidget) {
                    mWidgetCleanupState = WIDGET_BOUND;
                    return;
                }

                mWidgetLoadingId = mLauncher.getAppWidgetHost().allocateAppWidgetId();
                if(AppWidgetManagerCompat.getInstance(mLauncher).bindAppWidgetIdIfAllowed(
                        mWidgetLoadingId, pInfo, options)) {
                    mWidgetCleanupState = WIDGET_BOUND;
                }

            }
        };
        post(mBindWidgetRunnable);

        mInflateWidgetRunnable = new Runnable() {
            @Override
            public void run() {
                if (mWidgetCleanupState != WIDGET_BOUND) {
                    return;
                }
                AppWidgetHostView hostView = mLauncher.getAppWidgetHost().createView(
                        getContext(), mWidgetLoadingId, pInfo);
                info.boundWidget = hostView;
                mWidgetCleanupState = WIDGET_INFLATED;
                hostView.setVisibility(INVISIBLE);
                int[] unScaledSize = mLauncher.getWorkspace().estimateItemSize(info, false);

                // We want the first widget layout to be the correct size. This will be important
                // for width size reporting to the AppWidgetManager.
                DragLayer.LayoutParams lp = new DragLayer.LayoutParams(unScaledSize[0],
                        unScaledSize[1]);
                lp.x = lp.y = 0;
                lp.customPosition = true;
                hostView.setLayoutParams(lp);
                mLauncher.getDragLayer().addView(hostView);
            }
        };
        post(mInflateWidgetRunnable);
    }

    @Override
    public void onShortPress(View v) {
        // We are anticipating a long press, and we use this time to load bind and instantiate
        // the widget. This will need to be cleaned up if it turns out no long press occurs.
        if (mCreateWidgetInfo != null) {
            // Just in case the cleanup process wasn't properly executed. This shouldn't happen.
            cleanupWidgetPreloading(false);
        }
        mCreateWidgetInfo = new PendingAddWidgetInfo((PendingAddWidgetInfo) v.getTag());
        preloadWidget(mCreateWidgetInfo);
    }

    private void cleanupWidgetPreloading(boolean widgetWasAdded) {
        if (!widgetWasAdded) {
            // If the widget was not added, we may need to do further cleanup.
            PendingAddWidgetInfo info = mCreateWidgetInfo;
            mCreateWidgetInfo = null;

            if (mWidgetCleanupState == WIDGET_PRELOAD_PENDING) {
                // We never did any preloading, so just remove pending callbacks to do so
                removeCallbacks(mBindWidgetRunnable);
                removeCallbacks(mInflateWidgetRunnable);
            } else if (mWidgetCleanupState == WIDGET_BOUND) {
                 // Delete the widget id which was allocated
                if (mWidgetLoadingId != -1 && !info.isCustomWidget()) {
                    mLauncher.getAppWidgetHost().deleteAppWidgetId(mWidgetLoadingId);
                }

                // We never got around to inflating the widget, so remove the callback to do so.
                removeCallbacks(mInflateWidgetRunnable);
            } else if (mWidgetCleanupState == WIDGET_INFLATED) {
                // Delete the widget id which was allocated
                if (mWidgetLoadingId != -1 && !info.isCustomWidget()) {
                    mLauncher.getAppWidgetHost().deleteAppWidgetId(mWidgetLoadingId);
                }

                // The widget was inflated and added to the DragLayer -- remove it.
                AppWidgetHostView widget = info.boundWidget;
                mLauncher.getDragLayer().removeView(widget);
            }
        }
        mWidgetCleanupState = WIDGET_NO_CLEANUP_REQUIRED;
        mWidgetLoadingId = -1;
        mCreateWidgetInfo = null;
        PagedViewWidget.resetShortPressTarget();
    }

    @Override
    public void cleanUpShortPress(View v) {
        if (!mDraggingWidget) {
            cleanupWidgetPreloading(false);
        }
    }

    private boolean beginDraggingWidget(PagedViewWidget v) {
        mDraggingWidget = true;
        // Get the widget preview as the drag representation
        ImageView image = (ImageView) v.findViewById(R.id.widget_preview);
        PendingAddItemInfo createItemInfo = (PendingAddItemInfo) v.getTag();

        // If the ImageView doesn't have a drawable yet, the widget preview hasn't been loaded and
        // we abort the drag.
        if (image.getDrawable() == null) {
            mDraggingWidget = false;
            return false;
        }

        // Compose the drag image
        Bitmap preview;
        Bitmap outline;
        float scale = 1f;
        Point previewPadding = null;

        if (createItemInfo instanceof PendingAddWidgetInfo) {
            // This can happen in some weird cases involving multi-touch. We can't start dragging
            // the widget if this is null, so we break out.
            if (mCreateWidgetInfo == null) {
                return false;
            }

            PendingAddWidgetInfo createWidgetInfo = mCreateWidgetInfo;
            createItemInfo = createWidgetInfo;
            int[] size = mLauncher.getWorkspace().estimateItemSize(createWidgetInfo, true);

            FastBitmapDrawable previewDrawable = (FastBitmapDrawable) image.getDrawable();
            float minScale = 1.25f;
            int maxWidth = Math.min((int) (previewDrawable.getIntrinsicWidth() * minScale), size[0]);

            int[] previewSizeBeforeScale = new int[1];
            preview = getWidgetPreviewLoader().generateWidgetPreview(createWidgetInfo.info,
                    maxWidth, null, previewSizeBeforeScale);
            // Compare the size of the drag preview to the preview in the AppsCustomize tray
            int previewWidthInAppsCustomize = Math.min(previewSizeBeforeScale[0],
                    v.getActualItemWidth());
            scale = previewWidthInAppsCustomize / (float) preview.getWidth();

            // The bitmap in the AppsCustomize tray is always the the same size, so there
            // might be extra pixels around the preview itself - this accounts for that
            if (previewWidthInAppsCustomize < previewDrawable.getIntrinsicWidth()) {
                int padding =
                        (previewDrawable.getIntrinsicWidth() - previewWidthInAppsCustomize) / 2;
                previewPadding = new Point(padding, 0);
            }
        } else {
            PendingAddShortcutInfo createShortcutInfo = (PendingAddShortcutInfo) v.getTag();
            Drawable icon = mIconCache.getFullResIcon(createShortcutInfo.shortcutActivityInfo);
            preview = Utilities.createIconBitmap(icon, mLauncher);
            createItemInfo.spanX = createItemInfo.spanY = 1;
        }

        // Don't clip alpha values for the drag outline if we're using the default widget preview
        boolean clipAlpha = !(createItemInfo instanceof PendingAddWidgetInfo &&
                (((PendingAddWidgetInfo) createItemInfo).previewImage == 0));

        // Save the preview for the outline generation, then dim the preview
        outline = Bitmap.createScaledBitmap(preview, preview.getWidth(), preview.getHeight(),
                false);

        // Start the drag
        mLauncher.lockScreenOrientation();
        mLauncher.getWorkspace().onDragStartedWithItem(createItemInfo, outline, clipAlpha);
        mDragController.startDrag(image, preview, this, createItemInfo,
                DragController.DRAG_ACTION_COPY, previewPadding, scale);
        outline.recycle();
        preview.recycle();
        return true;
    }

    @Override
    protected boolean beginDragging(final View v) {
        if (!super.beginDragging(v)) return false;

        if (v instanceof PagedViewWidget) {
            if (!beginDraggingWidget((PagedViewWidget) v)) {
                return false;
            }
        } else {
            Log.e(TAG, "Unexpected dragging view: " + v);
        }

        // We delay entering spring-loaded mode slightly to make sure the UI
        // thready is free of any work.
        postDelayed(new Runnable() {
            @Override
            public void run() {
                // We don't enter spring-loaded mode if the drag has been cancelled
                if (mLauncher.getDragController().isDragging()) {
                    // Go into spring loaded mode (must happen before we startDrag())
                    mLauncher.enterSpringLoadedDragMode();
                }
            }
        }, 150);

        return true;
    }

    /**
     * Clean up after dragging.
     *
     * @param target where the item was dragged to (can be null if the item was flung)
     */
    private void endDragging(View target, boolean isFlingToDelete, boolean success) {
        if (isFlingToDelete || !success || (target != mLauncher.getWorkspace() &&
                !(target instanceof DeleteDropTarget) && !(target instanceof Folder))) {
            // Exit spring loaded mode if we have not successfully dropped or have not handled the
            // drop in Workspace
            mLauncher.exitSpringLoadedDragModeDelayed(true,
                    Launcher.EXIT_SPRINGLOADED_MODE_SHORT_TIMEOUT, null);
            mLauncher.unlockScreenOrientation(false);
        } else {
            mLauncher.unlockScreenOrientation(false);
        }
    }

    @Override
    public View getContent() {
        if (getChildCount() > 0) {
            return getChildAt(0);
        }
        return null;
    }

    @Override
    public void onLauncherTransitionPrepare(Launcher l, boolean animated, boolean toWorkspace) {
        mInTransition = true;
        if (toWorkspace) {
            cancelAllTasks(false);
        }
    }

    @Override
    public void onLauncherTransitionStart(Launcher l, boolean animated, boolean toWorkspace) {
    }

    @Override
    public void onLauncherTransitionStep(Launcher l, float t) {
    }

    @Override
    public void onLauncherTransitionEnd(Launcher l, boolean animated, boolean toWorkspace) {
        mInTransition = false;
        mForceDrawAllChildrenNextFrame = !toWorkspace;
        if (!toWorkspace) {
            loadPreviewsForPage(getNextPage());
        }
    }

    @Override
    public void onDropCompleted(View target, DragObject d, boolean isFlingToDelete,
            boolean success) {
        // Return early and wait for onFlingToDeleteCompleted if this was the result of a fling
        if (isFlingToDelete) return;

        endDragging(target, false, success);

        // Display an error message if the drag failed due to there not being enough space on the
        // target layout we were dropping on.
        if (!success) {
            boolean showOutOfSpaceMessage = false;
            if (target instanceof Workspace) {
                int currentScreen = mLauncher.getCurrentWorkspaceScreen();
                Workspace workspace = (Workspace) target;
                CellLayout layout = (CellLayout) workspace.getChildAt(currentScreen);
                ItemInfo itemInfo = (ItemInfo) d.dragInfo;
                if (layout != null) {
                    layout.calculateSpans(itemInfo);
                    showOutOfSpaceMessage =
                            !layout.findCellForSpan(null, itemInfo.spanX, itemInfo.spanY);
                }
            }
            if (showOutOfSpaceMessage) {
                mLauncher.showOutOfSpaceMessage(false);
            }

            d.deferDragViewCleanupPostAnimation = false;
        }
        cleanupWidgetPreloading(success);
        mDraggingWidget = false;
    }

    @Override
    public void onFlingToDeleteCompleted() {
        // We just dismiss the drag when we fling, so cleanup here
        endDragging(null, true, true);
        cleanupWidgetPreloading(false);
        mDraggingWidget = false;
    }

    @Override
    public boolean supportsFlingToDelete() {
        return true;
    }

    @Override
    public boolean supportsAppInfoDropTarget() {
        return true;
    }

    @Override
    public boolean supportsDeleteDropTarget() {
        return false;
    }

    @Override
    public float getIntrinsicIconScaleFactor() {
        LauncherAppState app = LauncherAppState.getInstance();
        DeviceProfile grid = app.getDynamicGrid().getDeviceProfile();
        return (float) grid.allAppsIconSizePx / grid.iconSizePx;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAllTasks(true);
    }

    @Override
    public void trimMemory() {
        super.trimMemory();
        cancelAllTasks(true);
    }

    private void cancelAllTasks(boolean clearCompletedTasks) {
        for (int page = getPageCount() - 1; page >= 0; page--) {
            final PagedViewGridLayout layout = (PagedViewGridLayout) getPageAt(page);
            if (layout != null) {
                for (int i = 0; i < layout.getChildCount(); i++) {
                    ((PagedViewWidget) layout.getChildAt(i)).deletePreview(clearCompletedTasks);
                }
            }
        }
    }


    public void setContentType(ContentType type) {
        // Widgets appear to be cleared every time you leave, always force invalidate for them
        if (mContentType != type || type == ContentType.Widgets) {
            int page = (mContentType != type) ? 0 : getCurrentPage();
            mContentType = type;
            invalidatePageData(page, true);
        }
    }

    public ContentType getContentType() {
        return mContentType;
    }

    public void setPageBackgroundsVisible(boolean visible) {
        mPageBackgroundsVisible = visible;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; ++i) {
            Drawable bg = getChildAt(i).getBackground();
            if (bg != null) {
                bg.setAlpha(visible ? 255 : 0);
            }
        }
    }

    /*
     * Widgets PagedView implementation
     */
    private void setupPage(PagedViewGridLayout layout) {
        // Note: We force a measure here to get around the fact that when we do layout calculations
        // immediately after syncing, we don't have a proper width.
        int widthSpec = MeasureSpec.makeMeasureSpec(mContentWidth, MeasureSpec.AT_MOST);
        int heightSpec = MeasureSpec.makeMeasureSpec(mContentHeight, MeasureSpec.AT_MOST);

        Drawable bg = getContext().getResources().getDrawable(R.drawable.quantum_panel_dark);
        if (bg != null) {
            bg.setAlpha(mPageBackgroundsVisible ? 255 : 0);
            layout.setBackground(bg);
        }
        layout.measure(widthSpec, heightSpec);
    }

    public void syncWidgetPageItems(final int page, final boolean immediate) {
        int numItemsPerPage = mWidgetCountX * mWidgetCountY;

        final PagedViewGridLayout layout = (PagedViewGridLayout) getPageAt(page);

        // Calculate the dimensions of each cell we are giving to each widget
        final ArrayList<Object> items = new ArrayList<Object>();
        int contentWidth = mContentWidth - layout.getPaddingLeft() - layout.getPaddingRight();
        final int cellWidth = contentWidth / mWidgetCountX;
        int contentHeight = mContentHeight - layout.getPaddingTop() - layout.getPaddingBottom();

        final int cellHeight = contentHeight / mWidgetCountY;

        // Prepare the set of widgets to load previews for in the background
        int offset = page * numItemsPerPage;
        for (int i = offset; i < Math.min(offset + numItemsPerPage, mWidgets.size()); ++i) {
            items.add(mWidgets.get(i));
        }

        // Prepopulate the pages with the other widget info, and fill in the previews later
        layout.setColumnCount(layout.getCellCountX());
        for (int i = 0; i < items.size(); ++i) {
            Object rawInfo = items.get(i);
            PendingAddItemInfo createItemInfo = null;
            PagedViewWidget widget = (PagedViewWidget) mLayoutInflater.inflate(
                    R.layout.apps_customize_widget, layout, false);

            if (rawInfo instanceof LauncherAppWidgetProviderInfo) {
                // Fill in the widget information
                LauncherAppWidgetProviderInfo info = (LauncherAppWidgetProviderInfo) rawInfo;
                createItemInfo = new PendingAddWidgetInfo(info, null);

                widget.applyFromAppWidgetProviderInfo(info, -1, getWidgetPreviewLoader());
                widget.setTag(createItemInfo);
                widget.setShortPressListener(this);
            } else if (rawInfo instanceof ResolveInfo) {
                // Fill in the shortcuts information
                ResolveInfo info = (ResolveInfo) rawInfo;
                createItemInfo = new PendingAddShortcutInfo(info.activityInfo);
                createItemInfo.itemType = LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT;
                createItemInfo.componentName = new ComponentName(info.activityInfo.packageName,
                        info.activityInfo.name);
                widget.applyFromResolveInfo(mPackageManager, info, getWidgetPreviewLoader());
                widget.setTag(createItemInfo);
            }

            widget.setOnClickListener(this);
            widget.setOnLongClickListener(this);
            widget.setOnTouchListener(this);
            widget.setOnKeyListener(mKeyListener);

            // Layout each widget
            int ix = i % mWidgetCountX;
            int iy = i / mWidgetCountX;

            if (ix > 0) {
                View border = widget.findViewById(R.id.left_border);
                border.setVisibility(View.VISIBLE);
            }
            if (ix < mWidgetCountX - 1) {
                View border = widget.findViewById(R.id.right_border);
                border.setVisibility(View.VISIBLE);
            }

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(iy, GridLayout.START),
                    GridLayout.spec(ix, GridLayout.TOP));
            lp.width = cellWidth;
            lp.height = cellHeight;
            lp.setGravity(Gravity.TOP | Gravity.START);
            layout.addView(widget, lp);
        }

        if (immediate && !mInTransition) {
            loadPreviewsForPage(page);
        }
    }

    private void loadPreviewsForPage(int page) {
        final PagedViewGridLayout layout = (PagedViewGridLayout) getPageAt(page);

        if (layout != null) {
            for (int i = 0; i < layout.getChildCount(); i++) {
                ((PagedViewWidget) layout.getChildAt(i)).ensurePreview();
            }
        }
    }

    @Override
    public void syncPages() {
        disablePagedViewAnimations();

        removeAllViews();
        cancelAllTasks(true);

        Context context = getContext();
        if (mContentType == ContentType.Widgets) {
            for (int j = 0; j < mNumWidgetPages; ++j) {
                PagedViewGridLayout layout = new PagedViewGridLayout(context, mWidgetCountX,
                        mWidgetCountY);
                setupPage(layout);
                addView(layout, new PagedView.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT));
            }
        } else {
            throw new RuntimeException("Invalid ContentType");
        }

        enablePagedViewAnimations();
    }

    @Override
    public void syncPageItems(int page, boolean immediate) {
        if (mContentType == ContentType.Widgets) {
            syncWidgetPageItems(page, immediate);
        } else {
            Log.e(TAG, "Unexpected ContentType");
        }
    }

    // We want our pages to be z-ordered such that the further a page is to the left, the higher
    // it is in the z-order. This is important to insure touch events are handled correctly.
    View getPageAt(int index) {
        return getChildAt(indexToPage(index));
    }

    @Override
    protected int indexToPage(int index) {
        return getChildCount() - index - 1;
    }

    // In apps customize, we have a scrolling effect which emulates pulling cards off of a stack.
    @Override
    protected void screenScrolled(int screenCenter) {
        super.screenScrolled(screenCenter);
        enableHwLayersOnVisiblePages();
    }

    private void enableHwLayersOnVisiblePages() {
        final int screenCount = getChildCount();

        getVisiblePages(mTempVisiblePagesRange);
        int leftScreen = mTempVisiblePagesRange[0];
        int rightScreen = mTempVisiblePagesRange[1];
        int forceDrawScreen = -1;
        if (leftScreen == rightScreen) {
            // make sure we're caching at least two pages always
            if (rightScreen < screenCount - 1) {
                rightScreen++;
                forceDrawScreen = rightScreen;
            } else if (leftScreen > 0) {
                leftScreen--;
                forceDrawScreen = leftScreen;
            }
        } else {
            forceDrawScreen = leftScreen + 1;
        }

        for (int i = 0; i < screenCount; i++) {
            final View layout = (View) getPageAt(i);
            if (!(leftScreen <= i && i <= rightScreen &&
                    (i == forceDrawScreen || shouldDrawChild(layout)))) {
                layout.setLayerType(LAYER_TYPE_NONE, null);
            }
        }

        for (int i = 0; i < screenCount; i++) {
            final View layout = (View) getPageAt(i);

            if (leftScreen <= i && i <= rightScreen &&
                    (i == forceDrawScreen || shouldDrawChild(layout))) {
                if (layout.getLayerType() != LAYER_TYPE_HARDWARE) {
                    layout.setLayerType(LAYER_TYPE_HARDWARE, null);
                }
            }
        }
    }

    protected void overScroll(float amount) {
        dampedOverScroll(amount);
    }

    /**
     * Used by the parent to get the content width to set the tab bar to
     * @return
     */
    public int getPageContentWidth() {
        return mContentWidth;
    }

    @Override
    protected void onPageEndMoving() {
        super.onPageEndMoving();
        mForceDrawAllChildrenNextFrame = true;
        // We reset the save index when we change pages so that it will be recalculated on next
        // rotation
        mSaveInstanceStateItemIndex = -1;
    }

    @Override
    protected void onPageBeginMoving() {
        super.onPageBeginMoving();
        if (!mInTransition) {
            getVisiblePages(sTempPosArray);
            for (int i = sTempPosArray[0]; i <= sTempPosArray[1]; i++) {
                loadPreviewsForPage(i);
            }
        }
    }

    /*
     * AllAppsView implementation
     */
    public void setup(Launcher launcher, DragController dragController) {
        mLauncher = launcher;
        mDragController = dragController;
    }

    /**
     * We should call thise method whenever the core data changes (mWidgets) so that we can
     * appropriately determine when to invalidate the PagedView page data.  In cases where the data
     * has yet to be set, we can requestLayout() and wait for onDataReady() to be called in the
     * next onMeasure() pass, which will trigger an invalidatePageData() itself.
     */
    private void invalidateOnDataChange() {
        if (!isDataReady()) {
            // The next layout pass will trigger data-ready if both widgets and apps are set, so
            // request a layout to trigger the page data when ready.
            requestLayout();
        } else {
            cancelAllTasks(false);
            invalidatePageData();
        }
    }

    public void reset() {
        // If we have reset, then we should not continue to restore the previous state
        mSaveInstanceStateItemIndex = -1;

        if (mContentType != ContentType.Widgets) {
            setContentType(ContentType.Widgets);
        }

        if (mCurrentPage != 0) {
            invalidatePageData(0);
        }
    }

    private AppsCustomizeTabHost getTabHost() {
        return (AppsCustomizeTabHost) mLauncher.findViewById(R.id.apps_customize_pane);
    }

    public void dumpState() {
        // TODO: Dump information related to current list of Applications, Widgets, etc.
        dumpAppWidgetProviderInfoList(TAG, "mWidgets", mWidgets);
    }

    private void dumpAppWidgetProviderInfoList(String tag, String label,
            ArrayList<Object> list) {
        Log.d(tag, label + " size=" + list.size());
        for (Object i: list) {
            if (i instanceof AppWidgetProviderInfo) {
                AppWidgetProviderInfo info = (AppWidgetProviderInfo) i;
                Log.d(tag, "   label=\"" + info.label + "\" previewImage=" + info.previewImage
                        + " resizeMode=" + info.resizeMode + " configure=" + info.configure
                        + " initialLayout=" + info.initialLayout
                        + " minWidth=" + info.minWidth + " minHeight=" + info.minHeight);
            } else if (i instanceof ResolveInfo) {
                ResolveInfo info = (ResolveInfo) i;
                Log.d(tag, "   label=\"" + info.loadLabel(mPackageManager) + "\" icon="
                        + info.icon);
            }
        }
    }

    public void surrender() {
        // TODO: If we are in the middle of any process (ie. for holographic outlines, etc) we
        // should stop this now.

        // Stop all background tasks
        cancelAllTasks(true);
    }

    /*
     * We load an extra page on each side to prevent flashes from scrolling and loading of the
     * widget previews in the background with the AsyncTasks.
     */
    final static int sLookBehindPageCount = 2;
    final static int sLookAheadPageCount = 2;
    protected int getAssociatedLowerPageBound(int page) {
        final int count = getChildCount();
        int windowSize = Math.min(count, sLookBehindPageCount + sLookAheadPageCount + 1);
        int windowMinIndex = Math.max(Math.min(page - sLookBehindPageCount, count - windowSize), 0);
        return windowMinIndex;
    }
    protected int getAssociatedUpperPageBound(int page) {
        final int count = getChildCount();
        int windowSize = Math.min(count, sLookBehindPageCount + sLookAheadPageCount + 1);
        int windowMaxIndex = Math.min(Math.max(page + sLookAheadPageCount, windowSize - 1),
                count - 1);
        return windowMaxIndex;
    }

    protected String getCurrentPageDescription() {
        int page = (mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage;
        int stringId = R.string.default_scroll_format;
        int count = 0;

        if (mContentType == ContentType.Widgets) {
            stringId = R.string.apps_customize_widgets_scroll_format;
            count = mNumWidgetPages;
        } else {
            throw new RuntimeException("Invalid ContentType");
        }

        return String.format(getContext().getString(stringId), page + 1, count);
    }
}
