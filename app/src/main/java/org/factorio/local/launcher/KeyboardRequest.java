package org.factorio.local.launcher;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Only the two SDL keyboard URLs; never accepts general URL opening. */
public final class KeyboardRequest {
    public final boolean show;
    public final int x,y,width,height,mode;
    private KeyboardRequest(boolean show,int x,int y,int width,int height,int mode) {
        this.show=show;this.x=x;this.y=y;this.width=width;this.height=height;this.mode=mode;
    }
    public static KeyboardRequest parse(String value) throws IOException {
        if("steam://close/keyboard".equals(value))return new KeyboardRequest(false,0,0,0,0,0);
        String prefix="steam://open/keyboard?";
        if(value==null||value.length()>384||!value.startsWith(prefix))throw new IOException("Invalid keyboard request");
        Map<String,Integer> fields=new HashMap<>();
        for(String pair:value.substring(prefix.length()).split("&",-1)) {
            String[] parts=pair.split("=",-1);
            if(parts.length!=2||!parts[0].matches("XPosition|YPosition|Width|Height|Mode")||!parts[1].matches("-?[0-9]{1,6}"))
                throw new IOException("Invalid keyboard field");
            if(fields.put(parts[0],Integer.parseInt(parts[1]))!=null)throw new IOException("Repeated keyboard field");
        }
        if(fields.size()!=5)throw new IOException("Incomplete keyboard request");
        int x=fields.get("XPosition"),y=fields.get("YPosition"),w=fields.get("Width"),h=fields.get("Height"),m=fields.get("Mode");
        if(Math.abs(x)>65536||Math.abs(y)>65536||w<0||h<0||w>65536||h>65536||m<0||m>3)throw new IOException("Keyboard field out of range");
        return new KeyboardRequest(true,x,y,w,h,m);
    }
}
