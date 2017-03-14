package jobs;

import models.WebPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.On;
import util.Git;

import static org.apache.commons.lang.StringUtils.isNotEmpty;

@On("cron.webContentPull") @NoTransaction
public class WebContentPull extends Job {
  private static final Logger logger = LoggerFactory.getLogger(WebContentPull.class);

  private boolean firstRun = true;
  
  @Override public void doJob() throws Exception {
    if (Play.runingInTestMode() || !WebPage.ROOT.dir.child(".git").exists()) return;
    try {
      String result = Git.safePull();
      if (isNotEmpty(result))
        logger.info("Pulled content: " + result);
    }
    catch (Git.ExecException failedToPull) {
      if (firstRun) {
        logger.error("Failed to update " + WebPage.ROOT.dir, failedToPull);
        firstRun = false;
      }
      else {
        logger.warn("Failed to update {}: {}", WebPage.ROOT.dir,
            shortenErrorMessage(failedToPull));
      }
    }
  }

  String shortenErrorMessage(Git.ExecException failedToPull) {
    return failedToPull.toString().substring(0, failedToPull.toString().indexOf('\n')).trim();
  }
}
