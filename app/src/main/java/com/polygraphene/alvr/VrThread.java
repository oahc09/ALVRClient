package com.polygraphene.alvr;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.util.Log;
import android.view.Surface;

import java.util.concurrent.TimeUnit;

class VrThread extends Thread {
    private static final String TAG = "VrThread";

    private static final String KEY_SERVER_ADDRESS = "serverAddress";
    private static final String KEY_SERVER_PORT = "serverPort";

    private static final int PORT = 9944;

    private MainActivity mMainActivity;

    private VrContext mVrContext = new VrContext();
    private ThreadQueue mQueue = null;
    private boolean mResumed = false;

    private SurfaceTexture mSurfaceTexture;
    private Surface mSurface;

    private boolean mRendered = false;
    private boolean mRenderRequested = false;
    private long mFrameIndex = 0;
    private final Object mWaiter = new Object();

    private LoadingTexture mLoadingTexture = new LoadingTexture();

    // Worker threads
    private TrackingThread mTrackingThread;
    private DecoderThread mDecoderThread;
    private UdpReceiverThread mReceiverThread;

    private StatisticsCounter mCounter = new StatisticsCounter();
    private LatencyCollector mLatencyCollector = new LatencyCollector();

    private int m_RefreshRate = 60;

    // Called from native
    public interface VrFrameCallback {
        @SuppressWarnings("unused")
        long waitFrame();
    }

    public VrThread(MainActivity mainActivity) {
        this.mMainActivity = mainActivity;
    }

    public void onSurfaceCreated(final Surface surface) {
        post(new Runnable() {
            @Override
            public void run() {
                mVrContext.onSurfaceCreated(surface);
            }
        });
    }

    public void onSurfaceChanged(final Surface surface) {
        post(new Runnable() {
            @Override
            public void run() {
                mVrContext.onSurfaceChanged(surface);
            }
        });
    }

    public void onSurfaceDestroyed() {
        post(new Runnable() {
            @Override
            public void run() {
                mVrContext.onSurfaceDestroyed();
            }
        });
    }

