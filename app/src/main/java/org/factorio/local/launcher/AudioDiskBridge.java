package org.factorio.local.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Candidate only: original SDL disk PCM -> private FIFO -> Android AudioTrack. Call create on a
 * session worker before starting the engine. Feed the live engine output to acceptEngineOutput;
 * close on every engine exit/cancellation. Never attach this to a mode that retains
 * --disable-audio. No raw PCM is stored.
 */
public final class AudioDiskBridge implements AutoCloseable {
    private final File fifo;
    private final FileInputStream input;
    private final FileDescriptor readFd, guardFd;
    private final CountDownLatch formatReady = new CountDownLatch(1);
    private final Pattern formatLine;
    private final Thread worker;
    private final StringBuilder outputTail = new StringBuilder();
    private volatile boolean running = true,
            workerFinished,
            formatNegotiated,
            playing,
            backgroundPaused;
    private volatile AudioTrack track;
    private volatile String status = "waiting-for-engine-format", error, format;
    private volatile int channels, frequency, frameBytes, encoding, bufferBytes, underruns;
    private volatile long bytesRead, bytesWritten, writeCalls, partialWrites, zeroWrites;
    private volatile long writeNanos, maxWriteNanos, longWrites, readWakeups, playedFrames;
    private volatile long startedMillis, firstPcmMillis, lastPcmMillis;
    private long headWraps, lastHead;
    private static final int READ_BYTES = 8192;

    /** Own unique cache path, no existing file deletion or external storage. */
    public static AudioDiskBridge create(Context context) throws Exception {
        File dir = new File(context.getCacheDir(), "audio");
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create private audio directory");
        File fifo = new File(dir, "pcm-" + UUID.randomUUID() + ".fifo");
        Os.mkfifo(fifo.getAbsolutePath(), 0600);
        FileDescriptor guard = null, read = null;
        try {
            // The private nonblocking guard prevents pre-writer EOF/HUP spinning,
            // and lets SDL fopen complete before its format log becomes available.
            guard =
                    Os.open(
                            fifo.getAbsolutePath(),
                            OsConstants.O_RDWR | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC,
                            0);
            read =
                    Os.open(
                            fifo.getAbsolutePath(),
                            OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC,
                            0);
            return new AudioDiskBridge(fifo, guard, read);
        } catch (Exception failure) {
            if (read != null)
                try {
                    Os.close(read);
                } catch (Exception ignored) {
                }
            if (guard != null)
                try {
                    Os.close(guard);
                } catch (Exception ignored) {
                }
            fifo.delete();
            throw failure;
        }
    }

    private AudioDiskBridge(File fifo, FileDescriptor guard, FileDescriptor read) {
        this.fifo = fifo;
        guardFd = guard;
        readFd = read;
        input = new FileInputStream(read);
        formatLine =
                Pattern.compile(
                        "Writing to file \\["
                                + Pattern.quote(fifo.getAbsolutePath())
                                + "\\], format=([A-Z0-9]+) channels=([0-9]{1,3}) freq=([0-9]{1,6})\\.");
        worker = new Thread(this::pump, "factorio-private-pcm");
        worker.setDaemon(true);
        worker.start();
    }

    public void setBackgroundPaused(boolean paused) {
        backgroundPaused = paused;
    }

    public String fifoPath() {
        return fifo.getAbsolutePath();
    }

