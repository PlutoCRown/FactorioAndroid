package org.factorio.local.launcher;

import android.content.Context;
import android.hardware.input.InputManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.*;
import android.view.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.factorio.local.launcher.input.GamepadEventQueue;
import org.factorio.local.launcher.input.VirtualGamepadState;
import org.json.*;

/** Android gamepad state -> same-UID private socket -> engine's native SDL gamepad. */
public final class ControllerRelay implements InputManager.InputDeviceListener, AutoCloseable {
    private final Context context;
    private final InputManager manager;
    private final boolean synthetic;
    private final Object lock = new Object();
    private final Thread worker;
    private volatile boolean running = true, connected;
    private volatile LocalSocket socket;
    private volatile String socketPath, lastError;
    private int deviceId = -1,
            keyButtons,
            hatButtons,
            packets,
            gamepadKeyEvents,
            axisEvents,
            instance;
    private String deviceName;
    private short[] axes = neutralAxes();
    private boolean recognized, everRecognized;
    private int recognizedPackets, lastRecognizedInstance;
    private long generation;
    private final VirtualGamepadState virtual = new VirtualGamepadState();
    private final GamepadEventQueue virtualEvents = new GamepadEventQueue();
    private int virtualEventsPublished, virtualQueueOverflows;

    public void setVirtualEnabled(boolean enabled) {
        synchronized (lock) {
            virtual.enabled(enabled);
            virtualEvents.clear();
            generation++;
            lock.notifyAll();
        }
    }

    private void publishVirtual(VirtualGamepadState.Snapshot before) {
        VirtualGamepadState.Snapshot after = virtual.snapshot();
        if (after.sameState(before)) return;
        if (!virtualEvents.offer(after)) {
            virtual.releaseAll();
            virtualEvents.offer(virtual.snapshot());
            virtualQueueOverflows++;
        }
        virtualEventsPublished++;
        generation++;
        lock.notifyAll();
    }

    public void virtualButton(String owner, int button, boolean down) {
        synchronized (lock) {
            VirtualGamepadState.Snapshot before = virtual.snapshot();
            virtual.button(owner, button, down);
            publishVirtual(before);
        }
    }

    public void virtualTrigger(String owner, int trigger, boolean down) {
        synchronized (lock) {
            VirtualGamepadState.Snapshot before = virtual.snapshot();
            virtual.trigger(owner, trigger, down);
            publishVirtual(before);
        }
    }

    public void virtualStick(int stick, float x, float y) {
        synchronized (lock) {
            VirtualGamepadState.Snapshot before = virtual.snapshot();
            virtual.stick(stick, x, y);
            publishVirtual(before);
        }
    }

    public void releaseVirtual() {
        synchronized (lock) {
            virtual.releaseAll();
            virtualEvents.clear();
            generation++;
            lock.notifyAll();
        }
    }

    private static short[] neutralAxes() {
        return new short[] {0, 0, 0, 0, Short.MIN_VALUE, Short.MIN_VALUE};
    }

    private static boolean gamepad(InputDevice device) {
        if (device == null || device.isVirtual()) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    public static JSONArray inventory() throws Exception {
        JSONArray devices = new JSONArray();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (gamepad(d))
                devices.put(
                        new JSONObject()
                                .put("id", id)
                                .put("name", d.getName())
                                .put("sources", d.getSources()));
        }
        return devices;
    }

