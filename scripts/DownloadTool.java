import java.io.*;
import java.net.*;
import java.nio.file.*;

/** Download only; callers verify pinned size/hash before extracting anything. */
class DownloadTool {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IOException("Usage: DownloadTool.java URL PART_FILE PROXY_OR_EMPTY");
        Path file=Path.of(args[1]);long offset=Files.exists(file)?Files.size(file):0;
        Proxy proxy=Proxy.NO_PROXY;
        if(!args[2].isEmpty()&&!args[2].equals("-")){URI p=URI.create(args[2]);proxy=new Proxy(Proxy.Type.HTTP,new InetSocketAddress(p.getHost(),p.getPort()));}
        URL url=new URL(args[0]);HttpURLConnection c=null;
        for(int redirects=0;redirects<10;redirects++) {
            c=(HttpURLConnection)url.openConnection(proxy);c.setConnectTimeout(30000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);
            if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
            int code=c.getResponseCode();
            if(code==301||code==302||code==303||code==307||code==308){url=new URL(url,c.getHeaderField("Location"));c.disconnect();continue;}
            if(code!=200&&code!=206)throw new IOException("Download HTTP "+code);
            if(code==206&&!c.getHeaderField("Content-Range").startsWith("bytes "+offset+"-"))throw new IOException("Unexpected download range");
            if(code==200)offset=0;
            try(InputStream in=c.getInputStream();OutputStream out=Files.newOutputStream(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,offset>0?StandardOpenOption.APPEND:StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer=new byte[65536];long total=offset,last=System.nanoTime();
                for(int n;(n=in.read(buffer))!=-1;){out.write(buffer,0,n);total+=n;if(System.nanoTime()-last>20000000000L){System.out.println("Downloaded "+total+" bytes");last=System.nanoTime();}}
                System.out.println("Download complete: "+total+" bytes");
            }finally{c.disconnect();}
            return;
        }
        throw new IOException("Too many download redirects");
    }
}
