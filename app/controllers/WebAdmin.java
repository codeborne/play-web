package controllers;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.net.InetAddresses;
import models.WebPage;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.data.validation.Required;
import play.i18n.Messages;
import play.mvc.*;
import play.rebel.RebelController;
import play.security.AuthenticationService;
import play.security.Secured;
import play.vfs.VirtualFile;
import util.Git.*;

import javax.inject.Inject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static controllers.Web.isAllowed;
import static java.util.Arrays.asList;
import static models.WebPage.ROOT;
import static models.WebPage.canonicalPath;
import static org.apache.commons.io.FileUtils.copyDirectory;
import static org.apache.commons.io.FileUtils.deleteDirectory;
import static org.apache.commons.lang.StringUtils.*;
import static util.Git.*;

@Check("cms")
@Secured
public class WebAdmin extends RebelController {
  private static final Logger logger = LoggerFactory.getLogger(WebAdmin.class);
  private static final Pattern LINKS = Pattern.compile("(href|src)=\"([^\"]*)\"");
  @Inject static AuthenticationService authenticationService;

  public void status() throws IOException, InterruptedException, ExecException {
    git("add", ".");
    String status = git("status", "--porcelain");

    // remove added and later deleted uncommitted files
    for (String line : status.split("\r?\n")) {
      String[] parts = line.split("\\s+", 2);
      if ("AD".equals(parts[0])) // Added/Deleted
        git("rm", "--cached", parts[1]);
    }
    status = git("status", "--porcelain");

    Set<String> unpushed = new HashSet<>(asList(split(git("log", "origin/master..master", "--pretty=format:%h"), "\n")));

    String[] log = git("log", "--pretty=format:%h%x09%ct%x09%an%x09%ae%x09%s%x09%b%x03", "--max-count=50").split("\u0003");
    renderTemplate(ImmutableMap.of("status", status, "log", log, "unpushed", unpushed));
  }

  @Catch(ExecException.class)
  public void gitFailure(ExecException e) {
    logger.error("git failed: " + e.code + ": " + e.getMessage());
    flash.error(e.getMessage());
    if (!"WebAdmin.status".equals(request.action)) {
      redirect("/webadmin/status");
    }
  }

  public void publish(String message, String[] paths) throws Exception {
    checkAuthenticity();
    if (paths == null || paths.length == 0) {
      redirect("/webadmin/status");
    }
    
    validateGitPaths(paths);

    List<String> args = new ArrayList<>(asList("commit",
        "-m", defaultIfEmpty(message, "no message specified"),
        "--author=" + authenticationService.connected() + " <" + authenticationService.connected() + ">"));
    if (paths != null) args.addAll(asList(paths));
    String committed = git(args);

    flash.put("success", committed);
    redirect("/webadmin/push");
  }

  protected static void validateGitPaths(String...paths) {
    for (String path : paths) {
      if (path.startsWith("-") || path.contains(".."))
        forbidden("Invalid paths");
    }
  }

  public void push() throws InterruptedException, IOException, ExecException {
    safePull();
    String push = git("push", "origin", "master");
    flash.put("success", push + "\n" + flash.get("success"));
    redirect("/webadmin/status");
  }

  public void history(String path) throws InterruptedException, IOException, ExecException {
    validateGitPaths(path);
    WebPage page = WebPage.forPath(path);
    List<String> args = new ArrayList<>(asList("log", "--pretty=format:%h%x09%ct%x09%an%x09%ae%x09%s%x09%b%x03", "--max-count=50"));
    if (path.startsWith("/")) path = path.substring(1);
    for (VirtualFile file : page.dir.list()) {
      if (!file.isDirectory()) args.add(path + file.getName());
    }
    String[] log = git(args).split("\u0003");
    renderTemplate(ImmutableMap.of("page", page, "log", log));
  }

