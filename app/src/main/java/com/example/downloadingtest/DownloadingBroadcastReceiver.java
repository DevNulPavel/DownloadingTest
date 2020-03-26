package com.example.downloadingtest;

import android.content.BroadcastReceiver;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.example.downloadingtest.DownloadingService;

public class DownloadingBroadcastReceiver extends BroadcastReceiver {
    final private String TAG = "DOWNLOAD_TAG";
    private DownloadingService _service = null;

    public DownloadingBroadcastReceiver(DownloadingService service){
        Log.d(TAG, "Broadcast message receiver created");
        _service = service;
    }

    public void onDestroy() {
        Log.d(TAG, "Broadcast message receiver onDestroy");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Broadcast message received");

        if (intent.getAction().equals(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) {
            //Fetching the download id received with the broadcast
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

            Log.d(TAG, "Loading broadcast message: " + id);

            //Checking if the received broadcast is for our enqueued download by matching download id
            _service.loadingFinished(id);

        }else if (intent.getAction().equals(DownloadManager.ACTION_NOTIFICATION_CLICKED)) {

        }else if (intent.getAction().equals(DownloadManager.ACTION_VIEW_DOWNLOADS)) {

        }
    }
}
