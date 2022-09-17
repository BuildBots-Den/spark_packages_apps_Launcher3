/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.launcher3.model;

import static com.android.launcher3.provider.LauncherDbUtils.dropTable;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Point;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.Utilities;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.graphics.LauncherPreviewRenderer;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.pm.InstallSessionHelper;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;
import com.android.launcher3.util.GridOccupancy;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo;
import com.android.launcher3.widget.WidgetManagerHelper;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This class takes care of shrinking the workspace (by maximum of one row and one column), as a
 * result of restoring from a larger device or device density change.
 */
public class GridSizeMigrationTaskV2 {

    private static final String TAG = "GridSizeMigrationTaskV2";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final SQLiteDatabase mDb;
    private final DbReader mSrcReader;
    private final DbReader mDestReader;

    private final List<DbEntry> mHotseatItems;
    private final List<DbEntry> mWorkspaceItems;

    private final List<DbEntry> mHotseatDiff;
    private final List<DbEntry> mWorkspaceDiff;

    private final int mDestHotseatSize;
    private final int mTrgX, mTrgY;

    @VisibleForTesting
    protected GridSizeMigrationTaskV2(Context context, SQLiteDatabase db, DbReader srcReader,
            DbReader destReader, int destHotseatSize, Point targetSize) {
        mContext = context;
        mDb = db;
        mSrcReader = srcReader;
        mDestReader = destReader;

        mHotseatItems = destReader.loadHotseatEntries();
        mWorkspaceItems = destReader.loadAllWorkspaceEntries();

        mHotseatDiff = calcDiff(mSrcReader.loadHotseatEntries(), mHotseatItems);
        mWorkspaceDiff = calcDiff(mSrcReader.loadAllWorkspaceEntries(), mWorkspaceItems);
        mDestHotseatSize = destHotseatSize;

        mTrgX = targetSize.x;
        mTrgY = targetSize.y;
    }

    /**
     * Check given a new IDP, if migration is necessary.
     */
    public static boolean needsToMigrate(Context context, InvariantDeviceProfile idp) {
        return needsToMigrate(new DeviceGridState(context), new DeviceGridState(idp));
    }

    private static boolean needsToMigrate(
            DeviceGridState srcDeviceState, DeviceGridState destDeviceState) {
        boolean needsToMigrate = !destDeviceState.isCompatible(srcDeviceState);
        if (needsToMigrate) {
            Log.i(TAG, "Migration is needed. destDeviceState: " + destDeviceState
                    + ", srcDeviceState: " + srcDeviceState);
        }
        return needsToMigrate;
    }

    /** See {@link #migrateGridIfNeeded(Context, InvariantDeviceProfile)} */
    public static boolean migrateGridIfNeeded(Context context) {
        if (context instanceof LauncherPreviewRenderer.PreviewContext) {
            return true;
        }
        return migrateGridIfNeeded(context, null);
    }

