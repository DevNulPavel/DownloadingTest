package com.example.downloadingtest;

import android.app.Activity;
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
import android.os.Handler;
import android.util.Log;
import android.util.Pair;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;

import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;


public class FilesLoader extends Object {
    final private String TAG = "DOWNLOAD_TAG";
    private Activity _context;
    private DownloadManager _downloadManager;
    private HashMap<Long, LoadingInfo> _files_loading = null;
    private Timer _timer = null;

    BroadcastReceiver _loadingFinishedReceiver = new BroadcastReceiver () {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Broadcast message received");

            if (intent.getAction().equals(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                Log.d(TAG, "Loading broadcast message: " + id);

                onLoadingFinished(id);
            }
        }
    };

    BroadcastReceiver _notificationClickedReceiver = new BroadcastReceiver () {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Notification message received");

            if (intent.getAction().equals(DownloadManager.ACTION_NOTIFICATION_CLICKED)) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                Log.d(TAG, "Loading broadcast message: " + id);

                onNotificationClicked(id);
            }
        }
    };

    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public FilesLoader(Activity context){
        _context = context;
        _downloadManager = (DownloadManager)context.getSystemService(_context.DOWNLOAD_SERVICE);
        _files_loading = new HashMap<Long, LoadingInfo>();
        _timer = new Timer();

        // Регистрируем получателя широковещательных сообщений
        context.registerReceiver(_loadingFinishedReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        context.registerReceiver(_notificationClickedReceiver, new IntentFilter(DownloadManager.ACTION_NOTIFICATION_CLICKED));

        enableTimeoutForLoadingId();
    }

    protected void finalize() {
        Log.d(TAG, "Service onDestroy");

        // Убираем Receiver
        _context.unregisterReceiver(_loadingFinishedReceiver);
        _context.unregisterReceiver(_notificationClickedReceiver);
    }

    private void onLoadingFinished(long loadingId){
        Log.d(TAG, "Service loadingFinished: " + loadingId);
        synchronized (_files_loading){
            Query query = new Query().setFilterById(loadingId);
            Cursor cursor = _downloadManager.query(query);

            if (cursor.moveToFirst()) {
                int idStatusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                //int idLocalUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                //int idReasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);

                int status = cursor.getInt(idStatusIndex);

                // Успешно загрузили файлик
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    LoadingInfo info = _files_loading.get(loadingId);

                    Log.d(TAG, "Service loadingFinished success: " + loadingId);

                // Произошла ошибка загрузки файла
                } else if (status == DownloadManager.STATUS_FAILED) {
                    int reason = cursor.getInt(idStatusIndex);

                    switch (reason) {
                        case DownloadManager.ERROR_FILE_ERROR:
                            Log.d(TAG, "Service file error: " + loadingId);
                            break;
                        case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                            Log.d(TAG, "Service device not found: " + loadingId);
                            break;
                        case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                            Log.d(TAG, "Service space doesn't exist: " + loadingId);
                            break;
                        case DownloadManager.ERROR_HTTP_DATA_ERROR:
                            Log.d(TAG, "Service http data error: " + loadingId);
                            break;
                        case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                            Log.d(TAG, "Service loadingFinished success: " + loadingId);
                            break;
                        case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                            Log.d(TAG, "Service loadingFinished success: " + loadingId);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    private void onNotificationClicked (long loadingId){
        Log.d(TAG, "Service loadingFinished: " + loadingId);
        synchronized (_files_loading){
            if(_files_loading.containsKey(loadingId)){
                Intent dm = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
                dm.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                _context.startActivity(dm);
            }
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

    public void enableTimeoutForLoadingId(){

        _timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // use runOnUiThread(Runnable action)
                _context.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Vector<Long> removeArray = new Vector<>();

                        synchronized (_files_loading) {
                            Iterator it = _files_loading.entrySet().iterator();
                            while (it.hasNext()) {
                                Map.Entry<Long, LoadingInfo> pair = (Map.Entry) it.next();
                                long downloadingID = pair.getValue().loadingId;
                                long createDate = pair.getValue().createTime;

                                long curTime = System.currentTimeMillis();
                                if(curTime - createDate > 20000){
                                    pair.getValue().createTime = curTime;

                                    final Cursor cursor = _downloadManager.query(new Query().setFilterById(downloadingID));

                                    if (cursor.moveToFirst()) {
                                        int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                                        switch (status) {
                                            /*I introdused 'isEntered' param to eliminate first response from this method
                                             * I don't know why but I get STATUS_PENDING always on first run, so this is an ugly workaround*/
                                            case DownloadManager.STATUS_PENDING: {
                                                Log.d("status", "STATUS_PENDING - timeout");
                                                _downloadManager.remove(downloadingID);
                                                removeArray.add(downloadingID);

                                                // TODO: Process cancel

                                                break;
                                            }

                                            case DownloadManager.STATUS_PAUSED: {
                                                Log.d("status", "STATUS_PAUSED - error");
                                                break;
                                            }

                                            case DownloadManager.STATUS_RUNNING: {
                                                Log.d("status", "STATUS_RUNNING - good");
                                                break;
                                            }

                                            case DownloadManager.STATUS_SUCCESSFUL: {
                                                Log.d("status", "STATUS_SUCCESSFUL - done");
                                                break;
                                            }

                                            case DownloadManager.STATUS_FAILED: {
                                                Log.d("status", "STATUS_FAILED - error");
                                                _downloadManager.remove(downloadingID);
                                                removeArray.add(downloadingID);

                                                // TODO: Process cancel

                                                break;
                                            }
                                        }
                                    }
                                }
                            }

                            for(int i = 0; i < removeArray.size(); i++){
                                Long index = removeArray.elementAt(i);
                                _files_loading.remove(index);
                            }
                        }
                    }
                });
            }
        }, 1000, 1000);
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
                info.createTime = System.currentTimeMillis();

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
            final Long downloadingID = _downloadManager.enqueue(request);// enqueue puts the download request in the queue.

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
            info.createTime = System.currentTimeMillis();

            synchronized (_files_loading){
                _files_loading.put(downloadingID, info);
            }
        }
    }
}
