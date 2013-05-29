package plugins;

import models.WebPage;
import play.Logger;
import play.PlayPlugin;
import play.mvc.Router;
import play.mvc.Scope;

import java.lang.reflect.Method;
import java.util.List;

import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.substring;

public class WebContentPlugin extends PlayPlugin {
  private long lastModified;

  @Override public void onApplicationStart() {
    addWebRoutes(WebPage.ROOT.children());
    Router.addRoute("GET", "/en/?.*", "Web.serveContent");

    for (WebPage page : WebPage.all()) {
      String alias = page.metadata.getProperty("alias");
      if (isNotEmpty(alias)) {
        if (!alias.startsWith("/")) alias = "/" + alias;
        Router.addRoute("GET", alias + "/?", "Web.redirectAlias", "{path:'" + page.path + "'}", "");
      }
    }

    Router.addRoute("GET", "/news/?.*", "Web.news");
    Router.addRoute("GET", "/analytics/?.*", "Web.news");
    Router.addRoute("GET", "/about/depository/news/?.*", "Web.news");
    Router.addRoute("GET", "/en/news/?.*", "Web.news");

    lastModified = WebPage.ROOT.dir.lastModified();
  }

  @Override public void detectChange() {
    if (WebPage.ROOT.dir.lastModified() > lastModified) {
      Logger.info(WebPage.ROOT.dir + " change detected, reloading web routes");
      onApplicationStart();
    }
  }

  @Override public void beforeActionInvocation(Method actionMethod) {
    Scope.RenderArgs.current().put("rootPage", WebPage.rootForLocale());
  }

  private void addWebRoutes(List<WebPage> pages) {
    for (WebPage page : pages) {
      String path = page.path;
      if (path.endsWith("/")) path = substring(path, 0, -1);
      Router.addRoute("GET", path + "/?.*", "Web.serveContent");
    }
  }
}
