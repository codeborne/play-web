package models;

import com.google.common.base.Predicate;
import play.Logger;
import play.Play;
import play.i18n.Lang;
import play.templates.JavaExtensions;
import play.vfs.VirtualFile;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.Collections2.filter;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Lists.newArrayList;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.reverse;
import static java.util.Collections.sort;
import static org.apache.commons.lang.StringUtils.*;
import static play.libs.Codec.byteToHexString;

public class WebPage implements Serializable, Comparable<WebPage> {
  public static final Set<String> ALLOWED_FILE_TYPES = new HashSet<>(asList(Play.configuration.getProperty("web.downloadable.files", "png,jpg,gif,pdf,rtf,swf,mp3,flv,zip").split("\\s*,\\s*")));
  public static final String BOM = new String(new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF});

  public static WebPage ROOT = new WebPage();
  public static WebPage ROOT_EN = forPath("/en/");

  public String path;
  public VirtualFile dir;
  public int level;
  public Properties metadata = new Properties();
  public String title;
  public String template;
  public int order;

  /** ROOT */
  WebPage() {
    this.dir = VirtualFile.open(canonicalPath(Play.getFile(Play.configuration.getProperty("web.content", "web-content"))));
    this.path = "/";
  }

  WebPage(VirtualFile dir, String path) {
    this.dir = dir;
    this.path = path.endsWith("/") ? path : path + "/";
    this.level = countMatches(this.path, "/") - 1;

    loadMetadata();
    title = metadata.getProperty("title");
    if (isEmpty(title)) title = generateTitle();
    template = metadata.getProperty("template", "custom");

    try {
      order = parseInt(metadata.getProperty("order", "99"));
    }
    catch (NumberFormatException e) {
      order = 99;
    }
  }

  protected String generateTitle() {
    return dir.getName();
  }

  public static WebPage rootForLocale() {
    return Lang.get().equals("ru") ? ROOT : ROOT_EN;
  }

  public static VirtualFile toVirtualFile(String path) {
    VirtualFile file = ROOT.dir.child(path);
    if (!file.exists()) file = resolveLinkedPages(file);
    if (!canonicalPath(file.getRealFile()).startsWith(ROOT.dir.getRealFile().getPath()))
      throw new SecurityException("Access denied");
    return file;
  }

  private static VirtualFile resolveLinkedPages(VirtualFile file) {
    File parent = file.getRealFile();
    while (!parent.exists()) parent = parent.getParentFile();

    WebPage existingParent = forPath(VirtualFile.open(parent));
    String contentFrom = existingParent.metadata.getProperty("contentFrom");
    if (isEmpty(contentFrom)) return file;

    WebPage contentParent = forPath(contentFrom);
    return VirtualFile.open(new File(contentParent.dir.getRealFile(), file.getRealFile().getPath().replace(parent.getPath(), "")));
  }

  public static <P extends WebPage> P forPath(String path) {
    return forPath(toVirtualFile(path), path);
  }

  @SuppressWarnings("unchecked")
  static <P extends WebPage> P forPath(VirtualFile dir, String path) {
    if (path.contains("/news") || path.startsWith("/analytics")) return (P)new News(dir, path);
    else return (P)new WebPage(dir, path);
  }

  public static <P extends WebPage> P forPath(VirtualFile dir) {
    String path = dir.getRealFile().getPath().replace(ROOT.dir.getRealFile().getPath(), "").replace('\\', '/');
    return forPath(dir, path);
  }

  public List<WebPage> children() {
    List<WebPage> children = new ArrayList<>();
    if (metadata.getProperty("contentFrom") != null) {
      for (WebPage child : forPath(metadata.getProperty("contentFrom")).children()) {
        child.path = path + child.dir.getName() + "/";
        children.add(child);
      }
    }

    for (VirtualFile entry : dir.list()) {
      if (entry.isDirectory() && !entry.getName().startsWith(".") && !entry.equals(ROOT_EN.dir)) {
        WebPage child = forPath(entry);
        if (child instanceof News || !child.metadata.isEmpty())
          children.add(child);
      }
    }
    sort(children);
    return children;
  }

  public WebPage parent() {
    return path.equals(ROOT.path) ? null : forPath(path.substring(0, path.lastIndexOf('/', path.length() - 2)));
  }

  public String topParentName() {
    return path.substring(1, path.indexOf("/", 1));
  }

  public String loadFile(String filename) {
    return dir.child(filename).contentAsString();
  }

  private void loadMetadata() {
    VirtualFile metaFile = dir.child("metadata.properties");
    if (metaFile.exists()) {
      try (Reader reader = new InputStreamReader(metaFile.inputstream(), "UTF-8")) {
        metadata.load(reader);
      }
      catch (IOException e) {
        Logger.error("Cannot load " + metaFile, e);
      }
    }
  }

  public Map<String, String> contentParts() {
    if ("true".equals(metadata.getProperty("contentFromNewestChild")))
      return contentPartsFromAnotherPage(getLast(children()));
    else if (isNotEmpty(metadata.getProperty("contentFrom")))
      return contentPartsFromAnotherPage(forPath(metadata.getProperty("contentFrom")));

    Map<String, String> parts = new HashMap<>();
    for (VirtualFile file : dir.list()) {
      if (file.getName().endsWith(".html")) {
        String name = substring(file.getName(), 0, -5);
        parts.put(name, "<div class=\"" + name + " editable\">" + processContent(file.contentAsString()) + "</div>");
      }
    }
    return parts;
  }

  private Map<String, String> contentPartsFromAnotherPage(WebPage page) {
    template = page.template;
    Map<String, String> parts = page.contentParts();
    for (String key : parts.keySet()) {
      // fix images and links
      parts.put(key, parts.get(key).replaceAll("(src|href)=\"([^/:]+?)\"", "$1=\"" + page.path + "$2\""));
    }
    return parts;
  }

  private static String safeUrlEncode(String url) {
    try {
      byte[] bytes = url.getBytes("UTF-8");
      StringBuilder sb = new StringBuilder(url.length() * 2);
      for (byte b : bytes) {
        if (b == ' ') sb.append('+');
        else if ((b & 0xFF) < 0x7F) sb.append((char) b);
        else sb.append(String.format("%%%02X", b));
      }
      return sb.toString();
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  String processContent(String content) {
    Pattern linkPattern = Pattern.compile("<a([^>]*?)href=\"([^\"]+?)\"([^>]*?)>([^<>]+?)</a>", Pattern.DOTALL);
    Matcher m = linkPattern.matcher(removeBOM(content));
    StringBuffer result = new StringBuffer();
    while (m.find()) {
      String filename = m.group(2);
      String filetype = filename.substring(filename.lastIndexOf('.') + 1);
      if (filename.startsWith("http")) {
        m.appendReplacement(result, "<a$1class=\"external\" href=\"$2\"$3>$4</a>");
      }
      else if (filename.startsWith("mailto:")) {
        String email = m.group(2).replace("mailto:", "");
        String href = byteToHexString(email.getBytes());
        String text = m.group(4);
        if (text.equals(email)) text = href;
        m.appendReplacement(result, "<a$1class=\"email\" href=\"cryptmail:" + href + "\"$3>" + text + "</a>");
      }
      else if (filename.contains("://") || !ALLOWED_FILE_TYPES.contains(filetype))
        m.appendReplacement(result, "<a$1href=\"" + safeUrlEncode(m.group(2)) + "\"$3>$4</a>");
      else {
        VirtualFile file = (filename.startsWith("/") ? ROOT.dir : dir).child(filename);
        double lengthKb = file.length() / 1024.0;
        String size = lengthKb > 1024 ? format("%.1f Mb", lengthKb / 1024) : format("%.0f Kb", lengthKb);
        m.appendReplacement(result, "<a$1class=\"download " + filetype + (file.exists() ? "" : " unavailable") + "\" href=\"" + (filename.startsWith("/") ? "" : path) + safeUrlEncode(m.group(2)) + "\"$3>" +
            "$4 (" + filetype.toUpperCase() + ", " + size + ")</a>");
      }
    }
    m.appendTail(result);
    return result.toString();
  }

  @Override public int compareTo(WebPage that) {
    int result = this.order - that.order;
    if (result == 0) result = this.path.compareTo(that.path);
    return result;
  }

  public static List<Template> availableTemplates() throws IOException {
    List<Template> templates = new ArrayList<>();
    for (VirtualFile file : Play.templatesPath.get(0).child("Web/templates").list()) {
      templates.add(new Template(file));
    }
    sort(templates);
    return templates;
  }

  @Override public boolean equals(Object obj) {
    if (obj instanceof WebPage) {
      WebPage webPage = (WebPage) obj;
      return path.equals(webPage.path);
    }
    return false;
  }

  @Override public int hashCode() {
    return path.hashCode();
  }

  @Override public String toString() {
    return getClass().getSimpleName() + "[" + path + "]";
  }

  public static class Template implements Comparable<Template> {
    public String name;
    public String description;
    public List<String> parts = new ArrayList<>();

    static Pattern partsPattern = Pattern.compile("#\\{get\\s+'(.+?)'");
    static Pattern extendsPattern = Pattern.compile("#\\{extends\\s+'(.+?)'");

    public Template(VirtualFile file) {
      name = file.getName().replace(".html", "");
      addPartsOf(file);
    }

    private void addPartsOf(VirtualFile file) {
      String source = file.contentAsString();

      Matcher m = extendsPattern.matcher(source);
      while (m.find()) {
        String extendsFile = m.group(1);
        if (extendsFile.startsWith("Web/"))
          addPartsOf(Play.templatesPath.get(0).child(extendsFile));
      }

      m = partsPattern.matcher(source);
      while (m.find()) {
        parts.add(m.group(1));
      }
    }

    @Override public int compareTo(Template that) {
      return name.compareTo(that.name);
    }
  }

  public List<WebPage> parents() {
    List<WebPage> structure = new ArrayList<>();
    WebPage current = this;
    while (current.level > 1) {
      current = current.parent();
      if (current.equals(WebPage.ROOT_EN)) continue;
      structure.add(current);
    }
    reverse(structure);
    return structure;
  }

  public static List<WebPage> all() {
    List<WebPage> pages = WebPage.ROOT.childrenRecursively();
    addChildrenRecursively(pages, WebPage.ROOT_EN);
    return pages;
  }

  public List<WebPage> childrenRecursively() {
    List<WebPage> pages = new ArrayList<>();
    addChildrenRecursively(pages, this);
    return pages;
  }

  public Date date() {
    return new Date(dir.lastModified());
  }

  private static void addChildrenRecursively(List<WebPage> pages, WebPage page) {
    for (WebPage child : page.children()) {
      pages.add(child);
      addChildrenRecursively(pages, child);
    }
  }

  static String canonicalPath(File file) {
    try {
      return file.getCanonicalPath();
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static String removeTags(String s) {
    return s.replaceAll("<br\\s*/?>", "\n").replaceAll("</?.+?>", "");
  }

  static String removeBOM(String content) {
    return content.replace(BOM, "");
  }

  public static class News extends WebPage {
    public News(VirtualFile dir, String path) {
      super(dir, path);
    }

    protected String generateTitle() {
      if (isMonth()) return JavaExtensions.format(date(), "MMMM");
      else return super.generateTitle();
    }

    public Date date() {
      String path = this.path.replaceFirst("^.*/(news|analytics)/", "");
      if (path.endsWith("/")) path = path.substring(0, path.length()-1);
      if (path.lastIndexOf('-') > path.length() - 3) path = path.substring(0, path.lastIndexOf('-')); // remove trailing '-' from dates, eg 2013/05/03-2
      try {
        return new SimpleDateFormat("yyyy/MM/dd").parse(path);
      }
      catch (ParseException e) {
        try {
          return new SimpleDateFormat("yyyy/MM").parse(path);
        }
        catch (ParseException ignore) {
        }
      }
      return super.date();
    }

    public boolean isStory() {
      return path.matches(".*/\\d{4}/\\d{2}/[^/]+/");
    }

    public boolean isMonth() {
      return path.matches(".*/\\d{4}/\\d{2}/");
    }

    public boolean isYear() {
      return path.matches(".*/\\d{4}/");
    }

    public List<WebPage> findNews(final String tag) {
      // TODO: need more efficient implementation
      List<WebPage> news = newArrayList(filter(childrenRecursively(), new Predicate<WebPage>() {
        @Override public boolean apply(WebPage page) {
          return !page.metadata.isEmpty() && !page.metadata.getProperty("hidden", "false").equals("true")
              && (tag == null || page.metadata.getProperty("tags", "").contains(tag));
        }
      }));
      reverse(news);
      return news;
    }
  }
}