  public void diff(String path, String revision) throws InterruptedException, IOException, ExecException {
    validateGitPaths(path);
    WebPage page = WebPage.forPath(path);
    List<String> args = new ArrayList<>(asList("diff", revision));
    if (path.startsWith("/")) path = path.substring(1);
    if (isNotEmpty(path)) {
      args.add("--");
      for (VirtualFile file : page.dir.list()) {
        if (!file.isDirectory()) args.add(path + file.getName());
      }
    }
    String diff = git(args);
    renderTemplate(ImmutableMap.of("page", page, "revision", revision, "diff", diff));
  }

  public void downloadRevision(String path, String revision) throws IOException {
    validateGitPaths(path);
    if (path.startsWith("/")) path = path.substring(1);
    InputStream stream = gitForStream("show", revision + ":" + path);
    renderBinary(stream, FilenameUtils.getName(path), true);
  }

  public void restore(String path, String revision) throws InterruptedException, IOException, ExecException {
    checkAuthenticity();
    validateGitPaths(path);
    WebPage page = WebPage.forPath(path);
    List<String> args = new ArrayList<>(asList("checkout", revision, "--"));
    if (path.startsWith("/")) path = path.substring(1);
    for (VirtualFile file : page.dir.list()) {
      if (!file.isDirectory()) args.add(path + file.getName());
    }
    git(args);
    logger.info("Restored " + page.path + " to " + revision);
    redirect(page.path);
  }

  public void revert(String status, String filePath) throws InterruptedException, IOException, ExecException {
    checkAuthenticity();
    validateGitPaths(filePath);
    if (status.startsWith("A")) git("rm", "-f", filePath);
    else git("checkout", "HEAD", "--", filePath);
    redirect("/webadmin/status");
  }

  public void doc() {
    Collection<WebPage.Template> templates = WebPage.availableTemplates();
    renderTemplate(ImmutableMap.of("templates", templates));
  }

  public void saveContent(@Required String path, @Required String part) throws IOException {
    checkAuthenticity();
    if (validation.hasErrors()) forbidden();
    validateGitPaths(path);
    WebPage page = WebPage.forPath(path);
    try (OutputStream out = page.dir.child(part).outputstream()) {
      IOUtils.copy(request.body, out);
    }
    renderText(Messages.get("web.admin.saved"));
  }

  public void checkLinks(boolean verifyExternal) {
    List<String> problems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    Multimap<String, String> externals = LinkedListMultimap.create();

    for (WebPage page : WebPage.all()) {
      for (Map.Entry<String, String> part : page.contentParts().entrySet()) {
        String name = part.getKey();
        Matcher links = LINKS.matcher(part.getValue());

        while (links.find()) {
          String url = links.group(2);
          url = url.replaceFirst("#.*$", "");
          url = url.replace("+", " ");

          if (isEmpty(url)) continue;

          try {
            if (url.startsWith("http:") || url.startsWith("https:"))
              externals.put(url, page.path + name + ".html");
            else if (url.startsWith("javascript:"))
              warnings.add(page.path + name + ".html - " + url);
            else if (url.startsWith("/public"))
              verifyPublicURL(url);
            else if (!url.startsWith("mailto:") && !url.startsWith("cryptmail:") && !url.startsWith("tel:")) {
              url = url.replaceFirst("\\?.*$", "");

              Map<String, String> route = Router.route("GET", url);

              if (route.isEmpty() && url.startsWith("/")) {
                verifyURL(ROOT, url);
              }
              else if (route.isEmpty()) {
                verifyURL(page, url);
              }
              else if ("Web.redirectAlias".equals(route.get("action"))) {
                fixRedirectAlias(route, page, name, url);
              }
            }
          }
          catch (Exception e) {
            problems.add(page.path + name + ".html - " + e);
          }
        }
      }
    }

    if (verifyExternal) {
      for (String url : externals.keySet()) {
        if (isSafeToScan(url)) {
          try {
            verifyExternalURL(url);
          }
          catch (Exception e) {
            for (String page : externals.get(url)) problems.add(page + " - " + url + " [" + e.getMessage() + "]");
          }
        }
        else {
          for (String page : externals.get(url)) warnings.add(page + " - " + url);
        }
      }
    }

    renderTemplate(ImmutableMap.of("problems", problems, "warnings", warnings, "externals", externals));
  }

