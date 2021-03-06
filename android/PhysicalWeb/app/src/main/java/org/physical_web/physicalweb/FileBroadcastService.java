/*
 * Copyright 2016 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.physical_web.physicalweb;

import android.annotation.TargetApi;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Shares URLs via bluetooth.
 * Also interfaces with PWS to shorten URLs that are too long for Eddystone URLs.
 * Lastly, it surfaces a persistent notification whenever a URL is currently being broadcast.
 **/
@TargetApi(21)
public class FileBroadcastService extends Service {

    private static final String TAG = "FileBroadcastService";
    private static final int BROADCASTING_NOTIFICATION_ID = 7;
    public static final String FILE_KEY = "file";
    public static final String MIME_TYPE_KEY = "type";
    public int port;
    private NotificationManagerCompat mNotificationManager;
    private Handler mHandler = new Handler();
    private Uri mUri;
    private String mType;
    private byte[] mFile;
    private FileBroadcastServer mFileBroadcastServer;

    /////////////////////////////////
    // callbacks
    /////////////////////////////////

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
      if (mFileBroadcastServer != null) {
        mFileBroadcastServer.stop();
      }
      String mType = intent.getStringExtra(MIME_TYPE_KEY);
      Log.d(TAG, mType);
      Uri mUri = Uri.parse(intent.getStringExtra(FILE_KEY));
      Log.d(TAG, mUri.toString());
      port = Utils.getWifiDirectPort(this);
      try {
        mFile = getBytes(getContentResolver().openInputStream(mUri));
      } catch (FileNotFoundException e) {
        Log.d(TAG, e.getMessage());
      } catch (IOException e) {
        Log.d(TAG, e.getMessage());
      }
      mNotificationManager = NotificationManagerCompat.from(this);
      mFileBroadcastServer = new FileBroadcastServer(port, mType, mFile);
      try {
        mFileBroadcastServer.start();
        createNotification();
      } catch (IOException e) {
        Log.d(TAG, e.getMessage());
      }
      sendBroadcast(new Intent("server"));
      WifiP2pManager mManager = (WifiP2pManager) this.getSystemService(Context.WIFI_P2P_SERVICE);
      WifiP2pManager.Channel mChannel = mManager.initialize(this, this.getMainLooper(), null);
      changeWifiName();
      mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
          Log.d(TAG, "discovering");
        }

        @Override
        public void onFailure(int reasonCode) {
          Log.d(TAG, "discovery failed " + reasonCode);
        }
      });
      return START_STICKY;
    }



    @Override
    public void onDestroy() {
      Log.d(TAG, "SERVICE onDestroy");
      unregisterReceiver(stopServiceReceiver);
      mFileBroadcastServer.stop();
      mNotificationManager.cancel(BROADCASTING_NOTIFICATION_ID);
      super.onDestroy();
    }

        // Surface a notification to the user that a URL is being broadcast
    // The notification specifies the URL being broadcast (the long URL)
    // and cannot be swiped away
    private void createNotification() {
      Intent resultIntent = new Intent();
      TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
      stackBuilder.addParentStack(BroadcastActivity.class);
      stackBuilder.addNextIntent(resultIntent);
      PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0,
          PendingIntent.FLAG_UPDATE_CURRENT);
      registerReceiver(stopServiceReceiver, new IntentFilter("myFilter2"));
      PendingIntent pIntent = PendingIntent.getBroadcast(this, 0, new Intent("myFilter2"),
          PendingIntent.FLAG_UPDATE_CURRENT);
      NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this)
        .setSmallIcon(R.drawable.ic_leak_add_white_24dp)
        .setContentTitle("Physical Web is sharing with WifiDirect")
        .setContentText(Integer.toString(port))
        .setOngoing(true)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.stop), pIntent);
      mBuilder.setContentIntent(resultPendingIntent);

      NotificationManager mNotificationManager = (NotificationManager) getSystemService(
          Context.NOTIFICATION_SERVICE);
      mNotificationManager.notify(BROADCASTING_NOTIFICATION_ID, mBuilder.build());
    }

    protected BroadcastReceiver stopServiceReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        Log.d(TAG, context.toString());
        stopSelf();
      }
    };

    private void changeWifiName() {
      try {
        WifiP2pManager manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        WifiP2pManager.Channel channel = manager.initialize(this, getMainLooper(), null);
        Class[] paramTypes = new Class[3];
        paramTypes[0] = WifiP2pManager.Channel.class;
        paramTypes[1] = String.class;
        paramTypes[2] = WifiP2pManager.ActionListener.class;
        Method setDeviceName = manager.getClass().getMethod(
            "setDeviceName", paramTypes);
        setDeviceName.setAccessible(true);

        Object arglist[] = new Object[3];
        arglist[0] = channel;
        arglist[1] = "PW-Share-" + port;
        arglist[2] = new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                Log.d(TAG, "setDeviceName succeeded");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "setDeviceName failed");
            }
        };
        setDeviceName.invoke(manager, arglist);

      } catch (NoSuchMethodException e) {
        Log.d(TAG, e.getMessage());
      } catch (IllegalAccessException e) {
        Log.d(TAG, e.getMessage());
      } catch (IllegalArgumentException e) {
        Log.d(TAG, e.getMessage());
      } catch (InvocationTargetException e) {
        Log.d(TAG, e.getMessage());
      }
    }

    public byte[] getBytes(InputStream inputStream) throws IOException {
      ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
      int bufferSize = 1024;
      byte[] buffer = new byte[bufferSize];

      int len = 0;
      while ((len = inputStream.read(buffer)) != -1) {
        byteBuffer.write(buffer, 0, len);
      }
      return byteBuffer.toByteArray();
    }

}
