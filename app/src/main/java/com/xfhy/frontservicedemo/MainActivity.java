package com.xfhy.frontservicedemo;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        findViewById(R.id.btn_refect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hook();
            }
        });
        findViewById(R.id.btn_show_normal).setOnClickListener(this);
        findViewById(R.id.btn_error_channel).setOnClickListener(this);
        findViewById(R.id.btn_error_layout).setOnClickListener(this);
    }

    private void hook() {
        try {
            //todo xfhy ActivityThread在哪里赋值的？

            //拿ActivityThread实例
            Class<?> aClass = Class.forName("android.app.ActivityThread");
            Field sCurrentActivityThread = aClass.getDeclaredField("sCurrentActivityThread");
            sCurrentActivityThread.setAccessible(true);
            Object activityThread = sCurrentActivityThread.get(aClass);

            //拿到SCHEDULE_CRASH的值
            Class<?> HClass = Class.forName("android.app.ActivityThread$H");
            Field scheduleCrashField = HClass.getDeclaredField("SCHEDULE_CRASH");
            scheduleCrashField.setAccessible(true);
            final int whatForScheduleCrash = scheduleCrashField.getInt(HClass);

            //拿mH实例
            Field mHField = aClass.getDeclaredField("mH");
            mHField.setAccessible(true);
            Handler mH = (Handler) mHField.get(activityThread);   //activityThread  实例

            //给mH设置一个mCallback
            Class<?> handlerClass = Class.forName("android.os.Handler");
            Field mCallbackField = handlerClass.getDeclaredField("mCallback");
            mCallbackField.setAccessible(true);
            mCallbackField.set(mH, new Handler.Callback() {
                @Override
                public boolean handleMessage(@NonNull Message msg) {
                    if (msg.what == whatForScheduleCrash) {
                        Log.d("xfhy_hook", "收到一杯罚酒");
                        return true;
                    }
                    return false;
                }
            });

            Log.d("xfhy_hook", "hook success");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View v) {
        Intent intent = new Intent(this, FrontService.class);
        switch (v.getId()) {
            case R.id.btn_show_normal:
                intent.putExtra("show", 1);
                break;
            case R.id.btn_error_channel:
                intent.putExtra("show", 2);
                break;
            case R.id.btn_error_layout:
                intent.putExtra("show", 3);
                break;
        }

        Handler handler = new Handler(Looper.myLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.d("xfhy666","开始startService()");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            }
        }, 10 * 1000);
    }
}