    /**
     * When migrating the grid for preview, we copy the table
     * {@link LauncherSettings.Favorites#TABLE_NAME} into
     * {@link LauncherSettings.Favorites#PREVIEW_TABLE_NAME}, run grid size migration from the
     * former to the later, then use the later table for preview.
     *
     * Similarly when doing the actual grid migration, the former grid option's table
     * {@link LauncherSettings.Favorites#TABLE_NAME} is copied into the new grid option's
     * {@link LauncherSettings.Favorites#TMP_TABLE}, we then run the grid size migration algorithm
     * to migrate the later to the former, and load the workspace from the default
     * {@link LauncherSettings.Favorites#TABLE_NAME}.
     *
     * @return false if the migration failed.
     */
    public static boolean migrateGridIfNeeded(Context context, InvariantDeviceProfile idp) {
        boolean migrateForPreview = idp != null;
        if (!migrateForPreview) {
            idp = LauncherAppState.getIDP(context);
        }

        DeviceGridState srcDeviceState = new DeviceGridState(context);
        DeviceGridState destDeviceState = new DeviceGridState(idp);
        if (!needsToMigrate(srcDeviceState, destDeviceState)) {
            return true;
        }

        HashSet<String> validPackages = getValidPackages(context);

        if (migrateForPreview) {
            if (!LauncherSettings.Settings.call(
                    context.getContentResolver(),
                    LauncherSettings.Settings.METHOD_PREP_FOR_PREVIEW,
                    destDeviceState.getDbFile()).getBoolean(
                    LauncherSettings.Settings.EXTRA_VALUE)) {
                return false;
            }
        } else if (!LauncherSettings.Settings.call(
                context.getContentResolver(),
                LauncherSettings.Settings.METHOD_UPDATE_CURRENT_OPEN_HELPER,
                destDeviceState.getDbFile()).getBoolean(
                LauncherSettings.Settings.EXTRA_VALUE)) {
            return false;
        }

        long migrationStartTime = System.currentTimeMillis();
        try (SQLiteTransaction t = (SQLiteTransaction) LauncherSettings.Settings.call(
                context.getContentResolver(),
                LauncherSettings.Settings.METHOD_NEW_TRANSACTION).getBinder(
                LauncherSettings.Settings.EXTRA_VALUE)) {

            DbReader srcReader = new DbReader(t.getDb(),
                    migrateForPreview ? LauncherSettings.Favorites.TABLE_NAME
                            : LauncherSettings.Favorites.TMP_TABLE,
                    context, validPackages);
            DbReader destReader = new DbReader(t.getDb(),
                    migrateForPreview ? LauncherSettings.Favorites.PREVIEW_TABLE_NAME
                            : LauncherSettings.Favorites.TABLE_NAME,
                    context, validPackages);

            Point targetSize = new Point(destDeviceState.getColumns(), destDeviceState.getRows());
            GridSizeMigrationTaskV2 task = new GridSizeMigrationTaskV2(context, t.getDb(),
                    srcReader, destReader, destDeviceState.getNumHotseat(), targetSize);
            task.migrate(srcDeviceState, destDeviceState);

            if (!migrateForPreview) {
                dropTable(t.getDb(), LauncherSettings.Favorites.TMP_TABLE);
            }

            t.commit();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error during grid migration", e);

            return false;
        } finally {
            Log.v(TAG, "Workspace migration completed in "
                    + (System.currentTimeMillis() - migrationStartTime));

            if (!migrateForPreview) {
                // Save current configuration, so that the migration does not run again.
                destDeviceState.writeToPrefs(context);
            }
        }
    }

    @VisibleForTesting
    protected boolean migrate(DeviceGridState srcDeviceState, DeviceGridState destDeviceState) {
        if (mHotseatDiff.isEmpty() && mWorkspaceDiff.isEmpty()) {
            return false;
        }

        // Sort the items by the reading order.
        Collections.sort(mHotseatDiff);
        Collections.sort(mWorkspaceDiff);

        // Migrate hotseat
        HotseatPlacementSolution hotseatSolution = new HotseatPlacementSolution(mDb, mSrcReader,
                mDestReader, mContext, mDestHotseatSize, mHotseatItems, mHotseatDiff);
        hotseatSolution.find();

        // Migrate workspace.
        // First we create a collection of the screens
        List<Integer> screens = new ArrayList<>();
        for (int screenId = 0; screenId <= mDestReader.mLastScreenId; screenId++) {
            screens.add(screenId);
        }

        boolean preservePages = false;
        if (screens.isEmpty() && FeatureFlags.ENABLE_NEW_MIGRATION_LOGIC.get()) {
            preservePages = destDeviceState.compareTo(srcDeviceState) >= 0
                    && destDeviceState.getColumns() - srcDeviceState.getColumns() <= 2;
        }

        // Then we place the items on the screens
        for (int screenId : screens) {
            if (DEBUG) {
                Log.d(TAG, "Migrating " + screenId);
            }
            GridPlacementSolution workspaceSolution = new GridPlacementSolution(mDb, mSrcReader,
                    mDestReader, mContext, screenId, mTrgX, mTrgY, mWorkspaceDiff, false);
            workspaceSolution.find();
            if (mWorkspaceDiff.isEmpty()) {
                break;
            }
        }

        // In case the new grid is smaller, there might be some leftover items that don't fit on
        // any of the screens, in this case we add them to new screens until all of them are placed.
        int screenId = mDestReader.mLastScreenId + 1;
        while (!mWorkspaceDiff.isEmpty()) {
            GridPlacementSolution workspaceSolution = new GridPlacementSolution(mDb, mSrcReader,
                    mDestReader, mContext, screenId, mTrgX, mTrgY, mWorkspaceDiff,
                    preservePages);
            workspaceSolution.find();
            screenId++;
        }

        return true;
    }

