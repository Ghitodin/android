/* ownCloud Android client application
 *   Copyright (C) 2012-2014 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.owncloud.android.datamodel;

import java.io.File;
import java.lang.ref.WeakReference;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.AsyncTask;
import android.widget.ImageView;

import com.owncloud.android.MainApp;
import com.owncloud.android.R;
import com.owncloud.android.lib.common.OwnCloudAccount;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory;
import com.owncloud.android.lib.common.accounts.AccountUtils.Constants;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.status.OwnCloudVersion;
import com.owncloud.android.ui.adapter.DiskLruImageCache;
import com.owncloud.android.utils.BitmapUtils;
import com.owncloud.android.utils.DisplayUtils;

/**
 * Manager for concurrent access to thumbnails cache. 
 *  
 * @author Tobias Kaminsky
 * @author David A. Velasco
 */
public class ThumbnailsCacheManager {
    
    private static final String TAG = ThumbnailsCacheManager.class.getSimpleName();
    
    private static final String CACHE_FOLDER = "thumbnailCache";
    private static final String MINOR_SERVER_VERSION_FOR_THUMBS = "7.8.0";
    
    private static final Object mThumbnailsDiskCacheLock = new Object();
    private static DiskLruImageCache mThumbnailCache = null;
    private static boolean mThumbnailCacheStarting = true;
    
    private static final int DISK_CACHE_SIZE = 1024 * 1024 * 10; // 10MB
    private static final CompressFormat mCompressFormat = CompressFormat.JPEG;
    private static final int mCompressQuality = 70;
    private static OwnCloudClient mClient = null;
    private static String mServerVersion = null;

    public static Bitmap mDefaultImg = 
            BitmapFactory.decodeResource(
                    MainApp.getAppContext().getResources(), 
                    DisplayUtils.getResourceId("image/png", "default.png")
            );

    
    public static class InitDiskCacheTask extends AsyncTask<File, Void, Void> {

        @Override
        protected Void doInBackground(File... params) {
            synchronized (mThumbnailsDiskCacheLock) {
                mThumbnailCacheStarting = true;

                if (mThumbnailCache == null) {
                    try {
                        // Check if media is mounted or storage is built-in, if so, 
                        // try and use external cache dir; otherwise use internal cache dir
                        final String cachePath = 
                                MainApp.getAppContext().getExternalCacheDir().getPath() + 
                                File.separator + CACHE_FOLDER;
                        Log_OC.d(TAG, "create dir: " + cachePath);
                        final File diskCacheDir = new File(cachePath);
                        mThumbnailCache = new DiskLruImageCache(
                                diskCacheDir, 
                                DISK_CACHE_SIZE, 
                                mCompressFormat, 
                                mCompressQuality
                        );
                    } catch (Exception e) {
                        Log_OC.d(TAG, "Thumbnail cache could not be opened ", e);
                        mThumbnailCache = null;
                    }
                }
                mThumbnailCacheStarting = false; // Finished initialization
                mThumbnailsDiskCacheLock.notifyAll(); // Wake any waiting threads
            }
            return null;
        }
    }
    
    
    public static void addBitmapToCache(String key, Bitmap bitmap) {
        synchronized (mThumbnailsDiskCacheLock) {
            if (mThumbnailCache != null) {
                mThumbnailCache.put(key, bitmap);
            }
        }
    }


    public static Bitmap getBitmapFromDiskCache(String key) {
        synchronized (mThumbnailsDiskCacheLock) {
            // Wait while disk cache is started from background thread
            while (mThumbnailCacheStarting) {
                try {
                    mThumbnailsDiskCacheLock.wait();
                } catch (InterruptedException e) {}
            }
            if (mThumbnailCache != null) {
                return (Bitmap) mThumbnailCache.getBitmap(key);
            }
        }
        return null;
    }

    public interface AsyncTaskFile {
        public String getId();
        public String getTagId();
        public String getPath();
        public String getRemotePath();
        public boolean getNeedsUpdateThumbnail();
        public void setNeedsUpdateThumbnail(boolean needsUpdate);
        public boolean getIsDown();
        public Object getFile();

    }

    public static class AsyncTaskFileLocal implements AsyncTaskFile{

        private File mFile;
        private boolean mNeedsUpdate;

        public AsyncTaskFileLocal(File file){
            mFile = file;
            mNeedsUpdate = false;
        }

