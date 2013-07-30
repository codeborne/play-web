package controllers;

import com.google.common.base.Predicate;
import models.WebPage;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import play.Logger;
import play.Play;
import play.data.validation.Required;
import play.libs.WS;
import play.mvc.Catch;
import play.mvc.Router;
import play.vfs.VirtualFile;
import util.Git;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.Collections2.filter;
import static controllers.Web.isAllowed;
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

  public static void publish(String message, String[] paths) throws IOException, InterruptedException, Git.ExecException {
    if (paths == null || paths.length == 0) status();

    List<String> args = new ArrayList<>(asList("commit",
        "-m", defaultIfEmpty(message, "no message specified"),
        "--author=" + getUser().customer.getFullName() + " <" + getUser().username + ">"));
    args.addAll(asList(paths));
    String committed = git(args.toArray(new String[args.size()]));

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

  public static void saveContent(@Required String path, @Required String part) throws IOException {
    checkAuthenticPost();
    if (validation.hasErrors()) forbidden();
    WebPage page = WebPage.forPath(path);
    try (OutputStream out = page.dir.child(part).outputstream()) {
      IOUtils.copy(request.body, out);
    }
    renderText(play.i18n.Messages.get("web.admin.saved"));
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
          url = url.replaceFirst("#.*$", "");
          url = url.replace("+", " ");
          if (isEmpty(url)) continue;

          try {
            if (url.startsWith("/public")) { checkPublic(url); continue; }
            else if (url.startsWith("mailto:")) continue;
            else if (url.startsWith("http:") || url.startsWith("https:")) {
              if (checkExternal) checkExternal(url);
              continue;
            }

            url = url.replaceFirst("\\?.*$", "");
            if (fixIfAlias(page, part.getKey(), url)) continue;
            else if (url.startsWith("/")) checkAbsolute(url);
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
    try {
      WS.HttpResponse response = WS.url(url).timeout("5s").get();
      int status = response.getStatus();
      if (status != 200 && status != 301 && status != 302)
        throw new IOException(url + " - " + status + ": " + response.getStatusText());
    }
    catch (RuntimeException e) {
      throw new IOException(url, e);
    }
  }

  private static void checkAbsolute(String url) throws FileNotFoundException {
    checkRelative(WebPage.ROOT, url);
  }

  private static void checkRelative(WebPage page, String url) throws FileNotFoundException {
    VirtualFile file = page.dir.child(url);
    if (!file.exists())
      throw new FileNotFoundException(url);
  }

  public static void browse(String path) throws MalformedURLException {
    if (isEmpty(path)) {
      path = new URL(request.headers.get("referer").value()).getPath();
      request.querystring += "&path=" + path;
      redirect(Router.reverse("WebAdmin.browse").url + "?" + request.querystring);
    }
    WebPage page = WebPage.forPath(path);
    List<VirtualFile> list = page.dir.list();
    Collection<VirtualFile> files = filter(list, new Predicate<VirtualFile>() {
      @Override public boolean apply(VirtualFile file) {
        return file.isDirectory() || isAllowed(file);
      }
    });
    render(page, files);
  }

  public static void upload(String path, File data) throws Throwable {
    checkAuthenticity();
    WebPage page = WebPage.forPath(path);
    VirtualFile file = page.dir.child(data.getName());
    try (InputStream in = new FileInputStream(data)) {
      try (OutputStream out = file.outputstream()) {
        IOUtils.copy(in, out);
      }
    }
    if (!request.querystring.contains("path=")) request.querystring += "&path=" + path;
    redirect(Router.reverse("WebAdmin.browse").url + "?" + request.querystring);
  }

  public static void delete(@Required String path, @Required String name, boolean redirectToPath) throws Throwable {
    checkAuthenticity();
    if (validation.hasErrors()) forbidden(validation.errorsMap().toString());
    WebPage page = WebPage.forPath(path);
    VirtualFile file = page.dir.child(name);
    file.getRealFile().delete();
    if (redirectToPath) redirect(path);
    if (!request.querystring.contains("path=")) request.querystring += "&path=" + path;
    redirect(Router.reverse("WebAdmin.browse").url + "?" + request.querystring);
  }

  public static void addPageDialog(String parentPath, String redirectTo) {
    WebPage parent = null;
    if (parentPath != null) {
      parent = WebPage.forPath(parentPath);
      List<WebPage> children = parent.children();
      if (!children.isEmpty()) renderArgs.put("template", children.get(0).metadata.getProperty("template"));
    }
    render(parent, redirectTo);
  }

  public static void addPage(@Required String parentPath, @Required String title, @Required String name, @Required String template, String redirectTo) {
    checkAuthenticPost();
    if (validation.hasErrors()) forbidden(validation.errorsMap().toString());
    WebPage page = WebPage.forPath(parentPath + name);
    if (page.dir.exists()) forbidden();
    page.dir.getRealFile().mkdirs();
    page.dir.child("metadata.properties").write("title: " + title + "\ntemplate: " + template + "\n");
    redirect(defaultIfEmpty(redirectTo, page.path));
  }

  public static void addNewsDialog() {
    render();
  }

  public static void addNews(@Required String path, @Required String title, @Required Date date, String tags) {
    checkAuthenticPost();
    if (validation.hasErrors()) forbidden(validation.errorsMap().toString());
    WebPage.News parent = WebPage.forPath(path);
    if (parent.isStory()) parent = (WebPage.News) parent.parent();
    if (parent.isMonth()) parent = (WebPage.News) parent.parent();
    if (parent.isYear()) parent = (WebPage.News) parent.parent();

    String pathSuffix = new SimpleDateFormat("yyyy/MM/dd").format(date);
    File dir = new File(parent.dir.getRealFile(), pathSuffix);
    while (dir.exists()) dir = new File(dir.getPath() + "-1");
    dir.mkdirs();

    VirtualFile vdir = VirtualFile.open(dir);
    vdir.child("metadata.properties").write("title: " + title + "\ntags: " + defaultString(tags) + "\n");
    vdir.child("content.html").write(play.i18n.Messages.get("web.admin.defaultContent"));

    WebPage.News page = WebPage.forPath(vdir);
    redirect(page.path);
  }

  public static void addFileDialog(String path, String redirectTo) {
    WebPage page = WebPage.forPath(path);
    render(page, redirectTo);
  }

  public static void addFile(@Required String path, @Required String name, @Required String title, String redirectTo) {
    checkAuthenticPost();
    if (validation.hasErrors()) forbidden(validation.errorsMap().toString());
    WebPage page = WebPage.forPath(path);
    name = name.replaceAll("\\W", "");
    String content = defaultContent(defaultString(redirectTo, page.path));
    page.dir.child(name + ".html").write("<h3>" + title + "</h3>\n\n" + content);
    redirect(defaultIfEmpty(redirectTo, page.path));
  }

  private static String defaultContent(String path) {
    WebPage page = WebPage.forPath(path);
    return page.dir.child("template.html").exists() ? page.dir.child("template.html").contentAsString() : play.i18n.Messages.get("web.admin.defaultContent");
  }

  public static void metadataDialog(String path) {
    WebPage page = WebPage.forPath(path);
    render(page);
  }

  public static void saveMetadata(@Required String path, @Required String title, String tags, String description, String keywords, String order, String alias, boolean hidden) throws IOException {
    checkAuthenticPost();
    if (validation.hasErrors()) forbidden(validation.errorsMap().toString());
    WebPage page = WebPage.forPath(path);
    page.metadata.setProperty("title", title);
    if (isNotEmpty(tags)) page.metadata.setProperty("tags", defaultString(tags)); else page.metadata.remove("tags");
    if (isNotEmpty(description)) page.metadata.setProperty("description", defaultString(description)); else page.metadata.remove("description");
    if (isNotEmpty(keywords)) page.metadata.setProperty("keywords", defaultString(keywords)); else page.metadata.remove("keywords");
    if (isNotEmpty(order)) page.metadata.setProperty("order", order); else page.metadata.remove("order");
    if (isNotEmpty(alias)) page.metadata.setProperty("alias", alias); else page.metadata.remove("alias");
    if (hidden) page.metadata.setProperty("hidden", "true"); else page.metadata.remove("hidden");

    try (Writer out = new OutputStreamWriter(page.dir.child("metadata.properties").outputstream(), "UTF-8")) {
      for (String key : page.metadata.stringPropertyNames()) {
        out.write(key + ": " + page.metadata.getProperty(key).replace("\n", "\\n") + "\n");
      }
    }
    redirect(page.path);
  }

  public static void copyPage(String path, String name) throws IOException {
    checkAuthenticity();
    WebPage page = WebPage.forPath(path);
    FileUtils.copyDirectory(page.dir.getRealFile(), new File(page.dir.getRealFile().getParentFile(), name));
    redirect(page.parent().path + name);
  }
}