    /** Return what's in the src but not in the dest */
    private static List<DbEntry> calcDiff(List<DbEntry> src, List<DbEntry> dest) {
        Map<String, Integer> destIdSet = new HashMap<>();
        for (DbEntry entry : dest) {
            String entryID = entry.getEntryMigrationId();
            if (destIdSet.containsKey(entryID)) {
                destIdSet.put(entryID, destIdSet.get(entryID) + 1);
            } else {
                destIdSet.put(entryID, 1);
            }
        }
        List<DbEntry> diff = new ArrayList<>();
        for (DbEntry entry : src) {
            String entryID = entry.getEntryMigrationId();
            if (destIdSet.containsKey(entryID)) {
                Integer count = destIdSet.get(entryID);
                if (count <= 0) {
                    diff.add(entry);
                    destIdSet.remove(entryID);
                } else {
                    destIdSet.put(entryID, count - 1);
                }
            } else {
                diff.add(entry);
            }
        }
        return diff;
    }

    private static void insertEntryInDb(SQLiteDatabase db, Context context, DbEntry entry,
            String srcTableName, String destTableName) {
        int id = copyEntryAndUpdate(db, context, entry, srcTableName, destTableName);

        if (entry.itemType == LauncherSettings.Favorites.ITEM_TYPE_FOLDER) {
            for (Set<Integer> itemIds : entry.mFolderItems.values()) {
                for (int itemId : itemIds) {
                    copyEntryAndUpdate(db, context, itemId, id, srcTableName, destTableName);
                }
            }
        }
    }

    private static int copyEntryAndUpdate(SQLiteDatabase db, Context context,
            DbEntry entry, String srcTableName, String destTableName) {
        return copyEntryAndUpdate(db, context, entry, -1, -1, srcTableName, destTableName);
    }

    private static int copyEntryAndUpdate(SQLiteDatabase db, Context context,
            int id, int folderId, String srcTableName, String destTableName) {
        return copyEntryAndUpdate(db, context, null, id, folderId, srcTableName, destTableName);
    }

    private static int copyEntryAndUpdate(SQLiteDatabase db, Context context,
            DbEntry entry, int id, int folderId, String srcTableName, String destTableName) {
        int newId = -1;
        Cursor c = db.query(srcTableName, null,
                LauncherSettings.Favorites._ID + " = '" + (entry != null ? entry.id : id) + "'",
                null, null, null, null);
        while (c.moveToNext()) {
            ContentValues values = new ContentValues();
            DatabaseUtils.cursorRowToContentValues(c, values);
            if (entry != null) {
                entry.updateContentValues(values);
            } else {
                values.put(LauncherSettings.Favorites.CONTAINER, folderId);
            }
            newId = LauncherSettings.Settings.call(context.getContentResolver(),
                    LauncherSettings.Settings.METHOD_NEW_ITEM_ID).getInt(
                    LauncherSettings.Settings.EXTRA_VALUE);
            values.put(LauncherSettings.Favorites._ID, newId);
            db.insert(destTableName, null, values);
        }
        c.close();
        return newId;
    }

    private static void removeEntryFromDb(SQLiteDatabase db, String tableName, IntArray entryIds) {
        db.delete(tableName,
                Utilities.createDbSelectionQuery(LauncherSettings.Favorites._ID, entryIds), null);
    }

    private static HashSet<String> getValidPackages(Context context) {
        // Initialize list of valid packages. This contain all the packages which are already on
        // the device and packages which are being installed. Any item which doesn't belong to
        // this set is removed.
        // Since the loader removes such items anyway, removing these items here doesn't cause
        // any extra data loss and gives us more free space on the grid for better migration.
        HashSet<String> validPackages = new HashSet<>();
        for (PackageInfo info : context.getPackageManager()
                .getInstalledPackages(PackageManager.GET_UNINSTALLED_PACKAGES)) {
            validPackages.add(info.packageName);
        }
        InstallSessionHelper.INSTANCE.get(context)
                .getActiveSessions().keySet()
                .forEach(packageUserKey -> validPackages.add(packageUserKey.mPackageName));
        return validPackages;
    }

