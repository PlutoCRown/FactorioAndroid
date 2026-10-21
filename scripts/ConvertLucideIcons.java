import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Reproduce Android vectors from the three retained official Lucide SVGs. */
class ConvertLucideIcons {
    static String attr(Element e,String name){String value=e.getAttribute(name);if(value.isBlank())throw new IllegalArgumentException("Missing "+name);return value;}
    static String num(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    static String point(double x,double y){return num(x)+","+num(y);}
    static double number(Element e,String name){return Double.parseDouble(attr(e,name));}
    static String path(Element e) {
        return switch(e.getLocalName()) {
            case "path" -> attr(e,"d");
            case "line" -> "M"+attr(e,"x1")+","+attr(e,"y1")+" L"+attr(e,"x2")+","+attr(e,"y2");
            case "rect" -> {
                double x=number(e,"x"),y=number(e,"y"),w=number(e,"width"),h=number(e,"height"),rx=number(e,"rx"),ry=number(e,"ry");
                if(rx<0||ry<0||2*rx>w||2*ry>h)throw new IllegalArgumentException("Unsupported rounded rectangle");
                String a=" A"+num(rx)+","+num(ry)+" 0,0 1,";
                yield "M"+point(x+rx,y)+" H"+num(x+w-rx)+a+point(x+w,y+ry)+" V"+num(y+h-ry)+a+point(x+w-rx,y+h)+" H"+num(x+rx)+a+point(x,y+h-ry)+" V"+num(y+ry)+a+point(x+rx,y)+" Z";
            }
            default -> throw new IllegalArgumentException("Unsupported SVG element "+e.getLocalName());
        };
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of("").toAbsolutePath();if(args.length==1)root=Path.of(args[0]).toAbsolutePath();
        var parser=DocumentBuilderFactory.newInstance();parser.setNamespaceAware(true);parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        parser.setFeature("http://xml.org/sax/features/external-general-entities",false);parser.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        var files=new LinkedHashMap<String,String>();files.put("house","ic_nav_home");files.put("sliders-horizontal","ic_nav_settings");files.put("tablet","ic_nav_device");
        for(var item:files.entrySet()) {
            Path source=root.resolve("app/src/main/assets/lucide/"+item.getKey()+".svg");Element svg=parser.newDocumentBuilder().parse(source.toFile()).getDocumentElement();
            if(!"0 0 24 24".equals(svg.getAttribute("viewBox"))||!"none".equals(svg.getAttribute("fill"))||!"2".equals(svg.getAttribute("stroke-width")))throw new IllegalArgumentException("Unexpected SVG style");
            var out=new StringBuilder("<!-- Generated from Lucide "+item.getKey()+".svg; retained ISC/Feather MIT license: assets/lucide/LICENSE. -->\n<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\">\n");
            NodeList children=svg.getChildNodes();for(int i=0;i<children.getLength();i++)if(children.item(i) instanceof Element e) {
                String data=path(e).replace("&","&amp;").replace("\"","&quot;");
                out.append("    <path android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"2\"\n        android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" android:pathData=\"").append(data).append("\" />\n");
            }
            out.append("</vector>\n");Files.writeString(root.resolve("app/src/main/res/drawable/"+item.getValue()+".xml"),out);
            System.out.println(item.getKey()+".svg -> "+item.getValue()+".xml");
        }
    }
}
