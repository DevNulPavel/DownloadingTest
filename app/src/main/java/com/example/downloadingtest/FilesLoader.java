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
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.util.Pair;

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

    public FilesLoader(Context context, Vector<LoadTask> tasks){
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

    // Checks if a volume containing external storage is available
    // for read and write.
    private boolean isExternalStorageWritable() {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED;
    }

    // Checks if a volume containing external storage is available to at least read.
    private boolean isExternalStorageReadable() {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED ||
                Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED_READ_ONLY;
    }

    public void startLoading(Vector<LoadTask> tasks) {
        HashMap<String, Pair<Integer, Long>> activeLoads = new HashMap<>();
        {
            int statuses =  DownloadManager.STATUS_FAILED |
                    DownloadManager.STATUS_PAUSED |
                    DownloadManager.STATUS_PENDING |
                    DownloadManager.STATUS_RUNNING;
                    //DownloadManager.STATUS_SUCCESSFUL;
            Query q = new Query().setFilterByStatus(statuses);

            Cursor cursor = _downloadManager.query(q);
            int idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
            int idStatus = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            //int idFilename = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME);
            int idUri = cursor.getColumnIndex(DownloadManager.COLUMN_URI);
            int idTotalBytes = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);

            boolean hasFirst = cursor.moveToFirst();
            if (hasFirst) {
                do{
                    int id = cursor.getInt(idIndex);
                    int status = cursor.getInt(idStatus);
                    long bytesTotal = cursor.getLong(idTotalBytes);
                    //String filename = cursor.getString(idFilename);
                    String url = cursor.getString(idUri);

                    activeLoads.put(url, Pair.create(id, bytesTotal));
                    Log.d(TAG, "Active loading: " + id + " " + status + " " + url);

                } while (cursor.moveToNext());
            }
            cursor.close();
        }

//        if (!isExternalStorageReadable() || isExternalStorageWritable()){
//            return;
//        }


        for(LoadTask task: tasks){
            Log.d(TAG, "Service startLoading: " + task.url + " " + task.file);

            // Уже активные загрузки просто накидываем
            if (activeLoads.containsKey(task.url)){
                Pair<Integer, Long> pair = activeLoads.get(task.url);

                long downloadingID = pair.first;
                long totalBytes = pair.second;

                LoadingInfo info = new LoadingInfo();
                info.loadingId = downloadingID;
                info.loadSize = totalBytes;
                info.file = task.file;
                info.url = task.url;

                synchronized (_files_loading){
                    _files_loading.put(downloadingID, info);
                }

                continue;
            }

            // Получаем URL
            Uri url = Uri.parse(task.url);


            // Выходной файлик
            //_context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            //_context.getExternalFilesDir(null)
            // Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File file = new File(_context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), task.file);
            Log.d(TAG, "Service startLoading: " + file.getAbsolutePath());


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
