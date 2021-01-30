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
        findViewById(R.id.btn_loop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loop();
            }
        });
        findViewById(R.id.btn_show_normal).setOnClickListener(this);
        findViewById(R.id.btn_error_channel).setOnClickListener(this);
        findViewById(R.id.btn_error_layout).setOnClickListener(this);
    }

    private void loop() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Looper.loop();
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    private void hook() {
        try {
            //ActivityThread的sCurrentActivityThread在哪里赋值的？
            //答：main()->attach()中，它是全局单例。

            //拿ActivityThread的class对象
            Class<?> activityThreadClazz = Class.forName("android.app.ActivityThread");
            Field sCurrentActivityThread = activityThreadClazz.getDeclaredField("sCurrentActivityThread");
            sCurrentActivityThread.setAccessible(true);
            Object activityThread = sCurrentActivityThread.get(activityThreadClazz);

            //拿到SCHEDULE_CRASH的值
            Class<?> HClass = Class.forName("android.app.ActivityThread$H");
            Field scheduleCrashField = HClass.getDeclaredField("SCHEDULE_CRASH");
            scheduleCrashField.setAccessible(true);
            final int whatForScheduleCrash = scheduleCrashField.getInt(HClass);

            //拿mH实例
            Field mHField = activityThreadClazz.getDeclaredField("mH");
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

        Log.d("xfhy666","开始startService()");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
//测试时App在前台弹出Service，然后home到桌面，此时看它的状态
//方案A ： 正常展示通知的方式启动Service
//方案B ： 展示通知时，使用一个没有注册的channel
//方案C ： 展示通知时，使用一个错误的布局
//华为 Android 8.0.0 ,EMUI 8.0.0, 方案A :  oom cur=200，方案B :  oom cur=200,方案C :  200
//小米8（打了2020.9的补丁） Android 10，MIUI 12,方案A :  oom cur=50，方案B :  5秒后崩溃 ，方案C :  5秒后崩溃
//小米6 Android 8，MIUI 10,方案A :  oom cur=200，方案B :  oom cur=200，方案C :  200
//vivo nex Android 10,Funtouch OS 9.2,方案A :  adj=0,方案B :  adj=7,方案C :  adj=0
//原生Android 9，方案A :  adj=3，方案B : adj=11，方案C : adj=3
//三星 Galaxy A60,Android 10,补丁级别： 2020.12，方案B和C都会崩溃
