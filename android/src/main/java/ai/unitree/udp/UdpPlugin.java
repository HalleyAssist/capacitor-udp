package ai.unitree.udp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;
import android.util.Base64;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import java.net.*;
import java.util.*;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;

import org.json.JSONException;

import ai.unitree.udp.UdpSocket;

@NativePlugin()
public class UdpPlugin extends Plugin {
    private static final String LOG_TAG = "CapacitorUDP";
    private Map<Integer, UdpSocket> sockets = new ConcurrentHashMap<Integer, UdpSocket>();
    private BlockingQueue<SelectorMessage> selectorMessages = new LinkedBlockingQueue<SelectorMessage>();
    private int nextSocket = 0;
    private Selector selector;
    private SelectorThread selectorThread;

    private BroadcastReceiver dataForwardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int socketId = intent.getIntExtra("socketId", -1);
            String address = intent.getStringExtra("address");
            int port = intent.getIntExtra("port", -1);
            byte[] data = intent.getByteArrayExtra("data");
            try {
                UdpSocket socket = obtainSocket(socketId);
                if (!socket.isBound)
                    throw new Exception("Not bound yet");
                socket.addSendPacket(address, port, data, null);
                addSelectorMessage(socket, SelectorMessageType.SO_ADD_WRITE_INTEREST, null);
            } catch (Exception e) {
            }

        }
    };

    @Override
    protected void handleOnStart() {
        startSelectorThread();
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(dataForwardReceiver,
                new IntentFilter("capacitor-udp-forward"));
    }

    @Override
    protected void handleOnStop() {
        Log.i("lifecycle", "stop");
        stopSelectorThread();
    }

    @Override
    protected void handleOnRestart() {
        Log.i("lifecycle", "restart");
        startSelectorThread();
    }

    @PluginMethod()
    public void create(PluginCall call) {
        try {
            JSObject properties = call.getObject("properties");
            UdpSocket socket = new UdpSocket(nextSocket++, properties);
            sockets.put(Integer.valueOf(socket.getSocketId()), socket);
            JSObject ret = new JSObject();
            ret.put("socketId", socket.getSocketId());
            ret.put("ipv4", socket.ipv4Address.getHostAddress());
            String ipv6 = socket.ipv6Address.getHostAddress();
            int ip6InterfaceIndex = ipv6.indexOf("%");
            if (ip6InterfaceIndex > 0) {
                ret.put("ipv6", ipv6.substring(0, ip6InterfaceIndex));
            } else {
                ret.put("ipv6", ipv6);
            }

            call.success(ret);

        } catch (Exception e) {
            call.reject("create error", e);
        }
    }

    private UdpSocket obtainSocket(int socketId) throws Exception {
        UdpSocket socket = sockets.get(Integer.valueOf(socketId));
        if (socket == null) {
            throw new Exception("No socket with socketId " + socketId);
        }
        return socket;
    }

    @PluginMethod()
    public void update(PluginCall call) {
        try {
            int socketId = call.getInt("socketId");
            JSObject properties = call.getObject("properties");
            UdpSocket socket = obtainSocket(socketId);
            socket.setProperties(properties);
            call.success();
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void setPaused(PluginCall call) {
        int socketId = call.getInt("socketId");
        boolean paused = call.getBoolean("paused");
        try {
            UdpSocket socket = obtainSocket(socketId);
            socket.setPaused(paused);
            if (paused) {
                // Read interest will be removed when socket is readable on selector thread.
                call.success();
            } else {
                addSelectorMessage(socket, SelectorMessageType.SO_ADD_READ_INTEREST, call);
            }
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void bind(PluginCall call) {
        int socketId = call.getInt("socketId");
        String address = call.getString("address");
        int port = call.getInt("port");
        try {
            UdpSocket socket = obtainSocket(socketId);
            socket.bind(address, port);
            addSelectorMessage(socket, SelectorMessageType.SO_BIND, call);
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void send(PluginCall call) {
        try {
            int socketId = call.getInt("socketId");
            String address = call.getString("address");
            int port = call.getInt("port");
            String bufferString = call.getString("buffer");
            byte[] data = Base64.decode(bufferString, Base64.DEFAULT);
            UdpSocket socket = obtainSocket(socketId);
            if (!socket.isBound)
                throw new Exception("Not bound yet");
            socket.addSendPacket(address, port, data, call);
            addSelectorMessage(socket, SelectorMessageType.SO_ADD_WRITE_INTEREST, null);
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void closeAllSockets(PluginCall call) {
        try {
            for (UdpSocket socket : sockets.values()) {
                addSelectorMessage(socket, SelectorMessageType.SO_CLOSE, null);
            }
            call.success();
        } catch (Exception e) {
            call.error(e.getMessage());
        }

    }

    @PluginMethod()
    public void close(PluginCall call) {
        try {
            int socketId = call.getInt("socketId");
            UdpSocket socket = obtainSocket(socketId);
            addSelectorMessage(socket, SelectorMessageType.SO_CLOSE, call);
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void getInfo(PluginCall call) {
        try {
            int socketId = call.getInt("socketId");
            UdpSocket socket = obtainSocket(socketId);
            call.success(socket.getInfo());
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void getSockets(PluginCall call) {
        try {
            JSArray results = new JSArray();
            for (UdpSocket socket : sockets.values()) {
                results.put(socket.getInfo());
            }
            JSObject ret = new JSObject();
            ret.put("sockets", results);
            call.success(ret);
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void joinGroup(PluginCall call) {
        try {
            int socketId = call.getInt("socketId");
            String address = call.getString("address");
            UdpSocket socket = obtainSocket(socketId);
            socket.joinGroup(address);
            call.success();
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void leaveGroup(PluginCall call) {
        try {
            int socketId = call.getInt("socketId");
            String address = call.getString("address");
            UdpSocket socket = obtainSocket(socketId);
            socket.leaveGroup(address);
            call.success();
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void setMulticastTimeToLive(PluginCall call) {
        try {
            int socketId = call.getInt("socketId");
            int ttl = call.getInt("ttl");
            UdpSocket socket = obtainSocket(socketId);
            socket.setMulticastTimeToLive(ttl);
            call.success();
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void setBroadcast(PluginCall call) {
        try {
            int socketId = call.getInt("socketId");
            boolean enabled = call.getBoolean("enabled");
            UdpSocket socket = obtainSocket(socketId);
            socket.setBroadcast(enabled);
            call.success();
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void setMulticastLoopbackMode(PluginCall call) {
        try {
            int socketId = call.getInt("socketId");
            boolean enabled = call.getBoolean("enabled");
            UdpSocket socket = obtainSocket(socketId);
            socket.setMulticastLoopbackMode(enabled, call);
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    @PluginMethod()
    public void getJoinedGroups(PluginCall call) {
        try {
            int socketId = call.getInt("socketId");
            UdpSocket socket = obtainSocket(socketId);

            JSArray results = new JSArray(socket.getJoinedGroups());
            JSObject ret = new JSObject();
            ret.put("groups", results);
            call.success(ret);
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    private void sendReceiveErrorEvent(int code, String message) {
        JSObject error = new JSObject();
        try {
            error.put("message", message);
            error.put("resultCode", code);
            notifyListeners("receiveError", error, false);
        } catch (Exception e) {
        }
    }

    // This is a synchronized method because regular read and multicast read on
    // different threads, and we need to send data and metadata in serial in order
    // to decode the receive event correctly. Alternatively, we can send Multipart
    // messages.
    private synchronized void sendReceiveEvent(byte[] data, int socketId, String address, int port) {
        JSObject ret = new JSObject();
        try {
            ret.put("socketId", socketId);
            int ip6InterfaceIndex = address.indexOf("%");
            if (ip6InterfaceIndex > 0) {
                ret.put("remoteAddress", address.substring(0, ip6InterfaceIndex));
            } else {
                ret.put("remoteAddress", address);
            }
            ret.put("remotePort", port);
            String bufferString = new String(Base64.encode(data, Base64.DEFAULT));
            ret.put("buffer", bufferString);
            notifyListeners("receive", ret, false);
        } catch (Exception e) {
        }
    }

    private void startSelectorThread() {
        if (selectorThread != null)
            return;
        selectorThread = new SelectorThread(selectorMessages, sockets);
        selectorThread.start();
    }

    private void stopSelectorThread() {
        if (selectorThread == null)
            return;
        addSelectorMessage(null, SelectorMessageType.T_STOP, null);
        try {
            selectorThread.join();
            selectorThread = null;
        } catch (InterruptedException e) {
        }
    }

    private void addSelectorMessage(UdpSocket socket, SelectorMessageType type, PluginCall call) {
        try {
            selectorMessages.put(new SelectorMessage(socket, type, call));
            if (selector != null)
                selector.wakeup();
        } catch (InterruptedException e) {
        }
    }

    private enum SelectorMessageType {
        SO_BIND, SO_CLOSE, SO_ADD_READ_INTEREST, SO_ADD_WRITE_INTEREST, T_STOP;
    }

    private NetworkInterface getNetworkInterface() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                if (addrs.size() < 2)
                    continue;
                if (addrs.get(0).isLoopbackAddress())
                    continue;
                return intf;
            }
        } catch (Exception ignored) {
        } // for now eat exceptions
        return null;
    }

    private InetAddress getIPAddress(boolean useIPv4) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                if (addrs.size() < 2)
                    continue;
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        // boolean isIPv4 = InetAddressUtils.isIPv4Address(sAddr);
                        boolean isIPv4 = sAddr.indexOf(':') < 0;
                        if (useIPv4) {
                            if (isIPv4)
                                return addr;
                            // return sAddr;
                        } else {
                            if (!isIPv4) {
                                int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                                return addr;
                                // return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0,
                                // delim).toUpperCase();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } // for now eat exceptions
        return InetAddress.getLoopbackAddress();
    }

    private class SelectorMessage {
        final UdpSocket socket;
        final SelectorMessageType type;
        final PluginCall call;

        SelectorMessage(UdpSocket socket, SelectorMessageType type, PluginCall call) {
            this.socket = socket;
            this.type = type;
            this.call = call;
        }
    }

    private class SelectorThread extends Thread {

        private BlockingQueue<SelectorMessage> selectorMessages;
        private Map<Integer, UdpSocket> sockets;
        private boolean running = true;

        SelectorThread(BlockingQueue<SelectorMessage> selectorMessages, Map<Integer, UdpSocket> sockets) {
            this.selectorMessages = selectorMessages;
            this.sockets = sockets;
        }

        private void processPendingMessages() {

            while (selectorMessages.peek() != null) {
                SelectorMessage msg = null;
                try {
                    msg = selectorMessages.take();
                    switch (msg.type) {
                        case SO_BIND:
                            msg.socket.register(selector, SelectionKey.OP_READ);
                            msg.socket.isBound = true;
                            break;
                        case SO_CLOSE:
                            msg.socket.close();
                            sockets.remove(Integer.valueOf(msg.socket.getSocketId()));
                            break;
                        case SO_ADD_READ_INTEREST:
                            msg.socket.addInterestSet(SelectionKey.OP_READ);
                            break;
                        case SO_ADD_WRITE_INTEREST:
                            msg.socket.addInterestSet(SelectionKey.OP_WRITE);
                            break;
                        case T_STOP:
                            running = false;
                            break;
                    }

                    if (msg.call != null)
                        msg.call.success();

                } catch (InterruptedException e) {
                } catch (IOException e) {
                    if (msg.call != null) {
                        msg.call.error(e.getMessage());
                    }
                }
            }

        }

        public void run() {

            try {
                if (selector == null)
                    selector = Selector.open();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            // process possible messages that send during openning the selector
            // before select.
            processPendingMessages();

            Iterator<SelectionKey> it;

            while (running) {

                try {
                    selector.select();
                } catch (IOException e) {
                    continue;
                }

                it = selector.selectedKeys().iterator();

                while (it.hasNext()) {

                    SelectionKey key = it.next();
                    it.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    UdpSocket socket = (UdpSocket) key.attachment();

                    if (key.isReadable()) {
                        socket.read();
                    }

                    if (key.isWritable()) {
                        socket.dequeueSend();
                    }
                } // while next

                processPendingMessages();
            }
        }
    }
}
