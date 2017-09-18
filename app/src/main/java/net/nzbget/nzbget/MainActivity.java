package net.nzbget.nzbget;

/**
 * Copyright 2015 Western Digital Corporation. All rights reserved.
 */

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

import com.wdc.nassdk.MyCloudUIServer;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getName();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int port = MyCloudUIServer.getPort(getApplicationContext());
                Log.d(TAG, "Port on which Ui server listening to :" + port);
                // This service intent is created to simulate the My Cloud Device.
                // this service will be invoked automatically with real my cloud user id in actual My Cloud Device.
                Intent intent = new Intent(MainActivity.this, StartupService.class);
                intent.setAction("START");
                intent.putExtra("MyCloudId", "ThisIsNotAnID");
                MainActivity.this.startService(intent);
            }
        }).start();
    }
}
