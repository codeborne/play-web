package plugins;

import models.WebPage;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.i18n.Lang;
import play.mvc.Http;
import play.mvc.Router;
import play.mvc.Scope;

import java.lang.reflect.Method;
import java.util.List;

import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.substring;

public class WebContentPlugin extends PlayPlugin {
  private long lastModified;
  private int genericRouteIndex;

  @Override public void onApplicationStart() {
    genericRouteIndex = Router.routes.size();
    for (int i = 0; i < Router.routes.size(); i++) {
      Router.Route route = Router.routes.get(i);
      if (route.action.equals("{controller}.{action}")) {
        genericRouteIndex = i; break;
      }
    }

    addWebRoutes(WebPage.ROOT.children());
    Router.addRoute(genericRouteIndex, "GET", WebPage.ROOT_EN.path + "?.*", "Web.serveContent", null);

    for (WebPage page : WebPage.all()) {
      String alias = page.metadata.getProperty("alias");
      if (isNotEmpty(alias)) {
        if (!alias.startsWith("/")) alias = "/" + alias;
        Router.appendRoute("GET", alias + "/?", "Web.redirectAlias", "{path:'" + page.path + "'}", null, null, 0);
      }
    }

    lastModified = WebPage.ROOT.dir.lastModified();
  }

  @Override public void detectChange() {
    if (WebPage.ROOT.dir.lastModified() > lastModified) {
      Logger.info(WebPage.ROOT.dir + " change detected, reloading web routes");
      onApplicationStart();
    }
  }

  @Override public void beforeActionInvocation(Method actionMethod) {
    if (actionMethod.getDeclaringClass().getSimpleName().equals("Web"))
      fixLocale();
    Scope.RenderArgs.current().put("rootPage", WebPage.rootForLocale());
  }

  private void fixLocale() {
    String locale = Lang.get();
    String expectedLocale = Http.Request.current().path.startsWith("/en") ? "en" : "ru";
    if (!expectedLocale.equals(locale)) {
      Lang.change(expectedLocale);
      // play puts only session cookie, let's have a longer one
      Http.Response.current().setCookie(Play.configuration.getProperty("application.lang.cookie", "PLAY_LANG"), expectedLocale, "10000d");
    }
  }

  private void addWebRoutes(List<WebPage> pages) {
    for (WebPage page : pages) {
      String path = page.path;
      if (path.endsWith("/")) path = substring(path, 0, -1);
      Router.addRoute(genericRouteIndex, "GET", path + "/?.*", "Web.serveContent", null);
      if ("prod".equals(Play.id))  // cache top-level pages on production
        Router.addRoute(genericRouteIndex, "GET", path + "/", "Web.serveCachedContent", null);
    }
  }
}
