package controllers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebAdminTest {

  @Test
  public void urlsNotSafeToScan() {
    assertFalse(WebAdmin.isSafeToScan(""));
    assertFalse(WebAdmin.isSafeToScan(" "));
    assertFalse(WebAdmin.isSafeToScan("localhost"));
    assertFalse(WebAdmin.isSafeToScan("http://localhost"));
    assertFalse(WebAdmin.isSafeToScan("http://localhost/a"));
    assertFalse(WebAdmin.isSafeToScan("http://a.local"));
    assertFalse(WebAdmin.isSafeToScan("https://a.local"));
    assertFalse(WebAdmin.isSafeToScan("https://a.local/b"));
  }

  @Test
  public void ipAddressesAreNotSafeToScan() {
    assertFalse(WebAdmin.isSafeToScan("http://127.0.0.1"));
    assertFalse(WebAdmin.isSafeToScan("http://192.168.0.1/a"));
    assertFalse(WebAdmin.isSafeToScan("http://10.0.0.1"));
    assertFalse(WebAdmin.isSafeToScan("http://10.0.0.255/b"));
    assertFalse(WebAdmin.isSafeToScan("10.10.10.10"));
  }

  @Test
  public void namedHostsAreSafeToScan() {
    assertTrue(WebAdmin.isSafeToScan("http://www.google.com"));
    assertTrue(WebAdmin.isSafeToScan("https://www.com"));
    assertTrue(WebAdmin.isSafeToScan("https://www.com/path/to/file"));
    assertTrue(WebAdmin.isSafeToScan("https://www.com/path/tp/file?arg=1&b="));
    assertTrue(WebAdmin.isSafeToScan("https://local/b"));
    assertTrue(WebAdmin.isSafeToScan("https://www.local.com/b"));
  }

  @Test
  public void brokenLinksAreNotSafeToScan() {
    assertFalse(WebAdmin.isSafeToScan("http://асэрп.рф/"));
    assertFalse(WebAdmin.isSafeToScan("http://www.haulottevostok.ru/ target="));
    assertFalse(WebAdmin.isSafeToScan("http://www.als-gk.ru /"));
    assertFalse(WebAdmin.isSafeToScan("http://&gt;www.kpmg.ru"));
    assertFalse(WebAdmin.isSafeToScan("http://www.thomson-webcast.net/de/dispatching/?event_id=d5f0d742caf4e2187d1cbcaad78e3439&amp;portal_id=11ee6e53436045303717815634e3327c "));
    assertFalse(WebAdmin.isSafeToScan("http://www.bspb.ru/56753/ "));
  }

  @Test
  public void customPortsAreNotSafeToScan() {
    assertFalse(WebAdmin.isSafeToScan("http://www.google.com:80"));
    assertFalse(WebAdmin.isSafeToScan("https://www.com:8090"));
  }
}