package org.factorio.local.launcher;

/** Exit codes are evidence, not a diagnosis; in particular 137 is not an OOM verdict. */
final class SessionExit {
    static final int POLICY_VERSION = 2;

    static String classify(
            boolean stopped,
            boolean completed,
            boolean startupFailure,
            boolean started,
            String fatal,
            int code) {
        if (stopped) return "stopped";
        if (startupFailure || !started) return "failed";
        if (completed) return "normal";
        if (fatal != null && !fatal.isEmpty()) return "failed";
        if (code == 0) return "normal";
        // An ended process without an orderly exit is unexpected; its cause is
        // still unknown. A SIGKILL-style status does not diagnose OOM or a crash.
        return code < 0 ? "unknown" : "interrupted";
    }
}
