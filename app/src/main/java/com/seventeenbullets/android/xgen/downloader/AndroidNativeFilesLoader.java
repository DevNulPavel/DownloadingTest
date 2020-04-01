package com.seventeenbullets.android.xgen.downloader;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.util.Pair;
//import java.nio.channels.FileChannel;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
//import java.nio.file.CopyOption;
//import java.nio.file.StandardCopyOption;
//import java.nio.file.Files;

import java.util.Timer;
import java.util.TimerTask;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;


public class AndroidNativeFilesLoader extends Object {
    final private String TAG = "DOWNLOAD_TAG";

    private Activity _context;
    private DownloadManager _downloadManager;
    private HashMap<Long, LoadingInfo> _activeFilesLoading;
    private Timer _timeoutTimer;
    private Timer _progressTimer;
    private LoadingSuccessCallback _successCallback;
    private LoadingProgressCallback _progressCallback;
    private LoadingFailedCallback _failedCallback;

    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public interface LoadingSuccessCallback {
        void onLoaded(LoadingInfo info);
    }

    public interface LoadingProgressCallback {
        void onLoadingPorgress(LoadingInfo info, long totalSize, long loadedSize);
    }

    public interface LoadingFailedCallback {
        void onLoadingFailed(LoadingInfo info);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////

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

