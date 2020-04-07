package com.example.downloadingtest;

import android.content.BroadcastReceiver;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.widget.Toast;
import android.util.Log;

import com.example.downloadingtest.DownloadingService;

public class DownloadingBroadcastReceiver extends BroadcastReceiver {
    final private String TAG = "DOWNLOAD_TAG";
    //private DownloadingService _service = null;

//    public DownloadingBroadcastReceiver(){
//    }

//    public DownloadingBroadcastReceiver(DownloadingService service){
//        Log.d(TAG, "Broadcast message receiver created");
//        _service = service;
//    }

//    public void onDestroy() {
//        Log.d(TAG, "Broadcast message receiver onDestroy");
//    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Toast.makeText(context, "Обнаружено сообщение!", Toast.LENGTH_LONG).show();

        Log.d(TAG, "Broadcast message received");

        if (intent.getAction().equals(DownloadManager.ACTION_NOTIFICATION_CLICKED)) {
            Log.d(TAG, "Receiver notification clicked");
        }
    }
}