    protected static class GridPlacementSolution {

        private final SQLiteDatabase mDb;
        private final DbReader mSrcReader;
        private final DbReader mDestReader;
        private final Context mContext;
        private final GridOccupancy mOccupied;
        private final int mScreenId;
        private final int mTrgX;
        private final int mTrgY;
        private final List<DbEntry> mSortedItemsToPlace;
        private final boolean mMatchingScreenIdOnly;

        private int mNextStartX;
        private int mNextStartY;

        GridPlacementSolution(SQLiteDatabase db, DbReader srcReader, DbReader destReader,
                Context context, int screenId, int trgX, int trgY, List<DbEntry> sortedItemsToPlace,
                boolean matchingScreenIdOnly) {
            mDb = db;
            mSrcReader = srcReader;
            mDestReader = destReader;
            mContext = context;
            mOccupied = new GridOccupancy(trgX, trgY);
            mScreenId = screenId;
            mTrgX = trgX;
            mTrgY = trgY;
            mNextStartX = 0;
            mNextStartY = mScreenId == 0 && Utilities.showQuickspace(mContext)
                    ? 1 /* smartspace */ : 0;
            List<DbEntry> existedEntries = mDestReader.mWorkspaceEntriesByScreenId.get(screenId);
            if (existedEntries != null) {
                for (DbEntry entry : existedEntries) {
                    mOccupied.markCells(entry, true);
                }
            }
            mSortedItemsToPlace = sortedItemsToPlace;
            mMatchingScreenIdOnly = matchingScreenIdOnly;
        }

        public void find() {
            Iterator<DbEntry> iterator = mSortedItemsToPlace.iterator();
            while (iterator.hasNext()) {
                final DbEntry entry = iterator.next();
                if (mMatchingScreenIdOnly && entry.screenId < mScreenId) continue;
                if (mMatchingScreenIdOnly && entry.screenId > mScreenId) break;
                if (entry.minSpanX > mTrgX || entry.minSpanY > mTrgY) {
                    iterator.remove();
                    continue;
                }
                if (findPlacement(entry)) {
                    insertEntryInDb(mDb, mContext, entry, mSrcReader.mTableName,
                            mDestReader.mTableName);
                    iterator.remove();
                }
            }
        }

        /**
         * Search for the next possible placement of an icon. (mNextStartX, mNextStartY) serves as
         * a memoization of last placement, we can start our search for next placement from there
         * to speed up the search.
         */
        private boolean findPlacement(DbEntry entry) {
            for (int y = mNextStartY; y <  mTrgY; y++) {
                for (int x = mNextStartX; x < mTrgX; x++) {
                    boolean fits = mOccupied.isRegionVacant(x, y, entry.spanX, entry.spanY);
                    boolean minFits = mOccupied.isRegionVacant(x, y, entry.minSpanX,
                            entry.minSpanY);
                    if (minFits) {
                        entry.spanX = entry.minSpanX;
                        entry.spanY = entry.minSpanY;
                    }
                    if (fits || minFits) {
                        entry.screenId = mScreenId;
                        entry.cellX = x;
                        entry.cellY = y;
                        mOccupied.markCells(entry, true);
                        mNextStartX = x + entry.spanX;
                        mNextStartY = y;
                        return true;
                    }
                }
                mNextStartX = 0;
            }
            return false;
        }
    }

    protected static class HotseatPlacementSolution {

        private final SQLiteDatabase mDb;
        private final DbReader mSrcReader;
        private final DbReader mDestReader;
        private final Context mContext;
        private final HotseatOccupancy mOccupied;
        private final List<DbEntry> mItemsToPlace;

        HotseatPlacementSolution(SQLiteDatabase db, DbReader srcReader, DbReader destReader,
                Context context, int hotseatSize, List<DbEntry> placedHotseatItems,
                List<DbEntry> itemsToPlace) {
            mDb = db;
            mSrcReader = srcReader;
            mDestReader = destReader;
            mContext = context;
            mOccupied = new HotseatOccupancy(hotseatSize);
            for (DbEntry entry : placedHotseatItems) {
                mOccupied.markCells(entry, true);
            }
            mItemsToPlace = itemsToPlace;
        }

