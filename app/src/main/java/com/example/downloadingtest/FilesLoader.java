package com.example.downloadingtest;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;


public class FilesLoader extends Object {
    final private String TAG = "DOWNLOAD_TAG";
    private Context _context;
    private DownloadManager _downloadManager;
    private HashMap<Long, LoadingInfo> _files_loading = null;

    BroadcastReceiver _receiver = new BroadcastReceiver () {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Broadcast message received");

            if (intent.getAction().equals(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) {
                //Fetching the download id received with the broadcast
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

                Log.d(TAG, "Loading broadcast message: " + id);

                //Checking if the received broadcast is for our enqueued download by matching download id
                loadingFinished(id);
            }else if (intent.getAction().equals(DownloadManager.ACTION_NOTIFICATION_CLICKED)) {
            }else if (intent.getAction().equals(DownloadManager.ACTION_VIEW_DOWNLOADS)) {
            }
        }
    };

    public FilesLoader(Context context, Vector<LoadTask> tasks, Handler progressCallback, Handler finishedCallback){
        _context = context;
        _downloadManager = (DownloadManager)context.getSystemService(_context.DOWNLOAD_SERVICE);
        _files_loading = new HashMap<Long, LoadingInfo>();

        // Регистрируем получателя широковещательных сообщений
        context.registerReceiver(_receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        startLoading(tasks);
    }

    protected void finalize() {
        Log.d(TAG, "Service onDestroy");

        // Убираем Receiver
        _context.unregisterReceiver(_receiver);
    }

    public void loadingFinished(long loadingId){
        Log.d(TAG, "Service loadingFinished: " + loadingId);
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
                info.file = pair.getValue().file;
                info.url = pair.getValue().url;
                info.totalSize = bytes_total;
                info.finishedSize =  bytes_downloaded;

                result.add(info);
            }
        }

        return result;
    }

    private void startLoading(Vector<LoadTask> tasks) {
        for(LoadTask task: tasks){
            Log.d(TAG, "Service startLoading: " + task.url + " " + task.file);

            // Выходной файлик
            File file = new File(_context.getExternalFilesDir(null), task.file);

            // Получаем URL
            Uri url = Uri.parse(task.url);

            Request request = new Request(url)
                    .setTitle("Downloading name")
                    .setDescription("Downloading description")  // Description of the Download Notification
                    .setNotificationVisibility(Request.VISIBILITY_VISIBLE)
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
            info.file = task.file;
            info.url = task.url;

            synchronized (_files_loading){
                _files_loading.put(downloadingID, info);
            }
        }
    }
}
