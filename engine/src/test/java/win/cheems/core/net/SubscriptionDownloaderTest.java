package win.cheems.core.net;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;

/** Offline subscription-provider fixtures; no real subscription tokens. */
public class SubscriptionDownloaderTest {
  private static final String URL_WITH_TOKEN = "https://provider.example/api/subscription/private-token-123";
  private static final byte[] YAML = ("proxies:\n" +
      "  - {name: test, type: hysteria2, server: example.com, port: 443, password: demo}\n")
      .getBytes(StandardCharsets.UTF_8);

  static class Stub extends HttpURLConnection {
    int status;
    byte[] body;
    String redirect;
    IOException failure;
    String agent;
    Stub(URL url,int status,byte[] body){super(url);this.status=status;this.body=body;}
    @Override public void setRequestProperty(String key,String value){
      if("User-Agent".equals(key))agent=value;
    }
    @Override public int getResponseCode() throws IOException {
      if(failure!=null)throw failure;
      return status;
    }
    @Override public String getHeaderField(String name){
      if("Location".equals(name))return redirect;
      if("subscription-userinfo".equals(name))return "upload=1; download=2; total=10";
      return null;
    }
    @Override public InputStream getInputStream(){return new ByteArrayInputStream(body);}
    @Override public void connect(){}
    @Override public void disconnect(){}
    @Override public boolean usingProxy(){return false;}
  }

  @Test public void retries422WithClashUserAgentAndParsesYaml() throws Exception {
    AtomicInteger requests=new AtomicInteger();
    SubscriptionDownloader.Result response=SubscriptionDownloader.download(URL_WITH_TOKEN, url->{
      int count=requests.incrementAndGet();
      return new Stub(url,count==1?422:200,YAML);
    });
    assertEquals(2,requests.get());
    assertEquals(1,Subscription.parse(new String(response.data,StandardCharsets.UTF_8)).size());
    assertTrue(response.usage.contains("total=10"));
  }

  @Test public void rejectsInsecureRedirectBeforeIssuingHttpRequest() throws Exception {
    AtomicInteger requests=new AtomicInteger();
    try {
      SubscriptionDownloader.download(URL_WITH_TOKEN,url->{
        requests.incrementAndGet();
        Stub response=new Stub(url,302,new byte[0]);
        response.redirect="http://provider.example/config";
        return response;
      });
      fail("Redirect to HTTP accepted");
    }catch(IOException e){
      assertTrue(e.getMessage().contains("HTTPS"));
      assertEquals(1,requests.get());
    }
  }

  @Test public void eofHasUsefulMessageWithoutLeakingSubscriptionToken() throws Exception {
    try {
      SubscriptionDownloader.download(URL_WITH_TOKEN,url->{
        Stub response=new Stub(url,200,YAML);
        response.failure=new EOFException(URL_WITH_TOKEN);
        return response;
      });
      fail("EOF not detected");
    }catch(IOException e){
      assertTrue(e.getMessage().contains("EOF"));
      assertFalse(e.getMessage().contains("private-token"));
    }
  }

  @Test public void tlsHandshakeErrorDoesNotDowngradeOrLeak() throws Exception {
    try {
      SubscriptionDownloader.download(URL_WITH_TOKEN,url->{
        Stub response=new Stub(url,200,YAML);
        response.failure=new SSLHandshakeException(URL_WITH_TOKEN);
        return response;
      });
      fail("TLS error ignored");
    }catch(IOException e){
      assertTrue(e.getMessage().contains("HTTPS"));
      assertFalse(e.getMessage().contains("private-token"));
    }
  }

  @Test public void refusesOversizePayload() throws Exception {
    try {
      SubscriptionDownloader.download(URL_WITH_TOKEN,url->
          new Stub(url,200,new byte[Subscription.MAX_BYTES+1]));
      fail("Oversize subscription allowed");
    }catch(IOException e){
      assertTrue(e.getMessage().contains("2 MB"));
    }
  }

  @Test public void rejectsOtherHttpErrorsWithoutRetry() throws Exception {
    AtomicInteger requests=new AtomicInteger();
    try {
      SubscriptionDownloader.download(URL_WITH_TOKEN,url->{
        requests.incrementAndGet();
        return new Stub(url,403,new byte[0]);
      });
      fail("403 accepted");
    }catch(IOException e){
      assertTrue(e.getMessage().contains("403"));
      assertEquals(1,requests.get());
    }
  }
}