    public void onResume() {
        synchronized (mWaiter) {
            mResumed = true;
            mWaiter.notifyAll();
        }
        if (mReceiverThread != null) {
            mReceiverThread.interrupt();
            try {
                mReceiverThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (mDecoderThread != null) {
            mDecoderThread.interrupt();
            try {
                mDecoderThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (mTrackingThread != null) {
            mTrackingThread.interrupt();
            try {
                mTrackingThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        post(new Runnable() {
            @Override
            public void run() {
                Log.v(TAG, "VrThread.onResume: Starting worker threads.");

                mReceiverThread = new UdpReceiverThread(mCounter, mOnChangeSettingsCallback, mLatencyCollector);
                mReceiverThread.setPort(PORT);
                mReceiverThread.set72Hz(mVrContext.is72Hz());
                loadConnectionState();
                mDecoderThread = new DecoderThread(mReceiverThread
                        , mMainActivity.getAvcDecoder(), mCounter, mRenderCallback, mMainActivity, mLatencyCollector);
                mTrackingThread = new TrackingThread();

                try {
                    mDecoderThread.start();
                    if (!mReceiverThread.start()) {
                        Log.e(TAG, "FATAL: Initialization of ReceiverThread failed.");
                        return;
                    }
                    // TrackingThread relies on ReceiverThread.
                    mTrackingThread.start();
                } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                    e.printStackTrace();
                }

                Log.v(TAG, "VrThread.onResume: Worker threads has started.");

                mVrContext.onResume();
            }
        });
    }
    public void onPause() {
        Log.v(TAG, "VrThread.onPause: Stopping worker threads.");
        synchronized (mWaiter) {
            mResumed = false;
            mWaiter.notifyAll();
        }
        // DecoderThread must be stopped before ReceiverThread
        if (mDecoderThread != null) {
            mDecoderThread.interrupt();
            try {
                mDecoderThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (mReceiverThread != null) {
            mReceiverThread.interrupt();
            try {
                mReceiverThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (mTrackingThread != null) {
            mTrackingThread.interrupt();
            try {
                mTrackingThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if(mReceiverThread != null){
            saveConnectionState(mReceiverThread.getServerAddress()
                    , mReceiverThread.getServerPort());
        }

        Log.v(TAG, "VrThread.onPause: All worker threads has stopped.");
        post(new Runnable() {
            @Override
            public void run() {
                mVrContext.onPause();
            }
        });
    }

    public void onKeyEvent(final int keyCode, final int action) {
        post(new Runnable() {
            @Override
            public void run() {
                mVrContext.onKeyEvent(keyCode, action);
            }
        });
    }

    // Called from onDestroy
    @Override
    public void interrupt() {
        post(new Runnable() {
            @Override
            public void run() {
                mLoadingTexture.destroyTexture();
                mQueue.interrupt();
            }
        });
    }

    private void post(Runnable runnable) {
        waitLooperPrepared();
        mQueue.post(runnable);
    }

    private void send(Runnable runnable) {
        waitLooperPrepared();
        mQueue.send(runnable);
    }

    private void waitLooperPrepared() {
        synchronized (this) {
            while (mQueue == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void run() {
        setName("VrThread");

        synchronized (this) {
            mQueue = new ThreadQueue();
            notifyAll();
        }

        mVrContext.initialize(mMainActivity);

        if(mVrContext.is72Hz()) {
            m_RefreshRate = 72;
        }else{
            m_RefreshRate = 60;
        }

        mSurfaceTexture = new SurfaceTexture(mVrContext.getSurfaceTextureID());
        mSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                Utils.frameLog(mFrameIndex, "onFrameAvailable");

                synchronized (mWaiter) {
                    mRenderRequested = false;
                    mRendered = true;
                    mWaiter.notifyAll();
                }
            }
        });
        mSurface = new Surface(mSurfaceTexture);

        mLoadingTexture.initializeMessageCanvas(mVrContext.getLoadingTexture());
        mLoadingTexture.drawMessage(mMainActivity.getVersionName() + "\nLoading...");

        Log.v(TAG, "Start loop of VrThread.");
        while(mQueue.waitIdle()) {
            if(!mVrContext.isVrMode() || !mResumed) {
                mQueue.waitNext();
                continue;
            }
            render();
        }

        Log.v(TAG, "Destroying vrapi state.");
        mVrContext.destroy();
    }

    private void render(){
        if (mReceiverThread.isConnected() && mDecoderThread.isFrameAvailable()) {
            mVrContext.render(new VrFrameCallback() {
                @Override
                public long waitFrame() {
                    long startTime = System.nanoTime();
                    synchronized (mWaiter) {
                        if (mRendered) {
                            Log.v(TAG, "updateTexImage(discard)");
                            mSurfaceTexture.updateTexImage();
                        }
                        mRenderRequested = true;
                        mRendered = false;
                    }
                    while (true) {
                        synchronized (mWaiter) {
                            if (!mResumed) {
                                return -1;
                            }
                            if (mRendered) {
                                mRendered = false;
                                mRenderRequested = false;
                                mSurfaceTexture.updateTexImage();
                                mLatencyCollector.Rendered1(mFrameIndex);
                                break;
                            }
                            if(System.nanoTime() - startTime > 1000 * 1000 * 1000L) {
                                // Idle for 1-sec.
                                return -1;
                            }
                            try {
                                mWaiter.wait(100);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    return mFrameIndex;
                }
            }, mLatencyCollector);
            mLatencyCollector.Submit(mFrameIndex);
        } else {
            if(mReceiverThread.isConnected()) {
                mLoadingTexture.drawMessage(mMainActivity.getVersionName() + "\n \nConnected!\nStreaming will begin soon!");
            }else {
                mLoadingTexture.drawMessage(mMainActivity.getVersionName() + "\n \nPress CONNECT button\non ALVR server.");
            }
            mVrContext.renderLoading();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private UdpReceiverThread.Callback mOnChangeSettingsCallback = new UdpReceiverThread.Callback() {
        @Override
        public void onConnected(final int width, final int height) {
            // We must wait completion of notifyGeometryChange
            // to ensure the first video frame arrives after notifyGeometryChange.
            send(new Runnable() {
                @Override
                public void run() {
                    mVrContext.setFrameGeometry(width, height);
                    mDecoderThread.notifyGeometryChange();
                }
            });
        }

        @Override
        public void onChangeSettings(int enableTestMode, int suspend) {
            mVrContext.onChangeSettings(enableTestMode, suspend);
        }
    };

    private DecoderThread.RenderCallback mRenderCallback = new DecoderThread.RenderCallback() {
        @Override
        public Surface getSurface() {
            return mSurface;
        }

        @Override
        public int renderIf(MediaCodec codec, int queuedOutputBuffer, long frameIndex) {
            //Log.v(TAG, "renderIf " + queuedOutputBuffer);
            synchronized (mWaiter) {
                if (!mRenderRequested) {
                    return queuedOutputBuffer;
                }
            }

            if (queuedOutputBuffer == -1) {
                return queuedOutputBuffer;
            }

            codec.releaseOutputBuffer(queuedOutputBuffer, true);
            synchronized (mWaiter) {
                //mRendered = true;
                mFrameIndex = frameIndex;
                //waiter.notifyAll();
            }
            return -1;
        }
    };

    // Called from native
    interface OnSendTrackingCallback {
        @SuppressWarnings("unused")
        void onSendTracking(byte[] buf, int len, long frameIndex);
    }

    class TrackingThread extends Thread {
        private static final String TAG = "TrackingThread";
        boolean mStopped = false;

        public void interrupt() {
            Log.v(TAG, "Stopping TrackingThread.");
            mStopped = true;
        }

        @Override
        public void run() {
            long previousFetchTime = System.nanoTime();
            while (!mStopped) {
                if (mVrContext.isVrMode() && mReceiverThread.isConnected()) {
                    long frameIndex = mVrContext.fetchTrackingInfo(mOnSendTrackingCallback);
                    mLatencyCollector.Tracking(frameIndex);
                }
                try {
                    previousFetchTime += 1000 * 1000 * 1000 / m_RefreshRate;
                    long next = previousFetchTime - System.nanoTime();
                    if(next < 0) {
                        // Exceed time!
                        previousFetchTime = System.nanoTime();
                    }else {
                        TimeUnit.NANOSECONDS.sleep(next);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            Log.v(TAG, "TrackingThread has stopped.");
        }
    }

    private OnSendTrackingCallback mOnSendTrackingCallback = new OnSendTrackingCallback() {
        @Override
        public void onSendTracking(byte[] buf, int len, long frameIndex) {
            mReceiverThread.send(buf, len);
        }
    };

    private void saveConnectionState(String serverAddress, int serverPort) {
        Log.v(TAG, "save connection state: " + serverAddress + " " + serverPort);
        SharedPreferences pref = mMainActivity.getSharedPreferences("pref", Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        // If server address is NULL, it means no preserved connection.
        edit.putString(KEY_SERVER_ADDRESS, serverAddress);
        edit.putInt(KEY_SERVER_PORT, serverPort);
        edit.apply();
    }

    private void loadConnectionState() {
        SharedPreferences pref = mMainActivity.getSharedPreferences("pref", Context.MODE_PRIVATE);
        String serverAddress = pref.getString(KEY_SERVER_ADDRESS, null);
        int serverPort = pref.getInt(KEY_SERVER_PORT, 0);

        saveConnectionState(null, 0);

        Log.v(TAG, "load connection state: " + serverAddress + " " + serverPort);
        mReceiverThread.recoverConnectionState(serverAddress, serverPort);
    }

}
