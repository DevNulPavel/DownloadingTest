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
import android.util.Log;
import android.util.Pair;
import android.net.ConnectivityManager;
//import java.nio.channels.FileChannel;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.security.MessageDigest;
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
import java.util.concurrent.atomic.AtomicLong;


public class AndroidNativeFilesLoader extends Object {
    final private String TAG = "DOWNLOAD_TAG";

    private Activity _context;
    private DownloadManager _downloadManager;
    private final HashMap<Long, LoadingInfo> _activeFilesLoading;
    private Timer _timeoutTimer;
    private Timer _progressTimer;
    private LoadingSuccessCallback _successCallback;
    private LoadingProgressCallback _progressCallback;
    private LoadingFailedCallback _failedCallback;
    private AtomicLong _lastFakeLoadingId;

    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public interface LoadingSuccessCallback {
        void onLoaded(LoadingInfo info);
    }

    public interface LoadingProgressCallback {
        void onLoadingPorgress(LoadingInfo info, long totalSize, long loadedSize);
    }

    public interface LoadingFailedCallback {
        void onLoadingFailed(LoadingInfo info, boolean canceled);
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
                if (_context.equals(context)){
                    onNotificationClicked();
                }
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
        _activeFilesLoading = new HashMap<>();
        _successCallback = successCallback;
        _progressCallback = progressCallback;
        _failedCallback = failedCallback;
        _lastFakeLoadingId = new AtomicLong(-1);

        // Регистрируем получателя широковещательных сообщений
        context.registerReceiver(_loadingFinishedReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        context.registerReceiver(_notificationClickedReceiver, new IntentFilter(DownloadManager.ACTION_NOTIFICATION_CLICKED));
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
        LoadingInfo info = null;
        synchronized (_activeFilesLoading) {
            // Проверяем наличие активной загрузки с таким ID
            if (_activeFilesLoading.containsKey(loadingId)) {
                // Получаем информацию и удаляем из загрузчика
                info = _activeFilesLoading.get(loadingId);
                _activeFilesLoading.remove(loadingId);
            }else{
                Log.d(TAG, "Service loadingFinished: no active loading for id = " + loadingId);
                return;
            }
        }

        if (info == null){
            Log.d(TAG, "Service loadingFinished: no active loading for id = " + loadingId);
            return;
        }

        // Проверка, что уже обработали
        if (info.processed){
            Log.d(TAG, "Service loadingFinished: loading processed for id = " + loadingId);
            return;
        }

        // Выставляем флаг, что уже обработали
        info.processed = true;

        // Получаем курсор по информации об данной загрузку
        Cursor cursor = _downloadManager.query(new Query().setFilterById(loadingId));
        if (cursor.moveToFirst()) {
            // Статус загрузки
            int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));

            Log.d(TAG, "Service loadingFinished 1: " + loadingId + " status: " + status);

