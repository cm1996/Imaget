package ssdut.chenmo.imagetlibrary;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import libcore.io.DiskLruCache;

/**
 * Created by chenmo
 * This class is the main util to download/use image
 * Use singleton to make using it easy and light
 * min SDK = 14
 * Warning: Thread unsafe, please use me only in UI Thread.
 */

public class Imaget {

    private static final String TAG = "Imaget";
    private static Context mContext;
    private LruCache<String, Bitmap> mLruCache;
    private DiskLruCache mDiskLruCache;


    /** 工具控制与使用相关 */
    private BitmapDecor mBitmapDecor;
    private static Imaget mInstance;
    private static String mUrl;
    private static boolean isWorking = false;
    private static long DISK_CACHE_SIZE = 30 * 1024 * 1024;

    /** 一些常量 */
    private static final int LOAD_FAIL= 1000;
    private static final int LRU_LOAD_SUCCESS = 1001;
    private static final int DLRU_LOAD_SUCCESS = 1002;
    private static final int NET_LOAD_SUCCESS = 1003;


    /** 线程池相关参数 依照AsyncTask配置 */
    private int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private int CORE_POOL_SIZE = CPU_COUNT + 1;
    private int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private int KEEP_ALIVE = 5;

    private final ThreadFactory mThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            return new Thread(r, "Imaget #" + mCount.getAndIncrement());
        }
    };

    private Executor THREAD_POOL_EXCUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(CORE_POOL_SIZE/2), mThreadFactory
    );

    public static void on(Context context) {

        mContext = context.getApplicationContext();
        if(!isWorking) {
            mInstance = new Imaget();
            /** 初始化内存缓存 */
            mInstance.mLruCache = new LruCache<String, Bitmap>
                    ((int)(Runtime.getRuntime().maxMemory())/1024/8) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getRowBytes()*value.getHeight()/1024;
                }
            };

            /** 初始化外存缓存 */
            File diskCache;
            String diskCachePath;
            if(Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                diskCachePath = Environment.getExternalStorageDirectory()+"/"+TAG;
            } else {
                diskCachePath = mContext.getFilesDir()+"/"+TAG;
            }
            diskCache = new File(diskCachePath);
            if(!diskCache.exists()) {
                diskCache.mkdirs();
            }
            long realDiskCacheSize =
                    diskCache.getUsableSpace()>DISK_CACHE_SIZE?
                            DISK_CACHE_SIZE:
                            diskCache.getUsableSpace()/2;
            try {
                if(realDiskCacheSize>0) {
                    mInstance.mDiskLruCache = DiskLruCache.open(diskCache, 1,1, realDiskCacheSize);
                } else {
                    Log.e(TAG, "DiskLruCache create failed for no space");
                    Log.e(TAG, "usable space: "+realDiskCacheSize);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        isWorking = true;
    }

    public static void off() {
        mInstance = null;
        System.gc();
        isWorking = false;
    }

    private Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case LRU_LOAD_SUCCESS:
                case DLRU_LOAD_SUCCESS:
                case NET_LOAD_SUCCESS:
                    ((RequestBean)msg.obj).onLoadListener.onSuccess(((RequestBean)msg.obj).bitmap);
                    break;
                case LOAD_FAIL:
                    ((RequestBean)msg.obj).onLoadListener.onFail();
                    break;
            }
        }
    };

    public static Imaget url(String url) throws Exception {
        if(isWorking) {
            mUrl = url;
            return mInstance;
        } else {
            throw new Exception("can not use imaget before Imaget.on(Context)");
        }
    }

    public void setBitmapDecor(BitmapDecor bitmapDecor) {
        mBitmapDecor = bitmapDecor;
    }

    public void load(OnLoadListener onLoadListener) {
        load(onLoadListener, 0, 0);
    }

    public void load(OnLoadListener onLoadListener, int requiredWidth, int requiredHeight) {
        realLoad(mUrl, 0, 0, onLoadListener);
    }

    public Bitmap loadSync(int requiredWidth, int requiredHeight) {
        Bitmap bitmap = null;
        String url = mUrl;
        if((bitmap = loadFromLru(url))!=null) {
            Log.d(TAG, "loadFromLru: "+url);
            if(mBitmapDecor != null) {
                bitmap = mBitmapDecor.decor(bitmap);
            }
            return bitmap;
        }
        if(mDiskLruCache != null) {
            if((bitmap = loadFromDiskLru(url,requiredWidth,requiredHeight))!=null) {
                Log.d(TAG, "loadFromDickLru: "+url);
                if(mBitmapDecor != null) {
                    bitmap = mBitmapDecor.decor(bitmap);
                }
                return bitmap;
            }
        }
        bitmap = loadFromNetwork(url,requiredWidth,requiredHeight);
        if(mBitmapDecor != null) {
            bitmap = mBitmapDecor.decor(bitmap);
        }
        return bitmap;
    }

    public void bind(ImageView imageView) {
        bind(imageView,0,0);
    }

    public void bind(final ImageView imageView, int requiredWidth, int requiredHeight) {
        final String url = mUrl;
        imageView.setTag(url);
        realLoad(mUrl, requiredWidth, requiredHeight, new OnLoadListener() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if(url.equals(imageView.getTag())) {
                    imageView.setImageBitmap(bitmap);
                }
                imageView.setTag("");
            }

            @Override
            public void onFail() {
                imageView.setTag("");
                Log.e(TAG, "load image failed");
            }
        });

    }


    private void realLoad(final String url,
                          final int requiredWidth, final int requiredHeight,
                          final OnLoadListener onLoadListener) {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                RequestBean requestBean = new RequestBean(url, onLoadListener);
                Message message = mHandler.obtainMessage();
                message.obj = requestBean;
                if((bitmap = loadFromLru(url))!=null) {
                    Log.d(TAG, "loadFromLru: "+url);
                    message.what = LRU_LOAD_SUCCESS;
                    if(mBitmapDecor != null) {
                        bitmap = mBitmapDecor.decor(bitmap);
                    }
                    requestBean.bitmap = bitmap;
                    mHandler.sendMessage(message);
                    return;
                }
                if(mDiskLruCache != null) {
                    if((bitmap = loadFromDiskLru(url,requiredWidth,requiredHeight))!=null) {
                        Log.d(TAG, "loadFromDickLru: "+url);
                        message.what = DLRU_LOAD_SUCCESS;
                        if(mBitmapDecor != null) {
                            bitmap = mBitmapDecor.decor(bitmap);
                        }
                        requestBean.bitmap = bitmap;
                        mHandler.sendMessage(message);
                        return;
                    }
                }
                if((bitmap = loadFromNetwork(url,requiredWidth,requiredHeight))!=null) {
                    Log.d(TAG, "loadFromNetwork: "+url);
                    message.what = NET_LOAD_SUCCESS;
                    if(mBitmapDecor != null) {
                        bitmap = mBitmapDecor.decor(bitmap);
                    }
                    requestBean.bitmap = bitmap;
                    mHandler.sendMessage(message);
                    return;
                }
                message.what = LOAD_FAIL;
                mHandler.sendMessage(message);
            }
        };
        THREAD_POOL_EXCUTOR.execute(runnable);
    }

    // async
    private Bitmap loadFromLru(String url) {
        String key = hashKey(url);
        return getBitmapFromLru(key);
    }

    // async
    private Bitmap loadFromDiskLru(String url, int requiredWidth, int requiredHeight) {
        Bitmap bitmap = null;
        String key = hashKey(url);
        DiskLruCache.Snapshot snapshot = null;
        try {
            snapshot = mDiskLruCache.get(key);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if(snapshot != null) {
            FileInputStream fis = (FileInputStream) snapshot.getInputStream(0);
            FileDescriptor fd = null;
            try {
                fd = fis.getFD();
            } catch (IOException e) {
                e.printStackTrace();
            }
            bitmap = getBitmapByFD(fd, requiredWidth, requiredHeight);
            if(bitmap != null) {
                addBitmapToLru(key, bitmap);
            }
        }
        return bitmap;
    }

    //async
    private Bitmap loadFromNetwork(String url, int requiredWidth, int requiredHeight) {
        String key = hashKey(url);
        if(mDiskLruCache != null) {
            DiskLruCache.Editor editor = null;
            try {
                editor = mDiskLruCache.edit(key);
                if(editor != null) {
                    OutputStream os;
                    os = editor.newOutputStream(0);
                    if(httpToDisk(url,os)) {
                        editor.commit();
                    } else {
                        editor.commit();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return loadFromDiskLru(url, requiredWidth, requiredHeight);
        } else {
            return httpToMemory(url);
        }


    }

    public boolean httpToDisk(String url, OutputStream os) {
        HttpURLConnection httpURLConnection = null;
        BufferedOutputStream bos = null;
        BufferedInputStream bis = null;

        try {
            URL realUrl = new URL(url);
            httpURLConnection = (HttpURLConnection) realUrl.openConnection();
            bis = new BufferedInputStream(httpURLConnection.getInputStream());
            bos = new BufferedOutputStream(os);

            int bitmapByte;
            while((bitmapByte = bis.read()) != -1) {
                bos.write(bitmapByte);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if(httpURLConnection != null) {
                httpURLConnection.disconnect();
            }
            if(bis != null) {
                try {
                    bis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if(bos != null) {
                try {
                    bos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public Bitmap httpToMemory(String url) {
        HttpURLConnection httpURLConnection = null;
        BufferedInputStream bis = null;
        Bitmap bitmap = null;

        try {
            URL realUrl = new URL(url);
            httpURLConnection = (HttpURLConnection) realUrl.openConnection();
            bis = new BufferedInputStream(httpURLConnection.getInputStream());
            bitmap = BitmapFactory.decodeStream(bis);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(httpURLConnection != null) {
                httpURLConnection.disconnect();
            }
            if(bis != null) {
                try {
                    bis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        addBitmapToLru(hashKey(url), bitmap);
        return bitmap;
    }

    //async
    private void addBitmapToLru(String key, Bitmap value) {
        if(mLruCache.get(key) == null) {
            mLruCache.put(key, value);
        }
    }

    //async
    private Bitmap getBitmapFromLru(String key) {
        return mLruCache.get(key);
    }

    private Bitmap getBitmapByFD(FileDescriptor fd, int requiredWidth, int requiredHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd, null, options);
        options.inSampleSize = getSampleSize(options,requiredWidth,requiredHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fd, null, options);

    }

    private int getSampleSize(BitmapFactory.Options options,
                              int requiredWidth, int requiredHeight) {
        int inSampleSize = 1;
        if(requiredHeight == 0||requiredHeight == 0) {return inSampleSize;}
        if(options.outHeight > requiredHeight || options.outWidth > requiredWidth) {
            while(options.outWidth/inSampleSize >= requiredWidth &&
                    options.outHeight/inSampleSize >= requiredHeight) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public interface OnLoadListener {
        void onSuccess(Bitmap bitmap);
        void onFail();
    }

    public interface BitmapDecor {
        Bitmap decor(Bitmap bitmap);
    }

    private class RequestBean {

        public RequestBean(String url, OnLoadListener onLoadListener) {
            this.url = url;
            this.onLoadListener = onLoadListener;
        }

        String url;
        OnLoadListener onLoadListener;
        Bitmap bitmap;

    }

    private String hashKey(String url) {
        return ""+url.hashCode();
    }
}

