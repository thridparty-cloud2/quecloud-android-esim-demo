package com.quec.tcp.esim.utils;

import com.quectel.basic.queclog.QLog;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class TCPClientUtils {

    private static final String TAG = "TCPClientUtils";
    private static volatile TCPClientUtils instance;

    private volatile Socket socket;
    private volatile InputStream inputStream;
    private volatile OutputStream outputStream;

    private String ipAddress;
    private int port;

    /**
     * tcp 客户端状态机
     *
     * 状态流转说明：
     *
     * idle
     *  └── 初始状态 / 已完全断开
     *      - 未创建 socket
     *      - 线程池未启动
     *      - 可安全调用 connect()
     *
     * connecting
     *  └── 正在建立 tcp 连接
     *      - socket 正在创建
     *      - 尚未开始收发数据
     *      - 禁止重复 connect()
     *
     * connected
     *  └── tcp 连接已建立，正常工作态
     *      - socket / inputstream / outputstream 就绪
     *      - 允许 send()
     *      - 接收线程正在运行
     *
     * disconnecting
     *  └── 正在断开连接（中间态）
     *      - 主动 disconnect 或异常触发
     *      - 正在关闭流 / socket / 线程池
     *      - 防止多线程重复触发断连逻辑
     *
     * 合法状态流转：
     * idle → connecting → connected → disconnecting → idle
     *
     * 非法流转将被忽略：
     * - connected → connecting
     * - disconnecting → connecting
     */
    private enum State {
        IDLE,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicBoolean disconnectedNotified = new AtomicBoolean(false);

    /** ===== 线程池 ===== */
    private ExecutorService connectExecutor;
    private ExecutorService sendExecutor;
    private ExecutorService receiveExecutor;

    /** ===== 回调 ===== */
    private OnServerConnectedCallbackBlock connectedCallback;
    private OnCannotConnectedCallbackBlock cannotConnectedCallback;
    private OnServerDisconnectedCallbackBlock disconnectedCallback;
    private OnReceiveCallbackBlock receivedCallback;

    private TCPClientUtils() {
    }

    public static TCPClientUtils getInstance() {
        if (instance == null) {
            synchronized (TCPClientUtils.class) {
                if (instance == null) {
                    instance = new TCPClientUtils();
                }
            }
        }
        return instance;
    }

    /* =================== 连接 =================== */

    public synchronized void connect(String ipAddress, int port) {
        if (state.get() != State.IDLE) {
            if (cannotConnectedCallback != null) {
                cannotConnectedCallback.cannotConnectedCallback();
            }
            QLog.w(TAG, "当前状态不可连接: " + state.get());
            return;
        }

        this.ipAddress = ipAddress;
        this.port = port;
        this.state.set(State.CONNECTING);
        this.disconnectedNotified.set(false);

        initExecutors();

        connectExecutor.execute(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(ipAddress, port), 5000);
                socket.setTcpNoDelay(true);

                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();

                state.set(State.CONNECTED);

                QLog.i(TAG, "TCP 连接成功");
                if (connectedCallback != null) {
                    connectedCallback.connectedCallback();
                }

                startReceiveLoop();

            } catch (Exception e) {
                QLog.e(TAG, "连接失败: " + e.getMessage());
                notifyDisconnected(e);
            }
        });
    }

    public boolean isConnected() {
        return socket != null
                && socket.isConnected()
                && !socket.isClosed()
                && !socket.isInputShutdown()
                && !socket.isOutputShutdown()
                && state.get() == State.CONNECTED;
    }

    /* =================== 接收 =================== */

    private void startReceiveLoop() {
        receiveExecutor.execute(() -> {
            byte[] buffer = new byte[1024];
            try {
                while (isConnected()) {
                    int len = inputStream.read(buffer); // 阻塞
                    if (len == -1) {
                        throw new Exception("服务器主动断开");
                    }

                    byte[] data = new byte[len];
                    System.arraycopy(buffer, 0, data, 0, len);

                    if (receivedCallback != null) {
                        receivedCallback.receiveCallback(data);
                    }
                }
            } catch (Exception e) {
                QLog.e(TAG, "接收异常: " + e.getMessage());
                notifyDisconnected(e);
            }
        });
    }

    /* =================== 发送 =================== */

    public void send(byte[] data) {
        if (sendExecutor == null) {
            QLog.e(TAG, "TCP 未连接，发送失败");
            return;
        }

        try {
            sendExecutor.execute(() -> {
                if (!isConnected()) {
                    QLog.e(TAG, "TCP 未连接，发送失败");
                    return;
                }
                try {
                    outputStream.write(data);
                    outputStream.flush();
                } catch (Exception e) {
                    QLog.e(TAG, "发送失败: " + e.getMessage());
                    notifyDisconnected(e);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            QLog.e(TAG, "线程池已关闭，发送任务被拒绝");
        }
    }

    /* =================== 断开 =================== */

    public synchronized void disconnect() {
        if (state.get() == State.IDLE || state.get() == State.DISCONNECTING) {
            return;
        }
        notifyDisconnected(new Exception("主动断开连接"));
    }

    private void notifyDisconnected(Exception e) {
        if (!disconnectedNotified.compareAndSet(false, true)) {
            return;
        }

        state.set(State.DISCONNECTING);

        QLog.i(TAG, "TCP 断开: " + e.getMessage());

        closeQuietly();

        state.set(State.IDLE);

        if (disconnectedCallback != null) {
            disconnectedCallback.disconnectedCallback(e);
        }
    }

    private void closeQuietly() {

        try {
            if (socket != null) {
                socket.shutdownInput();
            }
        } catch (Exception ignored) {}

        try {
            if (socket != null) {
                socket.shutdownOutput();
            }
        } catch (Exception ignored) {}

        try {
            if (socket != null) {
                socket.close();   // 关键：立刻打断 read()
            }
        } catch (Exception ignored) {}

        try {
            if (inputStream != null) inputStream.close();
        } catch (Exception ignored) {}

        try {
            if (outputStream != null) outputStream.close();
        } catch (Exception ignored) {}

        shutdownExecutors();
    }

    /* =================== Executor 管理 =================== */

    private void initExecutors() {
        connectExecutor = Executors.newSingleThreadExecutor();
        sendExecutor = Executors.newSingleThreadExecutor();
        receiveExecutor = Executors.newSingleThreadExecutor();
    }

    private void shutdownExecutors() {
        if (connectExecutor != null){
            connectExecutor.shutdownNow();
            connectExecutor = null;
        }
        if (sendExecutor != null) {
            sendExecutor.shutdownNow();
            sendExecutor=null;
        }
        if (receiveExecutor != null) {
            receiveExecutor.shutdownNow();
            receiveExecutor=null;
        }
    }

    /* =================== 回调 =================== */

    public interface OnServerConnectedCallbackBlock {
        void connectedCallback();
    }

    public interface OnCannotConnectedCallbackBlock {
        void cannotConnectedCallback();
    }

    public interface OnServerDisconnectedCallbackBlock {
        void disconnectedCallback(Exception e);
    }

    public interface OnReceiveCallbackBlock {
        void receiveCallback(byte[] receivedMessage);
    }

    public void setConnectedCallback(OnServerConnectedCallbackBlock callback) {
        this.connectedCallback = callback;
    }

    public void setCannotConnectedCallback(OnCannotConnectedCallbackBlock callback) {
        this.cannotConnectedCallback = callback;
    }

    public void setDisconnectedCallback(OnServerDisconnectedCallbackBlock callback) {
        this.disconnectedCallback = callback;
    }

    public void setReceivedCallback(OnReceiveCallbackBlock callback) {
        this.receivedCallback = callback;
    }
}
