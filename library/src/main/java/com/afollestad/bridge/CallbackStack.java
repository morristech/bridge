package com.afollestad.bridge;

import android.annotation.SuppressLint;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author Aidan Follestad (afollestad)
 */
final class CallbackStack {

    @SuppressLint("DefaultLocale")
    public static String createKey(Request req) {
        String key = String.format("%d\0%s\0%s", req.method(), req.url().replace("http://", "").replace("https://", ""),
                req.builder().mBody != null ? req.builder().mBody.length + "" : "");
        if (req.builder().mMethod == Method.POST ||
                req.builder().mMethod == Method.PUT) {
            final RequestBuilder builder = req.builder();
            String hash = null;
            if (builder.mPipe != null) {
                hash = builder.mPipe.hash();
            } else if (builder.mBody != null) {
                hash = HashUtil.hash(builder.mBody);
            }
            key += String.format("\0%s\0", hash);
        }
        return key;
    }

    private final Object LOCK = new Object();
    private List<Callback> mCallbacks;
    private Request mDriverRequest;
    private int mPercent = -1;
    private Handler mHandler;

    public CallbackStack() {
        mCallbacks = new ArrayList<>();
        mHandler = new Handler();
    }

    public int size() {
        synchronized (LOCK) {
            if (mCallbacks == null) return -1;
            return mCallbacks.size();
        }
    }

    public void push(Callback callback, Request request) {
        synchronized (LOCK) {
            if (mCallbacks == null)
                throw new IllegalStateException("This stack has already been fired or cancelled.");
            callback.isCancellable = request.isCancellable();
            callback.mTag = request.builder().mTag;
            mCallbacks.add(callback);
            if (mDriverRequest == null)
                mDriverRequest = request;
        }
    }

    public void fireAll(final Response response, final BridgeException error) {
        synchronized (LOCK) {
            if (mCallbacks == null)
                throw new IllegalStateException("This stack has already been fired.");
            for (final Callback cb : mCallbacks) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.response(mDriverRequest, response, error);
                    }
                });
            }
            mCallbacks.clear();
            mCallbacks = null;
        }
    }

    public void fireAllProgress(final Request request, final int current, final int total) {
        synchronized (LOCK) {
            if (mCallbacks == null)
                throw new IllegalStateException("This stack has already been fired.");
            int newPercent = (int) (((float) current / (float) total) * 100f);
            if (newPercent != mPercent) {
                mPercent = newPercent;
                synchronized (LOCK) {
                    for (final Callback cb : mCallbacks) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.progress(request, current, total, mPercent);
                            }
                        });
                    }
                }
            }
        }
    }

    public boolean cancelAll(Object tag, boolean force) {
        synchronized (LOCK) {
            if (mCallbacks == null)
                throw new IllegalStateException("This stack has already been cancelled.");
            final Iterator<Callback> callIter = mCallbacks.iterator();
            while (callIter.hasNext()) {
                final Callback callback = callIter.next();
                if (tag != null && !tag.equals(callback.mTag))
                    continue;
                if (callback.isCancellable || force) {
                    callIter.remove();
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.response(mDriverRequest, null, new BridgeException(mDriverRequest));
                        }
                    });
                }
            }
            if (mCallbacks.size() == 0) {
                mDriverRequest.mCancelCallbackFired = true;
                mDriverRequest.cancel(force);
                mCallbacks = null;
                return true;
            } else {
                return false;
            }
        }
    }
}