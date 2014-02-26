package plugins;

import models.WebPage;
import org.junit.Before;
import org.junit.Test;
import play.Play;
import play.mvc.Router;

import java.util.List;
import java.util.Properties;

import static java.util.Arrays.asList;
import static models.WebPage.forPath;
import static org.junit.Assert.assertEquals;
import static play.Play.Mode.DEV;
import static play.Play.Mode.PROD;

public class WebContentPluginTest {
  WebContentPlugin plugin = new WebContentPlugin();
  List<WebPage> pages;

  @Before
  public void setUp() {
    if (Play.configuration == null) Play.configuration = new Properties();
    Router.routes.clear();
    pages = asList(forPath("/en/marketing/"), forPath("/ru/marketing/"));
  }

  @Test
  public void contentMustNotBeCachedInDev() {
    Play.mode = DEV;
    plugin.addWebRoutes(pages);
    assertNonCached("/en/marketing/");
    assertNonCached("/ru/marketing/2013/10/31/");
  }

  @Test
  public void rootPagesAreCachedInProd() {
    Play.mode = PROD;
    plugin.addWebRoutes(pages);
    assertCached("/en/marketing/");
    assertCached("/ru/marketing/");
    assertNonCached("/ru/marketing/2013/10/31/");
  }

  private void assertCached(String url) {
    assertEquals("Web.serveContentCached", Router.route("GET", url).get("action"));
  }

  private void assertNonCached(String url) {
    assertEquals("Web.serveContent", Router.route("GET", url).get("action"));
  }
}
