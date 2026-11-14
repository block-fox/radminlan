package dev.radminlan.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private final ExecutorService updates = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "radminlan-pipe-updates");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean updatesStarted = new AtomicBoolean();
    private volatile boolean closed;

    public RadminPipeClient(String pipeName) { this.pipeName = pipeName; }

    /** Keeps the service's unsolicited peer updates flowing between explicit refreshes. Never writes to the pipe. */
    public void startPeerUpdates(Consumer<byte[]> frames, Runnable disconnected) {
        if (closed || !updatesStarted.compareAndSet(false, true)) return;
        updates.execute(() -> {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                try (WinPipe connected = WinPipe.open(pipeName, GENERIC_READ)) {
                    readUpdates(connected, frames);
                } catch (IOException | RuntimeException ignored) {
                    // A service restart or unavailable pipe is retried without
                    // retaining measurements from the previous connection.
                } finally {
                    disconnected.run();
                }
                if (closed || Thread.currentThread().isInterrupted()) return;
                try { Thread.sleep(500); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    private void readUpdates(WinPipe pipe, Consumer<byte[]> frames) throws IOException {
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        while (!closed && !Thread.currentThread().isInterrupted()) {
            int available = pipe.availableBytes();
            if (available == 0) {
                try { Thread.sleep(16); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            byte[] chunk = pipe.read(Math.min(64 * 1024, available));
            if (pending.size() + chunk.length > MAX_RESPONSE_SIZE) {
                throw new IOException("Radmin pipe update exceeds 8 MiB");
            }
            pending.write(chunk);
            byte[] bytes = pending.toByteArray();
            int offset = 0;
            while (offset + 4 <= bytes.length) {
                int length = RadminWire.readIntLE(bytes, offset);
                if (length <= 0 || length > MAX_RESPONSE_SIZE) throw new IOException("Invalid Radmin update frame");
                if (offset + 4L + length > bytes.length) break;
                offset += 4 + length;
            }
            if (offset == 0) continue;
            frames.accept(Arrays.copyOf(bytes, offset));
            pending.reset();
            pending.write(bytes, offset, bytes.length - offset);
        }
    }

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

    @Override public void close() {
        closed = true;
        updates.shutdownNow();
        reader.shutdownNow();
    }

    public record ReadResult(byte[] bytes, boolean canWrite, String transport) {}

    private static final class WinPipe implements AutoCloseable {
        private final MemorySegment handle;

        private WinPipe(MemorySegment handle) { this.handle = handle; }

        static WinPipe open(String name, int access) throws IOException {
            if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
                throw new IOException("Radmin named-pipe access is only available on Windows");
            }
            try (Arena arena = Arena.ofConfined()) {
                byte[] encoded = (name + "\0").getBytes(StandardCharsets.UTF_16LE);
                MemorySegment path = arena.allocate(encoded.length, 2);
                path.copyFrom(MemorySegment.ofArray(encoded));
                MemorySegment handle = Native.createFile(path, access, OPEN_EXISTING);
                if (!isInvalid(handle)) return new WinPipe(handle);
                int error = Native.lastError();
                throw win32("CreateFileW", error);
            }
        }

        int availableBytes() throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment available = arena.allocate(ValueLayout.JAVA_INT);
                int success = Native.peek(handle, available);
                if (success == 0) throw win32("PeekNamedPipe", Native.lastError());
                return Math.max(0, available.get(ValueLayout.JAVA_INT, 0));
            }
        }

        byte[] read(int length) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(length, 1);
                MemorySegment received = arena.allocate(ValueLayout.JAVA_INT);
                int success = Native.read(handle, buffer, length, received);
                if (success == 0) throw win32("ReadFile", Native.lastError());
                int count = received.get(ValueLayout.JAVA_INT, 0);
                return count <= 0 ? new byte[0] : buffer.asSlice(0, count).toArray(ValueLayout.JAVA_BYTE);
            }
        }

        void write(byte[] bytes) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(bytes.length, 1);
                buffer.copyFrom(MemorySegment.ofArray(bytes));
                MemorySegment written = arena.allocate(ValueLayout.JAVA_INT);
                int success = Native.write(handle, buffer, bytes.length, written);
                if (success == 0) throw win32("WriteFile", Native.lastError());
                int count = written.get(ValueLayout.JAVA_INT, 0);
                if (count != bytes.length) throw new IOException("WriteFile wrote " + count + "/" + bytes.length + " bytes");
            }
        }

        @Override public void close() throws IOException {
            if (Native.close(handle) == 0) throw win32("CloseHandle", Native.lastError());
        }

        private static boolean isInvalid(MemorySegment handle) {
            return handle.address() == 0L || handle.address() == -1L;
        }
    }

    private static IOException win32(String operation, int error) {
        return new IOException(operation + " failed (Win32 " + Integer.toUnsignedString(error) + ")");
    }

    private static final class Native {
        private static final Arena LIBRARY_ARENA = Arena.global();
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", LIBRARY_ARENA);
        private static final MethodHandle CREATE_FILE = bind("CreateFileW", FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        private static final MethodHandle PEEK_NAMED_PIPE = bind("PeekNamedPipe", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle READ_FILE = bind("ReadFile", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle WRITE_FILE = bind("WriteFile", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle CLOSE_HANDLE = bind("CloseHandle", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        private static final MethodHandle GET_LAST_ERROR = bind("GetLastError", FunctionDescriptor.of(ValueLayout.JAVA_INT));

        private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
            MemorySegment symbol = KERNEL32.find(name)
                    .orElseThrow(() -> new ExceptionInInitializerError("Missing kernel32 function " + name));
            return LINKER.downcallHandle(symbol, descriptor);
        }

        static MemorySegment createFile(MemorySegment path, int access, int disposition) throws IOException {
            return (MemorySegment) invoke(CREATE_FILE, path, access, FILE_SHARE_READ | FILE_SHARE_WRITE,
                    MemorySegment.NULL, disposition, 0, MemorySegment.NULL);
        }

        static int peek(MemorySegment handle, MemorySegment available) throws IOException {
            return (int) invoke(PEEK_NAMED_PIPE, handle, MemorySegment.NULL, 0,
                    MemorySegment.NULL, available, MemorySegment.NULL);
        }

        static int read(MemorySegment handle, MemorySegment buffer, int length, MemorySegment received) throws IOException {
            return (int) invoke(READ_FILE, handle, buffer, length, received, MemorySegment.NULL);
        }

        static int write(MemorySegment handle, MemorySegment buffer, int length, MemorySegment written) throws IOException {
            return (int) invoke(WRITE_FILE, handle, buffer, length, written, MemorySegment.NULL);
        }

        static int close(MemorySegment handle) throws IOException { return (int) invoke(CLOSE_HANDLE, handle); }
        static int lastError() throws IOException { return (int) invoke(GET_LAST_ERROR); }

        private static Object invoke(MethodHandle handle, Object... arguments) throws IOException {
            try { return handle.invokeWithArguments(arguments); }
            catch (Throwable e) { throw new IOException("Unable to call Windows named-pipe API", e); }
        }
    }
}
