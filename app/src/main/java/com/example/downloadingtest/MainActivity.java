package com.example.downloadingtest;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.app.DownloadManager.Query;
import android.database.Cursor;
import android.content.BroadcastReceiver;
import android.net.Uri;
import android.util.Log;
import com.example.downloadingtest.DownloadingBroadcastReceiver;
import com.example.downloadingtest.DownloadingService;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;
import java.util.concurrent.atomic.AtomicBoolean;


public class MainActivity extends AppCompatActivity {
    final private String TAG = "DOWNLOAD_TAG";
    private TextView _tv;
    private Button _download_button;
    private ProgressBar _progress_bar;
    //DownloadingService _downloadService;
    FilesLoader _loader = null;
    AtomicBoolean _bound = new AtomicBoolean(false);

    /*private ServiceConnection _connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            DownloadingService.Binder binder = (DownloadingService.Binder)service;
            _downloadService = binder.getService();
            _bound.set(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            _bound.set(false);
        }
    };*/

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        _tv = findViewById(R.id.loaded_text);
        _download_button = findViewById(R.id.download_button);
        _progress_bar = findViewById(R.id.progress_bar);

        // Example of a call to a native method
        _tv.setText(stringFromJNI());

        _download_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startBackgroundLoading();
            }
        });

        // Первоначальный старт сервиса
        // Intent intent = new Intent(this, DownloadingService.class);
        //  startService(intent); - почему-то все равно вызывается onCreate сервиса
        // bindService(intent, _connection, Context.BIND_AUTO_CREATE);

        _loader = new FilesLoader(this);

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true){
                    try{
                        Thread.sleep(500);
                    }catch (Exception e) {
                    }

                    if (_bound.get()){
                        final double progress = _loader.getPercentProgressInfo();

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                _progress_bar.setProgress((int)progress);
                            }
                        });
                    }
                }
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        //unbindService(_connection);

        // TODO: Надо ли отключать сервис?
        // stopService(new Intent(this, DownloadingService.class));
    }

    void startBackgroundLoading(){
        if(_bound.get()){
            _tv.setText("Loading");
            _loader.startLoading("http://speedtest.ftp.otenet.gr/files/test10Mb.db", "test_file.db");
        }
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}