        @Override
         public String getId() {
            return String.valueOf(mFile.hashCode());
        }

        @Override
        public String getTagId() {
            return String.valueOf(mFile.hashCode());
        }

        @Override
        public String getPath() {
            return mFile.getAbsolutePath();
        }

        @Override
        public String getRemotePath() {
            return null;
        }

        @Override
        public boolean getNeedsUpdateThumbnail() {
            return mNeedsUpdate;
        }

        @Override
        public void setNeedsUpdateThumbnail(boolean needsUpdate) {
            mNeedsUpdate = needsUpdate;
        }

        @Override
        public boolean getIsDown() {
            return false;
        }

        @Override
        public Object getFile() {
            return mFile;
        }
    }

    public static class AsyncTaskOCFile implements AsyncTaskFile{

        private OCFile mFile;

        public AsyncTaskOCFile(OCFile file){
            mFile = file;
        }

        @Override
        public String getId() {
            return mFile.getRemoteId();
        }

        @Override
        public String getTagId() {
            return String.valueOf(mFile.getFileId());
        }

        @Override
        public String getPath() {
            return mFile.getStoragePath();
        }

        @Override
        public String getRemotePath() {
            return mFile.getRemotePath();
        }

        @Override
        public boolean getNeedsUpdateThumbnail() {
            return mFile.needsUpdateThumbnail();
        }

        @Override
        public void setNeedsUpdateThumbnail(boolean needsUpdate) {
            mFile.setNeedsUpdateThumbnail(needsUpdate);
        }

        @Override
        public boolean getIsDown() {
            return mFile.isDown();
        }

        @Override
        public Object getFile() {
            return mFile;
        }
    }

    public static class ThumbnailGenerationGlobalTask extends AsyncTask<AsyncTaskFile, Void, Bitmap> {
        private final WeakReference<ImageView> mImageViewReference;
        private static Account mAccount;
        private AsyncTaskFile mFile;
        private FileDataStorageManager mStorageManager;


        public ThumbnailGenerationGlobalTask(ImageView imageView, FileDataStorageManager storageManager, Account account) {
            // Use a WeakReference to ensure the ImageView can be garbage collected
            mImageViewReference = new WeakReference<ImageView>(imageView);
            if (storageManager == null)
                throw new IllegalArgumentException("storageManager must not be NULL");
            mStorageManager = storageManager;
            mAccount = account;
        }

        public ThumbnailGenerationGlobalTask(ImageView imageView) {
            // Use a WeakReference to ensure the ImageView can be garbage collected
            mImageViewReference = new WeakReference<ImageView>(imageView);
        }

