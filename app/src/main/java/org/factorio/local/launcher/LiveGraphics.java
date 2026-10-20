package org.factorio.local.launcher;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded evidence from this engine process only; never infer FPS from refresh rate. */
final class LiveGraphics {
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
    private void line(String line) {
        if(line.matches(".*Error CrashHandler\\.cpp:[0-9]+: Received SIG[A-Z0-9]+.*"))
            values.put("fatalLine",line.substring(0,Math.min(line.length(),1024)).trim());
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
