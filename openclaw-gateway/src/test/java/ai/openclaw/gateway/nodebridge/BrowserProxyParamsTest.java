package ai.openclaw.gateway.nodebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import ai.openclaw.protocol.ErrorCodes;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrowserProxyParamsTest {

  @Test
  void rejectsEmptyMethod() {
    var r = BrowserProxyParams.parse(Map.of("path", "/foo"));
    assertInstanceOf(BrowserProxyParams.ParseResult.ParseError.class, r);
    var e = ((BrowserProxyParams.ParseResult.ParseError) r).error();
    assertEquals(ErrorCodes.INVALID_REQUEST, e.getCode());
  }

  @Test
  void rejectsProfileCreate() {
    var r = BrowserProxyParams.parse(Map.of("method", "POST", "path", "/profiles/create"));
    assertInstanceOf(BrowserProxyParams.ParseResult.ParseError.class, r);
  }

  @Test
  void acceptsGetAndBuildsProxyParams() {
    var r = BrowserProxyParams.parse(Map.of("method", "get", "path", "v1/status"));
    assertInstanceOf(BrowserProxyParams.ParseResult.Parsed.class, r);
    var p = (BrowserProxyParams.ParseResult.Parsed) r;
    assertEquals("GET", p.method());
    assertEquals("/v1/status", p.path());
    Map<String, Object> cmd = BrowserProxyParams.toProxyCommandParams(p);
    assertEquals("GET", cmd.get("method"));
    assertEquals("/v1/status", cmd.get("path"));
  }

  @Test
  void normalizePathTrimsTrailingSlashes() {
    assertEquals("/a/b", BrowserProxyParams.normalizeBrowserRequestPath("/a/b//"));
  }

  @Test
  void profileFromQuery() {
    var r =
        BrowserProxyParams.parse(
            Map.of(
                "method",
                "GET",
                "path",
                "/x",
                "query",
                Map.of("profile", "  p1  ")));
    var p = (BrowserProxyParams.ParseResult.Parsed) r;
    assertEquals("p1", p.profile());
    assertNotNull(BrowserProxyParams.toProxyCommandParams(p).get("profile"));
  }
}
