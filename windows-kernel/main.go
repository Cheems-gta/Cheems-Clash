package main

import (
 "bufio"
 "context"
 "encoding/json"
 "errors"
 "fmt"
 "io"
 "net"
 "os"
 "os/signal"
 "strconv"
 "strings"
 "syscall"
 "time"

 core "github.com/Cheems-gta/Cheems-Core/native/quic"
)

const version = "0.6.0-preview"

type config struct {
 Type string `json:"type"`
 Host string `json:"host"`
 Port int `json:"port"`
 SNI string `json:"sni"`
 Password string `json:"password"`
 LocalPort int `json:"local_port"`
 LocalSecret string `json:"local_secret"`
 Fingerprint string `json:"fingerprint"`
 ALPN string `json:"alpn"`
 CA string `json:"ca"`
 Pin string `json:"pin"`
}

// Windows has no Android VpnService to exclude upstream sockets from a TUN.
// It is a local authenticated SOCKS5 client, not yet a Windows system VPN.
type desktopNetwork struct{}
func (desktopNetwork) Protect(fd int64) bool { return true }
func (desktopNetwork) Resolve(host string) (string,error) {
 ips,err:=net.LookupIP(host)
 if err!=nil{return "",err}
 for _,ip:=range ips {if v4:=ip.To4();v4!=nil{return v4.String(),nil}}
 if len(ips)>0{return ips[0].String(),nil}
 return "",errors.New("no DNS addresses")
}

func main() {
 if len(os.Args)==2 && os.Args[1]=="--version" {fmt.Println("Making Windows",version); return}
 if err:=run();err!=nil {fmt.Fprintln(os.Stderr,"ERROR:",err);os.Exit(1)}
}

func run() error {
 if len(os.Args)!=2 || os.Args[1]!="--stdio" {return errors.New("usage: doreena-making --stdio")}
 reader:=bufio.NewReader(io.LimitReader(os.Stdin,65536))
 raw,err:=reader.ReadBytes('\n')
 if err!=nil{return fmt.Errorf("configuration missing: %w",err)}
 var c config
 if err=json.Unmarshal(raw,&c);err!=nil{return errors.New("invalid configuration")}
 if c.Host==""||c.Port<1||c.Port>65535||c.LocalPort<1||c.LocalPort>65535||len(c.LocalSecret)<24||len(c.LocalSecret)>255||c.Password=="" {return errors.New("invalid endpoint or authentication")}
 if c.CA!="" && !strings.HasPrefix(strings.TrimSpace(c.CA),"-----BEGIN CERTIFICATE-----") {return errors.New("CA must be inline PEM")}
 ctx,cancel:=context.WithCancel(context.Background())
 defer cancel()
 var server *core.SOCKS5Server
 var closeTransport func()
 addr:=net.JoinHostPort(c.Host,strconv.Itoa(c.Port))
 switch strings.ToLower(c.Type) {
 case "hysteria2","hy2":
  dialCtx,stop:=context.WithTimeout(ctx,15*time.Second)
  session,e:=core.DialHysteria2(dialCtx,core.Hysteria2Config{Address:addr,ServerName:c.SNI,Password:c.Password,Timeout:15*time.Second,TrustedCAPEM:c.CA,CertificateSHA256:c.Pin,Network:desktopNetwork{}})
  stop()
  if e!=nil{return fmt.Errorf("HY2 handshake: %w",e)}
  closeTransport=func(){_ = session.Close()}
  server,err=core.ListenSOCKS5(session,fmt.Sprintf("127.0.0.1:%d",c.LocalPort),c.LocalSecret)
  if err!=nil{closeTransport();return err}
 case "anytls":
  var alpn []string
  if c.ALPN!=""{alpn=strings.Split(c.ALPN,",")}
  client,e:=core.NewAnyTLSClient(ctx,core.AnyTLSConfig{Address:addr,ServerName:c.SNI,Password:c.Password,TrustedCAPEM:c.CA,CertificateSHA256:c.Pin,Fingerprint:c.Fingerprint,ALPN:alpn,Network:desktopNetwork{}})
  if e!=nil{return fmt.Errorf("AnyTLS setup: %w",e)}
  closeTransport=func(){_ = client.Close()}
  server,err=core.ListenSOCKS5WithDialer(fmt.Sprintf("127.0.0.1:%d",c.LocalPort),c.LocalSecret,client.OpenTCP)
  if err!=nil{closeTransport();return err}
 default:
  return errors.New("unsupported protocol; Windows preview supports HY2 and AnyTLS only")
 }
 defer closeTransport()
 defer server.Close()
 serveError:=make(chan error,1)
 go func(){serveError<-server.Serve()}()
 fmt.Println("READY")
 interrupt:=make(chan os.Signal,1)
 signal.Notify(interrupt,os.Interrupt,syscall.SIGTERM)
 defer signal.Stop(interrupt)
 stdinClosed:=make(chan struct{})
 go func(){_,_ = io.Copy(io.Discard,reader);close(stdinClosed)}()
 select {
 case <-stdinClosed:
 case <-interrupt:
 case err=<-serveError:
  return fmt.Errorf("SOCKS listener closed: %w",err)
 }
 return nil
}
