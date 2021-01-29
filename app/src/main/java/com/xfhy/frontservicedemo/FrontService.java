package com.xfhy.frontservicedemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

public class FrontService extends Service {

    String CHANNEL_ID = "demo_channel";
    String CHANNEL_ERROR_ID = "demo_channel_error";

    Thread thread;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d("xfhy666", "Service onCreate()");

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!thread.isInterrupted()) {
                    Log.d("xfhy666", "线程运行中..." + System.currentTimeMillis());
                    try {
                        Thread.sleep(3000);
                    } catch (Exception e) {
                        //e.printStackTrace();
                    }
                }
            }
        });
        thread.start();

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return super.onStartCommand(null, flags, startId);
        }
        int show = intent.getIntExtra("show", 1);
        if (show == 1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                showNormalNotify();
            }
        } else if (show == 2) {
            showNoChannelNotify();
        } else if (show == 3) {
            showErrorLayoutNotify();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * 没有创建channel
     * 1. 默认情况下是要崩溃的
     * 2. hook ActivityThread.mH 的mCallback
     * 3. 这个消息不处理  SCHEDULE_CRASH
     */
    private void showNoChannelNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        Notification notification = new Notification.Builder(this, CHANNEL_ERROR_ID)
                .setAutoCancel(false)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("运行中...")
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .build();
        try {
            startForeground(1, notification);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    private void showErrorLayoutNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        createChannel();

        RemoteViews remoteViewTemplate = new RemoteViews(getPackageName(), /*R.layout.layout_test*/4);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        builder.setOngoing(true);
        builder.setContent(remoteViewTemplate);
        builder.setTicker("fuck");
        builder.setPriority(NotificationCompat.PRIORITY_LOW);
        builder.setSmallIcon(R.mipmap.ic_launcher_round);
        try {
            startForeground(1, builder.build());
        } catch (Exception e) {
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void showNormalNotify() {
        createChannel();

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setAutoCancel(false)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("运行中...")
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .build();
        startForeground(1, notification);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createChannel() {

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel Channel = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH);
        //设置锁屏可见 VISIBILITY_PUBLIC=可见
        Channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        if (manager != null) {
            manager.createNotificationChannel(Channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (thread != null) {
            thread.interrupt();
        }
        Log.d("xfhy666", "Service onDestroy()");
    }
}