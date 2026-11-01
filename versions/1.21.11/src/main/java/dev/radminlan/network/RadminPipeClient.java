package dev.radminlan.network;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Connection to the official Windows Radmin control pipe. */
public final class RadminPipeClient implements AutoCloseable {
    public static final String DEFAULT_PIPE = "\\\\.\\pipe\\RadminVpnGuiChannel";
    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int FILE_SHARE_READ = 0x00000001;
    private static final int FILE_SHARE_WRITE = 0x00000002;
    private static final int OPEN_EXISTING = 3;
    private static final int MAX_RESPONSE_SIZE = 8 * 1024 * 1024;
    private final String pipeName;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "radminlan-pipe");
        t.setDaemon(true);
        return t;
    });

    public RadminPipeClient(String pipeName) { this.pipeName = pipeName; }

    public ReadResult readInitialFrames(Duration timeout) throws Exception {
        Future<ReadResult> future = reader.submit(() -> {
            WinPipe pipe;
            boolean canWrite = true;
            try {
                pipe = WinPipe.open(pipeName, GENERIC_READ | GENERIC_WRITE);
            } catch (IOException readWriteFailure) {
                try {
                    pipe = WinPipe.open(pipeName, GENERIC_READ);
                    canWrite = false;
                } catch (IOException readFailure) {
                    throw new IOException(readFailure.getMessage() + "; read/write open also failed: "
                            + readWriteFailure.getMessage(), readFailure);
                }
            }
            try (WinPipe connected = pipe) {
                String mode = canWrite ? "read/write" : "read-only";
                return new ReadResult(readAvailableBatch(connected, timeout), canWrite, pipeName + " (" + mode + ")");
            }
        });
        try { return future.get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS); }
        finally { future.cancel(true); }
    }

    /** Sends one command body, adding the little-endian pipe frame length. */
    public void sendCommand(byte[] body) throws IOException {
        if (body == null || body.length == 0) throw new IllegalArgumentException("Command body is empty");
        try (WinPipe pipe = WinPipe.open(pipeName, GENERIC_READ | GENERIC_WRITE)) {
            writeFrame(pipe, body);
        }
    }

    /** Sends a request and collects the response batch from the same duplex pipe connection. */
    public byte[] sendCommandAndReadSnapshot(byte[] body, Duration timeout) throws Exception {
        if (body == null || body.length == 0) throw new IllegalArgumentException("Command body is empty");
        Future<byte[]> future = reader.submit(() -> {
            try (WinPipe pipe = WinPipe.open(pipeName, GENERIC_READ | GENERIC_WRITE)) {
                writeFrame(pipe, body);
                // Command responses, especially CEnumPublicNetworks, can be
                // delivered as several frames with short gaps between them.
                // Keep the duplex handle open long enough to collect the
                // complete batch instead of returning after the first frame.
                return readAvailableBatch(pipe, timeout, 800L);
            }
        });
        try { return future.get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS); }
        finally { future.cancel(true); }
    }

    private static void writeFrame(WinPipe pipe, byte[] body) throws IOException {
        ByteBuffer frame = ByteBuffer.allocate(4 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(body.length).put(body);
        pipe.write(frame.array());
    }

    /** Polls the native pipe queue so the timeout does not depend on InputStream.available(). */
    private static byte[] readAvailableBatch(WinPipe pipe, Duration timeout) throws IOException {
        return readAvailableBatch(pipe, timeout, 120L);
    }

    private static byte[] readAvailableBatch(WinPipe pipe, Duration timeout, long quietMillis) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long lastDataAt = 0;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        while (System.nanoTime() < deadline) {
            int available = pipe.availableBytes();
            if (available > 0) {
                byte[] chunk = pipe.read(Math.min(64 * 1024, available));
                if (chunk.length == 0) continue;
                if (all.size() + chunk.length > MAX_RESPONSE_SIZE) {
                    throw new IOException("Radmin pipe response exceeds 8 MiB");
                }
                all.write(chunk);
                lastDataAt = System.nanoTime();
            } else if (all.size() > 0 && System.nanoTime() - lastDataAt >= TimeUnit.MILLISECONDS.toNanos(quietMillis)) {
                return all.toByteArray();
            } else {
                try { Thread.sleep(8); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading Radmin pipe", e);
                }
            }
        }
        if (all.size() == 0) {
            throw new IOException("Radmin control pipe opened, but no response bytes arrived within "
                    + timeout.toMillis() + " ms");
        }
        return all.toByteArray();
    }

    @Override public void close() { reader.shutdownNow(); }

    public record ReadResult(byte[] bytes, boolean canWrite, String transport) {}

    private static final class WinPipe implements AutoCloseable {
        private final WinNT.HANDLE handle;

        private WinPipe(WinNT.HANDLE handle) { this.handle = handle; }

        static WinPipe open(String name, int access) throws IOException {
            if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
                throw new IOException("Radmin named-pipe access is only available on Windows");
            }
            WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(name, access,
                    FILE_SHARE_READ | FILE_SHARE_WRITE, null, OPEN_EXISTING, 0, null);
            if (!isInvalid(handle)) return new WinPipe(handle);
            int error = Native.getLastError();
            throw win32("CreateFileW", error);
        }

        int availableBytes() throws IOException {
            IntByReference available = new IntByReference();
            if (!Kernel32.INSTANCE.PeekNamedPipe(handle, null, 0, null, available, null)) {
                throw win32("PeekNamedPipe", Native.getLastError());
            }
            return Math.max(0, available.getValue());
        }

        byte[] read(int length) throws IOException {
            byte[] buffer = new byte[length];
            IntByReference received = new IntByReference();
            if (!Kernel32.INSTANCE.ReadFile(handle, buffer, length, received, null)) {
                throw win32("ReadFile", Native.getLastError());
            }
            int count = received.getValue();
            if (count <= 0) return new byte[0];
            if (count == buffer.length) return buffer;
            return java.util.Arrays.copyOf(buffer, count);
        }

        void write(byte[] bytes) throws IOException {
            IntByReference written = new IntByReference();
            if (!Kernel32.INSTANCE.WriteFile(handle, bytes, bytes.length, written, null)) {
                throw win32("WriteFile", Native.getLastError());
            }
            int count = written.getValue();
            if (count != bytes.length) throw new IOException("WriteFile wrote " + count + "/" + bytes.length + " bytes");
        }

        @Override public void close() throws IOException {
            if (!Kernel32.INSTANCE.CloseHandle(handle)) throw win32("CloseHandle", Native.getLastError());
        }

        private static boolean isInvalid(WinNT.HANDLE handle) {
            if (handle == null) return true;
            long address = Pointer.nativeValue(handle.getPointer());
            return address == 0L || address == -1L;
        }
    }

    private static IOException win32(String operation, int error) {
        return new IOException(operation + " failed (Win32 " + Integer.toUnsignedString(error) + ")");
    }

}
