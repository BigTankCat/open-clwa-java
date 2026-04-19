package ai.openclaw.gateway.http;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client IP + "local admin" request checks aligned with Node {@code src/gateway/net.ts} and {@code
 * auth.ts} ({@code isLocalDirectRequest}).
 */
public final class GatewayHttpNet {

  private GatewayHttpNet() {}

  public static String resolveRequestClientIp(
      HttpServletRequest req,
      List<String> trustedProxies,
      boolean allowRealIpFallback) {
    if (req == null) {
      return "";
    }
    String remote = normalizeIp(req.getRemoteAddr());
    if (remote == null || remote.isEmpty()) {
      return "";
    }
    if (!isTrustedProxyAddress(remote, trustedProxies)) {
      return remote;
    }
    String xff = header(req, "X-Forwarded-For");
    String forwardedClient = resolveForwardedClientIp(xff, trustedProxies);
    if (forwardedClient != null && !forwardedClient.isBlank()) {
      return forwardedClient;
    }
    if (allowRealIpFallback) {
      String real = header(req, "X-Real-IP");
      String parsed = parseIpLiteral(real);
      if (parsed != null) {
        return parsed;
      }
    }
    return "";
  }

  /**
   * Node {@code isLocalDirectRequest}: loopback client, local-ish Host, and forwarded headers only
   * when the immediate peer is a trusted proxy.
   */
  public static boolean isLocalDirectRequest(
      HttpServletRequest req, List<String> trustedProxies, boolean allowRealIpFallback) {
    if (req == null) {
      return false;
    }
    String clientIp =
        resolveRequestClientIp(
            req, trustedProxies == null ? List.of() : trustedProxies, allowRealIpFallback);
    if (!isLoopbackAddress(clientIp)) {
      return false;
    }
    boolean hasForwarded =
        header(req, "X-Forwarded-For") != null
            || header(req, "X-Real-IP") != null
            || header(req, "X-Forwarded-Host") != null;
    String remoteRaw = normalizeIp(req.getRemoteAddr());
    boolean remoteTrusted = isTrustedProxyAddress(remoteRaw, trustedProxies);
    return isLocalishHost(req.getHeader("Host")) && (!hasForwarded || remoteTrusted);
  }

  public static boolean isLocalishHost(String hostHeader) {
    if (hostHeader == null || hostHeader.isBlank()) {
      return false;
    }
    String host = resolveHostName(hostHeader.trim().toLowerCase(Locale.ROOT));
    if (host.isEmpty()) {
      return false;
    }
    if ("localhost".equals(host) || host.endsWith(".localhost")) {
      return true;
    }
    if (host.endsWith(".ts.net")) {
      return true;
    }
    return isLoopbackHost(host);
  }

  static String resolveHostName(String hostHeaderLower) {
    String host = hostHeaderLower.trim();
    if (host.startsWith("[")) {
      int end = host.indexOf(']');
      if (end > 1) {
        return host.substring(1, end);
      }
    }
    // Strip port from any host:port form (mirrors TypeScript resolveHostName)
    int colon = host.lastIndexOf(':');
    if (colon > 0 && !host.startsWith("[") && host.indexOf(':') == colon) {
      return host.substring(0, colon);
    }
    return host;
  }

