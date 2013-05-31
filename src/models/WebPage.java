package models;

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

import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Lists.newArrayList;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.reverse;
import static java.util.Collections.sort;
import static org.apache.commons.lang.StringUtils.*;

public class WebPage implements Serializable, Comparable<WebPage> {
  public static final Set<String> ALLOWED_FILE_TYPES = new HashSet<>(asList("png", "jpg", "gif", "pdf", "rtf", "swf", "mp3", "zip", "rar", "7z", "xls", "xlsx", "ppt", "pptx", "doc", "docx"));
  public static final String BOM = new String(new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF});

  public static WebPage ROOT = new WebPage();
  public static WebPage ROOT_EN = new WebPage(ROOT.dir.child("en"));

  public String path;
  public VirtualFile dir;
  public int level;
  public Properties metadata;
  public String title;
  public int order;

  /** ROOT */
  WebPage() {
    this.dir = VirtualFile.open(canonicalPath(Play.getFile(Play.configuration.getProperty("web.content", "web-content"))));
    this.path = "/";
  }

  WebPage(VirtualFile dir) {
    this.dir = dir;
    this.path = dir.getRealFile().getPath().replace(ROOT.dir.getRealFile().getPath(), "").replace('\\', '/') + "/";
    this.level = countMatches(path, "/") - 1;

    metadata = loadMetadata();
    title = metadata.getProperty("title");
    if (isEmpty(title)) title = generateTitle();
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
    if (!canonicalPath(file.getRealFile()).startsWith(ROOT.dir.getRealFile().getPath()))
      throw new SecurityException("Access denied");
    return file;
  }

  public static <P extends WebPage> P forPath(String path) {
    return forPath(toVirtualFile(path));
  }

  @SuppressWarnings("unchecked")
  static <P extends WebPage> P forPath(VirtualFile dir) {
    String path = dir.getRealFile().getPath().replace(ROOT.dir.getRealFile().getPath(), "").replace('\\', '/');
    if (path.contains("/news")) return (P)new News(dir);
    if (path.startsWith("/analytics")) return (P)new Analytics(dir);
    else return (P)new WebPage(dir);
  }

  public List<WebPage> children() {
    List<WebPage> children = new ArrayList<>();
    for (VirtualFile entry : dir.list()) {
      if (entry.isDirectory() && !entry.getName().startsWith(".") && !entry.equals(ROOT_EN.dir)) {
        WebPage child = forPath(entry);
        children.add(child);
      }
    }
    sort(children);
    return children;
  }

  public WebPage parent() {
    return path.equals(ROOT.path) ? null : new WebPage(VirtualFile.open(dir.getRealFile().getParent()));
  }

  public String topParentName() {
    return path.substring(1, path.indexOf("/", 1));
  }

  public String loadFile(String filename) {
    return dir.child(filename).contentAsString();
  }

  private Properties loadMetadata() {
    Properties metadata = new Properties();
    VirtualFile metaFile = dir.child("metadata.properties");
    if (metaFile.exists()) {
      try (Reader reader = new InputStreamReader(metaFile.inputstream(), "UTF-8")) {
        metadata.load(reader);
      }
      catch (IOException e) {
        Logger.error("Cannot load " + metaFile, e);
      }
    }
    return metadata;
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

  private Map<String, String> contentPartsFromAnotherPage(WebPage child) {
    Map<String, String> parts = child.contentParts();
    for (String key : parts.keySet()) {
      // fix images and links
      parts.put(key, parts.get(key).replaceAll("(src|href)=\"([^/]+?)\"", "$1=\"" + child.path + "$2\""));
    }
    return parts;
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
        m.appendReplacement(result, "<a$1class=\"email\" href=\"$2\"$3>$4</a>");
      }
      else if (filename.contains("://") || !ALLOWED_FILE_TYPES.contains(filetype))
        m.appendReplacement(result, m.group());
      else {
        VirtualFile file = dir.child(filename);
        double lengthKb = file.length() / 1024.0;
        String size = lengthKb > 1024 ? format("%.1f Mb", lengthKb / 1024) : format("%.0f Kb", lengthKb);
        m.appendReplacement(result, "<a$1class=\"download " + filetype + (file.exists() ? "" : " unavailable") + "\" href=\"" + path + "$2\"$3>" +
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
    List<WebPage> structure = newArrayList();
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
    public News(VirtualFile dir) {
      super(dir);
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
  }

  public static class Analytics extends News {
    public Analytics(VirtualFile dir) {
      super(dir);
    }
  }
}
