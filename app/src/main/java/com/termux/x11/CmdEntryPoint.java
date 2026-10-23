/* SPDX-License-Identifier: GPL-3.0-or-later
 * Minimal binding for the pinned Termux:X11 JNI interface; no Termux app IPC.
 */
package com.termux.x11;

import android.os.ParcelFileDescriptor;
import dalvik.annotation.optimization.CriticalNative;

public final class CmdEntryPoint {
    private final MainActivity owner;

    public CmdEntryPoint(MainActivity owner) {
        this.owner = owner;
    }

    public native boolean start(String[] args);

    public native ParcelFileDescriptor getXConnection();

    public native ParcelFileDescriptor getLogcatOutput();

    public native void reportFatalError(String message);

    @CriticalNative
    private static native boolean connected();

    public void sendBroadcast() {
        owner.serverCallback();
    }

    public void sendBroadcastDelayed() {
        owner.serverCallback();
    }

    public void setDocumentTag(String tag) {
        owner.serverCallback();
    }
}
