package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/dlclark/regexp2"
	"strconv"

	"cfa/native/common"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

var processors = []processor{
	patchExternalController, // must before patchOverride, so we only apply ExternalController in Override settings
	patchOverride,
	patchDenyRules, // prepend API 封杀规则（须在 patchOverride 之后，读到最新名单）
	patchGeneral,
	patchProfile,
	patchDns,
	patchTun,
	patchListeners,
	patchProviders,
	validConfig,
}

// patchDenyRules 把 API 的封杀名单转成最前面的 REJECT 规则。
// 事后 DELETE /connections 只能断已建连接，App 秒重连照样刷；
// 规则层 PROCESS-NAME / UID REJECT 在连接建立瞬间就拒，才是真的掐死。
func patchDenyRules(cfg *config.RawConfig, _ string) error {
	deny := readDenyPackages()
	if len(deny) == 0 {
		return nil
	}

	prepended := make([]string, 0, len(deny)*2)
	for _, item := range deny {
		if item == "" {
			continue
		}
		if _, err := strconv.ParseUint(item, 10, 32); err == nil {
			prepended = append(prepended, "UID,"+item+",REJECT")
		} else {
			prepended = append(prepended, "PROCESS-NAME,"+item+",REJECT")
		}
	}
	if len(prepended) == 0 {
		return nil
	}

	cfg.Rule = append(prepended, cfg.Rule...)
	log.Infoln("patchDenyRules: prepend %d REJECT rules", len(prepended))
	return nil
}

type denyOverride struct {
	DenyPackages []string `json:"api-deny-packages"`
}

func readDenyPackages() []string {
	var out []string
	for _, slot := range []OverrideSlot{OverrideSlotPersist, OverrideSlotSession} {
		var d denyOverride
		if err := json.NewDecoder(strings.NewReader(ReadOverride(slot))).Decode(&d); err != nil {
			continue
		}
		out = append(out, d.DenyPackages...)
	}
	// 去重
	seen := map[string]bool{}
	uniq := make([]string, 0, len(out))
	for _, p := range out {
		if p != "" && !seen[p] {
			seen[p] = true
			uniq = append(uniq, p)
		}
	}
	return uniq
}

type processor func(cfg *config.RawConfig, profileDir string) error

func patchOverride(cfg *config.RawConfig, _ string) error {
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotPersist))).Decode(cfg); err != nil {
		log.Warnln("Apply persist override: %s", err.Error())
	}
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotSession))).Decode(cfg); err != nil {
		log.Warnln("Apply session override: %s", err.Error())
	}

	return nil
}

func patchExternalController(cfg *config.RawConfig, _ string) error {
	cfg.ExternalController = ""
	cfg.ExternalControllerTLS = ""

	return nil
}

func patchGeneral(cfg *config.RawConfig, profileDir string) error {
	cfg.Interface = ""
	cfg.RoutingMark = 0
	if cfg.ExternalController != "" || cfg.ExternalControllerTLS != "" {
		cfg.ExternalUI = profileDir + "/ui"
	}

	return nil
}

func patchProfile(cfg *config.RawConfig, _ string) error {
	cfg.Profile.StoreSelected = false
	cfg.Profile.StoreFakeIP = true

	return nil
}

func patchDns(cfg *config.RawConfig, _ string) error {
	if !cfg.DNS.Enable {
		cfg.DNS = config.DefaultRawConfig().DNS
		cfg.DNS.Enable = true
		cfg.DNS.NameServer = defaultNameServers
		cfg.DNS.EnhancedMode = C.DNSFakeIP
		cfg.DNS.FakeIPRange = defaultFakeIPRange
		cfg.DNS.FakeIPFilter = defaultFakeIPFilter

		cfg.ClashForAndroid.AppendSystemDNS = true
	}

	if cfg.ClashForAndroid.AppendSystemDNS {
		cfg.DNS.NameServer = append(cfg.DNS.NameServer, "system://")
	}

	return nil
}

func patchTun(cfg *config.RawConfig, _ string) error {
	cfg.Tun.Enable = false
	cfg.Tun.AutoRoute = false
	cfg.Tun.AutoDetectInterface = false
	return nil
}

func patchListeners(cfg *config.RawConfig, _ string) error {
	newListeners := make([]map[string]any, 0, len(cfg.Listeners))
	for _, mapping := range cfg.Listeners {
		if proxyType, existType := mapping["type"].(string); existType {
			switch proxyType {
			case "tproxy", "redir", "tun":
				continue // remove those listeners which is not supported
			}
		}
		newListeners = append(newListeners, mapping)
	}
	cfg.Listeners = newListeners
	return nil
}

func patchProviders(cfg *config.RawConfig, profileDir string) error {
	forEachProviders(cfg, func(index int, total int, key string, provider map[string]any, prefix string) {
		path, _ := provider["path"].(string)
		if len(path) > 0 {
			path = common.ResolveAsRoot(path)
		} else if url, ok := provider["url"].(string); ok {
			path = prefix + "/" + utils.MakeHash([]byte(url)).String() // same as C.GetPathByHash
		} else {
			return // both path and url are empty, maybe inline provider
		}
		provider["path"] = profileDir + "/providers/" + path
	})

	return nil
}

func validConfig(cfg *config.RawConfig, _ string) error {
	if len(cfg.Proxy) == 0 && len(cfg.ProxyProvider) == 0 {
		return errors.New("profile does not contain `proxies` or `proxy-providers`")
	}

	if _, err := regexp2.Compile(cfg.ClashForAndroid.UiSubtitlePattern, 0); err != nil {
		return fmt.Errorf("compile ui-subtitle-pattern: %s", err.Error())
	}

	return nil
}

func process(cfg *config.RawConfig, profileDir string) error {
	for _, p := range processors {
		if err := p(cfg, profileDir); err != nil {
			return err
		}
	}

	return nil
}