        @Override
        protected Bitmap doInBackground(AsyncTaskFile... params) {
            Bitmap thumbnail = null;

            try {
                if (mAccount != null) {
                    AccountManager accountMgr = AccountManager.get(MainApp.getAppContext());

                    mServerVersion = accountMgr.getUserData(mAccount, Constants.KEY_OC_VERSION);
                    OwnCloudAccount ocAccount = new OwnCloudAccount(mAccount, MainApp.getAppContext());
                    mClient = OwnCloudClientManagerFactory.getDefaultSingleton().
                            getClientFor(ocAccount, MainApp.getAppContext());
                }

                mFile = params[0];

                final String imageKey = String.valueOf(mFile.getId());

                // Check disk cache in background thread
                thumbnail = getBitmapFromDiskCache(imageKey);

                if (mFile instanceof AsyncTaskOCFile) {

                    // Not found in disk cache
                    if (thumbnail == null || mFile.getNeedsUpdateThumbnail()) {
                        // Converts dp to pixel
                        Resources r = MainApp.getAppContext().getResources();

                        int px = (int) Math.round(r.getDimension(R.dimen.file_icon_size));

                        if (mFile.getIsDown()) {
                            Bitmap bitmap = BitmapUtils.decodeSampledBitmapFromFile(
                                    mFile.getPath(), px, px);

                            if (bitmap != null) {
                                thumbnail = addThumbnailToCache(imageKey, bitmap, mFile.getPath(), px);

                                mFile.setNeedsUpdateThumbnail(false);
                                mStorageManager.saveFile((OCFile) mFile.getFile());
                            }

                        } else {
                            // Download thumbnail from server
                            if (mClient != null && mServerVersion != null) {
                                OwnCloudVersion serverOCVersion = new OwnCloudVersion(mServerVersion);
                                if (serverOCVersion.compareTo(new OwnCloudVersion(MINOR_SERVER_VERSION_FOR_THUMBS)) >= 0) {
                                    try {
                                        int status = -1;

                                        String uri = mClient.getBaseUri() + "/index.php/apps/files/api/v1/thumbnail/" +
                                                px + "/" + px + Uri.encode(mFile.getRemotePath(), "/");
                                        Log_OC.d("Thumbnail", "URI: " + uri);
                                        GetMethod get = new GetMethod(uri);
                                        status = mClient.executeMethod(get);
                                        if (status == HttpStatus.SC_OK) {
                                            byte[] bytes = get.getResponseBody();
                                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                                            thumbnail = ThumbnailUtils.extractThumbnail(bitmap, px, px);

                                            // Add thumbnail to cache
                                            if (thumbnail != null) {
                                                addBitmapToCache(imageKey, thumbnail);
                                            }
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                } else {
                                    Log_OC.d(TAG, "Server too old");
                                }
                            }
                        }
                    }
                } else if (mFile instanceof AsyncTaskFileLocal) {

                    // Not found in disk cache
                    if (thumbnail == null) {
                        // Converts dp to pixel
                        Resources r = MainApp.getAppContext().getResources();

                        int px = (int) Math.round(r.getDimension(R.dimen.file_icon_size));

                        Bitmap bitmap = BitmapUtils.decodeSampledBitmapFromFile(
                                mFile.getPath(), px, px);

                        if (bitmap != null) {
                            thumbnail = addThumbnailToCache(imageKey, bitmap, mFile.getPath(), px);
                        }
                    }

                }

                }catch(Throwable t){
                    // the app should never break due to a problem with thumbnails
                    Log_OC.e(TAG, "Generation of thumbnail for " + mFile + " failed", t);
                    if (t instanceof OutOfMemoryError) {
                        System.gc();
                    }
                }


            return thumbnail;
        }

        protected void onPostExecute(Bitmap bitmap){
            if (isCancelled()) {
                bitmap = null;
            }

            if (mImageViewReference != null && bitmap != null) {
                final ImageView imageView = mImageViewReference.get();
                final ThumbnailGenerationGlobalTask bitmapWorkerTask = getBitmapGlobalWorkerTask(imageView);
                if (this == bitmapWorkerTask && imageView != null) {
                    if (String.valueOf(imageView.getTag()).equals(mFile.getTagId())) {
                        imageView.setImageBitmap(bitmap);
                    }
                }
            }
        }

        private Bitmap addThumbnailToCache(String imageKey, Bitmap bitmap, String path, int px){

            Bitmap thumbnail = ThumbnailUtils.extractThumbnail(bitmap, px, px);

            // Rotate image, obeying exif tag
            thumbnail = BitmapUtils.rotateImage(thumbnail,path);

            // Add thumbnail to cache
            addBitmapToCache(imageKey, thumbnail);

            return thumbnail;

        }
    }

    public static boolean cancelPotentialGlobalWork(AsyncTaskFile file, ImageView imageView) {
        final ThumbnailGenerationGlobalTask bitmapWorkerTask = getBitmapGlobalWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final AsyncTaskFile bitmapData = bitmapWorkerTask.mFile;
            // If bitmapData is not yet set or it differs from the new data
            if (bitmapData == null || bitmapData != file) {
                // Cancel previous task
                bitmapWorkerTask.cancel(true);
            } else {
                // The same work is already in progress
                return false;
            }
        }
        // No task associated with the ImageView, or an existing task was cancelled
        return true;
    }

    public static ThumbnailGenerationGlobalTask getBitmapGlobalWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncGlobalDrawable) {
                final AsyncGlobalDrawable asyncDrawable = (AsyncGlobalDrawable) drawable;
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    public static class AsyncGlobalDrawable extends BitmapDrawable {
        private final WeakReference<ThumbnailGenerationGlobalTask> bitmapWorkerTaskReference;

        public AsyncGlobalDrawable(
                Resources res, Bitmap bitmap, ThumbnailGenerationGlobalTask bitmapWorkerTask
        ) {

            super(res, bitmap);
            bitmapWorkerTaskReference =
                    new WeakReference<ThumbnailGenerationGlobalTask>(bitmapWorkerTask);
        }

        public ThumbnailGenerationGlobalTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }
}
