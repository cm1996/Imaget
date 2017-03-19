# Imaget
## 简介
一个用于图片加载的库—— Imaget。可以使用在 Android 项目里，用于图片的加载以及与控件的绑定。实现了图片三级缓存（内存/外存/网络请求）和图片的高效加载。并同时提供了同步和异步加载机制。提供友好的链式使用接口，并结合静态和单例模式，在考虑方便的同时尽可能的减少占用的内存。
## 设计思路来源，尊重开源成果
- 本工具在设计上参考了网上的开源图片加载框架
- 缓存和压缩上参考任玉刚老师的著作《 Android 开发艺术探索》
- 磁盘缓存使用了 Android 官方文档推荐的[DiskLruCache](android.googlesource.com/platform/libcore/+/jb-mr2-release/luni/src/main/java/libcore/io/DiskLruCache.java), 真正使用的源文件由 CSDN 的郭霖老师提供。
## 使用
- 最低 API 14
- 仅工作在 UI 线程
- Imaget 使用之前应通过 Imaget.on(Context) 启动
- 如果不再使用，可通过 Imaget.off() 来停止服务，该功能或许能在一定层度上节省内存占用
- 针对列表的图片错位问题，本工具内置了设置 tag 的解决方案，可能与使用者代码冲突
### 异步
- Imaget.url(x).load(x);

        Imaget.url(url).Load(url, requiredWidth, requiredHeight, new OnLoadListener() {
            @Override
            public void onSuccess(Bitmap bitmap) {}
            @Override
            public void onFail() {}
        });
- Imaget.url(x).bind(x);

        Imaget.url(url).bind(imageView);
### 同步
- Imaget.url(x).loadSync(x); 此方法必须在非 UI 线程调用

        Bitmap bitmap = Imaget.url(url).loadSync(width,height);

### 使用装饰
- Imaget.url(x).setBitmapDecor(bitmapDecor).load(x);

## 错误及维护
欢迎使用该项目的开发者。本人水平有限。如果有什么错误，或者其他任何想法（包括版权问题），欢迎和我联系：chenmo1996@outlook.com
