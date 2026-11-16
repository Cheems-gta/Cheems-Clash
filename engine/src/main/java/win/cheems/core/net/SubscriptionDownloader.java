package win.cheems.core.net;

import java.io.*;
import java.net.*;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.*;

/** HTTPS-only subscription requests. Never exposes a subscription URL in failures. */
public final class SubscriptionDownloader {
  private SubscriptionDownloader() {}
  private static final String[] AGENTS={"ClashMetaForAndroid/2.11.20","clash.meta","ClashForAndroid/2.5.12","Cheems-Clash/0.3.1"};
  public static final class Result {
    public final byte[] data;
    public final String usage;
    Result(byte[] d,String u){data=d;usage=u;}
  }
  interface Factory { HttpURLConnection open(URL url) throws IOException; }
  public static Result download(String link) throws IOException {
    return download(link, url -> (HttpURLConnection)url.openConnection());
  }
  static Result download(String link,Factory factory) throws IOException {
    final URL start;
    try { start=new URL(link.trim()); }
    catch(Exception e){throw new IOException("订阅链接格式错误");}
    https(start);
    int code=0;
    for(String agent:AGENTS) {
      URL url=start;
      for(int redirect=0;redirect<6;redirect++) {
        https(url);
        HttpURLConnection conn;
        try{conn=factory.open(url);}catch(IOException e){throw network(e);}
        try {
          conn.setInstanceFollowRedirects(false);
          conn.setConnectTimeout(15000);conn.setReadTimeout(20000);
          conn.setRequestProperty("User-Agent",agent);
          conn.setRequestProperty("Accept","application/yaml, text/yaml, text/plain, */*");
          conn.setRequestProperty("Accept-Encoding","identity");
          int status;
          try {status=conn.getResponseCode();}catch(IOException e){throw network(e);}
          if(status==301||status==302||status==303||status==307||status==308){
            String location=conn.getHeaderField("Location");
            if(location==null||location.isEmpty())throw new IOException("订阅跳转缺少地址");
            try{url=new URL(url,location);}catch(Exception e){throw new IOException("订阅跳转地址无效");}
            continue;
          }
          if(status==400||status==406||status==422){code=status;break;}
          if(status<200||status>=300)throw new IOException("订阅服务器返回 HTTP "+status);
          try(InputStream raw=conn.getInputStream();
              InputStream input="gzip".equalsIgnoreCase(conn.getContentEncoding())?new GZIPInputStream(raw):raw){
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1){
              if(output.size()+n>Subscription.MAX_BYTES)throw new IOException("订阅内容超过 2 MB");
              output.write(buffer,0,n);
            }
            if(output.size()==0)throw new IOException("订阅返回空内容");
            return new Result(output.toByteArray(),conn.getHeaderField("subscription-userinfo"));
          }catch(IOException e){
            if(e.getMessage()!=null&&e.getMessage().startsWith("订阅"))throw e;
            throw network(e);
          }
        } finally {conn.disconnect();}
      }
      if(code==0)throw new IOException("订阅跳转次数过多");
    }
    throw new IOException("订阅服务器返回 HTTP "+code+"（已尝试兼容请求头）");
  }
  private static void https(URL u)throws IOException{
    if(!"https".equalsIgnoreCase(u.getProtocol()))throw new IOException("订阅及跳转地址必须使用 HTTPS");
    if(u.getUserInfo()!=null)throw new IOException("订阅 URL 不能含账号密码");
  }
  private static IOException network(IOException e) {
    String reason=e instanceof UnknownHostException?"DNS 解析失败":
      e instanceof SocketTimeoutException?"连接或读取超时":
      e instanceof SSLHandshakeException?"HTTPS 证书或握手失败":
      e instanceof SSLException?"HTTPS 连接失败":
      e instanceof EOFException?"连接提前关闭（EOF）":
      e instanceof SocketException?"TCP 连接中断":
      "网络请求失败（"+e.getClass().getSimpleName()+"）";
    return new IOException(reason,e);
  }
}
