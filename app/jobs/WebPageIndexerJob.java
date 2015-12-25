package jobs;

import play.db.jpa.NoTransaction;
import play.jobs.Every;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import util.WebPageIndexer;

@OnApplicationStart(async = true) @Every("6h") @NoTransaction
public class WebPageIndexerJob extends Job {
  private static final WebPageIndexer indexer = WebPageIndexer.getInstance();

  @Override public void doJob() throws Exception {
    if (indexer.shouldIndex())
      indexer.indexWebPages();
  }
}