        public void find() {
            for (int i = 0; i < mOccupied.mCells.length; i++) {
                if (!mOccupied.mCells[i] && !mItemsToPlace.isEmpty()) {
                    DbEntry entry = mItemsToPlace.remove(0);
                    entry.screenId = i;
                    // These values does not affect the item position, but we should set them
                    // to something other than -1.
                    entry.cellX = i;
                    entry.cellY = 0;
                    insertEntryInDb(mDb, mContext, entry, mSrcReader.mTableName,
                            mDestReader.mTableName);
                    mOccupied.markCells(entry, true);
                }
            }
        }

        private class HotseatOccupancy {

            private final boolean[] mCells;

            private HotseatOccupancy(int hotseatSize) {
                mCells = new boolean[hotseatSize];
            }

            private void markCells(ItemInfo item, boolean value) {
                mCells[item.screenId] = value;
            }
        }
    }

    protected static class DbReader {

        private final SQLiteDatabase mDb;
        private final String mTableName;
        private final Context mContext;
        private final Set<String> mValidPackages;
        private int mLastScreenId = -1;

        private final ArrayList<DbEntry> mHotseatEntries = new ArrayList<>();
        private final ArrayList<DbEntry> mWorkspaceEntries = new ArrayList<>();
        private final Map<Integer, ArrayList<DbEntry>> mWorkspaceEntriesByScreenId =
                new ArrayMap<>();

        DbReader(SQLiteDatabase db, String tableName, Context context,
                Set<String> validPackages) {
            mDb = db;
            mTableName = tableName;
            mContext = context;
            mValidPackages = validPackages;
        }

        protected ArrayList<DbEntry> loadHotseatEntries() {
            Cursor c = queryWorkspace(
                    new String[]{
                            LauncherSettings.Favorites._ID,                  // 0
                            LauncherSettings.Favorites.ITEM_TYPE,            // 1
                            LauncherSettings.Favorites.INTENT,               // 2
                            LauncherSettings.Favorites.SCREEN},              // 3
                    LauncherSettings.Favorites.CONTAINER + " = "
                            + LauncherSettings.Favorites.CONTAINER_HOTSEAT);

            final int indexId = c.getColumnIndexOrThrow(LauncherSettings.Favorites._ID);
            final int indexItemType = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ITEM_TYPE);
            final int indexIntent = c.getColumnIndexOrThrow(LauncherSettings.Favorites.INTENT);
            final int indexScreen = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SCREEN);