  static boolean isLoopbackHost(String host) {
    try {
      InetAddress addr = InetAddress.getByName(host);
      return addr.isLoopbackAddress();
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean isLoopbackAddress(String ip) {
    if (ip == null || ip.isBlank()) {
      return false;
    }
    String n = normalizeIp(ip);
    if (n == null || n.isEmpty()) {
      return false;
    }
    try {
      return InetAddress.getByName(n).isLoopbackAddress();
    } catch (Exception e) {
      return false;
    }
  }

  static String normalizeIp(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return "";
    }
    if (s.startsWith("/") && s.length() > 1) {
      s = s.substring(1);
    }
    int zone = s.indexOf('%');
    if (zone > 0) {
      s = s.substring(0, zone);
    }
    if (s.startsWith("[") && s.endsWith("]") && s.length() > 2) {
      s = s.substring(1, s.length() - 1);
    }
    return s;
  }

  static String parseIpLiteral(String raw) {
    String s = raw == null ? "" : raw.trim();
    if (s.isEmpty()) {
      return null;
    }
    String first = s.split(",")[0].trim();
    String n = normalizeIp(first);
    if (n == null || n.isEmpty()) {
      return null;
    }
    try {
      InetAddress.getByName(n);
      return n;
    } catch (Exception e) {
      return null;
    }
  }

  static boolean isTrustedProxyAddress(String ip, List<String> trustedProxies) {
    if (ip == null || ip.isBlank() || trustedProxies == null || trustedProxies.isEmpty()) {
      return false;
    }
    String normalized = normalizeIp(ip);
    for (String proxy : trustedProxies) {
      if (proxy == null || proxy.isBlank()) {
        continue;
      }
      String c = proxy.trim();
      if (isIpInCidrOrExact(normalized, c)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Supports exact IP or IPv4 CIDR ({@code a.b.c.d/nn}). IPv6 CIDR is not implemented; use exact
   * literals.
   */
  static boolean isIpInCidrOrExact(String ip, String cidrOrExact) {
    if (ip == null || cidrOrExact == null) {
      return false;
    }
    String spec = cidrOrExact.trim();
    if (spec.isEmpty()) {
      return false;
    }
    if (!spec.contains("/")) {
      String rhs = normalizeIp(spec);
      return rhs != null && rhs.equalsIgnoreCase(ip);
    }
    String[] parts = spec.split("/", 2);
    if (parts.length != 2) {
      return false;
    }
    String base = normalizeIp(parts[0].trim());
    int prefix;
    try {
      prefix = Integer.parseInt(parts[1].trim());
    } catch (NumberFormatException e) {
      return false;
    }
    if (base == null || !isProbablyIpv4(base) || prefix < 0 || prefix > 32) {
      return false;
    }
    if (!isProbablyIpv4(ip)) {
      return false;
    }
    int ipInt = ipv4ToInt(ip);
    int netInt = ipv4ToInt(base);
    if (ipInt < 0 || netInt < 0) {
      return false;
    }
    int mask = prefix == 0 ? 0 : (-1 << (32 - prefix));
    return (ipInt & mask) == (netInt & mask);
  }

  static int ipv4ToInt(String ipv4) {
    String[] oct = ipv4.split("\\.");
    if (oct.length != 4) {
      return -1;
    }
    int acc = 0;
    for (String o : oct) {
      int v;
      try {
        v = Integer.parseInt(o);
      } catch (NumberFormatException e) {
        return -1;
      }
      if (v < 0 || v > 255) {
        return -1;
      }
      acc = (acc << 8) | v;
    }
    return acc;
  }

  static boolean isProbablyIpv4(String s) {
    return s.chars().filter(ch -> ch == '.').count() == 3;
  }

  static String resolveForwardedClientIp(String forwardedFor, List<String> trustedProxies) {
    if (forwardedFor == null || forwardedFor.isBlank() || trustedProxies.isEmpty()) {
      return null;
    }
    List<String> chain = new ArrayList<>();
    for (String entry : forwardedFor.split(",")) {
      String parsed = parseIpLiteral(entry);
      if (parsed != null) {
        chain.add(parsed);
      }
    }
    if (chain.isEmpty()) {
      return null;
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
      String hop = chain.get(i);
      if (isLoopbackAddress(hop)) {
        continue;
      }
      if (!isTrustedProxyAddress(hop, trustedProxies)) {
        return hop;
      }
    }
    return null;
  }

  static String header(HttpServletRequest req, String name) {
    String v = req.getHeader(name);
    return v == null || v.isBlank() ? null : v.trim();
  }
}
