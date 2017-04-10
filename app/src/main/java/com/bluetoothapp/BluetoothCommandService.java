package com.bluetoothapp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

public class BluetoothCommandService {

    private static final String TAG = BluetoothCommandService.class.getSimpleName();
    //    private static final UUID MY_UUID = UUID.fromString("04c6093b-0000-1000-8000-00805f9b34fb");
//        private static final UUID MY_UUID = UUID.fromString("0000110100001000800000805f9b34fb");
        private static final UUID MY_UUID = UUID.fromString("446118f0-8b1e-11e2-9e96-0800200c9a66");
//    private static final UUID MY_UUID = UUID.randomUUID();
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mHandler;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    // Constants that indicate command to computer
    public static final int EXIT_CMD = -1;
    public static final int VOL_UP = 1;
    public static final int VOL_DOWN = 2;
    public static final int MOUSE_MOVE = 3;

    /**
     * Constructor. Prepares a new BluetoothChat session.
     *
     * @param context The UI Activity Context
     * @param handler A Handler to send messages back to the UI Activity
     */
    public BluetoothCommandService(Context context, Handler handler) {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        //mConnectionLostCount = 0;
        mHandler = handler;
    }

    /**
     * Set the current state of the chat connection
     *
     * @param state An integer defining the current connection state
     */
    private synchronized void setState(int state) {
        Log.e(TAG, "setState" + mState + " -> " + state);
        mState = state;

        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(RemoteBluetooth.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    public synchronized int getState() {
        return mState;
    }

    public synchronized void start() {
        Log.e(TAG, "start");

        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        setState(STATE_LISTEN);
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     *
     * @param device The BluetoothDevice to connect
     */
    public synchronized void connect(BluetoothDevice device) {
        Log.e(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     *
     * @param socket The BluetoothSocket on which the connection was made
     * @param device The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        Log.e(TAG, "connected");

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();

        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(RemoteBluetooth.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(RemoteBluetooth.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        // save connected device
        //mSavedDevice = device;
        // reset connection lost count
        //mConnectionLostCount = 0;

        setState(STATE_CONNECTED);
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        Log.e(TAG, "stop");
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        setState(STATE_NONE);
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     *
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

    public void write(int out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed(Exception e) {
        Log.e(TAG, "connection failed", e);
        setState(STATE_LISTEN);

        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(RemoteBluetooth.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(RemoteBluetooth.TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost() {
//        mConnectionLostCount++;
//        if (mConnectionLostCount < 3) {
//        	// Send a reconnect message back to the Activity
//	        Message msg = mHandler.obtainMessage(RemoteBluetooth.MESSAGE_TOAST);
//	        Bundle bundle = new Bundle();
//	        bundle.putString(RemoteBluetooth.TOAST, "Device connection was lost. Reconnecting...");
//	        msg.setData(bundle);
//	        mHandler.sendMessage(msg);
//	        
//        	connect(mSavedDevice);   	
//        } else {
        setState(STATE_LISTEN);
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(RemoteBluetooth.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(RemoteBluetooth.TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
//        }
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private BluetoothSocket socket;
        private BluetoothSocket fallBackSocket;
        private final BluetoothDevice device;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            try {
                socket = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "create() failed", e);
            }
        }

        public void run() {
            Log.e(TAG, "BEGIN mConnectThread");
            setName("ConnectThread");

            // Always cancel discovery because it will slow down a connection
            bluetoothAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                socket.connect();
            } catch (IOException e) {
//                connectionFailed(e);
                Log.e(TAG, e.toString());
                // Close the socket
                Class<?> clazz = socket.getRemoteDevice().getClass();
                Class<?>[] paramTypes = new Class<?>[]{Integer.TYPE};
                Method m;
                try {
                    m = clazz.getMethod("createRfcommSocket", paramTypes);
                    socket.getRemoteDevice().createRfcommSocketToServiceRecord(MY_UUID);
                    Object[] params = new Object[]{Integer.valueOf(1)};
                    fallBackSocket = (BluetoothSocket) m.invoke(socket.getRemoteDevice(), params);
                    Log.e(TAG, "fallBackSocket try to connect");
                    fallBackSocket.connect();
                    // Start the connected thread
                    connected(fallBackSocket, device);
                    Log.e(TAG, "fallBackSocket connected");
                } catch (Exception e1) {
                    Log.e(TAG, "fallBackSocket connection failed");
                    connectionFailed(e1);
                }
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                // Start the service over to restart listening mode
                BluetoothCommandService.this.start();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothCommandService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(socket, device);
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            Log.e(TAG, "create ConnectedThread");
            mmSocket = socket;
            // Get the BluetoothSocket input and output streams
            try {
                mmInStream = socket.getInputStream();
                mmOutStream = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
                throw new RuntimeException(e);
            }
        }

        public void run() {
            Log.e(TAG, "BEGIN mConnectedThread");
            byte[] buffer = new byte[1024];

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    // Read from the InputStream
                    int bytes = mmInStream.read(buffer);

                    // Send the obtained bytes to the UI Activity
                    mHandler.obtainMessage(RemoteBluetooth.MESSAGE_READ, bytes, -1, buffer)
                            .sendToTarget();
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    break;
                }
            }
        }

        /**
         * Write to the connected OutStream.
         *
         * @param buffer The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);

                // Share the sent message back to the UI Activity
//                mHandler.obtainMessage(BluetoothChat.MESSAGE_WRITE, -1, -1, buffer)
//                        .sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void write(int out) {
            try {
                mmOutStream.write(out);

                // Share the sent message back to the UI Activity
//                mHandler.obtainMessage(BluetoothChat.MESSAGE_WRITE, -1, -1, buffer)
//                        .sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                mmOutStream.write(EXIT_CMD);
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}