            // Успешно загрузили файлик
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                // Проверка хэша нужна или нет?
                if ((info.resultHash != null) && (info.resultHash.isEmpty() == false)){

                    // Хэш текущего временного файлика
                    String curHash = md5(info.tmpFilePath);

                    // Сравниваем хэши
                    if (curHash.equals(info.resultFilePath)){
                        Log.d(TAG, "Service loadingFinished success 1: hash check SUCCESS " + loadingId);

                        // Перемещаем наш файлик на его конечное место загрузки
                        moveFile(info.tmpFilePath, info.resultFilePath);

                        // Коллбек успешной загрузки
                        if (_successCallback != null){
                            _successCallback.onLoaded(info);
                        }

                        Log.d(TAG, "Service loadingFinished success 2: hash check SUCCESS " + loadingId);
                    }else{
                        Log.d(TAG, "Service loadingFinished success 1: hash check FAILED " + loadingId);

                        // Проверку хэша не прошли - удаляем файлик
                        removeFile(info.tmpFilePath);

                        // Коллбек ошибки загрузки
                        // TODO: Надо  дополнительную информацию
                        if (_failedCallback != null){
                            _failedCallback.onLoadingFailed(info, false);
                        }
                        Log.d(TAG, "Service loadingFinished success 2: hash check FAILED " + loadingId);
                    }
                }else{
                    // Нам не надо было проверять хэш

                    Log.d(TAG, "Service loadingFinished success 1: " + loadingId);

                    Log.d(TAG, "Service loadingFinished success 2: " + loadingId);

                    // Перемещаем файлик из временного пути в конечный
                    moveFile(info.tmpFilePath, info.resultFilePath);

                    Log.d(TAG, "Service loadingFinished success 3: " + loadingId);

                    // Вызываем коллбек успеха
                    if (_successCallback != null){
                        _successCallback.onLoaded(info);
                    }

                    Log.d(TAG, "Service loadingFinished success 4: " + loadingId);
                }
            }
            // Произошла ошибка загрузки файла
            else if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));

                Log.d(TAG, "Service loadingFinished failed 1: " + loadingId);

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
                        Log.d(TAG, "Service loadingFinished default: " + loadingId);
                        break;
                }

                // TODO: Причину тоже надо
                // Вызываем коллбек ошибки
                if (_failedCallback != null){
                    _failedCallback.onLoadingFailed(info, false);
                }
            }
            // Обработка прерывания загрузки
            else if (status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING) {
                Log.d(TAG, "Service loadingFinished: loading interrupt " + loadingId);

                // Принудительно убираем загрузку
                _downloadManager.remove(loadingId);

                // TODO: Причину тоже надо
                // Вызываем коллбек ошибки
                if (_failedCallback != null){
                    _failedCallback.onLoadingFailed(info, true);
                }
            }else{
                Log.d(TAG, "Service loadingFinished else case 0: !!!!! " + loadingId);
            }
        }
        // При прерывании загрузки бывают случаи, когда у нас нету информации о загрузки
        else{
            Log.d(TAG, "Service loadingFinished canceled 1: " + loadingId);

            Log.d(TAG, "Service loadingFinished canceled 3: " + loadingId);

            // TODO: Причину тоже надо
            // Вызываем коллбек ошибки
            if (_failedCallback != null){
                _failedCallback.onLoadingFailed(info, true);
            }

            Log.d(TAG, "Service loadingFinished canceled 4: " + loadingId);
        }
    }

    private void onNotificationClicked (){
        // Открываем нашу Activity по клику
        if (_context != null){
            Intent i = new Intent(_context, _context.getClass());
            i.setAction(Intent.ACTION_MAIN);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            _context.startActivity(i);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////

    private void enableTimeoutTimerForLoading(){
        if (_timeoutTimer != null){
            return;
        }

        // Код будет исполняться в главном потоке
        final Runnable code = new Runnable() {
            @Override
            public void run() {
                synchronized (_activeFilesLoading) {
                    // Список для удаления
                    Vector<Long> removeArray = new Vector<>();

                    Iterator it = _activeFilesLoading.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Long, LoadingInfo> pair = (Map.Entry) it.next();

                        // Не обрабатываем повторно
                        if (pair.getValue().processed == true){
                            continue;
                        }

                        // Получаем ID
                        long downloadingID = pair.getValue().loadingId;

                        // Получаем время запуска
                        long createDate = pair.getValue().checkTime;

                        //Log.d(TAG, "Check timeout for: " + downloadingID);

                        // Получаем таймаут
                        long TIMEOUT = pair.getValue().timeoutMSec;
                        if (TIMEOUT == 0){
                            TIMEOUT = 20000; // 20 Sec
                        }

                        long curTime = System.currentTimeMillis();
                        long elapsedTime = (curTime - createDate);
                        // Защита от перемотки времени
                        if (elapsedTime < 0){
                            elapsedTime = TIMEOUT + 1;
                        }
                        if(elapsedTime > TIMEOUT){
                            // Обновляем время последней проверки
                            pair.getValue().checkTime = curTime;

                            // Получаем курсор по свойствам загрузки
                            final Cursor cursor = _downloadManager.query(new Query().setFilterById(downloadingID));
                            if (cursor.moveToFirst()) {
                                // Проверяем статус
                                int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                                int reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));

                                boolean needLoadingBreak = false;

                                switch (status) {
                                    case DownloadManager.STATUS_PAUSED:{
                                        switch (reason){
                                            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                                            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                                            case DownloadManager.PAUSED_UNKNOWN:
                                            case DownloadManager.PAUSED_WAITING_TO_RETRY:{
                                                // Пропала сеть - тоже прерываем загрузку
                                                needLoadingBreak = true;
                                                Log.d(TAG, "Loading break for id = " + pair.getKey() + " STATUS_PAUSED, reason = " + reason);
                                            }break;
                                        }
                                    }break;

                                    case DownloadManager.STATUS_FAILED: {
                                        needLoadingBreak = true;
                                    }break;

                                    // TODO: Pending надо бы обрабатывать с несколькими попытками
                                    case DownloadManager.STATUS_PENDING:{
                                        // Для ожидающих проверяем, что у нас есть соединение
                                        ConnectivityManager cm = (ConnectivityManager)_context.getSystemService(Context.CONNECTIVITY_SERVICE);
                                        boolean networkIsAvailable = cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();

                                        // Если у нас и правда нет интернета - выходим
                                        if (networkIsAvailable == false){
                                            Log.d(TAG, "Loading break for id = " + pair.getKey() + " STATUS_PENDING, reason = " + reason);
                                            needLoadingBreak = true;
                                        }
                                    }break;

                                    case DownloadManager.STATUS_RUNNING:
                                    case DownloadManager.STATUS_SUCCESSFUL: {
                                        //Log.d("status", "STATUS_SUCCESSFUL - done");
                                    }break;
                                }

                                if (needLoadingBreak){
                                    Log.d(TAG, "Loading break for id = " + pair.getKey() + " status = " + status + ", reason = " + reason);

                                    // Удаляем из загрузчика
                                    _downloadManager.remove(downloadingID);

                                    // В список удаления добавляем
                                    removeArray.add(downloadingID);

                                    // Выставляем флаг обработанности
                                    pair.getValue().processed = true;

                                    // TODO: Причину тоже надо
                                    // Вызываем коллбек ошибки
                                    if (_failedCallback != null){
                                        _failedCallback.onLoadingFailed(pair.getValue(), false);
                                    }
                                }
                            }
                            cursor.close();
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

        _timeoutTimer = new Timer();

        // Запускаем таймер, который раз в секунду будет проверять прогресс загрузки
        _timeoutTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                _context.runOnUiThread(code);
            }
        }, 1000, 1000); // TODO: Check period
    }

    private void enableProgressTimerForLoading(){
        if (_progressTimer != null){
            return;
        }

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

                        Query q = new Query().setFilterById(pair.getKey());

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

        _progressTimer = new Timer();

        // Запускаем таймер, который раз в секунду будет проверять прогресс загрузки
        _progressTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                _context.runOnUiThread(code);
            }
        }, 250, 250); // TODO: Check period
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////

    private HashMap<String, Pair<Integer, Long>> getActiveLoadsInManager() {
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
        // Стартуем таймеры для прогресса и таймаута
        enableTimeoutTimerForLoading();
        enableProgressTimerForLoading();

        // Получаем список уже активных загрузок
        HashMap<String, Pair<Integer, Long>> activeLoads = getActiveLoadsInManager();

        // Идем по списку закачек
        Log.d(TAG, "Service startLoading: " + task.url + " " + task.resultFilePath);

        // Путь к временному файлику
        String tempFilePath = makeTmpFilePath(task.resultFilePath);

        // TODO: TEST!!!!!
        // Уже есть активные такие же загрузки - просто создаем информацию о них
        if (activeLoads.containsKey(task.url) && false){
            Log.d(TAG, "Service startLoading -1: " + task.url + " " + task.resultFilePath);
            Pair<Integer, Long> pair = activeLoads.get(task.url);

            long downloadingID = pair.first;
            long totalBytes = pair.second;

            Log.d(TAG, "Service startLoading -0.5: " + task.url + " " + task.resultFilePath);
            //Uri url = Uri.parse(task.url);
            //String filename = url.getLastPathSegment();

            Log.d(TAG, "Service startLoading 0: " + task.url + " " + task.resultFilePath);

            // Создаем задачу на загрузку
            LoadingInfo info = new LoadingInfo();
            info.loadingId = downloadingID;
            info.loadSize = totalBytes;
            info.tmpFilePath = tempFilePath;
            info.resultFilePath = task.resultFilePath;
            info.url = task.url;
            info.checkTime = System.currentTimeMillis();
            info.resultHash = task.resultHash;
            info.timeoutMSec = task.timeoutMsec;

            Log.d(TAG, "Service startLoading 1: " + task.url + " " + task.resultFilePath);

            // Сохраняем в список загрузок
            synchronized (_activeFilesLoading){
                _activeFilesLoading.put(downloadingID, info);
            }

            Log.d(TAG, "Service startLoading 2: " + task.url + " " + task.resultFilePath);

            return downloadingID;
        }
        // Проверяем, нет ли у нас уже загруженного временного файлика + его хэш
        else if(new File(tempFilePath).exists() && (task.resultHash != null) && (task.resultHash.isEmpty() == false)){
            Log.d(TAG, "Service startLoading 2: temp file exists " + task.url + " " + task.resultFilePath);

            Log.d(TAG, "Service startLoading 2: check hash");

            // Получаем хэш этого самого файлика
            String currentMd5 = md5(tempFilePath);

            // Если файлик уже загружен и хэш совпадает
            if (currentMd5.equals(task.resultHash)){
                // Перемещаем файлик на конечное место
                moveFile(tempFilePath, task.resultFilePath);

                // Ставим в лупер коллбек успшной загрузки
                long fakeLoading = processFileIsAlreadyExist(task);

                // Возвращаем наш фейковый id
                return fakeLoading;
            }else{
                // Хэш оказался неверный - стартуем загрузку

                Log.d(TAG, "Service startLoading 3: " + task.url + " " + task.resultFilePath);

                // Стартуем загрузку
                long loadingID = startFileLoading(task);

                Log.d(TAG, "Service startLoading 4: " + task.url + " " + task.resultFilePath);

                return loadingID;
            }
        }
        // Проверяем, нет ли у нас уже загруженного конечного файлика
        else if(new File(task.resultFilePath).exists()){
            Log.d(TAG, "Service startLoading 2: file exists " + task.url + " " + task.resultFilePath);

            if ((task.resultHash != null) && (task.resultHash.isEmpty() == false)){
                Log.d(TAG, "Service startLoading 2: check hash");

                // Получаем хэш конечного файлика
                String currentMd5 = md5(task.resultFilePath);
                // Если все ок
                if (currentMd5.equals(task.resultHash)){

                    // Стартуем фиктивную загрузку
                    long fakeLoading = processFileIsAlreadyExist(task);

                    return fakeLoading;
                }else{
                    // Хэш не совпал - стартуем заново

                    Log.d(TAG, "Service startLoading 3: " + task.url + " " + task.resultFilePath);

                    // Стартуем загрузку
                    long loadingID = startFileLoading(task);

                    Log.d(TAG, "Service startLoading 4: " + task.url + " " + task.resultFilePath);

                    return loadingID;
                }
            }else{
                // Не можем проверить хэш - значит грузим заново
                Log.d(TAG, "Service startLoading: no check hash");
                long fakeLoading = processFileIsAlreadyExist(task);

                return fakeLoading;
            }
        }else{
            Log.d(TAG, "Service startLoading 3: " + task.url + " " + task.resultFilePath);

            // Стартуем загрузку
            long loadingID = startFileLoading(task);

            Log.d(TAG, "Service startLoading 4: id = " + loadingID + " " + task.url + " " + task.resultFilePath);

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

    private String md5(String filePath) {
        try {
            char[] hexDigits = "0123456789abcdef".toCharArray();

            FileInputStream fis   = new FileInputStream(filePath);

            String md5 = "";
            try {
                byte[] bytes = new byte[4096];
                int read = 0;
                MessageDigest digest = MessageDigest.getInstance("MD5");

                while ((read = fis.read(bytes)) != -1) {
                    digest.update(bytes, 0, read);
                }

                byte[] messageDigest = digest.digest();

                StringBuilder sb = new StringBuilder(32);

                for (byte b : messageDigest) {
                    sb.append(hexDigits[(b >> 4) & 0x0f]);
                    sb.append(hexDigits[b & 0x0f]);
                }

                md5 = sb.toString();
            } catch (Exception e) {
                e.printStackTrace();
            }

            return md5;
        } catch (Exception e) {
        }

        return "";
    }

    private long processFileIsAlreadyExist(LoadTask task){
        Log.d(TAG, "Service startLoading: result file already exists " + task.url + " " + task.resultFilePath);

        File tempFile = new File(task.resultFilePath);
        final long fileSize = tempFile.length();

        // Для ненастоящих загрузок используем отрицательные номера загрузки
        long downloadingID = _lastFakeLoadingId.addAndGet(-1);

        final LoadingInfo info = new LoadingInfo();
        info.loadingId = downloadingID;
        info.loadSize = fileSize;
        info.tmpFilePath = makeTmpFilePath(task.resultFilePath);
        info.resultFilePath = task.resultFilePath;
        info.url = task.url;
        info.checkTime = System.currentTimeMillis();
        info.resultHash = task.resultHash;
        info.processed = true;

        _context.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(_progressCallback != null){
                    _progressCallback.onLoadingPorgress(info, fileSize, fileSize);
                }
                if(_successCallback != null){
                    _successCallback.onLoaded(info);
                }
            }
        });

        return downloadingID;
    }

    private long startFileLoading(LoadTask task){
        // Получаем URL
        Uri url = Uri.parse(task.url);

        // Временный файлик
        File file = new File(makeTmpFilePath(task.resultFilePath));
        Log.d(TAG, "Service startLoading: " + file.getAbsolutePath());

        // Формируем запрос
        Request request = new Request(url)
                .setNotificationVisibility(Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(file))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);

        // Установка имени и описания загрузки
        if (task.loadingTitle.isEmpty() == false) {
            request.setTitle(task.loadingTitle);
        }
        if (task.loadingDescription.isEmpty() == false) {
            request.setDescription(task.loadingDescription);
        }

        synchronized (_activeFilesLoading){
            // Инициируем загрузку
            long tempDownloadingID = 0;
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
                info.checkTime = System.currentTimeMillis();
                info.timeoutMSec = task.timeoutMsec;
                info.processed = false;

                _activeFilesLoading.put(downloadingID, info);

                return downloadingID;
            }
        }

        return 0;
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

    private void removeFile(String filePath){
        File file = new File(filePath);
        if (file.exists()){
            file.delete();
        }
    }

}
