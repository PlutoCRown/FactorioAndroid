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
        check(GameProfileConfig.mapPaths("; version=14\n[path]\n","/data","/user").contains("; version=14\n[path]\nread-data=/data\nwrite-data=/user\n"),"future config version paths mapped without rewriting header");
        try {GameProfileConfig.mapPaths("; version=13\n[path]\n[path]\n","/data","/user");throw new AssertionError("duplicate path sections accepted");}catch(IOException expected) {}
        checks++;
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
        check("zh-CN".equals(GameProfileConfig.readLocale("; version=14\n[general]\nlocale=zh-CN\n")),"future config version locale");
        check("zh-CN".equals(GameProfileConfig.readLocale("[general]\nlocale=zh-CN\n")),"versionless config locale");
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
        check(!compat.contains("gpu-accelerated-compression=")&&!compat.contains("gpu-accelerated-mipmap-compression="),"preset leaves GPU acceleration to game capability detection");
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
        for(String bad:new String[]{"; version=13\n[graphics]\n[graphics]\n","; version=13\n[graphics]\nmax-texture-size=4096\nmax-texture-size=8192\n"}) {
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
        genericConfigurations();
        gpuPolicy();
        defaultTemplate();
        System.out.println(checks+" persistent profile configuration and locale checks passed");
    }

    private static void genericConfigurations() throws Exception {
        for(String header:new String[]{"","; version=14\n","# version=999\n","\ufeff; version=999\n","\ufeff"}) {
            String source=header+"[general] # user language\nlocale = de ; retain chosen language\n"
                +"[path] ; imported paths\n; read-data=__PATH__executable__/../../data\nread-data = /old/data # relocated\nwrite-data=/old/user\n"
                +"[graphics] # future settings\n; graphics-quality=low\nfuture-render-setting=experimental\n"
                +"[controls]\ncustom-key=retained # user binding\n";
            String paths=GameProfileConfig.mapPaths(source,"/new/data","/new/user");
            check("de".equals(GameProfileConfig.readLocale(paths)),"locale accepted with generic header "+header);
            check(paths.startsWith(header+"[general] # user language\n"),"header and section comment retained "+header);
            check(paths.contains("; read-data=__PATH__executable__/../../data\n")&&!paths.contains("/old/"),"commented defaults retained and active paths replaced "+header);
            check(paths.contains("read-data=/new/data\nwrite-data=/new/user\n"),"commented path header recognized "+header);
            check(paths.equals(GameProfileConfig.initializeLocale(paths,"zh-CN")),"generic config retains user locale byte for byte "+header);
            String preset=GameProfileConfig.applyGraphicsPreset(paths,"balanced");
            check(preset.contains("[graphics] # future settings\n; graphics-quality=low\nfuture-render-setting=experimental\n"),"future setting and comments retained "+header);
            check(preset.contains("[controls]\ncustom-key=retained # user binding\n")&&"de".equals(GameProfileConfig.readLocale(preset)),"controls and language survive preset "+header);
            check(preset.contains("max-texture-size=8192\n"),"generic config accepts requested preset "+header);
            String limited=GameProfileConfig.applySpriteLoadingLimit(preset,4);
            check(limited.contains("max-sprite-loading-threads=4\n")&&limited.startsWith(header),"generic config accepts independent loading limit "+header);
            check(limited.equals(GameProfileConfig.mapPaths(limited,"/new/data","/new/user")),"generic paths remain idempotent "+header);
        }
        String bom="\ufeff[general] # no version header\r\n; locale=auto\r\n[graphics]\r\nunknown-setting=true\r\n";
        String seeded=GameProfileConfig.initializeLocale(bom,"zh-CN");
        check(seeded.startsWith("\ufeff[general] # no version header\nlocale=zh-CN\n"),"BOM first section can seed locale without losing header");
        check("zh-CN".equals(GameProfileConfig.readLocale(seeded))&&seeded.contains("; locale=auto\n")&&seeded.contains("unknown-setting=true\n"),"BOM seeding preserves unrelated comments and user settings");
        for(String bad:new String[]{"/data\n[general]","/data\rwrite-data=other"}) {
            try{GameProfileConfig.mapPaths("[path]\n",bad,"/user");throw new AssertionError("read path injection accepted");}catch(IOException expected){checks++;}
            try{GameProfileConfig.mapPaths("[path]\n","/data",bad);throw new AssertionError("write path injection accepted");}catch(IOException expected){checks++;}
        }
    }

    private static void gpuPolicy() throws Exception {
        String explicit="\ufeff# version=999\n[graphics] # saved game choices\n"
            +"gpu-accelerated-compression=true # game capability result\n"
            +"gpu-accelerated-mipmap-compression=false ; user choice\nfuture-option=keep\n[general]\nlocale=fr\n";
        for(String preset:new String[]{"game","compat","balanced","quality"}) {
            String result=GameProfileConfig.applyGraphicsPreset(explicit,preset);
            check(result.contains("gpu-accelerated-compression=true # game capability result\n")
                &&result.contains("gpu-accelerated-mipmap-compression=false ; user choice\n"),"preset preserves explicit game GPU choices: "+preset);
            check(result.contains("future-option=keep\n")&&"fr".equals(GameProfileConfig.readLocale(result)),"preset preserves future option and locale: "+preset);
            String defaults=GameProfileConfig.applyGraphicsPreset("[graphics]\n",preset);
            check(!defaults.contains("gpu-accelerated-"),"preset does not impose either GPU acceleration setting: "+preset);
        }
        String legacy="; version=14\n[graphics] # former launcher policy\n"
            +"; gpu-accelerated-compression=false\ngpu-accelerated-compression = false # old override\n"
            +"gpu-accelerated-mipmap-compression=false ; old override\nfuture-option=keep\n"
            +"[general]\nlocale=de\ngpu-accelerated-compression=false\n";
        String released=GameProfileConfig.releaseLegacyGpuOverrides(legacy);
        check(released.contains("; gpu-accelerated-compression=false\n")&&!released.contains("# old override")&&!released.contains("; old override"),"policy migration removes only active former graphics overrides");
        check(released.contains("future-option=keep\n")&&released.contains("[general]\nlocale=de\ngpu-accelerated-compression=false\n"),"policy migration preserves unrelated settings and other sections");
        check(GameProfileConfig.releaseLegacyGpuOverrides(explicit).contains("gpu-accelerated-compression=true # game capability result\n"),"policy migration retains a true capability result");
    }

    private static void defaultTemplate() throws Exception {
        java.nio.file.Path asset=java.nio.file.Path.of("src/main/assets/config/default-config.ini");
        if(!java.nio.file.Files.isRegularFile(asset))asset=java.nio.file.Path.of("app/src/main/assets/config/default-config.ini");
        String template=new String(java.nio.file.Files.readAllBytes(asset),java.nio.charset.StandardCharsets.UTF_8);
        check(template.equals(GameProfileConfig.ensureFormatHeader(template,template)),"bundled game-written format header is retained");
        check(template.contains("[controls]\n")&&template.contains("[sound]\n")&&template.contains("[graphics]\n")
            &&template.contains("; max-sprite-loading-threads=32\n"),"complete game sections and documented defaults are present");
        for(String line:template.split("\n")) {
            String text=line.trim();
            if(!text.isEmpty()&&!text.startsWith(";")&&!text.startsWith("#")&&!text.startsWith("["))
                throw new AssertionError("Bundled template contains a recorded user's preference: "+text);
        }
        check(!template.contains("/data/data/")&&!template.contains("/data/user/")&&!template.contains("default-2.1.22"),"template contains no recorded device paths");
        String header=template.substring(0,template.indexOf('\n'));
        for(String preset:new String[]{"game","compat","balanced","quality"}) {
            String fresh=GameProfileConfig.mapPaths(template,"/import/data","/shared/userdata");
            fresh=GameProfileConfig.initializeLocale(fresh,"en");
            fresh=GameProfileConfig.applyGraphicsPreset(fresh,preset);
            fresh=GameProfileConfig.applySpriteLoadingLimit(fresh,1);
            check(fresh.startsWith(header+"\n")&&fresh.contains("; Semicolons")&&fresh.contains("[controls]\n"),"first launch retains native format and other sections: "+preset);
            check(fresh.contains("read-data=/import/data\nwrite-data=/shared/userdata\n")&&"en".equals(GameProfileConfig.readLocale(fresh))
                &&fresh.contains("\nmax-sprite-loading-threads=1\n"),"first-launch paths language and concurrency applied: "+preset);
            check(!fresh.contains("\ngpu-accelerated-compression=false\n")&&!fresh.contains("\nshow-fps=both\n")
                &&!fresh.contains("\ncustom-ui-scale=2\n"),"first launch does not inherit test preferences: "+preset);
        }
        String existing="[path]\nread-data=/old\n[general]\nlocale=de\n[controls]\ncustom=keep\n";
        String repaired=GameProfileConfig.ensureFormatHeader(existing,template);
        check(repaired.equals(header+"\n"+existing),"versionless profile gains only native metadata; all preferences remain");
        check(repaired.equals(GameProfileConfig.ensureFormatHeader(repaired,template)),"metadata repair is idempotent");
        check(GameProfileConfig.ensureFormatHeader(existing,"; version=77\n[path]\n").startsWith("; version=77\n"),"repair takes format metadata from template rather than a fixed version");
        for(String valid:new String[]{"; version=14\r\n[graphics]\r\nfuture=keep\r\n","\ufeff; version=999\n[path]\n","; legacy\nconfig-version=12\n[path]\n"})
            check(valid.equals(GameProfileConfig.ensureFormatHeader(valid,template)),"existing and legacy format versions are not replaced");
        check(GameProfileConfig.ensureFormatHeader("\ufeff"+existing,template).equals("\ufeff"+header+"\n"+existing),"BOM remains at the start during repair");
        String misplaced="; ordinary comment\n; version=13\n"+existing;
        check(GameProfileConfig.ensureFormatHeader(misplaced,template).equals(header+"\n"+misplaced),"metadata must precede the first unrelated comment, as required by the engine");
    }
}
