package ai.unitree.udp;

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

public class UdpSocket {
    private final int socketId;
    private final DatagramChannel channel;

    private MulticastSocket multicastSocket;

    private BlockingQueue<UdpSendPacket> sendPackets = new LinkedBlockingQueue<UdpSendPacket>();
    private Set<String> multicastGroups = new HashSet<String>();
    private SelectionKey key;
    private boolean isBound;

    private boolean paused;
    private DatagramPacket pausedMulticastPacket;

    private String name;
    private int bufferSize;

    private MulticastReadThread multicastReadThread;
    private boolean multicastLoopback;
    private InetAddress ipv4Address;
    private InetAddress ipv6Address;
    private NetworkInterface networkInterface;

    UdpSocket(int socketId, JSObject properties) throws JSONException, IOException {
        this.socketId = socketId;
        this.ipv4Address = getIPAddress(true);
        this.ipv6Address = getIPAddress(false);
        this.networkInterface = getNetworkInterface();
        channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, this.networkInterface);
        multicastSocket = null;

        // set socket default options
        paused = false;
        bufferSize = 4096;
        name = "";

        multicastReadThread = null;
        multicastLoopback = true;

        isBound = false;

        setProperties(properties);
        setBufferSize();
    }

    // Only call this method on selector thread
    void addInterestSet(int interestSet) {
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() | interestSet);
            key.selector().wakeup();
        }
    }

    // Only call this method on selector thread
    void removeInterestSet(int interestSet) {
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() & ~interestSet);
            key.selector().wakeup();
        }
    }

    int getSocketId() {
        return socketId;
    }

    void register(Selector selector, int interestSets) throws IOException {
        key = channel.register(selector, interestSets, this);
    }

    void setProperties(JSObject properties) throws JSONException, SocketException {

        if (!properties.isNull("name"))
            name = properties.getString("name");

        if (!properties.isNull("bufferSize")) {
            bufferSize = properties.getInt("bufferSize");
            setBufferSize();
        }
    }

    void setBufferSize() throws SocketException {
        channel.socket().setSendBufferSize(bufferSize);
        channel.socket().setReceiveBufferSize(bufferSize);
    }

    private void sendMulticastPacket(DatagramPacket packet) {
        byte[] out = packet.getData();

        // Truncate the buffer if the message was shorter than it.
        if (packet.getLength() != out.length) {
            byte[] temp = new byte[packet.getLength()];
            for (int i = 0; i < packet.getLength(); i++) {
                temp[i] = out[i];
            }
            out = temp;
        }

        sendReceiveEvent(out, socketId, packet.getAddress().getHostAddress(), packet.getPort());
    }

    private void bindMulticastSocket() throws SocketException {
        multicastSocket.bind(new InetSocketAddress(channel.socket().getLocalPort()));

        if (!paused) {
            multicastReadThread = new MulticastReadThread(multicastSocket);
            multicastReadThread.start();
        }
    }

    // Upgrade the normal datagram socket to multicast socket. All incoming
    // packet will be received on the multicast read thread. There is no way to
    // downgrade the same socket back to a normal datagram socket.
    private void upgradeToMulticastSocket() throws IOException {
        if (multicastSocket == null) {
            multicastSocket = new MulticastSocket(null);
            multicastSocket.setReuseAddress(true);
            multicastSocket.setLoopbackMode(false);

            if (channel.socket().isBound()) {
                bindMulticastSocket();
            }
        }
    }

    private void resumeMulticastSocket() {
        if (pausedMulticastPacket != null) {
            sendMulticastPacket(pausedMulticastPacket);
            pausedMulticastPacket = null;
        }

        if (multicastSocket != null && multicastReadThread == null) {
            multicastReadThread = new MulticastReadThread(multicastSocket);
            multicastReadThread.start();
        }
    }

    void setPaused(boolean paused) {
        this.paused = paused;
        if (!this.paused) {
            resumeMulticastSocket();
        }
    }

    void addSendPacket(String address, int port, byte[] data, PluginCall call) {
        UdpSendPacket sendPacket = new UdpSendPacket(address, port, data, call);
        try {
            sendPackets.put(sendPacket);
        } catch (Exception e) {
            call.error(e.getMessage());
        }
    }

    void bind(String address, int port) throws SocketException {
        channel.socket().setReuseAddress(true);
        channel.socket().bind(new InetSocketAddress(port));

        if (multicastSocket != null) {
            bindMulticastSocket();
        }
    }

    void send(String address, int port, byte[] data) {
        final SocketAddress addr = new InetSocketAddress(address, port);
        final ByteBuffer bb = ByteBuffer.wrap(data);
        channel.send(bb, addr);
    }

    // This method can be only called by selector thread.
    void dequeueSend() {
        if (sendPackets.peek() == null) {
            removeInterestSet(SelectionKey.OP_WRITE);
            return;
        }

        UdpSendPacket sendPacket = null;
        try {
            sendPacket = sendPackets.take();
            JSObject ret = new JSObject();
            int bytesSent = channel.send(sendPacket.data, sendPacket.address);
            ret.put("bytesSent", bytesSent);
            if (sendPacket.call != null)
                sendPacket.call.success(ret);
        } catch (InterruptedException e) {
        } catch (IOException e) {
            if (sendPacket.call != null)
                sendPacket.call.error(e.getMessage());
        }
    }

    void close() throws IOException {

        if (key != null && channel.isRegistered())
            key.cancel();

        channel.close();

        if (multicastSocket != null) {
            multicastSocket.close();
            multicastSocket = null;
        }

        if (multicastReadThread != null) {
            multicastReadThread.cancel();
            multicastReadThread = null;
        }
    }

    JSObject getInfo() throws JSONException {
        JSObject info = new JSObject();
        info.put("socketId", socketId);
        info.put("bufferSize", bufferSize);
        info.put("name", name);
        info.put("paused", paused);
        if (channel.socket().getLocalAddress() != null) {
            info.put("localAddress", channel.socket().getLocalAddress().getHostAddress());
            info.put("localPort", channel.socket().getLocalPort());
        }
        return info;
    }

    void joinGroup(String address) throws IOException {

        upgradeToMulticastSocket();

        if (multicastGroups.contains(address)) {
            Log.e(LOG_TAG, "Attempted to join an already joined multicast group.");
            return;
        }

        multicastGroups.add(address);
        multicastSocket.joinGroup(
                new InetSocketAddress(InetAddress.getByName(address), channel.socket().getLocalPort()),
                networkInterface);

    }

    void leaveGroup(String address) throws UnknownHostException, IOException {
        if (multicastGroups.contains(address)) {
            multicastGroups.remove(address);
            multicastSocket.leaveGroup(InetAddress.getByName(address));
        }
    }

    void setMulticastTimeToLive(int ttl) throws IOException {
        upgradeToMulticastSocket();
        multicastSocket.setTimeToLive(ttl);
    }

    void setMulticastLoopbackMode(boolean enabled, PluginCall call) throws IOException {
        upgradeToMulticastSocket();
        multicastSocket.setLoopbackMode(!enabled);
        multicastLoopback = enabled;
        JSObject ret = new JSObject();
        ret.put("enabled", !multicastSocket.getLoopbackMode());
        call.success(ret);
    }

    void setBroadcast(boolean enabled) throws IOException {
        channel.socket().setBroadcast(enabled);
    }

    public Collection<String> getJoinedGroups() {
        return multicastGroups;
    }

    // This method can be only called by selector thread.
    void read() {

        if (paused) {
            // Remove read interests to avoid seletor wakeup when readable.
            removeInterestSet(SelectionKey.OP_READ);
            return;
        }

        ByteBuffer recvBuffer = ByteBuffer.allocate(bufferSize);
        recvBuffer.clear();

        try {
            InetSocketAddress address = (InetSocketAddress) channel.receive(recvBuffer);

            recvBuffer.flip();
            byte[] recvBytes = new byte[recvBuffer.limit()];
            recvBuffer.get(recvBytes);
            if (address.getAddress().getHostAddress().contains(":") && multicastSocket != null) {
                return;
            }
            sendReceiveEvent(recvBytes, socketId, address.getAddress().getHostAddress(), address.getPort());
        } catch (IOException e) {
            sendReceiveErrorEvent(-2, e.getMessage());
        }
    }

    private class MulticastReadThread extends Thread {

        private final MulticastSocket socket;

        MulticastReadThread(MulticastSocket socket) {
            this.socket = socket;
        }

        public void run() {
            while (!Thread.currentThread().isInterrupted()) {

                if (paused) {
                    // Terminate the thread if the socket is paused
                    multicastReadThread = null;
                    return;
                }
                try {
                    byte[] out = new byte[socket.getReceiveBufferSize()];
                    DatagramPacket packet = new DatagramPacket(out, out.length);
                    socket.receive(packet);
                    if (!multicastLoopback) {
                        String fromAddress = packet.getAddress().getHostAddress();
                        String ip4 = ipv4Address.getHostAddress();
                        String ip6 = ipv6Address.getHostAddress();

                        if (fromAddress.equalsIgnoreCase(ip4) || fromAddress.equalsIgnoreCase(ip6)) {
                            continue;
                        }
                    }
                    if (paused) {
                        pausedMulticastPacket = packet;
                    } else {
                        sendMulticastPacket(packet);
                    }

                } catch (IOException e) {
                    sendReceiveErrorEvent(-2, e.getMessage());
                }
            }
        }

        public void cancel() {
            interrupt();
        }
    }

    private class UdpSendPacket {
        final SocketAddress address;
        final PluginCall call;
        final ByteBuffer data;

        UdpSendPacket(String address, int port, byte[] data, PluginCall call) {
            this.address = new InetSocketAddress(address, port);
            this.data = ByteBuffer.wrap(data);
            this.call = call;
        }
    }
}