package org.factorio.local.launcher;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded evidence from this engine process only; never infer FPS from refresh rate. */
final class LiveGraphics {
    private static final java.util.regex.Pattern FATAL=java.util.regex.Pattern.compile(".*Error CrashHandler\\.cpp:[0-9]+: Received (SIG[A-Z0-9]+).*");
    private static final String LOG_PREFIX="\\s*(?:[0-9]+(?:\\.[0-9]+)?\\s+)?(?:Info [^\\r\\n]+?:[0-9]+:\\s+)?";
    private static final java.util.regex.Pattern USER_QUIT=java.util.regex.Pattern.compile(LOG_PREFIX+"Quitting: user-quit\\.?\\s*");
    private static final java.util.regex.Pattern GOODBYE=java.util.regex.Pattern.compile(LOG_PREFIX+"Goodbye\\s*");
    private final StringBuilder pending=new StringBuilder();
    private final Map<String,String> values=new LinkedHashMap<>();
    private boolean oversizedLine;
    synchronized void accept(String chunk) {
        for(int i=0;i<chunk.length();i++) {
            char c=chunk.charAt(i);
            if(c=='\n') {
                if(!oversizedLine)line(pending.toString());
                pending.setLength(0);oversizedLine=false;
            } else if(!oversizedLine) {
                if(pending.length()>=8192) {pending.setLength(0);oversizedLine=true;}
                else pending.append(c);
            }
        }
    }
    synchronized void finish(){if(!oversizedLine&&pending.length()>0)line(pending.toString());pending.setLength(0);oversizedLine=false;}
    private void line(String line) {
        // Goodbye is logged inside GlobalContext destruction, before all native
        // cleanup completes. Confirm the user's exit, not a clean process teardown.
        if(!values.containsKey("fatalSignal")) {
            if(USER_QUIT.matcher(line).matches())values.put("userQuitRequested","true");
            if(GOODBYE.matcher(line).matches()&&values.containsKey("userQuitRequested")) {
                values.put("normalExitCompleted","true");values.put("userExitConfirmed","true");
            }
        }
        java.util.regex.Matcher fatal=FATAL.matcher(line);
        if(fatal.matches()&&!values.containsKey("fatalSignal")) {
            values.put("fatalLine",line.substring(0,Math.min(line.length(),1024)).trim());
            values.put("fatalSignal",fatal.group(1));
            values.put("faultPhase",values.containsKey("userExitConfirmed")?"shutdown":"runtime");
        }
        String[] markers={"Initialised OpenGL:","Graphics options:","Texture processor created","Factorio initialised"};
        String[] keys={"rendererLine","graphicsOptions","textureProcessor","initialized"};
        for(int i=0;i<markers.length;i++) {
            int at=line.indexOf(markers[i]);
            if(at<0)continue;
            if(i==3)values.put(keys[i],"true");
            else values.put(keys[i],line.substring(at,Math.min(line.length(),at+1024)).trim());
        }
    }
    synchronized Map<String,String> snapshot() {return new LinkedHashMap<>(values);}
}
