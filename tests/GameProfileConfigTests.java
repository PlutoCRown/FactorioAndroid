package org.factorio.local.launcher;
import java.io.IOException;

public final class GameProfileConfigTests {
    private static int checks;
    private static void check(boolean value,String name) {if(!value)throw new AssertionError(name);checks++;}
    private static void rejectLocale(String text,String name) throws Exception {
        try {GameProfileConfig.readLocale(text);throw new AssertionError(name+" accepted");}
        catch(IOException expected) {checks++;}
    }
    public static void main(String[] args) throws Exception {
        String original="; version=13\n[path]\nread-data=/old/data\nwrite-data=/old/user\n[graphics]\npreferred-video-driver=x11\n[controls]\ncustom=retained\n";
        String mapped=GameProfileConfig.mapPaths(original,"/game/data","/profile/userdata");
        check(mapped.contains("[graphics]\npreferred-video-driver=x11\n[controls]\ncustom=retained\n"),"settings preserved");
        check(!mapped.contains("/old/")&&mapped.contains("read-data=/game/data\nwrite-data=/profile/userdata\n"),"paths updated");
        check(mapped.equals(GameProfileConfig.mapPaths(mapped,"/game/data","/profile/userdata")),"restart idempotence");
        check(GameProfileConfig.mapPaths("; version=13\n[graphics]\nquality=low\n","/data","/user").endsWith("[path]\nread-data=/data\nwrite-data=/user\n"),"missing path section");
        try {GameProfileConfig.mapPaths("; version=14\n[path]\n","/data","/user");throw new AssertionError("unsupported version accepted");}catch(IOException expected) {}
        try {GameProfileConfig.mapPaths("; version=13\n[path]\n[path]\n","/data","/user");throw new AssertionError("duplicate path sections accepted");}catch(IOException expected) {}
        checks+=2; // Existing unsupported-version and duplicate-path checks above.
        String chinese="; version=13\n[general]\nlocale=zh-CN\n[path]\nread-data=/old\n";
        check("zh-CN".equals(GameProfileConfig.readLocale(chinese)),"Chinese locale");
        check("zh-CN".equals(GameProfileConfig.readLocale("; version=13\r\n[general]\r\nlocale = zh-CN\r\n")),"CRLF locale");
        check("zh-CN".equals(GameProfileConfig.readLocale("; version=13\n; locale=en\n[general] ; settings\n; locale=en\n  locale = zh-CN ; 用户选择的中文\n")),"locale comments");
        check(GameProfileConfig.readLocale("; version=13\n[graphics]\nlocale=de\n") == null,"locale outside general ignored");
        check(GameProfileConfig.readLocale("; version=13\n[general]\n; locale=zh-CN\n") == null,"missing/commented locale");
        check("en_GB".equals(GameProfileConfig.readLocale("; version=13\n[general]\nlocale=en_GB\n[graphics]\nlocale=de\n")),"only general locale selected");
        check("zh-CN".equals(GameProfileConfig.readLocale(GameProfileConfig.mapPaths(chinese,"/new/data","/new/user"))),"locale survives path update");
        rejectLocale("; version=13\n[general]\nlocale=zh-CN\nlocale=en\n","duplicate locale");
        rejectLocale("; version=13\n[general]\nlocale=zh-CN\nlocale=zh-CN\n","duplicate same locale");
        rejectLocale("; version=13\n[general]\nlocale=zh-CN\n[general]\nlocale=en\n","duplicate general section");
        String unsupported="; version=14\n[general]\nlocale=zh-CN\n";
        rejectLocale(unsupported,"unknown config version");
        rejectLocale("[general]\nlocale=zh-CN\n","missing config version");
        rejectLocale("; version=13\n[general]\nlocale=zh CN\n","invalid locale whitespace");
        rejectLocale("; version=13\n[general]\nlocale=../zh-CN\n","invalid locale path");
        rejectLocale("; version=13\n[general]\nlocale=\n","empty locale");
        rejectLocale("; version=13\n[general\nlocale=zh-CN\n","malformed section");
        String longest="en-abcdefgh-abcdefgh-abcdefgh-ab";
        check(longest.length()==32&&longest.equals(GameProfileConfig.readLocale("; version=13\n[general]\nlocale="+longest+"\n")),"32 character locale boundary");
        rejectLocale("; version=13\n[general]\nlocale="+longest+"c\n","locale over 32 characters");
        String saved="; version=13\r\n[general]\r\nlocale=zh-CN\r\n[graphics] ; saved\r\n; max-texture-size=0\r\nmax-texture-size=16384\r\ngraphics-quality=high\r\nshow-clouds=false\r\n[controls]\r\nmove-up=W\r\n[path]\r\nread-data=/game/data\r\nwrite-data=/profile/userdata\r\n";
        check(GameProfileConfig.applyGraphicsPreset(saved,"game").equals(saved),"game preset keeps exact original bytes");
        String compat=GameProfileConfig.applyGraphicsPreset(saved,"compat");
        check(compat.contains("graphics-quality=medium\n")&&compat.contains("max-texture-size=4096\n")&&!compat.contains("max-texture-size=16384"),"compat limits texture size without duplicate active value");
        check(compat.contains("gpu-accelerated-compression=false\n")&&compat.contains("gpu-accelerated-mipmap-compression=false\n"),"failed asset compression paths disabled");
        check(compat.contains("texture-compression-level=high-quality\n"),"compat sprites use the direct renderer compressed texture path");
        check(compat.contains("locale=zh-CN\n")&&compat.contains("[controls]\nmove-up=W\n")&&compat.contains("show-clouds=false\n"),"locale controls and unrelated graphics retained");
        check(compat.contains("[path]\nread-data=/game/data\nwrite-data=/profile/userdata\n"),"preset retains game and save paths");
        check(compat.equals(GameProfileConfig.applyGraphicsPreset(compat,"compat")),"preset restart is idempotent");
        check(compat.contains("; max-texture-size=0\n"),"commented defaults retained");
        String balanced=GameProfileConfig.applyGraphicsPreset(compat,"balanced");
        check(balanced.contains("max-texture-size=8192\n")&&balanced.contains("graphics-quality=medium\n"),"balanced switches existing preset");
        String quality=GameProfileConfig.applyGraphicsPreset(balanced,"quality");
        check(quality.contains("graphics-quality=high\n")&&quality.contains("max-texture-size=16384\n"),"16K texture preset applies the requested atlas limit");
        check(quality.contains("texture-compression-level=high-quality\n")&&!quality.contains("texture-compression-level=none\n"),"high-resolution sprites use compression instead of the memory-exhausting uncompressed atlas");
        check(!quality.contains("max-sprite-loading-threads="),"graphics preset leaves loading concurrency independent");
        check(quality.equals(GameProfileConfig.applyGraphicsPreset(quality,"game")),"game settings retain the proven high-quality compressed configuration");
        check(GameProfileConfig.applyGraphicsPreset(quality,"balanced").contains("texture-compression-level=high-quality\n"),"balanced sprites retain memory-saving compression");
        String loading="; version=13\n[graphics]\n; max-sprite-loading-threads=32\nmax-sprite-loading-threads=8\ntexture-compression-level=high-quality\n[controls]\ncustom=retained\n";
        check(GameProfileConfig.applySpriteLoadingLimit(loading,0).equals(loading),"follow game leaves current limit and bytes unchanged");
        String limited=GameProfileConfig.applySpriteLoadingLimit(loading,1);
        check(limited.contains("max-sprite-loading-threads=1\n")&&!limited.contains("\nmax-sprite-loading-threads=8\n"),"explicit limit replaces existing setting");
        check(limited.contains("; max-sprite-loading-threads=32\n")&&limited.contains("texture-compression-level=high-quality\n")&&limited.contains("[controls]\ncustom=retained\n"),"limit preserves comments graphics and controls");
        check(limited.equals(GameProfileConfig.applySpriteLoadingLimit(limited,1)),"loading setting is idempotent");
        check(GameProfileConfig.applySpriteLoadingLimit(GameProfileConfig.applyGraphicsPreset(loading,"game"),4).contains("max-sprite-loading-threads=4\n"),"loading setting independent of game graphics preset");
        check(GameProfileConfig.applySpriteLoadingLimit(chinese,2).contains("[graphics]\nmax-sprite-loading-threads=2\n"),"missing graphics section accepts loading setting");
        for(int bad:new int[]{-1,3,33,Integer.MAX_VALUE}) {
            try {GameProfileConfig.applySpriteLoadingLimit(loading,bad);throw new AssertionError("invalid limit accepted");}catch(IOException expected){checks++;}
        }
        for(String bad:new String[]{"; version=13\n[graphics]\nmax-sprite-loading-threads=1\nmax-sprite-loading-threads=2\n","; version=13\n[graphics]\n[graphics]\n"}) {
            try {GameProfileConfig.applySpriteLoadingLimit(bad,2);throw new AssertionError("ambiguous loading setting accepted");}catch(IOException expected){checks++;}
        }
        String absent=GameProfileConfig.applyGraphicsPreset(chinese,"compat");
        check(absent.contains("[graphics]\n")&&"zh-CN".equals(GameProfileConfig.readLocale(absent)),"new graphics section preserves locale");
        for(String bad:new String[]{"unknown","compat\n[path]"}) {
            try {GameProfileConfig.applyGraphicsPreset(saved,bad);throw new AssertionError("unknown preset accepted");}catch(IOException expected){checks++;}
        }
        for(String bad:new String[]{"; version=13\n[graphics]\n[graphics]\n","; version=13\n[graphics]\nmax-texture-size=4096\nmax-texture-size=8192\n","; version=14\n[graphics]\n"}) {
            try {GameProfileConfig.applyGraphicsPreset(bad,"compat");throw new AssertionError("ambiguous config accepted");}catch(IOException expected){checks++;}
        }
        String fresh=GameProfileConfig.initializeLocale(original,"zh-CN");
        check("zh-CN".equals(GameProfileConfig.readLocale(fresh)),"first game launch seeds Chinese");
        check(fresh.equals(GameProfileConfig.initializeLocale(fresh,"en")),"system change does not overwrite saved language");
        check("en".equals(GameProfileConfig.readLocale(GameProfileConfig.initializeLocale(original,"en"))),"first English launch");
        check(chinese.equals(GameProfileConfig.initializeLocale(chinese,"en")),"existing Chinese exact bytes preserved");
        String auto="; version=13\r\n[general]\r\nlocale=auto\r\n";
        check(auto.equals(GameProfileConfig.initializeLocale(auto,"zh-CN")),"explicit auto preserved");
        String german="; version=13\n[general]\nlocale=de\n";
        check(german.equals(GameProfileConfig.initializeLocale(german,"en")),"other existing game languages preserved");
        String commented="; version=13\r\n[general] ; user preferences\r\n; locale=auto\r\n[graphics]\r\nquality=high\r\n";
        String seeded=GameProfileConfig.initializeLocale(commented,"en");
        check("en".equals(GameProfileConfig.readLocale(seeded))&&seeded.contains("; locale=auto"),"commented default with existing general section");
        try{GameProfileConfig.initializeLocale(original,"en\n[path]");throw new AssertionError("locale injection accepted");}catch(IOException expected){checks++;}
        try{GameProfileConfig.initializeLocale("; version=13\n[general]\n[general]\n","en");throw new AssertionError("duplicate section seeded");}catch(IOException expected){checks++;}
        System.out.println(checks+" persistent profile configuration and locale checks passed");
    }
}
