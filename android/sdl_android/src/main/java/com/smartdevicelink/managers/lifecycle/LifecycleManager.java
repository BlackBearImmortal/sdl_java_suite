/*
 * Copyright (c) 2019-2020 Livio, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the
 * distribution.
 *
 * Neither the name of the Livio Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.smartdevicelink.managers.lifecycle;

import android.app.Service;
import android.content.Context;
import android.support.annotation.RestrictTo;

import com.smartdevicelink.SdlConnection.SdlSession;
import com.smartdevicelink.SdlConnection.SdlSession2;
import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.exception.SdlExceptionCause;
import com.smartdevicelink.protocol.enums.SessionType;
import com.smartdevicelink.proxy.interfaces.ISdlServiceListener;
import com.smartdevicelink.proxy.rpc.enums.SdlDisconnectedReason;
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType;
import com.smartdevicelink.security.SdlSecurityBase;
import com.smartdevicelink.streaming.video.VideoStreamingParameters;
import com.smartdevicelink.transport.BaseTransportConfig;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.smartdevicelink.transport.TCPTransportConfig;
import com.smartdevicelink.transport.USBTransportConfig;
import com.smartdevicelink.transport.enums.TransportType;
import com.smartdevicelink.util.DebugTool;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;

/**
 * The lifecycle manager creates a central point for all SDL session logic to converge. It should only be used by
 * the library itself. Usage outside the library is not permitted and will not be protected for in the future.
 *
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class LifecycleManager extends BaseLifecycleManager {
    private ISdlServiceListener videoServiceListener;
    private boolean videoServiceStartResponseReceived = false;
    private boolean videoServiceStartResponse = false;
    private WeakReference<Context> contextWeakReference;

    public LifecycleManager(AppConfig appConfig, BaseTransportConfig config, LifecycleListener listener) {
        super(appConfig, config, listener);
    }

    @Override
    void initialize() {
        super.initialize();

        //Handle legacy USB connections
        if (_transportConfig != null && TransportType.USB.equals(_transportConfig.getTransportType())) {
            //A USB transport config was provided
            USBTransportConfig usbTransportConfig = (USBTransportConfig) _transportConfig;
            if (usbTransportConfig.getUsbAccessory() == null) {
                DebugTool.logInfo(TAG,"Legacy USB transport config was used, but received null for accessory. Attempting to connect with router service");
                //The accessory was null which means it came from a router service
                MultiplexTransportConfig multiplexTransportConfig = new MultiplexTransportConfig(usbTransportConfig.getUSBContext(), appConfig.getAppID());
                multiplexTransportConfig.setRequiresHighBandwidth(true);
                multiplexTransportConfig.setSecurityLevel(MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF);
                multiplexTransportConfig.setPrimaryTransports(Collections.singletonList(TransportType.USB));
                multiplexTransportConfig.setSecondaryTransports(new ArrayList<TransportType>());
                _transportConfig = multiplexTransportConfig;
            }
        }

        if (_transportConfig != null && _transportConfig.getTransportType().equals(TransportType.MULTIPLEX)) {
            this.session = new SdlSession2(sdlConnectionListener, (MultiplexTransportConfig) _transportConfig);
        } else if (_transportConfig != null && _transportConfig.getTransportType().equals(TransportType.TCP)) {
            this.session = new SdlSession2(sdlConnectionListener, (TCPTransportConfig) _transportConfig);
        } else {
            this.session = SdlSession.createSession((byte) getProtocolVersion().getMajor(), sdlConnectionListener, _transportConfig);
        }
    }

    @Override
    void cycle(SdlDisconnectedReason disconnectedReason) {
        clean();
        initialize();
        if (!SdlDisconnectedReason.LEGACY_BLUETOOTH_MODE_ENABLED.equals(disconnectedReason) && !SdlDisconnectedReason.PRIMARY_TRANSPORT_CYCLE_REQUEST.equals(disconnectedReason)) {
            //We don't want to alert higher if we are just cycling for legacy bluetooth
            onClose("Sdl Proxy Cycled", new SdlException("Sdl Proxy Cycled", SdlExceptionCause.SDL_PROXY_CYCLED), disconnectedReason);
        }
        if (session != null) {
            try {
                session.startSession();
            } catch (SdlException e) {
                e.printStackTrace();
            }
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void setContext(Context context) {
        this.contextWeakReference = new WeakReference<>(context);
    }

    @Override
    void setSdlSecurityStaticVars() {
        super.setSdlSecurityStaticVars();

        Context context = null;
        Service service = null;

        if(this.contextWeakReference != null){
            context = contextWeakReference.get();
        }
        if (context != null && context instanceof Service) {
            service = (Service) context;
        }
        SdlSecurityBase.setAppService(service);
        SdlSecurityBase.setContext(context);
    }

    @Override
    void onServiceStarted(SessionType sessionType) {
        super.onServiceStarted(sessionType);
        if (sessionType.eq(SessionType.NAV)) {
            videoServiceStartResponseReceived = true;
            videoServiceStartResponse = true;
        }
    }

    @Override
    void onTransportDisconnected(String info, boolean availablePrimary, BaseTransportConfig transportConfig) {
        super.onTransportDisconnected(info, availablePrimary, transportConfig);
        if (availablePrimary) {
            _transportConfig = transportConfig;
            DebugTool.logInfo(TAG, "notifying RPC session ended, but potential primary transport available");
            cycle(SdlDisconnectedReason.PRIMARY_TRANSPORT_CYCLE_REQUEST);
        } else {
            onClose(info, null, null);
        }
    }

    @Override
    void onStartServiceNACKed(SessionType sessionType) {
        super.onStartServiceNACKed(sessionType);
        if (sessionType.eq(SessionType.NAV)) {
            videoServiceStartResponseReceived = true;
            videoServiceStartResponse = false;
        }
    }

    /**
     * This method will try to start the video service with the requested parameters.
     * When it returns it will attempt to store the accepted parameters if available.
     *
     * @param isEncrypted if the service should be encrypted
     * @param parameters  the desired video streaming parameters
     */
    @Override
    void startVideoService(boolean isEncrypted, VideoStreamingParameters parameters) {
        if (session == null) {
            DebugTool.logWarning(TAG, "SdlSession is not created yet.");
            return;
        }
        if (!session.getIsConnected()) {
            DebugTool.logWarning(TAG, "Connection is not available.");
            return;
        }

        session.setDesiredVideoParams(parameters);
        tryStartVideoStream(isEncrypted, parameters);
    }

    /**
     * Try to open a video service by using the video streaming parameters supplied.
     * Only information from codecs, width and height are used during video format negotiation.
     *
     * @param isEncrypted Specify true if packets on this service have to be encrypted
     * @param parameters  VideoStreamingParameters that are desired. Does not guarantee this is what will be accepted.
     * @return If the service is opened successfully, an instance of VideoStreamingParams is
     * returned which contains accepted video format. If the service is opened with legacy
     * mode (i.e. without any negotiation) then an instance of VideoStreamingParams is
     * returned. If the service was not opened then null is returned.
     */
    private void tryStartVideoStream(boolean isEncrypted, VideoStreamingParameters parameters) {
        if (session == null) {
            DebugTool.logWarning(TAG, "SdlSession is not created yet.");
            return;
        }
        if (getProtocolVersion() != null && getProtocolVersion().getMajor() >= 5 && !systemCapabilityManager.isCapabilitySupported(SystemCapabilityType.VIDEO_STREAMING)) {
            DebugTool.logWarning(TAG, "Module doesn't support video streaming.");
            return;
        }
        if (parameters == null) {
            DebugTool.logWarning(TAG, "Video parameters were not supplied.");
            return;
        }


        if (!videoServiceStartResponseReceived || !videoServiceStartResponse //If we haven't started the service before
                || (videoServiceStartResponse && isEncrypted && !session.isServiceProtected(SessionType.NAV))) { //Or the service has been started but we'd like to start an encrypted one
            session.setDesiredVideoParams(parameters);

            videoServiceStartResponseReceived = false;
            videoServiceStartResponse = false;

            addVideoServiceListener();
            session.startService(SessionType.NAV, session.getSessionId(), isEncrypted);

        }
    }

    private void addVideoServiceListener() {
        // videos may be started and stopped. Only add this once
        if (videoServiceListener == null) {

            videoServiceListener = new ISdlServiceListener() {
                @Override
                public void onServiceStarted(SdlSession session, SessionType type, boolean isEncrypted) {
                    videoServiceStartResponseReceived = true;
                    videoServiceStartResponse = true;
                }

                @Override
                public void onServiceEnded(SdlSession session, SessionType type) {
                    // reset nav flags so nav can start upon the next transport connection
                    videoServiceStartResponseReceived = false;
                    videoServiceStartResponse = false;
                }

                @Override
                public void onServiceError(SdlSession session, SessionType type, String reason) {
                    // if there is an error reset the flags so that there is a chance to restart streaming
                    videoServiceStartResponseReceived = false;
                    videoServiceStartResponse = false;
                }
            };
            session.addServiceListener(SessionType.NAV, videoServiceListener);
        }
    }

    /**
     * Closes the opened video service (serviceType 11)
     *
     * @return true if the video service is closed successfully, return false otherwise
     */
    @Override
    void endVideoStream() {
        if (session == null) {
            DebugTool.logWarning(TAG, "SdlSession is not created yet.");
            return;
        }
        if (!session.getIsConnected()) {
            DebugTool.logWarning(TAG, "Connection is not available.");
            return;
        }

        session.stopVideoStream();
    }

    @Override
    void startAudioService(boolean isEncrypted) {
        if (session == null) {
            DebugTool.logWarning(TAG, "SdlSession is not created yet.");
            return;
        }
        if (!session.getIsConnected()) {
            DebugTool.logWarning(TAG, "Connection is not available.");
            return;
        }
        session.startService(SessionType.PCM, session.getSessionId(), isEncrypted);
    }

    /**
     * Closes the opened audio service (serviceType 10)
     *
     * @return true if the audio service is closed successfully, return false otherwise
     */
    @Override
    void endAudioStream() {
        if (session == null) {
            DebugTool.logWarning(TAG, "SdlSession is not created yet.");
            return;
        }
        if (!session.getIsConnected()) {
            DebugTool.logWarning(TAG, "Connection is not available.");
            return;
        }

        session.stopAudioStream();
    }
}
