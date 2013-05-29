package util;

import models.WebPage;
import org.apache.commons.io.IOUtils;
import play.Play;

import java.io.IOException;
import java.io.InputStreamReader;

public class Git {
  public static synchronized String git(String ... command) throws IOException, InterruptedException, ExecException {
    String[] cmdLine = new String[command.length + 1];
    cmdLine[0] = "git";
    System.arraycopy(command, 0, cmdLine, 1, command.length);
    return exec(cmdLine);
  }

  static String exec(String ... cmdLine) throws IOException, ExecException, InterruptedException {
    Process proc = new ProcessBuilder(cmdLine).directory(WebPage.ROOT.dir.getRealFile()).redirectErrorStream(true).start();
    String out = IOUtils.toString(new InputStreamReader(proc.getInputStream()));
    int status = proc.waitFor();
    if (status != 0) {
      throw new ExecException(status, out);
    }
    return out;
  }

  public static String safePull() throws InterruptedException, IOException, ExecException {
    String mergeStrategy = Play.id.equals("itest") ? "-Xours" : "--ff-only";

    try {
      exec("find", "-name", ".DAV", "-exec", "rm", "-fr", "{}", ";");
    }
    catch (ExecException ignore) {}

    String pull = git("pull", mergeStrategy, "origin", "master");

    try {
      exec("chmod", "-Rf", "g+w", ".");
    }
    catch (ExecException ignore) {}

    return pull;
  }

  public static class ExecException extends Exception {
    public int code;

    public ExecException(int code, String message) {
      super(message);
      this.code = code;
    }
  }
}
