package util;

import models.WebPage;
import org.apache.commons.io.IOUtils;
import play.Play;

import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;

public class Git {
  public static PullListener pullListener;

  public static synchronized String git(String ... cmdLine) throws IOException, InterruptedException, ExecException {
    return exec(addExecutable(cmdLine));
  }

  public static synchronized InputStream gitForStream(String ... cmdLine) throws IOException {
    return execProc(addExecutable(cmdLine)).getInputStream();
  }

  private static String[] addExecutable(String[] command) {
    String[] cmdLine = new String[command.length + 1];
    cmdLine[0] = "git";
    System.arraycopy(command, 0, cmdLine, 1, command.length);
    return cmdLine;
  }

  static Process execProc(String ... cmdLine) throws IOException {
    return new ProcessBuilder(cmdLine).directory(WebPage.ROOT.dir.getRealFile()).redirectErrorStream(true).start();
  }

  static String exec(String ... cmdLine) throws IOException, ExecException, InterruptedException {
    Process proc = execProc(cmdLine);
    try (InputStream in = proc.getInputStream()) {
      String out = IOUtils.toString(in);
      int status = proc.waitFor();
      if (status != 0)
        throw new ExecException(status, out);
      return out;
    }
  }

  public static String safePull() throws InterruptedException, IOException, ExecException {
    String mergeStrategy = "cms".equals(Play.id) ? "-Xours" : "--ff-only";

    try {
      exec("find", "-name", ".DAV", "-exec", "rm", "-fr", "{}", ";");
    }
    catch (ExecException ignore) {}

    String pull = git("pull", mergeStrategy, "origin", "master");
    if (pull.contains("Already up-to-date")) pull = "";

    try {
      exec("chmod", "-Rf", "g+w", ".");
    }
    catch (ExecException ignore) {}

    try {
      exec("chgrp", "-Rf", Play.configuration.getProperty("web.pull.group", "apache"), ".");
    }
    catch (ExecException ignore) {}

    notifyListener(pull);
    return pull;
  }

  private static void notifyListener(String pull) {
    if (pullListener == null) return;

    try (Scanner lines = new Scanner(pull)) {
      while (lines.hasNextLine()) {
        String line = lines.nextLine();
        if (line.startsWith(" create mode "))
          pullListener.created(line.substring(" create mode 100644 ".length()));
      }
    }
  }

  public static class ExecException extends Exception {
    public final int code;

    public ExecException(int code, String message) {
      super(message);
      this.code = code;
    }
  }

  public interface PullListener {
    void created(String path);
  }
}
