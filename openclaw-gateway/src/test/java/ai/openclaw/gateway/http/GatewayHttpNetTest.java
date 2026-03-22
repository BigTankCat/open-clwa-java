package ai.openclaw.gateway.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GatewayHttpNetTest {

  @Test
  void loopbackRecognized() {
    assertTrue(GatewayHttpNet.isLoopbackAddress("127.0.0.1"));
    assertTrue(GatewayHttpNet.isLoopbackAddress("::1"));
  }

  @Test
  void ipv4CidrMatch() {
    assertTrue(GatewayHttpNet.isIpInCidrOrExact("10.0.0.5", "10.0.0.0/8"));
    assertFalse(GatewayHttpNet.isIpInCidrOrExact("10.0.0.5", "192.168.0.0/16"));
  }

  @Test
  void localishHost() {
    assertTrue(GatewayHttpNet.isLocalishHost("localhost:18789"));
    assertTrue(GatewayHttpNet.isLocalishHost("127.0.0.1"));
    assertTrue(GatewayHttpNet.isLocalishHost("myhost.ts.net"));
  }
}