    /** Merge ONLY into the actual audio-enabled engine ProcessBuilder environment. */
    public Map<String, String> environment() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("SDL_AUDIO_DRIVER", "disk");
        values.put("SDL_AUDIO_DISK_OUTPUT_FILE", fifo.getAbsolutePath());
        // Remove SDL's additional sleep: the finite FIFO and blocking AudioTrack
        // writes follow the hardware clock. 1.0 would add a second pacing source.
        values.put("SDL_AUDIO_DISK_TIMESCALE", "0");
        return values;
    }

    /** Streaming chunks are accepted; format must describe this exact own FIFO. */
    public synchronized void acceptEngineOutput(String chunk) {
        if (!running || chunk == null || chunk.isEmpty()) return;
        outputTail.append(chunk);
        Matcher found = formatLine.matcher(outputTail);
        while (found.find() && running) {
            String next = found.group(1);
            int count = Integer.parseInt(found.group(2)), rate = Integer.parseInt(found.group(3));
            if (formatNegotiated) {
                if (!next.equals(format) || count != channels || rate != frequency)
                    fail("Engine changed PCM format during one FIFO session");
            } else {
                try {
                    int code, size;
                    if ("F32LE".equals(next) || "F32".equals(next)) {
                        code = AudioFormat.ENCODING_PCM_FLOAT;
                        size = 4;
                    } else if ("S16LE".equals(next) || "S16".equals(next)) {
                        code = AudioFormat.ENCODING_PCM_16BIT;
                        size = 2;
                    } else if (("S32LE".equals(next) || "S32".equals(next))
                            && Build.VERSION.SDK_INT >= 31) {
                        code = AudioFormat.ENCODING_PCM_32BIT;
                        size = 4;
                    } else if ("U8".equals(next)) {
                        code = AudioFormat.ENCODING_PCM_8BIT;
                        size = 1;
                    } else throw new IOException("Unsupported exact SDL PCM format: " + next);
                    if (count != 1 && count != 2)
                        throw new IOException("Unverified PCM channel map: " + count);
                    if (rate < 8000 || rate > 192000)
                        throw new IOException("Unexpected PCM rate: " + rate);
                    if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN)
                        throw new IOException("Relay requires little-endian Android");
                    format = next;
                    channels = count;
                    frequency = rate;
                    encoding = code;
                    frameBytes = size * count;
                    formatNegotiated = true;
                    formatReady.countDown();
                } catch (Exception invalid) {
                    fail(invalid.toString());
                }
            }
        }
        // Keep a bounded suffix solely for split-line parsing; not a game log.
        if (outputTail.length() > 4096) outputTail.delete(0, outputTail.length() - 2048);
    }

    private void fail(String message) {
        error = message;
        status = "failed";
        close();
    }

    private void updateHead(AudioTrack current) {
        long now = Integer.toUnsignedLong(current.getPlaybackHeadPosition());
        if (now < lastHead) headWraps += 1L << 32;
        lastHead = now;
        playedFrames = headWraps + now;
        underruns = current.getUnderrunCount();
    }

    private void pump() {
        AudioTrack current = null;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            int formatWait = 0;
            while (running && !formatReady.await(1, TimeUnit.SECONDS)) {
                if (!backgroundPaused && ++formatWait >= 60)
                    throw new IOException(
                            "No verified SDL disk PCM descriptor within 60 foreground seconds");
            }
            if (!running) return;
            int mask =
                    channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minimum = AudioTrack.getMinBufferSize(frequency, mask, encoding);
            if (minimum <= 0)
                throw new IOException("AudioTrack rejects negotiated PCM format: " + minimum);
            bufferBytes = Math.max(minimum, frequency * frameBytes / 25);
            bufferBytes = ((bufferBytes + frameBytes - 1) / frameBytes) * frameBytes;
            if (bufferBytes > 2 * 1024 * 1024)
                throw new IOException("Unexpected audio buffer size");
            current =
                    new AudioTrack.Builder()
                            .setAudioAttributes(
                                    new AudioAttributes.Builder()
                                            .setUsage(AudioAttributes.USAGE_GAME)
                                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                            .build())
                            .setAudioFormat(
                                    new AudioFormat.Builder()
                                            .setEncoding(encoding)
                                            .setSampleRate(frequency)
                                            .setChannelMask(mask)
                                            .build())
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(bufferBytes)
                            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            .build();
            track = current;
            if (!running) return;
            if (current.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IOException("AudioTrack initialization failed");
            byte[] bytes = new byte[READ_BYTES + 16];
            int carry = 0;
            ByteBuffer pcm =
                    ByteBuffer.allocateDirect(READ_BYTES + 16).order(ByteOrder.LITTLE_ENDIAN);
            StructPollfd poll = new StructPollfd();
            poll.fd = readFd;
            poll.events = (short) OsConstants.POLLIN;
            startedMillis = SystemClock.elapsedRealtime();
            status = "streaming";
            while (running) {
                if (backgroundPaused) {
                    if (playing) {
                        current.pause();
                        playing = false;
                        status = "background-paused";
                    }
                    Thread.sleep(20);
                    continue;
                }
                if (!playing) {
                    current.play();
                    playing = true;
                    status = "streaming";
                }

                int ready = Os.poll(new StructPollfd[] {poll}, 250);
                if (!running) break;
                if (ready == 0) continue;
                if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0)
                    throw new IOException("Private PCM FIFO poll error");
                if ((poll.revents & OsConstants.POLLIN) == 0)
                    throw new IOException("Unexpected private PCM FIFO hangup");
                readWakeups++;
                int count = input.read(bytes, carry, READ_BYTES - carry);
                if (count < 0) throw new IOException("Unexpected PCM FIFO EOF");
                if (count == 0) throw new IOException("Unexpected zero-byte PCM FIFO read");
                bytesRead += count;
                long when = SystemClock.elapsedRealtime();
                lastPcmMillis = when;
                if (firstPcmMillis == 0) firstPcmMillis = when;
                int available = carry + count, aligned = available - available % frameBytes;
                pcm.clear();
                pcm.put(bytes, 0, aligned);
                pcm.flip();
                while (running && pcm.hasRemaining()) {
                    // Start with actual PCM ready; never play a fabricated format.
                    if (!playing) {
                        current.play();
                        playing = true;
                    }
                    int requested = pcm.remaining();
                    long before = SystemClock.elapsedRealtimeNanos();
                    int sent = current.write(pcm, requested, AudioTrack.WRITE_BLOCKING);
                    long elapsed = SystemClock.elapsedRealtimeNanos() - before;
                    writeCalls++;
                    writeNanos += elapsed;
                    maxWriteNanos = Math.max(maxWriteNanos, elapsed);
                    if (elapsed >= 10000000L) longWrites++;
                    if (!running) break;
                    if (sent < 0) throw new IOException("AudioTrack.write failed: " + sent);
                    if (sent == 0) {
                        zeroWrites++;
                        throw new IOException("AudioTrack blocking write made no progress");
                    }
                    if (sent % frameBytes != 0)
                        throw new IOException("AudioTrack returned a partial PCM frame");
                    if (sent < requested) partialWrites++;
                    bytesWritten += sent;
                    updateHead(current);
                }
                carry = available - aligned;
                if (carry > 0) System.arraycopy(bytes, aligned, bytes, 0, carry);
            }
        } catch (InterruptedException interrupted) {
            if (running) {
                error = interrupted.toString();
                status = "failed";
            }
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (running) {
                error = failure.toString();
                status = "failed";
            }
        } finally {
            running = false;
            playing = false;
            if (current != null) {
                try {
                    updateHead(current);
                } catch (Exception ignored) {
                }
                try {
                    current.pause();
                } catch (Exception ignored) {
                }
                try {
                    current.flush();
                } catch (Exception ignored) {
                }
                try {
                    current.release();
                } catch (Exception ignored) {
                }
            }
            track = null;
            try {
                input.close();
            } catch (Exception ignored) {
            }
            try {
                Os.close(guardFd);
            } catch (Exception ignored) {
            }
            fifo.delete();
            workerFinished = true;
            if (error == null) status = "closed";
        }
    }

    public JSONObject report() throws Exception {
        long frames = frameBytes == 0 ? 0 : bytesWritten / frameBytes;
        return new JSONObject()
                .put("candidate", "SDL disk FIFO to AudioTrack")
                .put("status", status)
                .put("formatNegotiated", formatNegotiated)
                .put("format", format)
                .put("channels", channels)
                .put("frequency", frequency)
                .put("audioTrackBufferBytes", bufferBytes)
                .put("pcmBytesRead", bytesRead)
                .put("pcmBytesWritten", bytesWritten)
                .put("framesEnqueued", frames)
                .put("playbackHeadFrames", playedFrames)
                .put("estimatedQueuedFrames", Math.max(0, frames - playedFrames))
                .put("underruns", underruns)
                .put("writeCalls", writeCalls)
                .put("partialWrites", partialWrites)
                .put("zeroWrites", zeroWrites)
                .put("writeTimeMillis", writeNanos / 1000000.0)
                .put("maxWriteMillis", maxWriteNanos / 1000000.0)
                .put("writesAtLeast10ms", longWrites)
                .put("readWakeups", readWakeups)
                .put("startedElapsedMillis", startedMillis)
                .put("firstPcmElapsedMillis", firstPcmMillis)
                .put("lastPcmElapsedMillis", lastPcmMillis)
                .put("workerFinished", workerFinished)
                .put("error", error)
                .put("backgroundPaused", backgroundPaused)
                .put("audibleOutputReviewed", false)
                .put("rawPcmStored", false)
                .put("clockMode", "AudioTrack backpressure; SDL disk timescale 0");
    }

    /** Session worker only, after close; lets private FIFO cleanup finish before process exit. */
    public boolean awaitClosed() throws InterruptedException {
        if (Thread.currentThread() != worker) worker.join(2000);
        return workerFinished;
    }

    /** Never join/read/stop/release AudioTrack on the caller (possibly UI) thread. */
    @Override
    public void close() {
        running = false;
        formatReady.countDown();
        worker.interrupt();
        AudioTrack current = track;
        if (current != null) {
            Thread unblock =
                    new Thread(
                            () -> {
                                try {
                                    current.pause();
                                } catch (Exception ignored) {
                                }
                            },
                            "factorio-pcm-unblock");
            unblock.setDaemon(true);
            unblock.start();
        }
    }
}