  private void verifyPublicURL(String url) throws IOException {
    if (!Play.getVirtualFile(url).exists()) throw new FileNotFoundException(url);
  }

  private void verifyURL(WebPage page, String url) throws FileNotFoundException {
    VirtualFile file = page.dir.child(url);
    if (!file.exists()) throw new FileNotFoundException(url);
  }

  private void fixRedirectAlias(Map<String, String> route, WebPage page, String name, String url) throws IOException {
    VirtualFile file = page.dir.child(name + ".html");
    String html = file.contentAsString();
    html = html.replace("\"" + url + "\"", "\"" + route.get("path") + "\"");
    file.write(html);
    throw new IOException("Fixed link " + url + " to " + route.get("path"));
  }

  private void verifyExternalURL(String url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    try {
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      connection.setRequestMethod("HEAD");
      int status = connection.getResponseCode();
      if (status < 200 || status > 399)
        throw new IOException("status: " + status);
    }
    finally {
      connection.disconnect();
    }
  }

  @Util static boolean isSafeToScan(String url) {
    try {
      URI uri = URI.create(url);
      if ("localhost".equals(uri.getHost())) return false;
      if (uri.getHost().endsWith(".local")) return false;
      if (uri.getPort() >= 0) return false;
      return !InetAddresses.isInetAddress(uri.getHost());
    }
    catch (Exception e) {
      return false;
    }
  }

  public void browse(String path) throws MalformedURLException {
    if (isEmpty(path)) {
      Http.Header referer = request.headers.get("referer");
      path = referer != null ? new URL(referer.value()).getPath() : "/";
      request.querystring += "&path=" + path;
      redirect(Router.reverse("WebAdmin.browse").url + "?" + request.querystring);
    }
    WebPage page = WebPage.forPath(path);
    List<VirtualFile> files = page.dir.list();
    files.removeIf(file -> !file.isDirectory() && !isAllowed(file));
    renderTemplate(ImmutableMap.of("page", page, "files", files));
  }

  public void upload(String path, File data) throws Throwable {
    checkAuthenticity();
    WebPage page = WebPage.forPath(path);
    VirtualFile file = page.dir.child(data.getName());
    checkFileBelongsToCmsContentRoot(file);
    try (InputStream in = new FileInputStream(data)) {
      try (OutputStream out = file.outputstream()) {
        IOUtils.copy(in, out);
      }
    }
    if (!request.querystring.contains("path=")) request.querystring += "&path=" + path;
    redirect(Router.reverse("WebAdmin.browse").url + "?" + request.querystring);
  }

  public void delete(@Required String path, @Required String name, String redirectTo) throws Throwable {
    checkAuthenticity();
    if (validation.hasErrors()) forbidden(validation.errorsMap().toString());
    WebPage page = WebPage.forPath(path);
    VirtualFile file = page.dir.child(name);
    checkFileBelongsToCmsContentRoot(file);
    file.getRealFile().delete();
    if (redirectTo != null) redirect(redirectTo);
    if (!request.querystring.contains("path=")) request.querystring += "&path=" + path;
    redirect(Router.reverse("WebAdmin.browse").url + "?" + request.querystring);
  }

  private static void checkFileBelongsToCmsContentRoot(VirtualFile file) {
    if (!canonicalPath(file.getRealFile()).startsWith(canonicalPath(ROOT.dir.getRealFile()))) forbidden("Access denied");
  }

