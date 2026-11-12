package core

import (
 "context"
 "encoding/json"
 "errors"
 "net/netip"
 "os"
 "sync"
 "syscall"
 "time"

 "github.com/metacubex/mihomo/adapter/outboundgroup"
 "github.com/metacubex/mihomo/component/dialer"
 "github.com/metacubex/mihomo/config"
 C "github.com/metacubex/mihomo/constant"
 "github.com/metacubex/mihomo/hub/executor"
 "github.com/metacubex/mihomo/listener"
 LC "github.com/metacubex/mihomo/listener/config"
 "github.com/metacubex/mihomo/listener/sing_tun"
 "github.com/metacubex/mihomo/tunnel"
 "github.com/metacubex/mihomo/tunnel/statistic"
)

type Protector interface { Protect(fd int) bool }
var mu sync.Mutex
var tun *sing_tun.Listener

// The Android service retains the original ParcelFileDescriptor; the core owns a dup.
func Start(home, text string, fd int, protector Protector) error {
 mu.Lock(); defer mu.Unlock()
 stop()
 if err := os.MkdirAll(home,0700); err != nil { return err }
 C.SetHomeDir(home)
 dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
  ok := false
  if err := conn.Control(func(s uintptr) { ok = protector.Protect(int(s)) }); err != nil { return err }
  if !ok { return errors.New("Android rejected socket protection") }; return nil
 }
 raw, err := config.UnmarshalRawConfig([]byte(text)); if err != nil { return err }
 // Android owns routing and the TUN lifecycle. Never expose imported listeners/controllers.
 raw.Tun.Enable = false
 raw.ShadowSocksConfig=""; raw.VmessConfig=""; raw.TuicServer.Enable=false; raw.IPTables.Enable=false; raw.NTP.Enable=false
 raw.Interface=""; raw.RoutingMark=0; raw.AllowLan=false; raw.BindAddress="127.0.0.1"
 raw.Port=0; raw.SocksPort=0; raw.MixedPort=0; raw.RedirPort=0; raw.TProxyPort=0
 raw.Listeners=nil; raw.Tunnels=nil
 raw.ExternalController=""; raw.ExternalControllerTLS=""; raw.ExternalControllerUnix=""; raw.ExternalControllerPipe=""
 raw.ExternalUI=""; raw.ExternalUIURL=""
 raw.IPv6=false
 raw.DNS.Enable=true; raw.DNS.IPv6=false; raw.DNS.Listen=""
 if len(raw.DNS.NameServer)==0 { raw.DNS.NameServer=[]string{"https://1.1.1.1/dns-query","https://dns.google/dns-query"} }
 if len(raw.DNS.DefaultNameserver)==0 { raw.DNS.DefaultNameserver=[]string{"1.1.1.1","8.8.8.8"} }
 cfg, err := config.ParseRawConfig(raw); if err != nil { return err }
 executor.ApplyConfig(cfg,true)
 duplicate, err := syscall.Dup(fd); if err != nil { stop(); return err }
 tun, err = sing_tun.New(LC.Tun{Enable:true, Device:"cheems", Stack:C.TunGvisor, MTU:1500, FileDescriptor:duplicate, DNSHijack:[]string{"any:53"}, Inet4Address:[]netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")}}, tunnel.Tunnel)
 if err != nil { syscall.Close(duplicate); stop(); return err }
 statistic.DefaultManager.ResetStatistic()
 return nil
}
func stop() {
 if tun != nil { _=tun.Close(); tun=nil }
 listener.Cleanup()
 statistic.DefaultManager.Range(func(t statistic.Tracker) bool { _=t.Close(); return true })
}
func Stop() { mu.Lock(); defer mu.Unlock(); stop() }
func Traffic() string { up,down:=statistic.DefaultManager.Now(); b,_:=json.Marshal(map[string]int64{"up":up,"down":down}); return string(b) }
func Proxies() string { mu.Lock(); defer mu.Unlock(); b,_:=json.Marshal(tunnel.Proxies()); return string(b) }
func Select(group, name string) error {
 mu.Lock(); defer mu.Unlock()
 p,ok:=tunnel.Proxies()[group]; if !ok { return errors.New("group not found") }
 s,ok:=p.Adapter().(*outboundgroup.Selector); if !ok { return errors.New("group is automatic") }; return s.Set(name)
}
func Delay(name string) (int,error) {
 p,ok:=tunnel.Proxies()[name]; if !ok {return 0,errors.New("proxy not found")}
 ctx,cancel:=context.WithTimeout(context.Background(),8*time.Second); defer cancel()
 d,err:=p.URLTest(ctx,"https://www.gstatic.com/generate_204",nil); return int(d),err
}
func Version() string { return "Mihomo v1.19.34" }
