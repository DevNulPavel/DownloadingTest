package com.example.downloadingtest;

import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;


import com.seventeenbullets.android.xgen.downloader.LoadingInfo;
import com.seventeenbullets.android.xgen.downloader.ProgressInfo;


public class DownloadingService extends Service {
    final private String TAG = "DOWNLOAD_TAG";
    private Binder _binder;
    //private DownloadingBroadcastReceiver _downloadingReceiver = null;
    private DownloadManager _downloadManager;
    private HashMap<Long, LoadingInfo> _files_loading = null;

    public class Binder extends android.os.Binder {
        public DownloadingService getService() {
            return DownloadingService.this;
        }
    }

    @Override
    public void onCreate(){
        Log.d(TAG, "Service onCreate");
        super.onCreate();

        _downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        //_downloadingReceiver = new DownloadingBroadcastReceiver(this);
        _files_loading = new HashMap<Long, LoadingInfo>();

        _binder = new Binder();

        // Регистрируем получателя широковещательных сообщений
        //this.registerReceiver(_downloadingReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        super.onDestroy();

        // Убираем Receiver
        //this.unregisterReceiver(_downloadingReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        //START_NOT_STICKY – сервис не будет перезапущен после того, как был убит системой
        //START_STICKY – сервис будет перезапущен после того, как был убит системой
        //START_REDELIVER_INTENT – сервис будет перезапущен после того, как был убит системой. Кроме этого, сервис снова получит все вызовы startService, которые не были завершены методом stopSelf(startId).
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent){
        Log.d(TAG, "Service onBind");
        return _binder;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////

    public void loadingFinished(long loadingId){
        Log.d(TAG, "Service loadingFinished: " + loadingId);
        int startId = -1;
        synchronized (_files_loading){
            LoadingInfo info = _files_loading.get(loadingId);
        }
    }

    public double getPercentProgressInfo(){
        Vector<Cursor> cursors = new Vector<Cursor>();

        synchronized (_files_loading){
            Iterator it = _files_loading.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, LoadingInfo> pair = (Map.Entry)it.next();

                Query q = new Query();
                q.setFilterById(pair.getKey());

                final Cursor cursor = _downloadManager.query(q);
                cursors.add(cursor);

            }
        }

        // TODO: Thread safe?
        int bytes_downloaded = 0;
        int bytes_total = 0;
        for (Cursor cursor: cursors){
            cursor.moveToFirst();
            bytes_downloaded += cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            bytes_total += cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            cursor.close();
        }

        double dl_progress = (bytes_downloaded * 100.0) / bytes_total;

        return dl_progress;
    }

    public Vector<ProgressInfo> getFilesProgressInfo(){
        Vector<ProgressInfo> result = new Vector<ProgressInfo>();

        synchronized (_files_loading){
            Iterator it = _files_loading.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, LoadingInfo> pair = (Map.Entry)it.next();

                Query q = new Query();
                q.setFilterById(pair.getKey());

                final Cursor cursor = _downloadManager.query(q);

                cursor.moveToFirst();
                long bytes_downloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long bytes_total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                cursor.close();

                ProgressInfo info = new ProgressInfo();
                //info.file = pair.getValue().file;
                info.url = pair.getValue().url;
                info.totalSize = bytes_total;
                info.finishedSize =  bytes_downloaded;

                result.add(info);
            }
        }

        return result;
    }

    public void startLoading(String url_string, String file_path) {
        Log.d(TAG, "Service startLoading: " + url_string + " " + file_path);

        // Выходной файлик
        File file = new File(getExternalFilesDir(null), file_path);

        // Получаем URL
        Uri url = Uri.parse(url_string);

        DownloadManager.Request request = new DownloadManager.Request(url)
                .setTitle("Downloading name")
                .setDescription("Downloading description")  // Description of the Download Notification
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(file))
                .setAllowedOverMetered(true) // По мобильной сети
                .setAllowedOverRoaming(true);

        // Инициируем загрузку
        Long downloadingID = _downloadManager.enqueue(request);// enqueue puts the download request in the queue.

        Query q = new Query();
        q.setFilterById(downloadingID);
        final Cursor cursor = _downloadManager.query(q);
        cursor.moveToFirst();
        int bytes_total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        cursor.close();

        LoadingInfo info = new LoadingInfo();
        info.loadingId = downloadingID;
        info.loadSize = bytes_total;
        //info.file = file_path;
        info.url = url_string;

        synchronized (_files_loading){
            _files_loading.put(downloadingID, info);
        }
    }

    /*private void startProgressThread(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean downloading = true;
                while (downloading) {

                    ArrayList<Cursor> cursors = new ArrayList<Cursor>();

                    synchronized (_files_loading){
                        Iterator<Long> iter = _files_loading.iterator();
                        while (iter.hasNext()) {
                            Long val = iter.next();

                            Query q = new Query();
                            q.setFilterById(val);

                            final Cursor cursor = _downloadManager.query(q);
                            cursors.add(cursor);

                        }
                    }

                    int bytes_downloaded = 0;
                    int bytes_total = 0;
                    for (Cursor cursor: cursors){
                        cursor.moveToFirst();
                        bytes_downloaded += cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        bytes_total += cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        cursor.close();
                    }

                    if (cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
                        downloading = false;
                    }

                    final double dl_progress = (bytes_downloaded * 100.0) / bytes_total;

                    // TODO: Sleep
                    //final String message = statusMessage(cursor);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            _progress_bar.setProgress((int)dl_progress);
                            //_tv.setText(message);
                        }
                    });

                    //Log.d(Constants.MAIN_VIEW_ACTIVITY, statusMessage(cursor));
                }

            }
        }).start();
    }*/


    /*private String statusMessage(Cursor c) {
        String msg = "???";

        switch (c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS))) {
            case DownloadManager.STATUS_FAILED:
                msg = "Download failed!";
                break;

            case DownloadManager.STATUS_PAUSED:
                msg = "Download paused!";
                break;

            case DownloadManager.STATUS_PENDING:
                msg = "Download pending!";
                break;

            case DownloadManager.STATUS_RUNNING:
                msg = "Download in progress!";
                break;

            case DownloadManager.STATUS_SUCCESSFUL:
                msg = "Download complete!";
                break;

            default:
                msg = "Download is nowhere in sight";
                break;
        }

        return (msg);
    }*/
}
