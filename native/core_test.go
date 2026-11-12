package core
import "testing"
type deny struct{}
func (deny) Protect(fd int) bool { return false }
func TestInvalidConfigurationDoesNotStartVPN(t *testing.T) {
 if err:=Start(t.TempDir(),"proxies: [",-1,deny{}); err==nil {t.Fatal("invalid YAML accepted")}
 Stop(); Stop()
}
func TestUnknownSelectorFails(t *testing.T) {
 if err:=Select("missing-group","missing-node"); err==nil {t.Fatal("missing selector accepted")}
}
