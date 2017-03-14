package jobs;

import org.junit.Test;
import util.Git;

import static org.junit.Assert.*;

public class WebContentPullTest {
  WebContentPull job = new WebContentPull();

  @Test
  public void shortensErrorMessage() {
    assertEquals("util.Git$ExecException: ssh: connect to host git.bspb.ru port 22: Network is unreachable",
        job.shortenErrorMessage(new Git.ExecException(1, "ssh: connect to host git.bspb.ru port 22: Network is unreachable\n" +
            "fatal: Could not read from remote repository.\n" +
            "\n" +
            "Please make sure you have the correct access rights\n" +
            "and the repository exists.")));

  }
}