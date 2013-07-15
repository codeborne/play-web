package jobs;

import models.WebPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import util.Git;

import java.util.Properties;

@OnApplicationStart(async = true) @NoTransaction
public class NewsNotifier extends Job implements Git.PullListener {
  static Logger logger = LoggerFactory.getLogger(NewsNotifier.class);

  @Override public void doJob() throws Exception {
    Git.pullListener = this;
  }

  @Override public void created(String path) {
    if (!path.endsWith("/metadata.properties")) return;
    path = path.replace("/metadata.properties", "");
    WebPage page = WebPage.forPath("/" + path);
    if (!(page instanceof WebPage.News)) return;
    if (!((WebPage.News)page).isStory()) return;

    newsPublished(path, page);
  }

  private void newsPublished(String path, WebPage page) {
    logger.info("News story published: " + path + ", meta: " + page.metadata);
  }

  public static void main(String[] args) throws Exception {
    Play.configuration = new Properties();
    Play.configuration.put("web.content", "../bspb-web");
    new NewsNotifier().created("news/2013/04/26/metadata.properties");
  }
}
