import org.factorio.local.launcher.SafeTarReader;
import java.security.MessageDigest;

/** Consume the real archive without extracting files or running game code. */
public final class ArchiveInventory {
    public static void main(String[] args) throws Exception {
        long bytes=0; int files=0, directories=0; String engineHash=null;
        try(SafeTarReader tar=new SafeTarReader(System.in)) {
            byte[] buffer=new byte[65536];
            for(SafeTarReader.Entry entry;(entry=tar.next())!=null;) {
                if(entry.directory) { directories++; continue; }
                files++; bytes+=entry.size;
                MessageDigest digest=entry.name.equals("factorio/bin/arm64/factorio")
                    ?MessageDigest.getInstance("SHA-256"):null;
                for(int n;(n=tar.read(buffer,0,buffer.length))!=-1;)
                    if(digest!=null) digest.update(buffer,0,n);
                if(digest!=null) {
                    StringBuilder hex=new StringBuilder();
                    for(byte b:digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                    engineHash=hex.toString();
                }
            }
            System.out.println("{\"files\":"+files+",\"directories\":"+directories
                +",\"members\":"+(files+directories)+",\"unpackedBytes\":"+bytes
                +",\"tarBytes\":"+tar.bytesRead()+",\"engineSha256\":\""+engineHash+"\"}");
        }
    }
}
