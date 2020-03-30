package com.example.downloadingtest;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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
import android.os.Environment;

import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;

import com.seventeenbullets.android.xgen.downloader.AndroidNativeRequestManager;


public class MainActivity extends AppCompatActivity {
    final private String TAG = "DOWNLOAD_TAG";
    private TextView _tv;
    private Button _download_button;
    private ProgressBar _progress_bar;
    //DownloadingService _downloadService;
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

    public native String stringFromJNI();
    public native void testNativeRequest();

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

        AndroidNativeRequestManager.initialize(this);

        /*new Thread(new Runnable() {
            @Override
            public void run() {
                while (true){
                    try{
                        Thread.sleep(500);
                    }catch (Exception e) {
                    }

                    //if (_bound.get()){
                    //}

                    final double progress = _loader.getPercentProgressInfo();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            _progress_bar.setProgress((int)progress);
                        }
                    });
                }
            }
        }).start();*/
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AndroidNativeRequestManager.finish();

        //unbindService(_connection);

        // TODO: Надо ли отключать сервис?
        // stopService(new Intent(this, DownloadingService.class));
    }

    void startBackgroundLoading(){
        if(_bound.get()){
            _tv.setText("Loading");
        }

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                // Explain to the user why we need to read the contacts
            }
            if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                // Explain to the user why we need to read the contacts
            }

            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    213123123);

            // MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE is an
            // app-defined int constant that should be quite unique

            return;
        }

        testNativeRequest();
    }
}
