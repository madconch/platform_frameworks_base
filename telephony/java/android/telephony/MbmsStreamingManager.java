/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.telephony;

import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.telephony.mbms.InternalStreamingManagerCallback;
import android.telephony.mbms.InternalStreamingServiceCallback;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.MbmsStreamingManagerCallback;
import android.telephony.mbms.MbmsUtils;
import android.telephony.mbms.StreamingService;
import android.telephony.mbms.StreamingServiceCallback;
import android.telephony.mbms.StreamingServiceInfo;
import android.telephony.mbms.vendor.IMbmsStreamingService;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

/**
 * This class provides functionality for streaming media over MBMS.
 */
public class MbmsStreamingManager {
    private static final String LOG_TAG = "MbmsStreamingManager";

    /**
     * Service action which must be handled by the middleware implementing the MBMS streaming
     * interface.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String MBMS_STREAMING_SERVICE_ACTION =
            "android.telephony.action.EmbmsStreaming";

    private AtomicReference<IMbmsStreamingService> mService = new AtomicReference<>(null);
    private InternalStreamingManagerCallback mInternalCallback;

    private final Context mContext;
    private int mSubscriptionId = INVALID_SUBSCRIPTION_ID;

    /** @hide */
    private MbmsStreamingManager(Context context, MbmsStreamingManagerCallback callback,
                    int subscriptionId, Handler handler) {
        mContext = context;
        mSubscriptionId = subscriptionId;
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }
        mInternalCallback = new InternalStreamingManagerCallback(callback, handler);
    }

    /**
     * Create a new MbmsStreamingManager using the given subscription ID.
     *
     * Note that this call will bind a remote service. You may not call this method on your app's
     * main thread. This may throw an {@link MbmsException}, indicating errors that may happen
     * during the initialization or binding process.
     *
     * @param context The {@link Context} to use.
     * @param callback A callback object on which you wish to receive results of asynchronous
     *                 operations.
     * @param subscriptionId The subscription ID to use.
     * @param handler The handler you wish to receive callbacks on. If null, callbacks will be
     *                processed on the main looper (in other words, the looper returned from
     *                {@link Looper#getMainLooper()}).
     */
    public static MbmsStreamingManager create(Context context,
            MbmsStreamingManagerCallback callback, int subscriptionId, Handler handler)
            throws MbmsException {
        MbmsStreamingManager manager = new MbmsStreamingManager(context, callback,
                subscriptionId, handler);
        manager.bindAndInitialize();
        return manager;
    }

    /**
     * Create a new MbmsStreamingManager using the system default data subscription ID.
     * See {@link #create(Context, MbmsStreamingManagerCallback, int, Handler)}.
     */
    public static MbmsStreamingManager create(Context context,
            MbmsStreamingManagerCallback callback, Handler handler)
            throws MbmsException {
        return create(context, callback, SubscriptionManager.getDefaultSubscriptionId(), handler);
    }

    /**
     * Create a new MbmsStreamingManager using the system default data subscription ID and
     * default {@link Handler}.
     * See {@link #create(Context, MbmsStreamingManagerCallback, int, Handler)}.
     */
    public static MbmsStreamingManager create(Context context,
            MbmsStreamingManagerCallback callback)
            throws MbmsException {
        return create(context, callback, SubscriptionManager.getDefaultSubscriptionId(), null);
    }

    /**
     * Terminates this instance, ending calls to the registered listener.  Also terminates
     * any streaming services spawned from this instance.
     *
     * May throw an {@link IllegalStateException}
     */
    public void dispose() {
        IMbmsStreamingService streamingService = mService.get();
        if (streamingService == null) {
            // Ignore and return, assume already disposed.
            return;
        }
        try {
            streamingService.dispose(mSubscriptionId);
        } catch (RemoteException e) {
            // Ignore for now
        }
        mService.set(null);
    }

    /**
     * An inspection API to retrieve the list of streaming media currently be advertised.
     * The results are returned asynchronously through the previously registered callback.
     * serviceClasses lets the app filter on types of programming and is opaque data between
     * the app and the carrier.
     *
     * Multiple calls replace the list of serviceClasses of interest.
     *
     * This may throw an {@link MbmsException} containing any error in
     * {@link android.telephony.mbms.MbmsException.GeneralErrors},
     * {@link MbmsException#ERROR_MIDDLEWARE_NOT_BOUND}, or
     * {@link MbmsException#ERROR_MIDDLEWARE_LOST}.
     *
     * May also throw an unchecked {@link IllegalArgumentException} or an
     * {@link IllegalStateException}
     *
     * @param classList A list of streaming service classes that the app would like updates on.
     */
    public void getStreamingServices(List<String> classList) throws MbmsException {
        IMbmsStreamingService streamingService = mService.get();
        if (streamingService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }
        try {
            int returnCode = streamingService.getStreamingServices(mSubscriptionId, classList);
            if (returnCode != MbmsException.SUCCESS) {
                throw new MbmsException(returnCode);
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        }
    }

    /**
     * Starts streaming a requested service, reporting status to the indicated callback.
     * Returns an object used to control that stream. The stream may not be ready for consumption
     * immediately upon return from this method -- wait until the streaming state has been
     * reported via
     * {@link android.telephony.mbms.StreamingServiceCallback#onStreamStateUpdated(int, int)}
     *
     * May throw an
     * {@link MbmsException} containing any of the error codes in
     * {@link android.telephony.mbms.MbmsException.GeneralErrors},
     * {@link MbmsException#ERROR_MIDDLEWARE_NOT_BOUND}, or
     * {@link MbmsException#ERROR_MIDDLEWARE_LOST}.
     *
     * May also throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     *
     * Asynchronous errors through the callback include any of the errors in
     * {@link android.telephony.mbms.MbmsException.GeneralErrors} or
     * {@link android.telephony.mbms.MbmsException.StreamingErrors}.
     *
     * @param serviceInfo The information about the service to stream.
     * @param callback A callback that'll be called when something about the stream changes.
     * @param handler A handler that calls to {@code callback} should be called on. If null,
     *                defaults to the handler provided via
     *                {@link #create(Context, MbmsStreamingManagerCallback, int, Handler)}.
     * @return An instance of {@link StreamingService} through which the stream can be controlled.
     */
    public StreamingService startStreaming(StreamingServiceInfo serviceInfo,
            StreamingServiceCallback callback, Handler handler) throws MbmsException {
        IMbmsStreamingService streamingService = mService.get();
        if (streamingService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }

        InternalStreamingServiceCallback serviceCallback = new InternalStreamingServiceCallback(
                callback, handler == null ? mInternalCallback.getHandler() : handler);

        StreamingService serviceForApp = new StreamingService(
                mSubscriptionId, streamingService, serviceInfo, serviceCallback);

        try {
            int returnCode = streamingService.startStreaming(
                    mSubscriptionId, serviceInfo.getServiceId(), serviceCallback);
            if (returnCode != MbmsException.SUCCESS) {
                throw new MbmsException(returnCode);
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        }

        return serviceForApp;
    }

    private void bindAndInitialize() throws MbmsException {
        MbmsUtils.startBinding(mContext, MBMS_STREAMING_SERVICE_ACTION,
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        IMbmsStreamingService streamingService =
                                IMbmsStreamingService.Stub.asInterface(service);
                        int result;
                        try {
                            result = streamingService.initialize(mInternalCallback,
                                    mSubscriptionId);
                        } catch (RemoteException e) {
                            Log.e(LOG_TAG, "Service died before initialization");
                            return;
                        } catch (RuntimeException e) {
                            Log.e(LOG_TAG, "Runtime exception during initialization");
                            try {
                                mInternalCallback.error(
                                        MbmsException.InitializationErrors
                                                .ERROR_UNABLE_TO_INITIALIZE,
                                        e.toString());
                            } catch (RemoteException e1) {
                                // ignore
                            }
                            return;
                        }
                        if (result != MbmsException.SUCCESS) {
                            try {
                                mInternalCallback.error(
                                        result, "Error returned during initialization");
                            } catch (RemoteException e) {
                                // ignore
                            }
                            return;
                        }
                        mService.set(streamingService);
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        mService.set(null);
                    }
                });
    }
}
