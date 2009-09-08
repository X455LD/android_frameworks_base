/*
 * Copyright (C) 2008 The Android Open Source Project
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

/**
 * TODO: Move this to services.jar
 * and make the contructor package private again.
 * @hide
 */

package android.server;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BluetoothA2dpService extends IBluetoothA2dp.Stub {
    private static final String TAG = "BluetoothA2dpService";
    private static final boolean DBG = true;

    public static final String BLUETOOTH_A2DP_SERVICE = "bluetooth_a2dp";

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String BLUETOOTH_ENABLED = "bluetooth_enabled";

    private static final int MESSAGE_CONNECT_TO = 1;

    private static final String PROPERTY_STATE = "State";

    private static final String SINK_STATE_DISCONNECTED = "disconnected";
    private static final String SINK_STATE_CONNECTING = "connecting";
    private static final String SINK_STATE_CONNECTED = "connected";
    private static final String SINK_STATE_PLAYING = "playing";

    private static int mSinkCount;

    private final Context mContext;
    private final IntentFilter mIntentFilter;
    private HashMap<BluetoothDevice, Integer> mAudioDevices;
    private final AudioManager mAudioManager;
    private final BluetoothService mBluetoothService;
    private final BluetoothAdapter mAdapter;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothIntent.DEVICE);
            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                               BluetoothError.ERROR);
                switch (state) {
                case BluetoothAdapter.STATE_ON:
                    onBluetoothEnable();
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    onBluetoothDisable();
                    break;
                }
            } else if (action.equals(BluetoothIntent.BOND_STATE_CHANGED_ACTION)) {
                int bondState = intent.getIntExtra(BluetoothIntent.BOND_STATE,
                                                   BluetoothError.ERROR);
                switch(bondState) {
                case BluetoothDevice.BOND_BONDED:
                    setSinkPriority(device, BluetoothA2dp.PRIORITY_AUTO);
                    break;
                case BluetoothDevice.BOND_BONDING:
                case BluetoothDevice.BOND_NOT_BONDED:
                    setSinkPriority(device, BluetoothA2dp.PRIORITY_OFF);
                    break;
                }
            } else if (action.equals(BluetoothIntent.REMOTE_DEVICE_CONNECTED_ACTION)) {
                if (getSinkPriority(device) > BluetoothA2dp.PRIORITY_OFF &&
                        isSinkDevice(device)) {
                    // This device is a preferred sink. Make an A2DP connection
                    // after a delay. We delay to avoid connection collisions,
                    // and to give other profiles such as HFP a chance to
                    // connect first.
                    Message msg = Message.obtain(mHandler, MESSAGE_CONNECT_TO, device);
                    mHandler.sendMessageDelayed(msg, 6000);
                }
            }
        }
    };

    public BluetoothA2dpService(Context context, BluetoothService bluetoothService) {
        mContext = context;

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        mBluetoothService = bluetoothService;
        if (mBluetoothService == null) {
            throw new RuntimeException("Platform does not support Bluetooth");
        }

        if (!initNative()) {
            throw new RuntimeException("Could not init BluetoothA2dpService");
        }

        mAdapter = (BluetoothAdapter) context.getSystemService(Context.BLUETOOTH_SERVICE);

        mIntentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        mIntentFilter.addAction(BluetoothIntent.BOND_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(BluetoothIntent.REMOTE_DEVICE_CONNECTED_ACTION);
        mContext.registerReceiver(mReceiver, mIntentFilter);

        mAudioDevices = new HashMap<BluetoothDevice, Integer>();

        if (mBluetoothService.isEnabled())
            onBluetoothEnable();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            cleanupNative();
        } finally {
            super.finalize();
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_CONNECT_TO:
                BluetoothDevice device = (BluetoothDevice) msg.obj;
                // check bluetooth is still on, device is still preferred, and
                // nothing is currently connected
                if (mBluetoothService.isEnabled() &&
                        getSinkPriority(device) > BluetoothA2dp.PRIORITY_OFF &&
                        lookupSinksMatchingStates(new int[] {
                            BluetoothA2dp.STATE_CONNECTING,
                            BluetoothA2dp.STATE_CONNECTED,
                            BluetoothA2dp.STATE_PLAYING,
                            BluetoothA2dp.STATE_DISCONNECTING}).size() == 0) {
                    log("Auto-connecting A2DP to sink " + device);
                    connectSink(device);
                }
                break;
            }
        }
    };

    private int convertBluezSinkStringtoState(String value) {
        if (value.equalsIgnoreCase("disconnected"))
            return BluetoothA2dp.STATE_DISCONNECTED;
        if (value.equalsIgnoreCase("connecting"))
            return BluetoothA2dp.STATE_CONNECTING;
        if (value.equalsIgnoreCase("connected"))
            return BluetoothA2dp.STATE_CONNECTED;
        if (value.equalsIgnoreCase("playing"))
            return BluetoothA2dp.STATE_PLAYING;
        return -1;
    }

    private boolean isSinkDevice(BluetoothDevice device) {
        String uuids[] = mBluetoothService.getRemoteUuids(device.getAddress());
        UUID uuid;
        if (uuids != null) {
            for (String deviceUuid: uuids) {
                uuid = UUID.fromString(deviceUuid);
                if (BluetoothUuid.isAudioSink(uuid)) {
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized boolean addAudioSink (BluetoothDevice device) {
        String path = mBluetoothService.getObjectPathFromAddress(device.getAddress());
        String propValues[] = (String []) getSinkPropertiesNative(path);
        if (propValues == null) {
            Log.e(TAG, "Error while getting AudioSink properties for device: " + device);
            return false;
        }
        Integer state = null;
        // Properties are name-value pairs
        for (int i = 0; i < propValues.length; i+=2) {
            if (propValues[i].equals(PROPERTY_STATE)) {
                state = new Integer(convertBluezSinkStringtoState(propValues[i+1]));
                break;
            }
        }
        mAudioDevices.put(device, state);
        handleSinkStateChange(device, BluetoothA2dp.STATE_DISCONNECTED, state);
        return true;
    }

    private synchronized void onBluetoothEnable() {
        String devices = mBluetoothService.getProperty("Devices");
        mSinkCount = 0;
        if (devices != null) {
            String [] paths = devices.split(",");
            for (String path: paths) {
                String address = mBluetoothService.getAddressFromObjectPath(path);
                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                String []uuids = mBluetoothService.getRemoteUuids(address);
                if (uuids != null)
                    for (String uuid: uuids) {
                        UUID remoteUuid = UUID.fromString(uuid);
                        if (BluetoothUuid.isAudioSink(remoteUuid) ||
                            BluetoothUuid.isAudioSource(remoteUuid) ||
                            BluetoothUuid.isAdvAudioDist(remoteUuid)) {
                            addAudioSink(device);
                            break;
                        }
                    }
            }
        }
        mAudioManager.setParameters(BLUETOOTH_ENABLED+"=true");
    }

    private synchronized void onBluetoothDisable() {
        if (!mAudioDevices.isEmpty()) {
            BluetoothDevice[] devices = new BluetoothDevice[mAudioDevices.size()];
            devices = mAudioDevices.keySet().toArray(devices);
            for (BluetoothDevice device : devices) {
                int state = getSinkState(device);
                switch (state) {
                    case BluetoothA2dp.STATE_CONNECTING:
                    case BluetoothA2dp.STATE_CONNECTED:
                    case BluetoothA2dp.STATE_PLAYING:
                        disconnectSinkNative(mBluetoothService.getObjectPathFromAddress(
                                device.getAddress()));
                        handleSinkStateChange(device, state, BluetoothA2dp.STATE_DISCONNECTED);
                        break;
                    case BluetoothA2dp.STATE_DISCONNECTING:
                        handleSinkStateChange(device, BluetoothA2dp.STATE_DISCONNECTING,
                                              BluetoothA2dp.STATE_DISCONNECTED);
                        break;
                }
            }
            mAudioDevices.clear();
        }

        mAudioManager.setParameters(BLUETOOTH_ENABLED + "=false");
    }

    public synchronized int connectSink(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (DBG) log("connectSink(" + device + ")");

        // ignore if there are any active sinks
        if (lookupSinksMatchingStates(new int[] {
                BluetoothA2dp.STATE_CONNECTING,
                BluetoothA2dp.STATE_CONNECTED,
                BluetoothA2dp.STATE_PLAYING,
                BluetoothA2dp.STATE_DISCONNECTING}).size() != 0) {
            return BluetoothError.ERROR;
        }

        if (mAudioDevices.get(device) == null && !addAudioSink(device))
            return BluetoothError.ERROR;

        int state = mAudioDevices.get(device);

        switch (state) {
        case BluetoothA2dp.STATE_CONNECTED:
        case BluetoothA2dp.STATE_PLAYING:
        case BluetoothA2dp.STATE_DISCONNECTING:
            return BluetoothError.ERROR;
        case BluetoothA2dp.STATE_CONNECTING:
            return BluetoothError.SUCCESS;
        }

        String path = mBluetoothService.getObjectPathFromAddress(device.getAddress());
        if (path == null)
            return BluetoothError.ERROR;

        // State is DISCONNECTED
        if (!connectSinkNative(path)) {
            return BluetoothError.ERROR;
        }
        return BluetoothError.SUCCESS;
    }

    public synchronized int disconnectSink(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (DBG) log("disconnectSink(" + device + ")");

        String path = mBluetoothService.getObjectPathFromAddress(device.getAddress());
        if (path == null) {
            return BluetoothError.ERROR;
        }

        switch (getSinkState(device)) {
        case BluetoothA2dp.STATE_DISCONNECTED:
            return BluetoothError.ERROR;
        case BluetoothA2dp.STATE_DISCONNECTING:
            return BluetoothError.SUCCESS;
        }

        // State is CONNECTING or CONNECTED or PLAYING
        if (!disconnectSinkNative(path)) {
            return BluetoothError.ERROR;
        } else {
            return BluetoothError.SUCCESS;
        }
    }

    public synchronized BluetoothDevice[] getConnectedSinks() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        Set<BluetoothDevice> sinks = lookupSinksMatchingStates(
                new int[] {BluetoothA2dp.STATE_CONNECTED, BluetoothA2dp.STATE_PLAYING});
        return sinks.toArray(new BluetoothDevice[sinks.size()]);
    }

    public synchronized int getSinkState(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        Integer state = mAudioDevices.get(device);
        if (state == null)
            return BluetoothA2dp.STATE_DISCONNECTED;
        return state;
    }

    public synchronized int getSinkPriority(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.getBluetoothA2dpSinkPriorityKey(device.getAddress()),
                BluetoothA2dp.PRIORITY_OFF);
    }

    public synchronized int setSinkPriority(BluetoothDevice device, int priority) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(device.getAddress())) {
            return BluetoothError.ERROR;
        }
        return Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.getBluetoothA2dpSinkPriorityKey(device.getAddress()), priority) ?
                BluetoothError.SUCCESS : BluetoothError.ERROR;
    }

    private synchronized void onSinkPropertyChanged(String path, String []propValues) {
        if (!mBluetoothService.isEnabled()) {
            return;
        }

        String name = propValues[0];
        String address = mBluetoothService.getAddressFromObjectPath(path);
        if (address == null) {
            Log.e(TAG, "onSinkPropertyChanged: Address of the remote device in null");
            return;
        }

        BluetoothDevice device = mAdapter.getRemoteDevice(address);

        if (name.equals(PROPERTY_STATE)) {
            int state = convertBluezSinkStringtoState(propValues[1]);
            if (mAudioDevices.get(device) == null) {
                // This is for an incoming connection for a device not known to us.
                // We have authorized it and bluez state has changed.
                addAudioSink(device);
            } else {
                int prevState = mAudioDevices.get(device);
                handleSinkStateChange(device, prevState, state);
            }
        }
    }

    private void handleSinkStateChange(BluetoothDevice device, int prevState, int state) {
        if (state != prevState) {
            if (state == BluetoothA2dp.STATE_DISCONNECTED ||
                    state == BluetoothA2dp.STATE_DISCONNECTING) {
                if (prevState == BluetoothA2dp.STATE_CONNECTED ||
                        prevState == BluetoothA2dp.STATE_PLAYING) {
                   // disconnecting or disconnected
                   Intent intent = new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                   mContext.sendBroadcast(intent);
                }
                mSinkCount--;
            } else if (state == BluetoothA2dp.STATE_CONNECTED) {
                mSinkCount ++;
            }
            mAudioDevices.put(device, state);

            Intent intent = new Intent(BluetoothA2dp.SINK_STATE_CHANGED_ACTION);
            intent.putExtra(BluetoothIntent.DEVICE, device);
            intent.putExtra(BluetoothA2dp.SINK_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothA2dp.SINK_STATE, state);
            mContext.sendBroadcast(intent, BLUETOOTH_PERM);

            if (DBG) log("A2DP state : device: " + device + " State:" + prevState + "->" + state);
        }
    }

    private synchronized Set<BluetoothDevice> lookupSinksMatchingStates(int[] states) {
        Set<BluetoothDevice> sinks = new HashSet<BluetoothDevice>();
        if (mAudioDevices.isEmpty()) {
            return sinks;
        }
        for (BluetoothDevice device: mAudioDevices.keySet()) {
            int sinkState = getSinkState(device);
            for (int state : states) {
                if (state == sinkState) {
                    sinks.add(device);
                    break;
                }
            }
        }
        return sinks;
    }

    @Override
    protected synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mAudioDevices.isEmpty()) return;
        pw.println("Cached audio devices:");
        for (BluetoothDevice device : mAudioDevices.keySet()) {
            int state = mAudioDevices.get(device);
            pw.println(device + " " + BluetoothA2dp.stateToString(state));
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private native boolean initNative();
    private native void cleanupNative();
    private synchronized native boolean connectSinkNative(String path);
    private synchronized native boolean disconnectSinkNative(String path);
    private synchronized native Object []getSinkPropertiesNative(String path);
}
