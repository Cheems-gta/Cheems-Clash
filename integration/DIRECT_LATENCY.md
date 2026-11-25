# Domestic direct responsiveness experiment

- Existing package bypass policy is unchanged. Direct apps do not traverse Making.
- Automatic mass latency probes are removed; manual probes only while disconnected, with concurrency 2 and a cancel action. Connection is blocked while a probe is in flight.
- Underlying network selection honors Android's default physical network, prefers a previously validated network if the default is a VPN, then a validated alternative. Duplicate successful underlying network publications are suppressed. No MTU, DNS upstream, protocol, or certificate changes.
- Settings offers explicit DNS and HTTPS-header measurements to Baidu/Bilibili using the client's excluded physical network. No auto/background probe. These samples are not app-internal delays, may include cached DNS, TLS/HTTP setup and server response, and depend on CDN, radio, and server load. Compare repeated samples on the same network with VPN on/off. Do not infer improvement from compilation alone.