    public ControllerRelay(Context context, boolean synthetic) {
        this.context = context.getApplicationContext();
        this.synthetic = synthetic;
        manager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        manager.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));
        refresh();
        worker = new Thread(this::run, "android-gamepad-relay");
        worker.setDaemon(true);
        worker.start();
    }

    private void refresh() {
        int found = -1;
        String name = null;
        int[] ids = InputDevice.getDeviceIds();
        Arrays.sort(ids);
        for (int id : ids) {
            InputDevice d = InputDevice.getDevice(id);
            if (gamepad(d)) {
                found = id;
                name = d.getName();
                break;
            }
        }
        synchronized (lock) {
            if (found != deviceId) {
                deviceId = found;
                deviceName = name;
                keyButtons = hatButtons = 0;
                axes = neutralAxes();
                generation++;
                lock.notifyAll();
            }
        }
    }

    @Override
    public void onInputDeviceAdded(int id) {
        refresh();
    }

    @Override
    public void onInputDeviceRemoved(int id) {
        refresh();
    }

    @Override
    public void onInputDeviceChanged(int id) {
        refresh();
    }

    public void connect(String path) throws IOException {
        String prefix = new File(context.getCacheDir(), "x11/pad-").getAbsolutePath();
        if (path == null
                || !path.startsWith(prefix)
                || !path.endsWith(".sock")
                || path.length() > 100) throw new IOException("Unexpected controller socket path");
        synchronized (lock) {
            socketPath = path;
            generation++;
            lock.notifyAll();
        }
    }

    private static int button(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return 0;
            case KeyEvent.KEYCODE_BUTTON_B:
                return 1;
            case KeyEvent.KEYCODE_BUTTON_X:
                return 2;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return 3;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:
                return 4;
            case KeyEvent.KEYCODE_BUTTON_MODE:
                return 5;
            case KeyEvent.KEYCODE_BUTTON_START:
                return 6;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return 7;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return 8;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return 9;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return 10;
            case KeyEvent.KEYCODE_DPAD_UP:
                return 11;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 12;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return 13;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 14;
            default:
                return -1;
        }
    }

    public boolean key(KeyEvent event) {
        synchronized (lock) {
            if (deviceId < 0 || event.getDeviceId() != deviceId) return false;
            int which = button(event.getKeyCode()), action = event.getAction();
            if (which < 0 || (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP))
                return false;
            int updated =
                    action == KeyEvent.ACTION_DOWN
                            ? keyButtons | (1 << which)
                            : keyButtons & ~(1 << which);
            if (updated != keyButtons) {
                keyButtons = updated;
                gamepadKeyEvents++;
                generation++;
                lock.notifyAll();
            }
            return true;
        }
    }

    private static int availableAxis(InputDevice d, int primary, int fallback) {
        return d.getMotionRange(primary, InputDevice.SOURCE_JOYSTICK) != null ? primary : fallback;
    }

    private static short stick(MotionEvent event, InputDevice d, int axis) {
        InputDevice.MotionRange range = d.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
        if (range == null || range.getRange() <= 0) return 0;
        float half = range.getRange() / 2f,
                value = (event.getAxisValue(axis) - (range.getMax() + range.getMin()) / 2f) / half;
        if (Math.abs(value) <= range.getFlat() / half) value = 0;
        value = Math.max(-1f, Math.min(1f, value));
        return (short) Math.round(value < 0 ? value * 32768f : value * 32767f);
    }

    private static short trigger(MotionEvent event, InputDevice d, int axis) {
        InputDevice.MotionRange range = d.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
        if (range == null || range.getRange() <= 0) return Short.MIN_VALUE;
        float value = (event.getAxisValue(axis) - range.getMin()) / range.getRange();
        if (value <= range.getFlat() / range.getRange()) value = 0;
        value = Math.max(0f, Math.min(1f, value));
        return (short) Math.round(-32768f + value * 65535f);
    }

    public boolean motion(MotionEvent event) {
        synchronized (lock) {
            if (deviceId < 0
                    || event.getDeviceId() != deviceId
                    || event.getActionMasked() != MotionEvent.ACTION_MOVE
                    || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false;
            InputDevice d = event.getDevice();
            if (!gamepad(d)) return false;
            short[] next = {
                stick(event, d, MotionEvent.AXIS_X),
                stick(event, d, MotionEvent.AXIS_Y),
                stick(event, d, availableAxis(d, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)),
                stick(event, d, availableAxis(d, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)),
                trigger(
                        event,
                        d,
                        availableAxis(d, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)),
                trigger(event, d, availableAxis(d, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS))
            };
            int hat = 0;
            float x = event.getAxisValue(MotionEvent.AXIS_HAT_X),
                    y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (x < -.5f) hat |= 1 << 13;
            if (x > .5f) hat |= 1 << 14;
            if (y < -.5f) hat |= 1 << 11;
            if (y > .5f) hat |= 1 << 12;
            if (hat != hatButtons || !Arrays.equals(next, axes)) {
                hatButtons = hat;
                axes = next;
                axisEvents++;
                generation++;
                lock.notifyAll();
            }
            return true;
        }
    }

    public void focusChanged(boolean focused) {
        if (!focused)
            synchronized (lock) {
                keyButtons = hatButtons = 0;
                axes = neutralAxes();
                virtual.releaseAll();
                virtualEvents.clear();
                generation++;
                lock.notifyAll();
            }
    }

    private void run() {
        int sequence = 0;
        while (running) {
            String path = socketPath;
            if (path == null) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            LocalSocket connection = new LocalSocket();
            socket = connection;
            try {
                connection.connect(
                        new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
                connection.setSoTimeout(35000);
                connected = true;
                lastError = null;
                InputStream in = connection.getInputStream();
                OutputStream out = connection.getOutputStream();
                long sentGeneration = -1;
                while (running) {
                    ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                    synchronized (lock) {
                        if (sentGeneration == generation && virtualEvents.isEmpty())
                            lock.wait(1000);
                        if (!running) break;
                        VirtualGamepadState.Snapshot touch = virtualEvents.next(virtual.snapshot());
                        VirtualGamepadState.Snapshot merged =
                                VirtualGamepadState.merge(touch, keyButtons | hatButtons, axes);
                        buffer.put(new byte[] {'R', 'G', 'P', '1'})
                                .putInt(1)
                                .putInt(deviceId >= 0 || synthetic || touch.enabled ? 1 : 0)
                                .putInt(merged.buttons);
                        for (short axis : merged.axes()) buffer.putShort(axis);
                        buffer.putInt(++sequence);
                        sentGeneration = generation;
                    }
                    out.write(buffer.array());
                    out.flush();
                    byte[] response = new byte[16];
                    int offset = 0;
                    while (offset < response.length) {
                        int got = in.read(response, offset, response.length - offset);
                        if (got < 0) throw new EOFException("Controller bridge closed");
                        offset += got;
                    }
                    ByteBuffer ack = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
                    if (ack.get() != 'R'
                            || ack.get() != 'G'
                            || ack.get() != 'A'
                            || ack.get() != '1')
                        throw new IOException("Unexpected controller reply");
                    int id = ack.getInt(), known = ack.getInt(), serial = ack.getInt();
                    if (serial != sequence)
                        throw new IOException("Controller reply sequence differs");
                    synchronized (lock) {
                        instance = id;
                        recognized = known == 1;
                        packets++;
                        if (recognized) {
                            everRecognized = true;
                            recognizedPackets++;
                            lastRecognizedInstance = id;
                        }
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                if (running) lastError = e.toString();
            } finally {
                connected = false;
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
                synchronized (lock) {
                    recognized = false;
                    instance = 0;
                    virtual.releaseAll();
                    virtualEvents.clear();
                }
            }
            if (running)
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
        }
    }

    public JSONObject report() throws Exception {
        synchronized (lock) {
            return new JSONObject()
                    .put("syntheticControllerProbe", synthetic)
                    .put("virtualControllerEnabled", virtual.snapshot().enabled)
                    .put("virtualButtonsHeld", virtual.snapshot().buttons)
                    .put("virtualEventsPublished", virtualEventsPublished)
                    .put("virtualQueueOverflows", virtualQueueOverflows)
                    .put("physicalControllerId", deviceId)
                    .put(
                            "physicalControllerName",
                            deviceName == null ? JSONObject.NULL : deviceName)
                    .put("availableControllers", inventory())
                    .put("socketConnected", connected)
                    .put("nativeSdlInstance", instance)
                    .put("nativeGamepadRecognized", recognized)
                    .put("nativeGamepadEverRecognized", everRecognized)
                    .put("recognizedAcknowledgements", recognizedPackets)
                    .put("lastRecognizedInstance", lastRecognizedInstance)
                    .put("packetsAcknowledged", packets)
                    .put("physicalGamepadKeyEvents", gamepadKeyEvents)
                    .put("physicalAxisEvents", axisEvents)
                    .put("lastError", lastError == null ? JSONObject.NULL : lastError)
                    .put("physicalControllerBehaviorReviewed", false);
        }
    }

    @Override
    public void close() {
        running = false;
        manager.unregisterInputDeviceListener(this);
        synchronized (lock) {
            lock.notifyAll();
        }
        LocalSocket active = socket;
        if (active != null)
            try {
                active.close();
            } catch (IOException ignored) {
            }
        worker.interrupt();
        try {
            worker.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
