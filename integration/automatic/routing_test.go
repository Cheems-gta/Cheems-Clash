package mobilebridge

import (
	"context"
	"net"
	"golang.org/x/net/dns/dnsmessage"
	"net/netip"
	"testing"
)

func TestAutomaticRouting(t *testing.T) {
	r := &autoRouter{cache: make(map[netip.Addr]routeEntry)}
	for _, v := range []struct {
		domain string
		policy domainPolicy
	}{{"live.bilivideo.com.", directRoute}, {"CHATGPT.com.", proxyRoute}, {"notbilibili.com", byAddress}} {
		if domainRoute(v.domain) != v.policy {
			t.Fatal(v.domain)
		}
	}
	for _, v := range []struct {
		ip     string
		direct bool
	}{{"223.5.5.5", true}, {"1.1.1.1", false}, {"2606:4700:4700::1111", false}} {
		if r.direct(netip.MustParseAddr(v.ip)) != v.direct {
			t.Fatal(v.ip)
		}
	}
	for _, host := range []string{"live.bilivideo.com.", "chatgpt.com."} {
		name := dnsmessage.MustNewName(host)
		q := dnsmessage.Message{Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
		query, _ := q.Pack()
		q.Response = true
		q.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.AResource{A: [4]byte{1, 1, 1, 1}}}}
		answer, _ := q.Pack()
		r.remember(query, answer)
		if r.direct(netip.MustParseAddr("1.1.1.1")) != (host == "live.bilivideo.com.") {
			t.Fatal(host)
		}
	}
}

func TestDirectDatagramClosedDuringRead(t *testing.T) {
	local, peer := net.Pipe()
	defer local.Close()
	_ = peer.Close()
	d := &directDatagram{Conn: local}
	message, err := d.Read(context.Background())
	if err == nil || len(message.Payload) != 0 {
		t.Fatal("expected closed direct socket to return an error without data")
	}
}
