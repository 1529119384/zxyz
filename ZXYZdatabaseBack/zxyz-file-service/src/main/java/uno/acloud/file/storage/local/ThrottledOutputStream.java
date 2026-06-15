package uno.acloud.file.storage.local;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 限速输出流
 * <p>
 * 包装 OutputStream，在 write 之间 sleep 来实现下载限速。
 * </p>
 */
public class ThrottledOutputStream extends OutputStream {

    private final OutputStream delegate;
    private final long bytesPerSecond;

    private long windowStartMillis;
    private long bytesWrittenInWindow;

    public ThrottledOutputStream(OutputStream delegate, long bytesPerSecond) {
        this.delegate = delegate;
        this.bytesPerSecond = bytesPerSecond;
        this.windowStartMillis = System.currentTimeMillis();
        this.bytesWrittenInWindow = 0;
    }

    @Override
    public void write(int b) throws IOException {
        throttle(1);
        delegate.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int remaining = len;
        int offset = off;

        while (remaining > 0) {
            int available = availableInWindow();
            if (available <= 0) {
                sleepUntilNextWindow();
                continue;
            }

            int toWrite = Math.min(remaining, available);
            delegate.write(b, offset, toWrite);
            bytesWrittenInWindow += toWrite;
            offset += toWrite;
            remaining -= toWrite;
        }
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.flush();
        delegate.close();
    }

    private void throttle(int bytes) throws IOException {
        while (true) {
            int available = availableInWindow();
            if (available >= bytes) {
                bytesWrittenInWindow += bytes;
                return;
            }
            sleepUntilNextWindow();
        }
    }

    private int availableInWindow() {
        long now = System.currentTimeMillis();
        long elapsed = now - windowStartMillis;

        // 窗口已过期，重置
        if (elapsed >= 1000) {
            windowStartMillis = now;
            bytesWrittenInWindow = 0;
            return (int) bytesPerSecond;
        }

        return (int) Math.max(0, bytesPerSecond - bytesWrittenInWindow);
    }

    private void sleepUntilNextWindow() {
        long now = System.currentTimeMillis();
        long sleepMillis = windowStartMillis + 1000 - now;

        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 重置窗口
        windowStartMillis = System.currentTimeMillis();
        bytesWrittenInWindow = 0;
    }
}
