
> 本文中的[demo地址](https://github.com/xfhy/increase-process-priority)，文中源码为API 28

## 一、前言

前不久，看到维术大佬发表的一篇文章：[另一种黑科技保活方法](https://mp.weixin.qq.com/s/VS9XjiyzHJ0chbrLAA1k6Q)。文章内容主要是利用Android的2个bug（黑科技就是利用系统bug骚操作）来提升进程的优先级为前台进程，觉得挺有意思，于是决定找个时间研究一下。因为原文中大佬主要写的是思路，所以流程比较粗略，没有提供具体的demo实现。

> 可能有些朋友不知道维术大佬，太极·虚拟框架就是他创作的。

我就想着自己简单实现一下，搞个demo看看效果。结果不搞不知道啊，这玩意儿搞起来可太花时间了，太多知识盲区了。

留下了没有技术含量的泪水.jpg

本文将分析Android的这2个bug在哪里、如何才能触发、方案实施、Android修复bug方法、统计各厂商实际效果。

### 展示一个正常的前台服务

在日常的开发中，展示一个前台服务是经常使用到的一个功能，大致如下：

```java
//创建channel
private void createChannel() {
    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name),
            NotificationManager.IMPORTANCE_HIGH);
    channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
    if (manager != null) {
        manager.createNotificationChannel(channel);
    }
}

//展示通知 成为前台服务
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
```

## 二、方案1：创建前台服务时传递一个错误channel

### 前置知识：startForeground流程

首先来分析一下startForeground的流程，方便后续理解。咱们在代码里面使用startForeground（）会来到Service#startForeground()中

```java
//Service.java
private IActivityManager mActivityManager = null;
public final void startForeground(int id, Notification notification) {
    try {
        mActivityManager.setServiceForeground(
                new ComponentName(this, mClassName), mToken, id,
                notification, 0);
    } catch (RemoteException ex) {
    }
}
```

这个方法里面实际上是调用的mActivityManager的setServiceForeground()来完成实际操作。而这个mActivityManager是一个IActivityManager接口，这个接口的实例是谁呢？我通过分析发现在Service#attach中有对其赋值

```java
//Service.java
public final void attach(
        Context context,
        ActivityThread thread, String className, IBinder token,
        Application application, Object activityManager) {
    attachBaseContext(context);
    ......
    mActivityManager = (IActivityManager)activityManager;
}
```

因之前我写过一篇博客，刚好分析过[Service的启动流程](https://juejin.cn/post/6844903865201098766)，里面见过这个方法。 看到这个熟悉的attach，我知道，肯定是在ActivityThread里面调用的这个方法了。

```java
//ActivityThread.java
private void handleCreateService(CreateServiceData data) {
    //构建Service 利用反射取构建实例
    Service service = null;
    java.lang.ClassLoader cl = packageInfo.getClassLoader();
    service = packageInfo.getAppFactory()
            .instantiateService(cl, data.info.name, data.intent);
    
    //初始化ContextImpl
    ContextImpl context = ContextImpl.createAppContext(this, packageInfo);

    Application app = packageInfo.makeApplication(false, mInstrumentation);
    //注意啦，在这里 传入的是ActivityManager.getService()
    service.attach(context, this, data.info.name, data.token, app,
            ActivityManager.getService());
    //接下来马上就会调用Service的onCreate方法
    service.onCreate();
    
    //mServices是用来存储已经启动的Service的
    mServices.put(data.token, service);
    ....
}
```

原来传入的是ActivityManager.getService()，就是ActivityManagerService的binder引用。所以，上面的startForeground逻辑来到了ActivityManagerService的setServiceForeground()。

```java
//ActivityManagerService.java
@Override
public void setServiceForeground(ComponentName className, IBinder token,
        int id, Notification notification, int flags) {
    synchronized(this) {
        //mServices中可以找到某个已经启动了的Service
        mServices.setServiceForegroundLocked(className, token, id, notification, flags);
    }
}

//ActiveServices.java
public void setServiceForegroundLocked(ComponentName className, IBinder token,
        int id, Notification notification, int flags) {
    final int userId = UserHandle.getCallingUserId();
    final long origId = Binder.clearCallingIdentity();
    try {
        //根据className, token, userId找到需要创建前台服务的Service的ServiceRecord
        ServiceRecord r = findServiceLocked(className, token, userId);
        if (r != null) {
            setServiceForegroundInnerLocked(r, id, notification, flags);
        }
    } finally {
        Binder.restoreCallingIdentity(origId);
    }
}

/**
* @param id Notification ID.  Zero === exit foreground state for the given service.
*/
private void setServiceForegroundInnerLocked(final ServiceRecord r, int id,
        Notification notification, int flags) {
    if (id != 0) {
        if (notification == null) {
            throw new IllegalArgumentException("null notification");
        }
        // Instant apps 
        if (r.appInfo.isInstantApp()) {
            ......
        } else if (r.appInfo.targetSdkVersion >= Build.VERSION_CODES.P) {
            //Android P以上需要确认有权限
            mAm.enforcePermission(
                    android.Manifest.permission.FOREGROUND_SERVICE,
                    r.app.pid, r.appInfo.uid, "startForeground");
        }
        
        .....
        r.postNotification();
        if (r.app != null) {
            updateServiceForegroundLocked(r.app, true);
        }
        getServiceMapLocked(r.userId).ensureNotStartingBackgroundLocked(r);
        mAm.notifyPackageUse(r.serviceInfo.packageName,
                PackageManager.NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE);
    } else {
        ......
    }
}
```

ActivityManagerService转手就交给ActiveServices去处理，ActiveServices一顿操作来到ServiceRecord的postNotification，这里就比较重要的，仔细看一下

```java
//ServiceRecord.java
public void postNotification() {
    final int appUid = appInfo.uid;
    final int appPid = app.pid;
    if (foregroundId != 0 && foregroundNoti != null) {
        // Do asynchronous communication with notification manager to
        // avoid deadlocks.
        final String localPackageName = packageName;
        final int localForegroundId = foregroundId;
        final Notification _foregroundNoti = foregroundNoti;
        ams.mHandler.post(new Runnable() {
            public void run() {
                //NotificationManagerService
                NotificationManagerInternal nm = LocalServices.getService(
                        NotificationManagerInternal.class);
                if (nm == null) {
                    return;
                }
                Notification localForegroundNoti = _foregroundNoti;
                try {
                    if (localForegroundNoti.getSmallIcon() == null) {
                        // It is not correct for the caller to not supply a notification
                        // icon, but this used to be able to slip through, so for
                        // those dirty apps we will create a notification clearly
                        // blaming the app.
                        Slog.v(TAG, "Attempted to start a foreground service ("
                                + name
                                + ") with a broken notification (no icon: "
                                + localForegroundNoti
                                + ")");

                        CharSequence appName = appInfo.loadLabel(
                                ams.mContext.getPackageManager());
                        if (appName == null) {
                            appName = appInfo.packageName;
                        }
                        Context ctx = null;
                        try {
                            ctx = ams.mContext.createPackageContextAsUser(
                                    appInfo.packageName, 0, new UserHandle(userId));

                            Notification.Builder notiBuilder = new Notification.Builder(ctx,
                                    localForegroundNoti.getChannelId());

                            // it's ugly, but it clearly identifies the app
                            notiBuilder.setSmallIcon(appInfo.icon);

                            // mark as foreground
                            notiBuilder.setFlag(Notification.FLAG_FOREGROUND_SERVICE, true);

                            Intent runningIntent = new Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            runningIntent.setData(Uri.fromParts("package",
                                    appInfo.packageName, null));
                            PendingIntent pi = PendingIntent.getActivityAsUser(ams.mContext, 0,
                                    runningIntent, PendingIntent.FLAG_UPDATE_CURRENT, null,
                                    UserHandle.of(userId));
                            notiBuilder.setColor(ams.mContext.getColor(
                                    com.android.internal
                                            .R.color.system_notification_accent_color));
                            notiBuilder.setContentTitle(
                                    ams.mContext.getString(
                                            com.android.internal.R.string
                                                    .app_running_notification_title,
                                            appName));
                            notiBuilder.setContentText(
                                    ams.mContext.getString(
                                            com.android.internal.R.string
                                                    .app_running_notification_text,
                                            appName));
                            notiBuilder.setContentIntent(pi);

                            localForegroundNoti = notiBuilder.build();
                        } catch (PackageManager.NameNotFoundException e) {
                        }
                    }

                    //注意了，如果是没有创建channel，则会抛出一个RuntimeException
                    if (nm.getNotificationChannel(localPackageName, appUid,
                            localForegroundNoti.getChannelId()) == null) {
                        int targetSdkVersion = Build.VERSION_CODES.O_MR1;
                        try {
                            final ApplicationInfo applicationInfo =
                                    ams.mContext.getPackageManager().getApplicationInfoAsUser(
                                            appInfo.packageName, 0, userId);
                            targetSdkVersion = applicationInfo.targetSdkVersion;
                        } catch (PackageManager.NameNotFoundException e) {
                        }
                        if (targetSdkVersion >= Build.VERSION_CODES.O_MR1) {
                            throw new RuntimeException(
                                    "invalid channel for service notification: "
                                            + foregroundNoti);
                        }
                    }
                    if (localForegroundNoti.getSmallIcon() == null) {
                        // Notifications whose icon is 0 are defined to not show
                        // a notification, silently ignoring it.  We don't want to
                        // just ignore it, we want to prevent the service from
                        // being foreground.
                        throw new RuntimeException("invalid service notification: "
                                + foregroundNoti);
                    }
                    nm.enqueueNotification(localPackageName, localPackageName,
                            appUid, appPid, null, localForegroundId, localForegroundNoti,
                            userId);

                    foregroundNoti = localForegroundNoti; // save it for amending next time
                } catch (RuntimeException e) {
                    //上面的Exception 在这里会被捕获  展示Notification失败了
                    Slog.w(TAG, "Error showing notification for service", e);
                    // If it gave us a garbage notification, it doesn't
                    // get to be foreground.
                    //给我一个垃圾Notification，还想成为前台服务？妄想
                    ams.setServiceForeground(name, ServiceRecord.this,
                            0, null, 0);
                    //调用AMS#crashApplication()
                    ams.crashApplication(appUid, appPid, localPackageName, -1,
                            "Bad notification for startForeground: " + e);
                }
            }
        });
    }
}
```

这段代码的核心思想是构建Notification，然后告知NotificationManagerService需要展示通知。在展示通知之前，会先判断一下是否有为这个通知创建好channel，如果没有则抛出异常，然后方法末尾的catch会将抛出的异常给捕获住。

捕获住异常之后，系统执行收尾清理工作。系统知道这个通知创建失败了，将该Service设置为非前台。然后调用AMS的crashApplication()，看着方法名看起来是想营造一个crash给app。咱跟下去，看看是啥情况

```java
@Override
public void crashApplication(int uid, int initialPid, String packageName, int userId,
        String message) {
    synchronized(this) {
        //mAppErrors是AppErrors
        mAppErrors.scheduleAppCrashLocked(uid, initialPid, packageName, userId, message);
    }
}

//AppErrors.java
/**
* Induce a crash in the given app.
*/
void scheduleAppCrashLocked(int uid, int initialPid, String packageName, int userId,
        String message) {
    ProcessRecord proc = null;

    // Figure out which process to kill.  We don't trust that initialPid
    // still has any relation to current pids, so must scan through the
    // list.

    synchronized (mService.mPidsSelfLocked) {
        for (int i=0; i<mService.mPidsSelfLocked.size(); i++) {
            ProcessRecord p = mService.mPidsSelfLocked.valueAt(i);
            if (uid >= 0 && p.uid != uid) {
                continue;
            }
            if (p.pid == initialPid) {
                proc = p;
                break;
            }
            if (p.pkgList.containsKey(packageName)
                    && (userId < 0 || p.userId == userId)) {
                proc = p;
            }
        }
    }
    proc.scheduleCrash(message);
}
```

好家伙，从AppErrors的scheduleAppCrashLocked()注释看，是让一个app崩溃。

```java
//ProcessRecord.java
IApplicationThread thread; 
void scheduleCrash(String message) {
    // Checking killedbyAm should keep it from showing the crash dialog if the process
    // was already dead for a good / normal reason.
    if (!killedByAm) {
        if (thread != null) {
            long ident = Binder.clearCallingIdentity();
            try {
                //thread是IApplicationThread,实际上是ActivityThread中的ApplicationThread
                thread.scheduleCrash(message);
            } catch (RemoteException e) {
                // If it's already dead our work is done. If it's wedged just kill it.
                // We won't get the crash dialog or the error reporting.
                kill("scheduleCrash for '" + message + "' failed", true);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
}
```

ProcessRecord的scheduleCrash()的核心代码是执行thread的scheduleCrash()。但是这个thread是什么，我们暂时不知道。

这里的thread是IApplicationThread，IApplicationThread是一个接口并且继承自android.os.IInterface，它在源码中的存在形式是IApplicationThread.aidl （路径：frameworks/base/core/java/android/app/IApplicationThread.aidl）,[在线源码观看地址](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/IApplicationThread.aidl;l=59?q=IApplicationThread)。 看起来是在跨进程通信，通信双方是AMS进程与app进程。app端接收消息的地方在ActivityThread的ApplicationThread

```java
public final class ActivityThread extends ClientTransactionHandler {
    private class ApplicationThread extends IApplicationThread.Stub {
        //ApplicationThread是ActivityThread的内部类
        //看这个标准的样子，就知道肯定和aidl有关
    }
}

```

于是上面的ProcessRecord的scheduleCrash()其实是想通知ApplicationThread执行scheduleCrash()，注意，这里是跨进程的。

```java
//ActivityThread#ApplicationThread
public void scheduleCrash(String msg) {
    sendMessage(H.SCHEDULE_CRASH, msg);
}

void sendMessage(int what, Object obj) {
    sendMessage(what, obj, 0, 0, false);
}

private void sendMessage(int what, Object obj, int arg1, int arg2, boolean async) {
    Message msg = Message.obtain();
    msg.what = what;
    msg.obj = obj;
    msg.arg1 = arg1;
    msg.arg2 = arg2;
    if (async) {
        msg.setAsynchronous(true);
    }
    mH.sendMessage(msg);
}
```

而在ApplicationThread的scheduleCrash()方法中，看起来只是发了个消息给mH这个Handler。

```java
//ActivityThread.java
final H mH = new H();

class H extends Handler {
    public static final int SCHEDULE_CRASH          = 134;

    public void handleMessage(Message msg) {
        if (DEBUG_MESSAGES) Slog.v(TAG, ">>> handling: " + codeToString(msg.what));
        switch (msg.what) {
            ......
            case SCHEDULE_CRASH:
                throw new RemoteServiceException((String)msg.obj);
        }
    }
}
```

H这个Handler我们再熟悉不过了，什么绑定Application、绑定Service、停止Service、Activity生命周期回调什么的，都得靠这个Handler。H这个Handler在接收到`SCHEDULE_CRASH`这个消息时，会抛出一个RemoteServiceException。

到这里，startForeground()时传入一个不存在的channel的流程就走完了，系统会抛出一个异常导致app崩溃。正常情况下，这是没有什么问题的。

### 这里的漏洞是什么？

假设我把这个消息拦截下来，然后不抛出错误，那app岂不是正常继续运行咯。确实是这样。

### 方案实施

#### 思路1 拦截消息

系统让app这边抛出一个异常，自行结束生命。那我收到系统给的指示，然后不抛出异常，不就可以绕过了么？那么，怎么绕？

可以hook这个ActivityThread的H，拦截其`SCHEDULE_CRASH`消息，然后做自己想做的事情。

大体思路倒是有了，具体如何实现呢? 要hook这个H，那首先我们要拿到ActivityThread的实例（一个app进程对应着一个ActivityThread）。在搜寻ActivityThread的API过程中发现一个东西

```java
/** Reference to singleton {@link ActivityThread} */
private static volatile ActivityThread sCurrentActivityThread;

public static ActivityThread currentActivityThread() {
    return sCurrentActivityThread;
}

private void attach(boolean system, long startSeq) {
    sCurrentActivityThread = this;
    ......
}

public static void main(String[] args) {
    Looper.prepareMainLooper();

    ActivityThread thread = new ActivityThread();
    thread.attach(false, startSeq);

    if (sMainThreadHandler == null) {
        sMainThreadHandler = thread.getHandler();
    }

    Looper.loop();

    throw new RuntimeException("Main thread loop unexpectedly exited");
}
```

我注意到有一个sCurrentActivityThread的东西，在main方法里面一开始就初始化好了，然后从它的注释也能看出它是一个全局单例，即它就是ActivityThread的实例了，拿到它就好办了。然后接着我发现一个currentActivityThread（）的静态方法，妙啊，原来系统早就想好了，给我们提供了一个public静态方法方便获取ActivityThread实例。我兴致冲冲地跑去Activity里面使用时，却发现，我好像连ActivityThread这个类都无法访问。

```java
/**
 * {@hide}
 */
public final class ActivityThread extends ClientTransactionHandler {}
```
好家伙，加了`{@hide}`，静态方法是用不起了。虽然静态方法是用不起了，但是我们可以反射拿到这个sCurrentActivityThread静态变量。

```java
//拿ActivityThread的class对象
Class<?> activityThreadClazz = Class.forName("android.app.ActivityThread");
Field sCurrentActivityThread = activityThreadClazz.getDeclaredField("sCurrentActivityThread");
sCurrentActivityThread.setAccessible(true);
Object activityThread = sCurrentActivityThread.get(activityThreadClazz);
```

ActivityThread实例倒是拿到了，接下来我们需要拦截里面的H这个Handler的消息。自己写一个Handler然后把原来的H这个Handler替换掉？不行，里面那么多逻辑，我们自己搞风险太大了，而且不现实。但是，我们可以给这个Handler设置一个mCallback。回忆一下：

```java
//Handler.java
/**
 * Handle system messages here.
 */
public void dispatchMessage(Message msg) {
    if (msg.callback != null) {
        handleCallback(msg);
    } else {
        if (mCallback != null) {
            if (mCallback.handleMessage(msg)) {
                return;
            }
        }
        handleMessage(msg);
    }
}
```

Handler在分发消息时，发现mCallback不为空，则先交给mCallback处理，如果mCallback处理结果返回false，再交给handleMessage进行处理。

基于这个，咱思路有了，hook那个Handler的mCallback，然后只处理`SCHEDULE_CRASH`这个消息，其他的不管，还是交给原来的Handler的handleMessage进行处理。因为我们只处理`SCHEDULE_CRASH`这个消息，所以把风险降到了最低。

思路有了，show me the code：

```java
//拿到SCHEDULE_CRASH的int值，源码里面写的是134，为了防止官方后面修改了这个值，这个134不直接写死
Class<?> HClass = Class.forName("android.app.ActivityThread$H");
Field scheduleCrashField = HClass.getDeclaredField("SCHEDULE_CRASH");
scheduleCrashField.setAccessible(true);
final int whatForScheduleCrash = scheduleCrashField.getInt(HClass);

//拿mH实例
Field mHField = activityThreadClazz.getDeclaredField("mH");
mHField.setAccessible(true);
Handler mH = (Handler) mHField.get(activityThread); 

//给mH设置一个mCallback
Class<?> handlerClass = Class.forName("android.os.Handler");
Field mCallbackField = handlerClass.getDeclaredField("mCallback");
mCallbackField.setAccessible(true);
mCallbackField.set(mH, new Handler.Callback() {
    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (msg.what == whatForScheduleCrash) {
            Log.d("xfhy_hook", "收到一杯罚酒，我干了，你随意");
            return true;
        }
        return false;
    }
});
```

好了，到这里，我们已经hook成功了。现在去启动前台服务，用一个没有创建channel的通知看起来也不会崩溃了（也不一定，厂商可能修改了这部分逻辑，后面有验证结果）。这种办法启动的前台服务是不会展示任何通知在状态栏上的，用户无感知。

#### 思路2 Handle the exception in main loop

大家先看看下面这段代码，就这么一小段代码即可达到与思路1同样的效果。

```java
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
```

给主线程的Looper发送了一个消息，这个消息的callback是上面的这个Runnable，实际执行逻辑是一段看起来像死循环一样的代码。

分析一下，我们知道，在主线程中维护了Handler的消息机制，在应用启动的时候就做好了Looper的创建和初始化，然后开始使用Looper.loop()循环处理消息。

```java
//ActivityThread.java
public static void main(String[] args) {
    //准备主线的MainLooper
    Looper.prepareMainLooper();

    ActivityThread thread = new ActivityThread();
    thread.attach(false, startSeq);

    if (sMainThreadHandler == null) {
        sMainThreadHandler = thread.getHandler();
    }

    //开始loop循环
    Looper.loop();

    //loop循环是不能结束的，否则app就会异常退出咯
    throw new RuntimeException("Main thread loop unexpectedly exited");
}
```

我们在使用app的过程中，用户的所有操作事件、Activity生命周期回调、列表滑动等等，都是通过Looper的loop循环中完成处理的，其本质是将消息加入MessageQueue队列，然后循环从这个队列中取出消息并处理。如果没有消息可以处理的时候，会依靠Linux的epoll机制暂时挂起等待唤醒。下面是loop的核心代码：

```java
public static void loop() {
    final Looper me = myLooper();
    final MessageQueue queue = me.mQueue;
    for (;;) {
        Message msg = queue.next(); 
        msg.target.dispatchMessage(msg);
    }
}
```

死循环，不断取消息，没有消息的话就暂时挂起。我们上面那段短小精炼的代码，通过Handler往主线程发送了一个Runnable任务，然后在里面执行了一个死循环，死循环地执行Looper的loop方法读取消息。只要Looper的loop方法执行到了咱这个Message的callback，那么后面所有的主线程消息都会走到我们这个loop方法中进行处理。一旦发生了主线程崩溃，那么这里就可以进行异常捕获。然后又是死循环，捕获到异常之后，又开始继续执行Looper的loop方法，这样主线程就可以一直正常读取消息，刷新UI啥的都是正常的，不会有影响。

这样的话，不管在ActivityThread#mH的handleMessage()中抛出什么异常都没事了。

### Android是如何修复的

还好，谷歌在2020.8就修复了这个问题。

```java
//ServiceRecord.java
@@ -798,6 +798,7 @@
             final String localPackageName = packageName;
             final int localForegroundId = foregroundId;
             final Notification _foregroundNoti = foregroundNoti;
+            final ServiceRecord record = this;
             ams.mHandler.post(new Runnable() {
                 public void run() {
                     NotificationManagerInternal nm = LocalServices.getService(
@@ -896,10 +897,8 @@
                         Slog.w(TAG, "Error showing notification for service", e);
                         // If it gave us a garbage notification, it doesn't
                         // get to be foreground.
-                        ams.setServiceForeground(instanceName, ServiceRecord.this,
-                                0, null, 0, 0);
-                        ams.crashApplication(appUid, appPid, localPackageName, -1,
-                                "Bad notification for startForeground: " + e);
+                        ams.mServices.killMisbehavingService(record,
+                                appUid, appPid, localPackageName);
                     }
                 }
             });

//ActiveServices.java
+    void killMisbehavingService(ServiceRecord r,
+            int appUid, int appPid, String localPackageName) {
+        synchronized (mAm) {
+            stopServiceLocked(r);
+            mAm.crashApplication(appUid, appPid, localPackageName, -1,
+                    "Bad notification for startForeground", true /*force*/);
+        }
+    }
+

//AppErrors.java
//如果force是true，则5秒之后把app干死
if (force) {
    // If the app is responsive, the scheduled crash will happen as expected
    // and then the delayed summary kill will be a no-op.
    final ProcessRecord p = proc;
    mService.mHandler.postDelayed(
            () -> killAppImmediateLocked(p, "forced", "killed for invalid state"),
            5000L);
}

```

postNotification()的时候，如果发现是前台服务，那么将调用停掉该前台服务，当然crashApplication()还是得调的。

## 三、方案2：创建前台服务时搞一个错误布局

### 系统如何处理创建前台服务时的错误布局

这里就不带大家分析了，创建前台服务时遇到错误布局，最后会来到onNotificationError()。

```java
@Override
public void onNotificationError(int callingUid, int callingPid, String pkg, String tag, int id,
        int uid, int initialPid, String message, int userId) {
    cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 0, false, userId,
            REASON_ERROR, null);
}
```

可以看到，连崩溃都没有，只是简单取消一下通知就完了。

### 这里的漏洞是什么？

既然没有崩溃，那开发者就可以传递一个错误的布局id过来，然后只是通知被取消了，前台服务还是被创建成功了，而且还是没有展示通知的。

### 方案实施

只需要在开启前台服务的时候，自定义布局那里传递一个不存在的布局id即可。

```java
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
        e.printStackTrace();
    }
}
```

### Android是如何修复的

同样也是在2020.8修复了该问题，crashApplication方法这次传递的force是true，必须死。

```java
//NotificationManagerService.java
@Override
public void onNotificationError(int callingUid, int callingPid, String pkg, String tag,
        int id, int uid, int initialPid, String message, int userId) {
    final boolean fgService;
    synchronized (mNotificationLock) {
        NotificationRecord r = findNotificationLocked(pkg, tag, id, userId);
        fgService = r != null && (r.getNotification().flags & FLAG_FOREGROUND_SERVICE) != 0;
    }
    cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 0, false, userId,
            REASON_ERROR, null);
    if (fgService) {
        // Still crash for foreground services, preventing the not-crash behaviour abused
        // by apps to give us a garbage notification and silently start a fg service.
        Binder.withCleanCallingIdentity(
                () -> mAm.crashApplication(uid, initialPid, pkg, -1,
                    "Bad notification(tag=" + tag + ", id=" + id + ") posted from package "
                        + pkg + ", crashing app(uid=" + uid + ", pid=" + initialPid + "): "
                        + message, true /* force */));
    }
}
```

## 四、实际效果

测试方式：测试时App在前台弹出Service，然后按home键回到桌面，此时看app的adj（进程优先级，0表示在前台，越小则表示优先级越高）状态。

先使用命令行`adb shell ps -A | grep xfhy`找到我的demo进程，查看进程号

```
u0_a85       10502  1796 1445700 104284 0                   0 S com.xfhy.frontservicedemo
```

上面的10502即是进程号，然后通过`adb shell cat proc/10502/oom_adj`查看该进程adj值。这种方式只适用于部分手机，一些手机上会提示你没有权限，这时只能使用`adb shell dumpsys activity processes`，然后找到你的进程。如下面的日志中，有一个`oom: max=1001 curRaw=0 setRaw=0 cur=0 set=0`的值，勉强可以看出点东西，这些值也是越小则优先级越高。

```log
*APP* UID 10085 ProcessRecord{587081b 10502:com.xfhy.frontservicedemo/u0a85}
    user #0 uid=10085 gids={50085, 20085, 9997}
    requiredAbi=x86 instructionSet=null
    dir=/data/app/com.xfhy.frontservicedemo-rujs7qyX7dkx9BoO0UwMug==/base.apk publicDir=/data/app/com.xfhy.frontservicedemo-rujs7qyX7dkx9BoO0UwMug==/base.apk data=/data/user/0/com.xfhy.frontservicedemo
    packageList={com.xfhy.frontservicedemo}
    compat={320dpi}
    thread=android.app.IApplicationThread$Stub$Proxy@ed8ddb8
    pid=10502 starting=false
    lastActivityTime=-2m14s174ms lastPssTime=-36s962ms pssStatType=0 nextPssTime=+53s9ms
    adjSeq=17211 lruSeq=0 lastPss=32MB lastSwapPss=0.00 lastCachedPss=0.00 lastCachedSwapPss=0.00
    procStateMemTracker: best=1 (1=1 2.25x)
    cached=false empty=false
    oom: max=1001 curRaw=0 setRaw=0 cur=0 set=0
    curSchedGroup=3 setSchedGroup=3 systemNoUi=false trimMemoryLevel=0
    curProcState=2 repProcState=2 pssProcState=2 setProcState=2 lastStateTime=-2m14s174ms
    hasShownUi=true pendingUiClean=true hasAboveClient=false treatLikeActivity=false
    reportedInteraction=true time=-2m14s178ms
    hasClientActivities=false foregroundActivities=true (rep=true)
    startSeq=86
    lastRequestedGc=-2m14s211ms lastLowMemory=-2m14s211ms reportLowMemory=false
    Activities:
      - ActivityRecord{ee9b8e4 u0 com.xfhy.frontservicedemo/.MainActivity t17}
    Recent Tasks:
      - TaskRecord{a34a791 #17 A=com.xfhy.frontservicedemo U=0 StackId=12 sz=1}
    Connected Providers:
      - 6e7baff/com.android.providers.settings/.SettingsProvider->10502:com.xfhy.frontservicedemo/u0a85 s1/1 u0/0 +2m12s890ms
```

- 方案A ： 正常展示通知的方式启动Service,作为对比
- 方案B ： 展示通知时，使用一个没有注册的channel
- 方案C ： 展示通知时，使用一个错误的布局


手机 | Android版本 | ROM 版本 | 补丁版本 | 方案A | 方案B | 方案C
---|---|---|---|---|---|---
荣耀6x | 8.0 | 8.0 | 2020.9 | oom cur=200 | oom cur=200 | oom cur=200
小米8 | 10 | 12 | 2020.9 | oom cur=50 | 5秒后崩溃 | 5秒后崩溃
小米6 | 8 | 10 |  | oom cur=200 | oom cur=200 | oom cur=200
vivo nex | 10 | 9.2 |  | adj=0 | adj=7 | adj=0
三星 Galaxy A60 | 10 | | 2020.12 | | 崩溃 | 崩溃
原生 | 9 | | 2019.8 | adj=3 | adj=11 | adj=3

从实际效果来看，大部分情况下表现良好，但是部分手机上可能导致app崩溃，这是不能接受的。比较有趣的是,部分手机打了补丁依然能正常运行，没有杀死app。

## 五、题外话

- 系统有提示升级就尽快升级，里面可能修复了大量漏洞之类的，让手机更安全，买手机尽量选择更新系统比较频繁的。
- 2021年了，保活基本上是不太可能了，各大厂商招揽顶尖人才搞出稳定的Android系统，和系统抗衡是不可能的。还是好好做好产品，让用户爱上产品才是真正的保活
- **严正声明**：本文相关技术仅限于技术研究使用，不能用于非法目的，否则后果自负

## 六、资料

- [本文demo地址](https://github.com/xfhy/increase-process-priority)
- [另一种黑科技保活方法](https://mp.weixin.qq.com/s/VS9XjiyzHJ0chbrLAA1k6Q)
- [ServiceCheater](https://github.com/CrackerCat/ServiceCheater)
- [能否让APP永不崩溃？ 小光和我的对决](https://mp.weixin.qq.com/s/ZzkgnhalwBv9yAwHj8jQVA)
- [CVE-2020-0108](http://cve.mitre.org/cgi-bin/cvename.cgi?name=cve-2020-0108)
- [CVE-2020-0313](http://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2020-0313)