  public void addPageDialog(String parentPath, String redirectTo) {
    WebPage parent = null;
    if (parentPath != null) {
      parent = WebPage.forPath(parentPath);
      List<WebPage> children = parent.children();
      if (!children.isEmpty()) {
        renderArgs.put("template", children.get(0).metadata.getProperty("template"));
      }
    }
    renderArgs.put("parent", parent);
    renderArgs.put("redirectTo", redirectTo);
    render();
  }

  public void addPage(@Required String parentPath, @Required String title, @Required String name, @Required String template, String redirectTo) {
    checkAuthenticity();
    if (validation.hasErrors()) forbidden(validation.errorsMap().toString());
    WebPage page = WebPage.forPath(parentPath + name);
    if (page.dir.exists()) forbidden();
    page.dir.getRealFile().mkdirs();
    page.dir.child("metadata.properties").write("title: " + title + "\ntemplate: " + template + "\n");
    redirect(defaultIfEmpty(redirectTo, page.path));
  }

  public void addNewsDialog() {
    render();
  }

  public void addNews(@Required String path, @Required String title, @Required Date date, String tags) {
    checkAuthenticity();
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
    vdir.child("content.html").write(Messages.get("web.admin.defaultContent"));

    WebPage.News page = WebPage.forPath(vdir);
    redirect(page.path);
  }

  public void addFileDialog(String path, String redirectTo) {
    WebPage page = WebPage.forPath(path);
    renderArgs.put("page", page);
    renderArgs.put("redirectTo", redirectTo);
    render();
  }

  public void addFile(@Required String path, @Required String name, @Required String title, String redirectTo) {
    checkAuthenticity();
    if (validation.hasErrors()) forbidden(validation.errorsMap().toString());
    WebPage page = WebPage.forPath(path);
    name = name.replaceAll("\\W", "");
    String content = defaultContent(defaultString(redirectTo, page.path));
    page.dir.child(name + ".html").write("<h3>" + title + "</h3>\n\n" + content);
    redirect(defaultIfEmpty(redirectTo, page.path));
  }

  private static String defaultContent(String path) {
    WebPage page = WebPage.forPath(path);
    return page.dir.child("template.html").exists() ? page.dir.child("template.html").contentAsString() : Messages.get("web.admin.defaultContent");
  }

  public void metadataDialog(String path) {
    WebPage page = WebPage.forPath(path);
    List<WebPage.Template> templates = WebPage.availableTemplates();
    renderArgs.put("page", page);
    renderArgs.put("templates", templates);
    render();
  }

  public void saveMetadata(@Required String path) throws IOException {
    checkAuthenticity();
    if (validation.hasErrors()) forbidden(validation.errorsMap().toString());
    WebPage page = WebPage.forPath(path);

    Map<String, String> allParams = params.allSimple();
    allParams.keySet().removeAll(asList("path", "body", "authenticityToken", "action"));
    page.metadata.putAll(allParams);

    if (!allParams.containsKey("hidden")) page.metadata.remove("hidden");
    for (String key : page.metadata.stringPropertyNames()) {
      if (isEmpty(page.metadata.getProperty(key))) page.metadata.remove(key);
    }

    try (Writer out = new OutputStreamWriter(page.dir.child("metadata.properties").outputstream(), "UTF-8")) {
      for (String key : page.metadata.stringPropertyNames()) {
        out.write(key + ": " + page.metadata.getProperty(key).replace("\n", "\\n") + "\n");
      }
    }
    redirect(page.path);
  }

  public void copyPage(String path, String name) throws IOException {
    checkAuthenticity();
    WebPage page = WebPage.forPath(path);
    copyDirectory(page.dir.getRealFile(), new File(page.dir.getRealFile().getParentFile(), name));
    redirect(page.parent().path + name);
  }

  public void deletePage(String path) throws IOException {
    checkAuthenticity();
    WebPage page = WebPage.forPath(path);
    deleteDirectory(page.dir.getRealFile());
    redirect(page.parent().path);
  }
}
