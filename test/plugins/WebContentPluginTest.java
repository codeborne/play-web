package plugins;

import controllers.Web;
import models.WebPage;
import org.junit.Before;
import org.junit.Test;
import play.Play;
import play.i18n.Lang;
import play.mvc.Http;
import play.mvc.Router;
import play.mvc.Scope;

import java.util.List;
import java.util.Properties;

import static java.util.Arrays.asList;
import static models.WebPage.forPath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static play.Play.Mode.DEV;
import static play.Play.Mode.PROD;

public class WebContentPluginTest {
  WebContentPlugin plugin;
  List<WebPage> pages;
  Http.Request request;
  Http.Response response;

  @Before
  public void setUp() {
    if (Play.configuration == null) Play.configuration = new Properties();
    Http.Request.current.set(request = new Http.Request());
    Http.Response.current.set(response = new Http.Response());
    Scope.RenderArgs.current.set(new Scope.RenderArgs());
    request.path = "/";
    Play.langs.add("en");
    Play.langs.add("ru");
    Play.mode = PROD;
    Router.routes.clear();
    plugin = new WebContentPlugin();
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

  @Test
  public void frontPagesAreCachedByBrowserFor12h() throws NoSuchMethodException {
    plugin.beforeActionInvocation(Web.class.getMethod("serveContentCached"));
    assertEquals("max-age=43200", response.getHeader("Cache-Control"));
  }

  @Test
  public void otherPagesHaveDefaultBrowserCaching() throws NoSuchMethodException {
    plugin.beforeActionInvocation(Web.class.getMethod("serveContent"));
    assertNull(response.getHeader("Cache-Control"));
  }

  @Test
  public void browserCacheNotUsedInDev() throws NoSuchMethodException {
    Play.mode = DEV;
    plugin.beforeActionInvocation(Web.class.getMethod("serveContentCached"));
    assertNull(response.getHeader("Cache-Control"));
  }

  @Test
  public void localeIsSetAccordingToURL() throws NoSuchMethodException {
    assertMethodSwitchesLocale("serveContent");
    assertMethodSwitchesLocale("serveContentCached");
    assertMethodSwitchesLocale("news", String.class);
    assertMethodDoesntSwitchLocale("sitemap");
    assertMethodDoesntSwitchLocale("sitemapXml");
    assertMethodDoesntSwitchLocale("search", String.class);
  }

  private void assertMethodSwitchesLocale(String methodName, Class...argTypes) throws NoSuchMethodException {
    Lang.set("ru");
    request.path = "/en/about";
    plugin.beforeActionInvocation(Web.class.getMethod(methodName, argTypes));
    assertEquals("en", Lang.get());
  }

  private void assertMethodDoesntSwitchLocale(String methodName, Class...argTypes) throws NoSuchMethodException {
    Lang.set("ru");
    request.path = "/en/about";
    plugin.beforeActionInvocation(Web.class.getMethod(methodName, argTypes));
    assertEquals("ru", Lang.get());
  }

  private void assertCached(String url) {
    assertEquals("Web.serveContentCached", Router.route("GET", url).get("action"));
  }

  private void assertNonCached(String url) {
    assertEquals("Web.serveContent", Router.route("GET", url).get("action"));
  }
}
