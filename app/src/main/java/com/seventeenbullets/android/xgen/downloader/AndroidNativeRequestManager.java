package com.seventeenbullets.android.xgen.downloader;

import android.app.Activity;
import android.os.Environment;


public class AndroidNativeRequestManager extends Object {
    private static Activity _activity = null;
    private static AndroidNativeFilesLoader _loader = null;

    private static native void initializeNative();
    private static native void destroyNative();
    private static native void loadingSuccess(long handle);
    private static native void loadingProgress(long handle, long totalSize, long loadedSize);
    private static native void loadingFailed(long handle);

    ///////////////////////////////////////////////////////////////////////////////////////////////

    private static AndroidNativeFilesLoader.LoadingSuccessCallback _successCallback = new AndroidNativeFilesLoader.LoadingSuccessCallback() {
        @Override
        public void onLoaded(LoadingInfo info) {
            loadingSuccess(info.loadingId);
        }
    };

    private static AndroidNativeFilesLoader.LoadingProgressCallback _progressCallback = new AndroidNativeFilesLoader.LoadingProgressCallback() {
        @Override
        public void onLoadingPorgress(LoadingInfo info, long totalSize, long loadedSize) {
            loadingProgress(info.loadingId, totalSize, loadedSize);
        }
    };

    private static AndroidNativeFilesLoader.LoadingFailedCallback _failedCallback = new AndroidNativeFilesLoader.LoadingFailedCallback() {
        @Override
        public void onLoadingFailed(LoadingInfo info) {
            loadingFailed(info.loadingId);
        }
    };

    ///////////////////////////////////////////////////////////////////////////////////////////////

    public static void initialize(Activity activity){
        initializeNative();
        _activity = activity;
        _loader = new AndroidNativeFilesLoader(activity, _successCallback, _progressCallback, _failedCallback);
    }

    public static void finish(){
        destroyNative();
        _loader = null;         // TODO: destroy call
    }

    public static long startTestLoading(String url, String path){
        // TODO: path handle

        LoadTask task = new LoadTask();
        task.url = url;
        task.resultFolder = _activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();

        long loadingID = _loader.startLoading(task);

        return loadingID;
    }
}