    public AndroidNativeFilesLoader(Activity context,
                                    LoadingSuccessCallback successCallback,
                                    LoadingProgressCallback progressCallback,
                                    LoadingFailedCallback failedCallback){
        // Переменные, с которыми будет работа
        _context = context;
        _downloadManager = (DownloadManager)context.getSystemService(_context.DOWNLOAD_SERVICE);
        _timeoutTimer = new Timer();
        _progressTimer = new Timer();
        _activeFilesLoading = new HashMap<Long, LoadingInfo>();
        _successCallback = successCallback;
        _progressCallback = progressCallback;
        _failedCallback = failedCallback;

        // Регистрируем получателя широковещательных сообщений
        context.registerReceiver(_loadingFinishedReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        context.registerReceiver(_notificationClickedReceiver, new IntentFilter(DownloadManager.ACTION_NOTIFICATION_CLICKED));

        enableTimeoutTimerForLoading();
        enableProgressTimerForLoading();
    }

    // TODO: Вынести в метод, вызываемый руками
    protected void finalize() {
        Log.d(TAG, "Service onDestroy");

        // Сброс таймера
        _timeoutTimer.cancel();
        _progressTimer.cancel();

        // Убираем Receiver
        _context.unregisterReceiver(_loadingFinishedReceiver);
        _context.unregisterReceiver(_notificationClickedReceiver);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////

    private void onLoadingFinished(long loadingId){
        Log.d(TAG, "Service loadingFinished: " + loadingId);
        synchronized (_activeFilesLoading){
            if(_activeFilesLoading.containsKey(loadingId) == false){
                Log.d(TAG, "Service loadingFinished success 0: " + loadingId);
                return;
            }

            Cursor cursor = _downloadManager.query(new Query().setFilterById(loadingId));

            if (cursor.moveToFirst()) {
                int idStatusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                //int idLocalUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                //int idReasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);

                // Статус загрузки
                int status = cursor.getInt(idStatusIndex);

                LoadingInfo info = _activeFilesLoading.get(loadingId);
                _activeFilesLoading.remove(loadingId);

                // Успешно загрузили файлик
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    Log.d(TAG, "Service loadingFinished success 1: " + loadingId);
                    info.completed = true;

                    Log.d(TAG, "Service loadingFinished success 2: " + loadingId);

                    // Перемещаем файлик из временной папки в конечную
                    moveFile(info.tmpFilePath, info.resultFilePath);

                    Log.d(TAG, "Service loadingFinished success 3: " + loadingId);

                    // Вызываем коллбек ошибки
                    if (_successCallback != null){
                        _successCallback.onLoaded(info);
                    }

                    Log.d(TAG, "Service loadingFinished success 4: " + loadingId);
                }
                // Произошла ошибка загрузки файла
                else if (status == DownloadManager.STATUS_FAILED) {
                    int reason = cursor.getInt(idStatusIndex);

                    info.failed = true;

                    // TODO: Причину тоже надо
                    // Вызываем коллбек ошибки
                    if (_failedCallback != null){
                        _failedCallback.onLoadingFailed(info);
                    }

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
        synchronized (_activeFilesLoading){
            if(_activeFilesLoading.containsKey(loadingId)){
                Intent dm = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
                dm.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                _context.startActivity(dm);
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////

    private void enableTimeoutTimerForLoading(){
        // Код будет исполняться в главном потоке
        final Runnable code = new Runnable() {
            @Override
            public void run() {
                // Список для удаления
                Vector<Long> removeArray = new Vector<>();

                synchronized (_activeFilesLoading) {
                    Iterator it = _activeFilesLoading.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Long, LoadingInfo> pair = (Map.Entry) it.next();
                        long downloadingID = pair.getValue().loadingId;
                        long createDate = pair.getValue().createTime;

                        //Log.d(TAG, "Check timeout for: " + downloadingID);

                        long TIMEOUT = 20000; // 20 Sec

                        long curTime = System.currentTimeMillis();
                        if(curTime - createDate > TIMEOUT){
                            // Обновляем время последней проверки
                            pair.getValue().createTime = curTime;

                            final Cursor cursor = _downloadManager.query(new Query().setFilterById(downloadingID));
                            if (cursor.moveToFirst()) {

                                // Проверяем статус, если
                                int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                                switch (status) {
                                    // TODO: Pending надо бы обрабатывать с несколькими попытками
                                    case DownloadManager.STATUS_PENDING: {
                                        Log.d("status", "STATUS_PENDING - timeout");
                                        _downloadManager.remove(downloadingID);
                                        removeArray.add(downloadingID);
                                        pair.getValue().failed = true;

                                        // TODO: Причину тоже надо
                                        // Вызываем коллбек ошибки
                                        if (_failedCallback != null){
                                            _failedCallback.onLoadingFailed(pair.getValue());
                                        }

                                        break;
                                    }
                                    case DownloadManager.STATUS_FAILED: {
                                        Log.d("status", "STATUS_FAILED - error");
                                        _downloadManager.remove(downloadingID);
                                        removeArray.add(downloadingID);
                                        pair.getValue().failed = true;

                                        // TODO: Причину тоже надо
                                        // Вызываем коллбек ошибки
                                        if (_failedCallback != null){
                                            _failedCallback.onLoadingFailed(pair.getValue());
                                        }

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
                                }
                            }
                        }
                    }

                    // Удаляем фейлы из списка закачек
                    for(int i = 0; i < removeArray.size(); i++){
                        Long index = removeArray.elementAt(i);
                        _activeFilesLoading.remove(index);
                    }
                }
            }
        };

        // Запускаем таймер, который раз в секунду будет проверять прогресс загрузки
        _timeoutTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                _context.runOnUiThread(code);
            }
        }, 1000, 1000); // TODO: Check period
    }

    private void enableProgressTimerForLoading(){
        // Код будет исполняться в главном потоке
        final Runnable code = new Runnable() {
            @Override
            public void run() {
                synchronized (_activeFilesLoading){
                    Iterator it = _activeFilesLoading.entrySet().iterator();
                    while (it.hasNext()) {
                        // TODO: проверить, начинается ли с начала или нет?
                        Map.Entry<Long, LoadingInfo> pair = (Map.Entry)it.next();

                        //Log.d(TAG, "Check progress for 1: " + pair.getKey());

                        Query q = new Query();
                        q.setFilterById(pair.getKey());

                        //Log.d(TAG, "Check progress for 2: " + pair.getKey());

                        // Проверка на hasFirst
                        // Получаем сколкьо загрузили
                        final Cursor cursor = _downloadManager.query(q);
                        if (cursor.moveToFirst()){
                            // Сколько байт загружено
                            long bytes_downloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            long bytes_total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            cursor.close();

                            //Log.d(TAG, "Check progress for 3: " + pair.getKey());

                            // Вызываем коллбек прогресса
                            if ((bytes_total != bytes_downloaded) && (_progressCallback != null)){
                                _progressCallback.onLoadingPorgress(pair.getValue(), bytes_total, bytes_downloaded);
                            }

                            //Log.d(TAG, "Check progress for 4: " + pair.getKey());
                        }
                    }
                }
            }
        };

        // Запускаем таймер, который раз в секунду будет проверять прогресс загрузки
        _timeoutTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                _context.runOnUiThread(code);
            }
        }, 500, 500); // TODO: Check period
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////

    private HashMap<String, Pair<Integer, Long>> getActiveLoads() {
        HashMap<String, Pair<Integer, Long>> activeLoads = new HashMap<>();
        {
            int statuses =  DownloadManager.STATUS_PAUSED |
                            DownloadManager.STATUS_PENDING |
                            DownloadManager.STATUS_RUNNING;

            // TODO: Надо ли отслеживать STATUS_FAILED
            //DownloadManager.STATUS_FAILED |
            //DownloadManager.STATUS_SUCCESSFUL;
            Query q = new Query().setFilterByStatus(statuses);

            Cursor cursor = _downloadManager.query(q);
            int idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
            int idStatus = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int idUri = cursor.getColumnIndex(DownloadManager.COLUMN_URI);
            int idTotalBytes = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            //int idFilename = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME);

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

        return activeLoads;
    }

    public long startLoading(LoadTask task) {
        HashMap<String, Pair<Integer, Long>> activeLoads = getActiveLoads();

        // Идем по списку закачек
        Log.d(TAG, "Service startLoading: " + task.url + " " + task.resultFilePath);

        // Уже есть активные такие же загрузки - просто создаем информацию о них
        if (activeLoads.containsKey(task.url)){
            Log.d(TAG, "Service startLoading -1: " + task.url + " " + task.resultFilePath);
            Pair<Integer, Long> pair = activeLoads.get(task.url);

            long downloadingID = pair.first;
            long totalBytes = pair.second;

            Log.d(TAG, "Service startLoading -0.5: " + task.url + " " + task.resultFilePath);
            //Uri url = Uri.parse(task.url);
            //String filename = url.getLastPathSegment();

            Log.d(TAG, "Service startLoading 0: " + task.url + " " + task.resultFilePath);

            LoadingInfo info = new LoadingInfo();
            info.loadingId = downloadingID;
            info.loadSize = totalBytes;
            info.tmpFilePath = makeTmpFilePath(task.resultFilePath);
            info.resultFilePath = task.resultFilePath;
            info.url = task.url;
            info.createTime = System.currentTimeMillis();

            Log.d(TAG, "Service startLoading 1: " + task.url + " " + task.resultFilePath);

            synchronized (_activeFilesLoading){
                _activeFilesLoading.put(downloadingID, info);
            }

            Log.d(TAG, "Service startLoading 2: " + task.url + " " + task.resultFilePath);

            return downloadingID;
        }else{
            Log.d(TAG, "Service startLoading 3: " + task.url + " " + task.resultFilePath);

            // Стартуем загрузку
            long loadingID = startFileLoading(task);

            Log.d(TAG, "Service startLoading 4: " + task.url + " " + task.resultFilePath);

            return loadingID;
        }
    }

    private long getLoadingTotalSize(long loadingId){
        final Cursor cursor = _downloadManager.query(new Query().setFilterById(loadingId));
        cursor.moveToFirst();
        int bytes_total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        cursor.close();
        return bytes_total;
    }

    private long startFileLoading(LoadTask task){
        // Получаем URL
        Uri url = Uri.parse(task.url);

        // TODO: Проверка, что файлик уже есть
        // Выходной файлик
        //      _context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        //      _context.getExternalFilesDir(null)
        //      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        File file = new File(makeTmpFilePath(task.resultFilePath));
        Log.d(TAG, "Service startLoading: " + file.getAbsolutePath());

        // Формируем запрос
        Request request = new Request(url)
                .setTitle("Downloading name")
                .setDescription("Downloading description")
                .setNotificationVisibility(Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(file))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);

        synchronized (_activeFilesLoading){
            // Инициируем загрузку
            long tempDownloadingID = -1;
            try {
                tempDownloadingID = _downloadManager.enqueue(request);// enqueue puts the download request in the queue.
            }catch (Exception e){
                Log.d(TAG, "Service startLoading: " + e.getMessage());
            }finally {
            }

            if (tempDownloadingID > 0){
                final Long downloadingID = tempDownloadingID;

                long bytes_total = getLoadingTotalSize(downloadingID);

                LoadingInfo info = new LoadingInfo();
                info.loadingId = downloadingID;
                info.loadSize = bytes_total;
                info.tmpFilePath = file.getAbsolutePath();
                info.resultFilePath = task.resultFilePath;
                info.url = task.url;
                info.createTime = System.currentTimeMillis();

                _activeFilesLoading.put(downloadingID, info);

                return downloadingID;
            }
        }

        return -1;
    }

    private String makeTmpFilePath(String path){
        File resultPath = new File(path);
        String filename = resultPath.getName();
        String tempfilePath = _context.getExternalCacheDir().getAbsolutePath() + "/" + filename + ".tmp_andr_file";

        return tempfilePath;
    }

    // TODO: Более оптимальный вариант перемещения данных из одного места в другое
    private void moveFile(String inputPath, String outputPath) {
        File from = new File(inputPath);
        if(from.exists()) {
            Log.d(TAG, "File move 1: " + inputPath + " -> " + outputPath);

            InputStream in = null;
            OutputStream out = null;
            try {

                //create output directory if it doesn't exist
                File dir = new File(new File(outputPath).getParent());
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                in = new FileInputStream(inputPath);
                out = new FileOutputStream(outputPath);

                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                in.close();
                in = null;

                // write the output file
                out.flush();
                out.close();
                out = null;

                // delete the original file
                new File(inputPath).delete();
            }
            catch (Exception e) {
                Log.e(TAG, "File move failed: " + e.getMessage());
            }

            Log.d(TAG, "File move 2: " + inputPath + " -> " + outputPath);

            /*boolean success = from.renameTo(to);
            if (success){
                Log.d(TAG, "File move success: " + tmpFilePath + " -> " + resultPath);
            }else{
                Log.d(TAG, "File move ERROR: " + tmpFilePath + " -> " + resultPath);
            }*/
        }

    }

    /*private void renameTmpFile(String tmpFilePath, String resultPath){
        File from = new File(tmpFilePath);
        //File to = new File(tmpFilePath.replaceAll(".tmp_andr_file", ""));
        //File to = new File(resultPath);
        if(from.exists()) {
            try{
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }catch (Exception e){
                Log.d(TAG, "File move ERROR: " + tmpFilePath + " -> " + resultPath);
            }

            boolean success = from.renameTo(to);
            if (success){
                Log.d(TAG, "File move success: " + tmpFilePath + " -> " + resultPath);
            }else{
                Log.d(TAG, "File move ERROR: " + tmpFilePath + " -> " + resultPath);
            }
        }

        File oldFile = new File(tmpFilePath);
        File newFile = new File(resultPath);
        FileChannel outputChannel = null;
        FileChannel inputChannel = null;
        try {
            outputChannel = new FileOutputStream(newFile).getChannel();
            inputChannel = new FileInputStream(oldFile).getChannel();
            inputChannel.transferTo(0, inputChannel.size(), outputChannel);
            inputChannel.close();
            oldFile.delete();
        }catch (Exception e){
            Log.d(TAG, "File move error: " + e.getMessage());
        } finally {
            try {
                if (inputChannel != null) {
                    inputChannel.close();
                }
                if (outputChannel != null) {
                    outputChannel.close();
                }
            }catch (Exception e){
            }
        }
    }*/

    /*public double getPercentProgressInfo(){
        Vector<Cursor> cursors = new Vector<Cursor>();
        synchronized (_activeFilesLoading){
            Iterator it = _activeFilesLoading.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, LoadingInfo> pair = (Map.Entry)it.next();

                Query q = new Query();
                q.setFilterById(pair.getKey());

                final Cursor cursor = _downloadManager.query(q);
                cursors.add(cursor);

            }
        }
        // TODO: Thread safe?
        double bytes_downloaded = 0;
        double bytes_total = 0;
        for (Cursor cursor: cursors){
            cursor.moveToFirst();
            bytes_downloaded += cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            bytes_total += cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            cursor.close();
        }
        double totalProgress = bytes_downloaded / bytes_total;
        return totalProgress;
    }*/

    /*public Vector<ProgressInfo> getFilesProgressInfo(){
        Vector<ProgressInfo> result = new Vector<ProgressInfo>();
        synchronized (_activeFilesLoading){
            Iterator it = _activeFilesLoading.entrySet().iterator();
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
    }*/

    /*private boolean isExternalStorageWritable() {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED;
    }*/

    /*private boolean isExternalStorageReadable() {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED ||
                Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED_READ_ONLY;
    }*/
}
