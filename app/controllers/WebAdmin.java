package controllers;

import models.WebPage;
import play.Logger;
import play.data.validation.Required;
import play.mvc.Catch;
import util.Git;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.apache.commons.lang.StringUtils.defaultIfEmpty;
import static org.apache.commons.lang.StringUtils.split;
import static util.Git.git;
import static util.Git.safePull;

@Check("cms")
public class WebAdmin extends BaseController {
  public static void status() throws IOException, InterruptedException, Git.ExecException {
    git("add", ".");
    String status = git("status", "-s");

    // remove added and later deleted uncommitted files
    for (String line : status.split("\r?\n")) {
      String[] parts = line.split("\\s+", 2);
      if ("AD".equals(parts[0])) // Added/Deleted
        git("rm", "--cached", parts[1]);
    }
    status = git("status", "-s");

    Set<String> unpushed = new HashSet<>(asList(split(git("log", "origin/master..master", "--pretty=format:%h"), "\n")));

    String[] log = git("log", "--pretty=format:%h%x09%ct%x09%an%x09%ae%x09%s%x09%b%x03", "--max-count=50").split("\u0003");
    render(status, log, unpushed);
  }

  @Catch(Git.ExecException.class)
  public static void gitFailure(Git.ExecException e) throws InterruptedException, IOException, Git.ExecException {
    Logger.error("git failed: " + e.code + ": " + e.getMessage());
    flash.error(e.getMessage());
    if (!request.action.equals("WebAdmin.status")) status();
  }

  public static void publish(String message) throws IOException, InterruptedException, Git.ExecException {
    String committed = git("commit", "-a",
        "-m", defaultIfEmpty(message, "no message specified"),
        "--author=" + getUser().customer.getFullName() + " <" + getUser().username + ">");

    flash.put("success", committed);
    push();
  }

  public static void push() throws InterruptedException, IOException, Git.ExecException {
    safePull();
    String push = git("push", "origin", "master");
    flash.put("success", push + "\n" + flash.get("success"));
    status();
  }

  public static void doc() throws IOException {
    Collection<WebPage.Template> templates = WebPage.availableTemplates();
    render(templates);
  }

  public static void addPageDialog(String parentPath) {
    WebPage parent = null;
    if (parentPath != null) {
      parent = WebPage.forPath(parentPath);
      List<WebPage> children = parent.children();
      if (!children.isEmpty()) renderArgs.put("template", children.get(0).metadata.getProperty("template"));
    }
    render(parent);
  }

  public static void addPage(@Required String parentPath, @Required String name) {
    checkAuthenticPost();
    if (validation.hasErrors()) forbidden();
    WebPage page = WebPage.forPath(parentPath + "/" + name);
    // TODO: securely implement
    Web.sitemap();
  }

  public static void saveContent(@Required String path, @Required String part, @Required String html) {
    checkAuthenticPost();
    if (validation.hasErrors()) forbidden();
    WebPage page = WebPage.forPath(path);
    page.dir.child(part).write(html);
    renderText("OK");
  }
}