            IntArray entriesToRemove = new IntArray();
            while (c.moveToNext()) {
                DbEntry entry = new DbEntry();
                entry.id = c.getInt(indexId);
                entry.itemType = c.getInt(indexItemType);
                entry.screenId = c.getInt(indexScreen);

                try {
                    // calculate weight
                    switch (entry.itemType) {
                        case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                        case LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT:
                        case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION: {
                            entry.mIntent = c.getString(indexIntent);
                            verifyIntent(c.getString(indexIntent));
                            break;
                        }
                        case LauncherSettings.Favorites.ITEM_TYPE_FOLDER: {
                            int total = getFolderItemsCount(entry);
                            if (total == 0) {
                                throw new Exception("Folder is empty");
                            }
                            break;
                        }
                        default:
                            throw new Exception("Invalid item type");
                    }
                } catch (Exception e) {
                    if (DEBUG) {
                        Log.d(TAG, "Removing item " + entry.id, e);
                    }
                    entriesToRemove.add(entry.id);
                    continue;
                }
                mHotseatEntries.add(entry);
            }
            removeEntryFromDb(mDb, mTableName, entriesToRemove);
            c.close();
            return mHotseatEntries;
        }

        protected ArrayList<DbEntry> loadAllWorkspaceEntries() {
            Cursor c = queryWorkspace(
                    new String[]{
                            LauncherSettings.Favorites._ID,                  // 0
                            LauncherSettings.Favorites.ITEM_TYPE,            // 1
                            LauncherSettings.Favorites.SCREEN,               // 2
                            LauncherSettings.Favorites.CELLX,                // 3
                            LauncherSettings.Favorites.CELLY,                // 4
                            LauncherSettings.Favorites.SPANX,                // 5
                            LauncherSettings.Favorites.SPANY,                // 6
                            LauncherSettings.Favorites.INTENT,               // 7
                            LauncherSettings.Favorites.APPWIDGET_PROVIDER,   // 8
                            LauncherSettings.Favorites.APPWIDGET_ID},        // 9
                        LauncherSettings.Favorites.CONTAINER + " = "
                            + LauncherSettings.Favorites.CONTAINER_DESKTOP);
            return loadWorkspaceEntries(c);
        }

        private ArrayList<DbEntry> loadWorkspaceEntries(Cursor c) {
            final int indexId = c.getColumnIndexOrThrow(LauncherSettings.Favorites._ID);
            final int indexItemType = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ITEM_TYPE);
            final int indexScreen = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SCREEN);
            final int indexCellX = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CELLX);
            final int indexCellY = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CELLY);
            final int indexSpanX = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SPANX);
            final int indexSpanY = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SPANY);
            final int indexIntent = c.getColumnIndexOrThrow(LauncherSettings.Favorites.INTENT);
            final int indexAppWidgetProvider = c.getColumnIndexOrThrow(
                    LauncherSettings.Favorites.APPWIDGET_PROVIDER);
            final int indexAppWidgetId = c.getColumnIndexOrThrow(
                    LauncherSettings.Favorites.APPWIDGET_ID);

            IntArray entriesToRemove = new IntArray();
            WidgetManagerHelper widgetManagerHelper = new WidgetManagerHelper(mContext);
            while (c.moveToNext()) {
                DbEntry entry = new DbEntry();
                entry.id = c.getInt(indexId);
                entry.itemType = c.getInt(indexItemType);
                entry.screenId = c.getInt(indexScreen);
                mLastScreenId = Math.max(mLastScreenId, entry.screenId);
                entry.cellX = c.getInt(indexCellX);
                entry.cellY = c.getInt(indexCellY);
                entry.spanX = c.getInt(indexSpanX);
                entry.spanY = c.getInt(indexSpanY);

                try {
                    // calculate weight
                    switch (entry.itemType) {
                        case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                        case LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT:
                        case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION: {
                            entry.mIntent = c.getString(indexIntent);
                            verifyIntent(entry.mIntent);
                            break;
                        }
                        case LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET: {
                            entry.mProvider = c.getString(indexAppWidgetProvider);
                            ComponentName cn = ComponentName.unflattenFromString(entry.mProvider);
                            verifyPackage(cn.getPackageName());

                            int widgetId = c.getInt(indexAppWidgetId);
                            LauncherAppWidgetProviderInfo pInfo =
                                    widgetManagerHelper.getLauncherAppWidgetInfo(widgetId);
                            Point spans = null;
                            if (pInfo != null) {
                                spans = pInfo.getMinSpans();
                            }
                            if (spans != null) {
                                entry.minSpanX = spans.x > 0 ? spans.x : entry.spanX;
                                entry.minSpanY = spans.y > 0 ? spans.y : entry.spanY;
                            } else {
                                // Assume that the widget be resized down to 2x2
                                entry.minSpanX = entry.minSpanY = 2;
                            }

                            break;
                        }
                        case LauncherSettings.Favorites.ITEM_TYPE_FOLDER: {
                            int total = getFolderItemsCount(entry);
                            if (total == 0) {
                                throw new Exception("Folder is empty");
                            }
                            break;
                        }
                        default:
                            throw new Exception("Invalid item type");
                    }
                } catch (Exception e) {
                    if (DEBUG) {
                        Log.d(TAG, "Removing item " + entry.id, e);
                    }
                    entriesToRemove.add(entry.id);
                    continue;
                }
                mWorkspaceEntries.add(entry);
                if (!mWorkspaceEntriesByScreenId.containsKey(entry.screenId)) {
                    mWorkspaceEntriesByScreenId.put(entry.screenId, new ArrayList<>());
                }
                mWorkspaceEntriesByScreenId.get(entry.screenId).add(entry);
            }
            removeEntryFromDb(mDb, mTableName, entriesToRemove);
            c.close();
            return mWorkspaceEntries;
        }

        private int getFolderItemsCount(DbEntry entry) {
            Cursor c = queryWorkspace(
                    new String[]{LauncherSettings.Favorites._ID, LauncherSettings.Favorites.INTENT},
                    LauncherSettings.Favorites.CONTAINER + " = " + entry.id);

            int total = 0;
            while (c.moveToNext()) {
                try {
                    int id = c.getInt(0);
                    String intent = c.getString(1);
                    verifyIntent(intent);
                    total++;
                    if (!entry.mFolderItems.containsKey(intent)) {
                        entry.mFolderItems.put(intent, new HashSet<>());
                    }
                    entry.mFolderItems.get(intent).add(id);
                } catch (Exception e) {
                    removeEntryFromDb(mDb, mTableName, IntArray.wrap(c.getInt(0)));
                }
            }
            c.close();
            return total;
        }

        private Cursor queryWorkspace(String[] columns, String where) {
            return mDb.query(mTableName, columns, where, null, null, null, null);
        }

        /** Verifies if the mIntent should be restored. */
        private void verifyIntent(String intentStr)
                throws Exception {
            Intent intent = Intent.parseUri(intentStr, 0);
            if (intent.getComponent() != null) {
                verifyPackage(intent.getComponent().getPackageName());
            } else if (intent.getPackage() != null) {
                // Only verify package if the component was null.
                verifyPackage(intent.getPackage());
            }
        }

        /** Verifies if the package should be restored */
        private void verifyPackage(String packageName)
                throws Exception {
            if (!mValidPackages.contains(packageName)) {
                // TODO(b/151468819): Handle promise app icon restoration during grid migration.
                throw new Exception("Package not available");
            }
        }
    }

    protected static class DbEntry extends ItemInfo implements Comparable<DbEntry> {

        private String mIntent;
        private String mProvider;
        private Map<String, Set<Integer>> mFolderItems = new HashMap<>();

        /** Comparator according to the reading order */
        @Override
        public int compareTo(DbEntry another) {
            if (screenId != another.screenId) {
                return Integer.compare(screenId, another.screenId);
            }
            if (cellY != another.cellY) {
                return Integer.compare(cellY, another.cellY);
            }
            return Integer.compare(cellX, another.cellX);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DbEntry entry = (DbEntry) o;
            return Objects.equals(mIntent, entry.mIntent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mIntent);
        }

        public void updateContentValues(ContentValues values) {
            values.put(LauncherSettings.Favorites.SCREEN, screenId);
            values.put(LauncherSettings.Favorites.CELLX, cellX);
            values.put(LauncherSettings.Favorites.CELLY, cellY);
            values.put(LauncherSettings.Favorites.SPANX, spanX);
            values.put(LauncherSettings.Favorites.SPANY, spanY);
        }

        /** This id is not used in the DB is only used while doing the migration and it identifies
         * an entry on each workspace. For example two calculator icons would have the same
         * migration id even thought they have different database ids.
         */
        public String getEntryMigrationId() {
            switch (itemType) {
                case LauncherSettings.Favorites.ITEM_TYPE_FOLDER:
                    return getFolderMigrationId();
                case LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET:
                    return mProvider;
                case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
                    final String intentStr = cleanIntentString(mIntent);
                    try {
                        Intent i = Intent.parseUri(intentStr, 0);
                        return Objects.requireNonNull(i.getComponent()).toString();
                    } catch (Exception e) {
                        return intentStr;
                    }
                default:
                    return cleanIntentString(mIntent);
            }
        }

        /**
         * This method should return an id that should be the same for two folders containing the
         * same elements.
         */
        @NonNull
        private String getFolderMigrationId() {
            return mFolderItems.keySet().stream()
                    .map(intentString -> mFolderItems.get(intentString).size()
                            + cleanIntentString(intentString))
                    .sorted()
                    .collect(Collectors.joining(","));
        }

        /**
         * This is needed because sourceBounds can change and make the id of two equal items
         * different.
         */
        @NonNull
        private String cleanIntentString(@NonNull String intentStr) {
            try {
                Intent i = Intent.parseUri(intentStr, 0);
                i.setSourceBounds(null);
                return i.toURI();
            } catch (URISyntaxException e) {
                Log.e(TAG, "Unable to parse Intent string", e);
                return intentStr;
            }

        }
    }
}
