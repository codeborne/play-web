package controllers;

import models.WebPage;
import play.Logger;
import play.Play;
import play.data.validation.Required;
import play.libs.WS;
import play.mvc.Catch;
import play.mvc.Router;
import play.vfs.VirtualFile;
import util.Git;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static org.apache.commons.lang.StringUtils.*;
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

  public static void checkLinks(boolean checkExternal) {
    final Pattern links = Pattern.compile("(href|src)=\"([^\"]*)\"");
    List<String> problems = new ArrayList<>();

    for (WebPage page : WebPage.all()) {
      for (Map.Entry<String, String> part : page.contentParts().entrySet()) {
        String html = part.getValue();
        Matcher m = links.matcher(html);
        while (m.find()) {
          String url = m.group(2);
          url = url.replaceFirst("[#\\?].*$", "");
          url = url.replace("+", " ");
          if (isEmpty(url)) continue;

          try {
            if (url.startsWith("/public")) checkPublic(url);
            else if (fixIfAlias(page, part.getKey(), url)) continue;
            else if (url.startsWith("/")) checkAbsolute(url);
            else if (url.startsWith("mailto:")) continue;
            else if (url.startsWith("http:") || url.startsWith("https:"))
              if (checkExternal) checkExternal(url); else continue;
            else checkRelative(page, url);
          }
          catch (Exception e) {
            problems.add(page.path + part.getKey() + ".html - " + e.toString());
          }

          // TODO: validate metadata
        }
      }
    }
    render(problems);
  }

  private static void checkPublic(String url) throws IOException {
    if (!Play.getVirtualFile(url).exists())
      throw new FileNotFoundException(url);
  }

  private static boolean fixIfAlias(WebPage page, String name, String url) throws IOException {
    Map<String, String> args = Router.route("GET", url);
    if ("Web.redirectAlias".equals(args.get("action"))) {
      VirtualFile file = page.dir.child(name + ".html");
      String html = file.contentAsString();
      html = html.replace("\"" + url + "\"", "\"" + args.get("path") + "\"");
      file.write(html);
      throw new IOException("Fixed link " + url + " to " + args.get("path"));
    }
    return !args.isEmpty();
  }

  private static void checkExternal(String url) throws IOException {
    WS.HttpResponse response = WS.url(url).timeout("5s").get();
    int status = response.getStatus();
    if (status != 200 && status != 301 && status != 302)
      throw new IOException(url + " - " + status + ": " + response.getStatusText());
  }

  private static void checkAbsolute(String url) throws FileNotFoundException {
    checkRelative(WebPage.ROOT, url);
  }

  private static void checkRelative(WebPage page, String url) throws FileNotFoundException {
    VirtualFile file = page.dir.child(url);
    if (!file.exists())
      throw new FileNotFoundException(url);
  }
}
