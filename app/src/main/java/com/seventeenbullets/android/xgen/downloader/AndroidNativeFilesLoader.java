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
import android.os.Handler;
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
    private HashMap<Long, Long> _nativeToFakeLoadingIdMap;
    private final Vector<Thread> _threads;

    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static int NO_FREE_SPACE_ERROR = 1;
    public static int FILE_ERROR = 2;
    public static int FILE_ALREADY_EXISTS_ERROR = 3;
    public static int DEVICE_ERROR = 4;
    public static int REQUEST_ERROR = 5;
    public static int WRONG_HASH_ERROR = 6;
    public static int TIMEOUT_ERROR = 7;
    public static int UNKNOWN_ERROR = 99;

    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    public interface LoadingSuccessCallback {
        void onLoaded(LoadingInfo info);
    }

    public interface LoadingProgressCallback {
        void onLoadingPorgress(LoadingInfo info, long totalSize, long loadedSize);
    }

    public interface LoadingFailedCallback {
        void onLoadingFailed(LoadingInfo info, boolean canceled, int errorCode);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    BroadcastReceiver _loadingFinishedReceiver = new BroadcastReceiver () {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Broadcast message received");

            if (intent.getAction().equals(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) {
                long nativeId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                Log.d(TAG, "Loading broadcast message: " + nativeId);

                onLoadingFinished(nativeId);
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
        _lastFakeLoadingId = new AtomicLong(1);
        _nativeToFakeLoadingIdMap = new HashMap<>();
        _threads = new Vector<>();

        // Регистрируем получателя широковещательных сообщений
        context.registerReceiver(_loadingFinishedReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        context.registerReceiver(_notificationClickedReceiver, new IntentFilter(DownloadManager.ACTION_NOTIFICATION_CLICKED));
    }

    protected void finalize() {
        Log.d(TAG, "Service onDestroy");

        finishWork();
    }

    public void finishWork(){
        Log.d(TAG, "Finish work");

        // Сброс таймера
        if (_timeoutTimer != null){
            _timeoutTimer.cancel();
        }
        if (_progressTimer != null){
            _progressTimer.cancel();
        }

        synchronized (_threads){
            for(Thread t: _threads){
                try {
                    t.join();
                }catch (Exception e){
                }
            }
            _threads.clear();
        }

        // Убираем Receiver
        if(_loadingFinishedReceiver != null){
            _context.unregisterReceiver(_loadingFinishedReceiver);
            _loadingFinishedReceiver = null;
        }
        if (_notificationClickedReceiver != null){
            _context.unregisterReceiver(_notificationClickedReceiver);
            _loadingFinishedReceiver = null;
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////

    private void onLoadingFinished(final long nativeLoadingId){
        long loadingIdTmp = 0;
        synchronized (_nativeToFakeLoadingIdMap){
            if (_nativeToFakeLoadingIdMap.containsKey(nativeLoadingId)){
                loadingIdTmp = _nativeToFakeLoadingIdMap.get(nativeLoadingId);
            }else{
                return;
            }
        }
        final long fakeLoadingId = loadingIdTmp;

        Log.d(TAG, "Service loadingFinished: " + fakeLoadingId);
        LoadingInfo tmpinfo = null;
        synchronized (_activeFilesLoading) {
            // Проверяем наличие активной загрузки с таким ID
            if (_activeFilesLoading.containsKey(nativeLoadingId)) {
                // Получаем информацию и удаляем из загрузчика
                tmpinfo = _activeFilesLoading.get(nativeLoadingId);
                _activeFilesLoading.remove(nativeLoadingId);
            }else{
                Log.d(TAG, "Service loadingFinished: no active loading for id = " + fakeLoadingId);
                return;
            }
        }

        if (tmpinfo == null){
            Log.d(TAG, "Service loadingFinished: no active loading for id = " + fakeLoadingId);
            return;
        }

        final LoadingInfo info = tmpinfo;

        // Проверка, что уже обработали
        if (info.processed){
            Log.d(TAG, "Service loadingFinished: loading processed for id = " + fakeLoadingId);
            return;
        }

        // Выставляем флаг, что уже обработали
        info.processed = true;

        // Получаем курсор по информации об данной загрузку
        Cursor cursor = _downloadManager.query(new Query().setFilterById(nativeLoadingId));
        if (cursor.moveToFirst()) {
            // Статус загрузки
            int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));

            Log.d(TAG, "Service loadingFinished 1: " + fakeLoadingId + " status: " + status);

            // Успешно загрузили файлик
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                // Проверка хэша нужна или нет?
                if ((info.resultHash != null) && (info.resultHash.isEmpty() == false)){

                    // Хэш текущего временного файлика
                    md5Async(info.tmpFilePath, new RunnableString() {
                        @Override
                        public void run(String curHash) {
                            // Сравниваем хэши
                            if (curHash.equals(info.resultFilePath)){
                                Log.d(TAG, "Service loadingFinished success 1: hash check SUCCESS " + fakeLoadingId);

                                // Перемещаем наш файлик на его конечное место загрузки
                                moveFileAsync(info.tmpFilePath, info.resultFilePath, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Коллбек успешной загрузки
                                        if (_successCallback != null){
                                            _successCallback.onLoaded(info);
                                        }

                                        Log.d(TAG, "Service loadingFinished success 3: hash check SUCCESS " + fakeLoadingId);
                                    }
                                });

                                Log.d(TAG, "Service loadingFinished success 2: hash check SUCCESS " + fakeLoadingId);
                            }else{
                                Log.d(TAG, "Service loadingFinished success 1: hash check FAILED " + fakeLoadingId);

                                // Проверку хэша не прошли - удаляем файлик
                                removeFile(info.tmpFilePath);

                                // Коллбек ошибки загрузки
                                if (_failedCallback != null){
                                    _failedCallback.onLoadingFailed(info, false, WRONG_HASH_ERROR);
                                }
                                Log.d(TAG, "Service loadingFinished success 2: hash check FAILED " + fakeLoadingId);
                            }
                        }
                    });
                }else{
                    // Нам не надо было проверять хэш

                    Log.d(TAG, "Service loadingFinished success 1: " + fakeLoadingId);

                    Log.d(TAG, "Service loadingFinished success 2: " + fakeLoadingId);

                    // Перемещаем файлик из временного пути в конечный
                    moveFileAsync(info.tmpFilePath, info.resultFilePath, new Runnable() {
                        @Override
                        public void run() {
                            // Вызываем коллбек успеха
                            if (_successCallback != null){
                                _successCallback.onLoaded(info);
                            }

                            Log.d(TAG, "Service loadingFinished success 4: " + fakeLoadingId);
                        }
                    });

                    Log.d(TAG, "Service loadingFinished success 3: " + fakeLoadingId);
                }
            }
            // Произошла ошибка загрузки файла
            else if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));

                // Удаляем наш временный файлик
                removeFile(info.tmpFilePath);

                Log.d(TAG, "Service loadingFinished failed 1: " + fakeLoadingId);

                int errorCode = 0;
                switch (reason) {
                    case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                        errorCode = FILE_ALREADY_EXISTS_ERROR;
                        break;
                    case DownloadManager.ERROR_FILE_ERROR:
                        errorCode = FILE_ERROR;
                        break;
                    case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                        errorCode = DEVICE_ERROR;
                        break;
                    case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                        errorCode = NO_FREE_SPACE_ERROR;
                        break;
                    case DownloadManager.ERROR_CANNOT_RESUME:
                    case DownloadManager.ERROR_HTTP_DATA_ERROR:
                    case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                    case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                        errorCode = REQUEST_ERROR;
                        break;
                    default:
                        errorCode = UNKNOWN_ERROR;
                        break;
                }

                // Вызываем коллбек ошибки
                if (_failedCallback != null){
                    _failedCallback.onLoadingFailed(info, false, errorCode);
                }
            }
            // Обработка прерывания загрузки
            else if (status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING) {
                Log.d(TAG, "Service loadingFinished: loading interrupt " + fakeLoadingId);

                // Удаляем наш временный файлик если есть
                removeFile(info.tmpFilePath);

                // Принудительно убираем загрузку
                _downloadManager.remove(nativeLoadingId);

                // Вызываем коллбек ошибки
                if (_failedCallback != null){
                    _failedCallback.onLoadingFailed(info, true, 0);
                }
            }else{
                Log.d(TAG, "Service loadingFinished else case 0: !!!!! " + fakeLoadingId);
            }
        }
        // При прерывании загрузки бывают случаи, когда у нас нету информации о загрузки
        else{
            Log.d(TAG, "Service loadingFinished canceled 1: " + fakeLoadingId);

            // Удаляем наш временный файлик если есть
            removeFile(info.tmpFilePath);

            Log.d(TAG, "Service loadingFinished canceled 3: " + fakeLoadingId);

            // Вызываем коллбек ошибки
            if (_failedCallback != null){
                _failedCallback.onLoadingFailed(info, true, 0);
            }

            Log.d(TAG, "Service loadingFinished canceled 4: " + fakeLoadingId);
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
                        long downloadingID = pair.getValue().nativeLoadingId;
                        if (downloadingID <= 0){
                            continue;
                        }

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

                                    // Удаляем наш временный файлик если есть
                                    removeFile(pair.getValue().tmpFilePath);

                                    // Вызываем коллбек ошибки
                                    if (_failedCallback != null){
                                        _failedCallback.onLoadingFailed(pair.getValue(), false, TIMEOUT_ERROR);
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
        }, 1000, 1000);
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
                    // Создаем итератор
                    Iterator it = _activeFilesLoading.entrySet().iterator();
                    while (it.hasNext()) {
                        // Берем элемент
                        Map.Entry<Long, LoadingInfo> pair = (Map.Entry)it.next();

                        //Log.d(TAG, "Check progress for 1: " + pair.getKey());

                        // Создаем запрос для фильтрации
                        Query q = new Query().setFilterById(pair.getKey());

                        //Log.d(TAG, "Check progress for 2: " + pair.getKey());

                        // Получаем сколкьо загрузили
                        final Cursor cursor = _downloadManager.query(q);
                        // Проверка на hasFirst
                        if (cursor.moveToFirst()){
                            // Сколько байт загружено
                            long bytes_downloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            long bytes_total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                            //Log.d(TAG, "Check progress for 3: " + pair.getKey());

                            // Вызываем коллбек прогресса
                            if ((bytes_total != bytes_downloaded) && (_progressCallback != null)){
                                _progressCallback.onLoadingPorgress(pair.getValue(), bytes_total, bytes_downloaded);
                            }

                            //Log.d(TAG, "Check progress for 4: " + pair.getKey());
                        }
                        cursor.close();
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
        }, 250, 250);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////

    class ActiveLoadInfo{
        long id;
        int status;
        long totalSize;
        String url;
        String filePath;
    }

    private HashMap<String, ActiveLoadInfo> getActiveLoadsInManager() {
        HashMap<String, ActiveLoadInfo> activeLoads = new HashMap<>();
        {
            // Вкидываем задачи на паузе, ожидании и активные
            // В случае отсутствия соединения - обработается отвалом по таймеру
            int statuses =  DownloadManager.STATUS_PAUSED |
                            DownloadManager.STATUS_PENDING |
                            DownloadManager.STATUS_RUNNING;

            // TODO: Надо ли отслеживать STATUS_FAILED
            //DownloadManager.STATUS_FAILED |
            //DownloadManager.STATUS_SUCCESSFUL;
            Query q = new Query().setFilterByStatus(statuses);

            // Получаем индексы колонок
            Cursor cursor = _downloadManager.query(q);
            int idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
            int idStatus = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int idUri = cursor.getColumnIndex(DownloadManager.COLUMN_URI);
            int idTotalBytes = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            int idFilePath = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);

            if (cursor.moveToFirst()) {
                do{
                    long id = cursor.getLong(idIndex);
                    int status = cursor.getInt(idStatus);
                    long bytesTotal = cursor.getLong(idTotalBytes);
                    String filepath = cursor.getString(idFilePath); // Просто падает
                    String url = cursor.getString(idUri);

                    if (filepath != null && url != null){
                        filepath = filepath.replaceAll("file://", "");

                        ActiveLoadInfo info = new ActiveLoadInfo();
                        info.id = id;
                        info.status = status;
                        info.totalSize = bytesTotal;
                        info.filePath = filepath;
                        info.url = url;
                        activeLoads.put(url, info);
                        Log.d(TAG, "Already active loading: " + id + " " + status + " " + url);
                    }else{
                        Log.d(TAG, "Already active loading: missing filename or url");
                    }

                } while (cursor.moveToNext());
            }
            cursor.close();
        }

        return activeLoads;
    }

    public long startLoading(final LoadTask task) {
        // Стартуем таймеры для прогресса и таймаута
        enableTimeoutTimerForLoading();
        enableProgressTimerForLoading();

        // Получаем список уже активных загрузок
        HashMap<String, ActiveLoadInfo> activeLoads = getActiveLoadsInManager();

        // Идем по списку закачек
        Log.d(TAG, "Service startLoading: " + task.url + " " + task.resultFilePath);

        // Путь к временному файлику
        final String tempFilePath = makeTmpFilePath(task.resultFilePath);

        // Уже есть активные такие же загрузки - возвращаем ID
        if (activeLoads.containsKey(task.url)){
            Log.d(TAG, "Service startLoading -1: " + task.url + " " + task.resultFilePath);
            ActiveLoadInfo realLoadInfo = activeLoads.get(task.url);

            synchronized (_activeFilesLoading){
                // Проверяем наличие информации
                if (_activeFilesLoading.containsKey(realLoadInfo.id)){
                    // Если есть уже такая загрузка с правильным временным путем файлика - возвращаем ID
                    LoadingInfo ourInfo = _activeFilesLoading.get(realLoadInfo.id);
                    if(ourInfo.tmpFilePath.equals(realLoadInfo.filePath)){
                        return ourInfo.fakeLoadingId;
                    }
                }
            }

            // Если у нас новая загрузка по новому пути, но с тем же URL - стартуем ее
            if (realLoadInfo.filePath.equals(tempFilePath) == false){
                long fakeID = _lastFakeLoadingId.getAndAdd(1);
                startFileLoading(fakeID, task);
                return fakeID;
            }

            // Иначе просто создаем новую информацию о загрузке для отслеживания
            long nativeDownloadingID = realLoadInfo.id;
            long totalBytes = realLoadInfo.totalSize;

            Log.d(TAG, "Service startLoading 0: " + task.url + " " + task.resultFilePath);

            final long fakeLoadID = _lastFakeLoadingId.getAndAdd(1);
            synchronized (_nativeToFakeLoadingIdMap){
                _nativeToFakeLoadingIdMap.put(nativeDownloadingID, fakeLoadID);
            }

            // Создаем нашу задачу на загрузку
            LoadingInfo info = new LoadingInfo();
            info.fakeLoadingId = fakeLoadID;
            info.nativeLoadingId = nativeDownloadingID;
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
                _activeFilesLoading.put(nativeDownloadingID, info);
            }

            Log.d(TAG, "Service startLoading 2: " + task.url + " " + task.resultFilePath);

            return fakeLoadID;
        }
        // Проверяем, нет ли у нас уже загруженного временного файлика + его хэш, если хэш не был передан - значит надо начинать загрузку
        else if(new File(tempFilePath).exists() && (task.resultHash != null) && (task.resultHash.isEmpty() == false)){
            Log.d(TAG, "Service startLoading 2: temp file exists " + task.url + " " + task.resultFilePath);

            Log.d(TAG, "Service startLoading 2: check hash");

            final long newFakeLoadId = _lastFakeLoadingId.getAndAdd(1);

            // Получаем хэш этого самого файлика синхронно - тут без вариантов :-(
            md5Async(tempFilePath, new RunnableString() {
                @Override
                public void run(String currentMd5) {

                    // Если файлик уже загружен и хэш совпадает
                    if (currentMd5.equals(task.resultHash)){
                        // Ставим в лупер коллбек успшной загрузки
                        processTempFileAlreadyExists(newFakeLoadId, tempFilePath, task);
                    }else{
                        // Хэш оказался неверный - стартуем загрузку

                        Log.d(TAG, "Service startLoading 3: " + task.url + " " + task.resultFilePath);

                        // Стартуем загрузку
                        boolean isOk = startFileLoading(newFakeLoadId, task);
                        if (isOk){
                            Log.d(TAG, "Service startLoading 4: " + task.url + " " + task.resultFilePath);
                        }
                    }
                }
            });

            return newFakeLoadId;
        }
        // Проверяем, нет ли у нас уже загруженного конечного файлика + его хэш, если хэш не был передан - значит надо начинать загрузку
        else if(new File(task.resultFilePath).exists() && (task.resultHash != null) && (task.resultHash.isEmpty() == false)){
            Log.d(TAG, "Service startLoading 2: file exists " + task.url + " " + task.resultFilePath);

            Log.d(TAG, "Service startLoading 2: check hash");

            final long newFakeLoadId = _lastFakeLoadingId.getAndAdd(1);

            // Получаем хэш этого самого файлика синхронно - тут без вариантов :-(
            md5Async(task.resultFilePath, new RunnableString() {
                @Override
                public void run(String currentMd5) {
                    // Если все ок
                    if (currentMd5.equals(task.resultHash)){

                        // Стартуем фиктивную загрузку
                        processResultFileAlreadyExists(newFakeLoadId, task);
                    }else{
                        // Хэш не совпал - стартуем заново

                        Log.d(TAG, "Service startLoading 3: " + task.url + " " + task.resultFilePath);

                        // Стартуем загрузку
                        boolean isOk = startFileLoading(newFakeLoadId, task);
                        if (isOk){
                            Log.d(TAG, "Service startLoading 4: " + task.url + " " + task.resultFilePath);
                        }
                    }
                }
            });

            return newFakeLoadId;
        }
        else{
            Log.d(TAG, "Service startLoading 3: " + task.url + " " + task.resultFilePath);

            final long newFakeLoadId = _lastFakeLoadingId.getAndAdd(1);

            // Стартуем загрузку
            boolean isOk = startFileLoading(newFakeLoadId, task);
            if (isOk){
                Log.d(TAG, "Service startLoading 4: id = " + newFakeLoadId + " " + task.url + " " + task.resultFilePath);

                return newFakeLoadId;
            }
        }

        return 0;
    }

    private long getLoadingTotalSize(long loadingId){
        final Cursor cursor = _downloadManager.query(new Query().setFilterById(loadingId));
        cursor.moveToFirst();
        int bytes_total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        cursor.close();
        return bytes_total;
    }

    private String md5Sync(String filePath) {
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

    interface RunnableString{
        void run(String text);
    }

    private void md5Async(final String filePath, final RunnableString runnableStr) {
        Thread thread = new Thread(){
            public void run(){
                final String md5 = md5Sync(filePath);
                _context.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                            runnableStr.run(md5);
                    }
                });
            }
        };
        synchronized (_threads){
            _threads.add(thread);
            thread.start();
        }
    }

    private void processTempFileAlreadyExists(long fakeLoadingId, String tempFilePath, LoadTask task){
        Log.d(TAG, "Service startLoading: result file already exists " + task.url + " " + task.resultFilePath);

        File tempFile = new File(tempFilePath);
        final long fileSize = tempFile.length();

        final LoadingInfo info = new LoadingInfo();
        info.fakeLoadingId = fakeLoadingId;
        info.loadSize = fileSize;
        info.tmpFilePath = tempFilePath;
        info.resultFilePath = task.resultFilePath;
        info.url = task.url;
        info.checkTime = System.currentTimeMillis();
        info.resultHash = task.resultHash;
        info.processed = true;

        // Перемещаем файлик на конечное место - тут приходится синхронно
        moveFileAsync(tempFilePath, task.resultFilePath, new Runnable() {
            @Override
            public void run() {
                // Можно было бы обойтись обычным post, но с задержкой будет надежнее
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if(_progressCallback != null){
                            _progressCallback.onLoadingPorgress(info, fileSize, fileSize);
                        }
                        if(_successCallback != null){
                            _successCallback.onLoaded(info);
                        }
                    }
                }, 50);
            }
        });
    }

    private void processResultFileAlreadyExists(long fakeLoadingId, LoadTask task){
        Log.d(TAG, "Service startLoading: result file already exists " + task.url + " " + task.resultFilePath);

        File tempFile = new File(task.resultFilePath);
        final long fileSize = tempFile.length();

        final LoadingInfo info = new LoadingInfo();
        info.fakeLoadingId = fakeLoadingId;
        info.loadSize = fileSize;
        info.tmpFilePath = makeTmpFilePath(task.resultFilePath);
        info.resultFilePath = task.resultFilePath;
        info.url = task.url;
        info.checkTime = System.currentTimeMillis();
        info.resultHash = task.resultHash;
        info.processed = true;

        // Можно было бы обойтись обычным post, но с задержкой будет надежнее
        _context.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if(_progressCallback != null){
                            _progressCallback.onLoadingPorgress(info, fileSize, fileSize);
                        }
                        if(_successCallback != null){
                            _successCallback.onLoaded(info);
                        }
                    }
                }, 100);
            }
        });
    }

    private boolean startFileLoading(long fakeLoadID, LoadTask task){
        // Получаем URL
        Uri url = Uri.parse(task.url);

        // Временный файлик
        String tempFilePath = makeTmpFilePath(task.resultFilePath);
        File file = new File(tempFilePath);
        Log.d(TAG, "Service startLoading: " + file.getAbsolutePath());

        // Удаляем временные и конечные файлы если есть
        removeFile(tempFilePath);
        removeFile(task.resultFilePath);

        // Формируем запрос
        Request request = new Request(url)
                .setNotificationVisibility(Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(file));

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
                final Long nativeDownloadingID = tempDownloadingID;

                long bytes_total = getLoadingTotalSize(nativeDownloadingID);

                LoadingInfo info = new LoadingInfo();
                info.fakeLoadingId = fakeLoadID;
                info.nativeLoadingId = nativeDownloadingID;
                info.loadSize = bytes_total;
                info.tmpFilePath = file.getAbsolutePath();
                info.resultFilePath = task.resultFilePath;
                info.url = task.url;
                info.resultHash = task.resultHash;
                info.checkTime = System.currentTimeMillis();
                info.timeoutMSec = task.timeoutMsec;
                info.processed = false;

                _activeFilesLoading.put(nativeDownloadingID, info);

                synchronized (_nativeToFakeLoadingIdMap){
                    _nativeToFakeLoadingIdMap.put(nativeDownloadingID, fakeLoadID);
                }

                return true;
            }
        }

        return false;
    }

    private String makeTmpFilePath(String path){
        File resultPath = new File(path);
        String filename = resultPath.getName();
        String tempfilePath = _context.getExternalCacheDir().getAbsolutePath() + "/" + filename + ".tmp_andr_file";

        return tempfilePath;
    }

    private boolean moveFileSync(String inputPath, String outputPath) {
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

                return true;
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

        return false;
    }

    private void moveFileAsync(final String inputPath, final String outputPath, final Runnable success) {
        Thread thread = new Thread(){
            public void run(){
                boolean isOk = moveFileSync(inputPath, outputPath);
                if (isOk){
                    _context.runOnUiThread(success);
                }
            }
        };
        synchronized (_threads){
            _threads.add(thread);
            thread.start();
        }
    }

    private void removeFile(String filePath){
        File file = new File(filePath);
        if (file.exists()){
            file.delete();
        }
    }

}
