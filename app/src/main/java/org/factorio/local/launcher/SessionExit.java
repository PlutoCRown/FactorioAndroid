package org.factorio.local.launcher;

/** Exit codes are evidence, not a diagnosis; in particular 137 is not an OOM verdict. */
final class SessionExit {
    static String classify(boolean stopped,boolean completed,boolean startupFailure,boolean started,String fatal,int code) {
        if(stopped)return "stopped";
        if(startupFailure||!started)return "failed";
        if(completed)return "normal";
        if(fatal!=null&&!fatal.isEmpty())return "failed";
        return code==0?"normal":"unknown";
    }
}
