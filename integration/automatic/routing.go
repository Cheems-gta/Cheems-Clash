package mobilebridge

import (
	"context"
	_ "embed"
	"encoding/binary"
	"errors"
	quictransport "github.com/Cheems-gta/Cheems-Core/native/quic"
	"golang.org/x/net/dns/dnsmessage"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

//go:embed cn-prefixes.txt
var cnData string

var cnPrefixes = sync.OnceValue(func() map[netip.Prefix]bool {
	m := make(map[netip.Prefix]bool)
	for _, line := range strings.Fields(cnData) {
		m[netip.MustParsePrefix(line).Masked()] = true
	}
	return m
})

type domainPolicy uint8

const (
	byAddress domainPolicy = iota
	directRoute
	proxyRoute
)

func domainRoute(name string) domainPolicy {
	name = strings.ToLower(strings.TrimSuffix(name, "."))
	for _, rule := range []struct {
		suffix string
		route  domainPolicy
	}{
		{"bilibili.com", directRoute}, {"biliapi.net", directRoute}, {"biliapi.com", directRoute}, {"bilivideo.com", directRoute}, {"bilivideo.cn", directRoute}, {"hdslb.com", directRoute}, {"b23.tv", directRoute}, {"cn", directRoute},
		{"chatgpt.com", proxyRoute}, {"openai.com", proxyRoute}, {"oaistatic.com", proxyRoute}, {"oaiusercontent.com", proxyRoute},
	} {
		if name == rule.suffix || strings.HasSuffix(name, "."+rule.suffix) {
			return rule.route
		}
	}
	return byAddress
}

type routeEntry struct {
	route  domainPolicy
	expiry time.Time
}
type autoRouter struct {
	network Network
	mu      sync.Mutex
	cache   map[netip.Addr]routeEntry
}

func (r *autoRouter) direct(ip netip.Addr) bool {
	if r == nil || !ip.IsValid() {
		return false
	}
	ip = ip.Unmap()
	r.mu.Lock()
	e := r.cache[ip]
	r.mu.Unlock()
	if time.Now().Before(e.expiry) {
		return e.route == directRoute
	}
	prefixes := cnPrefixes()
	for bits := ip.BitLen(); bits >= 0; bits-- {
		if prefixes[netip.PrefixFrom(ip, bits).Masked()] {
			return true
		}
	}
	return false
}
func dnsPolicy(query []byte) domainPolicy {
	var m dnsmessage.Message
	if m.Unpack(query) != nil || len(m.Questions) != 1 {
		return byAddress
	}
	return domainRoute(m.Questions[0].Name.String())
}
func (r *autoRouter) remember(query, response []byte) {
	policy := dnsPolicy(query)
	if policy == byAddress {
		return
	}
	var q, m dnsmessage.Message
	if q.Unpack(query) != nil || m.Unpack(response) != nil || m.Header.ID != q.Header.ID || !m.Response || m.RCode != dnsmessage.RCodeSuccess || len(m.Questions) != 1 || m.Questions[0] != q.Questions[0] {
		return
	}
	names := map[string]bool{strings.ToLower(q.Questions[0].Name.String()): true}
	for i := 0; i < 16; i++ {
		added := false
		for _, a := range m.Answers {
			if c, ok := a.Body.(*dnsmessage.CNAMEResource); ok && names[strings.ToLower(a.Header.Name.String())] {
				n := strings.ToLower(c.CNAME.String())
				if !names[n] {
					names[n] = true
					added = true
				}
			}
		}
		if !added {
			break
		}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	for _, a := range m.Answers {
		if !names[strings.ToLower(a.Header.Name.String())] || a.Header.TTL == 0 {
			continue
		}
		var ip netip.Addr
		switch v := a.Body.(type) {
		case *dnsmessage.AResource:
			ip = netip.AddrFrom4(v.A)
		case *dnsmessage.AAAAResource:
			ip = netip.AddrFrom16(v.AAAA).Unmap()
		default:
			continue
		}
		if old := r.cache[ip]; old.route == proxyRoute && now.Before(old.expiry) && policy == directRoute {
			continue
		}
		if len(r.cache) >= 8192 {
			for k, v := range r.cache {
				if !now.Before(v.expiry) {
					delete(r.cache, k)
				}
			}
			if len(r.cache) >= 8192 {
				continue
			}
		}
		r.cache[ip] = routeEntry{policy, now.Add(time.Duration(min(a.Header.TTL, uint32(1800))) * time.Second)}
	}
}
func (r *autoRouter) dial(ctx context.Context, network, address string) (net.Conn, error) {
	d := net.Dialer{Timeout: 8 * time.Second, Control: func(_, _ string, c syscall.RawConn) error {
		ok := false
		if err := c.Control(func(fd uintptr) { ok = r.network.Protect(int64(fd)) }); err != nil {
			return err
		}
		if !ok {
			return errors.New("physical network binding rejected")
		}
		return nil
	}}
	return d.DialContext(ctx, network, address)
}

type directDatagram struct {
	net.Conn
	// Read has one consumer, which copies the payload before the next call.
	buffer [65535]byte
}

func (d *directDatagram) WriteTo(p []byte, target string) error {
	a, err := netip.ParseAddrPort(target)
	if err != nil || a.String() != d.RemoteAddr().String() {
		return errors.New("UDP destination changed")
	}
	_, err = d.Write(p)
	return err
}
func (d *directDatagram) Read(ctx context.Context) (quictransport.UDPMessage, error) {
	n, err := d.Conn.Read(d.buffer[:])
	if err != nil {
		return quictransport.UDPMessage{}, err
	}
	return quictransport.UDPMessage{Address: d.RemoteAddr().String(), Payload: d.buffer[:n]}, nil
}
func (r *autoRouter) domesticDNS(ctx context.Context, query []byte) ([]byte, error) {
	c, err := r.dial(ctx, "tcp", "223.5.5.5:53")
	if err != nil {
		return nil, err
	}
	defer c.Close()
	stop := context.AfterFunc(ctx, func() { c.Close() })
	defer stop()
	c.SetDeadline(time.Now().Add(4 * time.Second))
	frame := binary.BigEndian.AppendUint16(nil, uint16(len(query)))
	if _, err = c.Write(append(frame, query...)); err != nil {
		return nil, err
	}
	return readDNSFrame(c)
}
func readDNSFrame(c io.Reader) ([]byte, error) {
	var n uint16
	if err := binary.Read(c, binary.BigEndian, &n); err != nil {
		return nil, err
	}
	if n < 12 {
		return nil, errors.New("invalid DNS frame")
	}
	p := make([]byte, n)
	_, err := io.ReadFull(c, p)
	return p, err
}
func (h *vpnFlowHandler) routedTCP(ctx context.Context, ip netip.Addr, port uint16) (io.ReadWriteCloser, error) {
	if h.auto.direct(ip) {
		return h.auto.dial(ctx, "tcp", net.JoinHostPort(ip.String(), strconv.Itoa(int(port))))
	}
	return h.tcp.OpenTCP(ctx, ip.String(), int(port))
}
func (h *vpnFlowHandler) routedUDP(ctx context.Context, dst netip.AddrPort) (udpAssociation, error) {
	if h.auto.direct(dst.Addr()) {
		c, err := h.auto.dial(ctx, "udp", dst.String())
		if err != nil {
			return nil, err
		}
		return &directDatagram{Conn: c}, nil
	}
	if h.udp == nil {
		return nil, errors.New("node does not support UDP")
	}
	return h.udp.OpenUDPAssociation(dst.String())
}
func (h *vpnFlowHandler) serveDNSStream(ctx context.Context, c net.Conn) {
	stop := context.AfterFunc(ctx, func() { c.Close() })
	defer stop()
	for {
		q, err := readDNSFrame(c)
		if err != nil {
			return
		}
		var p []byte
		if dnsPolicy(q) == directRoute {
			p, err = h.auto.domesticDNS(ctx, q)
		} else {
			p, err = dnsOverTCP(ctx, h.tcp, q)
		}
		if err != nil || !matchingDNSResponse(q, p) {
			return
		}
		h.auto.remember(q, p)
		header := binary.BigEndian.AppendUint16(nil, uint16(len(p)))
		if _, err = c.Write(append(header, p...)); err != nil {
			return
		}
	}
}
