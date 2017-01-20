package controllers;

import com.google.common.collect.ImmutableMap;
import models.WebPage;
import org.apache.commons.io.IOUtils;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import play.Play;
import play.cache.CacheFor;
import play.db.jpa.JPA;
import play.db.jpa.NoTransaction;
import play.i18n.Messages;
import play.libs.Mail;
import play.libs.MimeTypes;
import play.libs.XML;
import play.mvc.*;
import play.rebel.RebelController;
import play.templates.BaseTemplate;
import play.templates.TagContext;
import play.vfs.VirtualFile;
import plugins.SetLangByURL;
import util.WebPageIndexer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.awt.RenderingHints.*;
import static java.lang.Math.min;
import static java.util.Arrays.asList;
import static java.util.Comparator.comparingInt;
import static models.WebPage.ALLOWED_FILE_TYPES;
import static models.WebPage.rootForLocale;
import static org.apache.commons.io.FilenameUtils.getExtension;
import static org.apache.commons.lang.StringUtils.*;
import static plugins.WebContentPlugin.cacheEnabled;
import static util.UrlEncoder.safeUrlEncode;

@With(Security.class) @NoTransaction
public class Web extends RebelController {
  private static final Logger logger = LoggerFactory.getLogger(Web.class);
  private static boolean usePlayGroovyTemplates = "true".equals(Play.configuration.getProperty("web.usePlayGroovyTemplates", "true"));
  static WebPageIndexer indexer = WebPageIndexer.getInstance();

  @After public void setHeaders() {
    Http.Response.current().setHeader("X-XSS-Protection", "1; mode=block");
    Http.Response.current().setHeader("X-UA-Compatible", "IE=edge"); // force IE to normal mode (not "compatibility")
  }

  @Before
  public void before() {
    renderArgs.put("includeHiddenPages", Security.check("cms"));
  }

  @SetLangByURL @CacheFor("5mn")
  public void serveContentCached() throws IOException, ParseException {
    serveContentInternal();
  }

  @SetLangByURL
  public void serveContent() throws IOException, ParseException {
    serveContentInternal();
  }

  private void serveContentInternal() throws IOException, ParseException {
    VirtualFile dir = serveFileOrGetDirectory();
    if (!request.path.endsWith("/")) redirect(request.path + "/");
    WebPage page = WebPage.forPath(dir);
    String redirectUrl = page.metadata.getProperty("redirect");
    if (isNotEmpty(redirectUrl)) redirect(fixRedirectUrl(redirectUrl));
    if (cacheEnabled()) response.cacheFor(Long.toString(page.dir.lastModified()), "12h", page.dir.lastModified());
    renderPage(page);
  }

  private VirtualFile serveFileOrGetDirectory() throws IOException, ParseException {
    VirtualFile file = WebPage.toVirtualFile(URLDecoder.decode(request.path, "UTF-8"));
    if (file.exists() && isAllowed(file)) {
      Http.Response.current().cacheFor("30d");
      renderBinary(file.getRealFile());
    }
    else if (!file.isDirectory()) showNotFoundError();
    return file;
  }

  private void showNotFoundError() throws IOException, ParseException {
    String contentType = MimeTypes.getContentType(request.path, "text/html");

    if (!indexer.shouldIndex() || !contentType.startsWith("text/html")) {
      notFound();
    }
    else {
      Http.Response.current().status = Http.StatusCode.NOT_FOUND;
      renderSearch(request.path.replaceFirst(".*/", ""));
    }
  }

  private String fixRedirectUrl(String url) {
    if (url.startsWith("http:") || url.startsWith("https:"))
      return url;
    else if (url.startsWith("/"))
      return safeUrlEncode(url);
    else
      return request.path + safeUrlEncode(url);
  }

  static boolean isAllowed(VirtualFile file) {
    return ALLOWED_FILE_TYPES.contains(getExtension(file.getName()));
  }

  public void search(String q) throws ParseException, IOException {
    if (!indexer.shouldIndex()) {
      error(404, Messages.get("error.notFound"));
      return;
    }
    renderSearch(q);
  }
  
  private void renderSearch(String q) throws ParseException, IOException {
    Query query = indexer.queryParser.parse("title:\"" + q + "\"^3 text:\"" + q + "\" keywords:\"" + q + "\"^2 path:\"" + q + "\"^2");
    TopDocs topDocs = indexer.searcher.search(query, 50);
    List<WebPage> results = new ArrayList<>();
    for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
      WebPage page = WebPage.forPath(indexer.reader.document(scoreDoc.doc).get("path"));
      results.add(page);
    }

    renderArgs.put("numResults", topDocs.totalHits);
    renderArgs.put("q", q);
    renderArgs.put("results", results);
    render();
  }

  public void contacts() {
    render();
  }

  public void map() {
    render();
  }

  public void locale(String locale) {
    redirect(Play.configuration.getProperty("web." + locale + ".home"));
  }

  @SetLangByURL
  public void news(String tag) throws Exception {
    VirtualFile dir = serveFileOrGetDirectory();
    tag = fixEncodingForIE(tag);
    WebPage.News page = WebPage.forPath(dir);
    List<WebPage> allNews = page.findNews(tag);
    if (isNotEmpty(tag) && allNews.isEmpty() && page.level >= 2) redirect(page.parent().path + "?" + request.querystring);
    List<WebPage> news = page.isStory() ? asList((WebPage)page) : limit(allNews, 30);
    List<Entry<String, Float>> tagFreq = tagFreq(page);
    int total = allNews.size();
    
    renderArgs.put("page", page);
    renderArgs.put("news", news);
    renderArgs.put("tag", tag);
    renderArgs.put("tagFreq", tagFreq);
    renderArgs.put("total", total);
    render("Web/templates/news.html");
  }

  private static List<Map.Entry<String, Float>> tagFreq(WebPage page) {
    Map<String, AtomicInteger> tagMap = indexer.tagsFreqByTopPage.get(page.topParentName());
    if (tagMap == null) return null;

    int total = 0;
    List<Map.Entry<String, AtomicInteger>> tags = new ArrayList<>(tagMap.size());
    for (Map.Entry<String, AtomicInteger> tag : tagMap.entrySet()) {
      tags.add(tag);
      total += tag.getValue().intValue();
    }
    tags.sort(comparingInt(t -> t.getValue().intValue()));

    LinkedList<Map.Entry<String, Float>> result = new LinkedList<>();
    boolean even = false;
    for (Map.Entry<String, AtomicInteger> tag : tags) {
      Map.Entry<String, Float> tagFreq = new HashMap.SimpleEntry<>(tag.getKey(), tag.getValue().floatValue()/total);
      if (even) result.addFirst(tagFreq); else result.add(tagFreq);
      even = !even;
    }
    return result;
  }

  static String fixEncodingForIE(String value) throws UnsupportedEncodingException {
    if (isEmpty(value) || value.charAt(0) < 32000) return value;
    // TODO: this is a workaround for double-bug in IE + Netty
    // IE doesn't URL-encode strings in hrefs by default and Netty assumes bytes are chars in HttpMessageDecoder.readLine()
    // alternative would be to use tag.urlEncode() only for IE in news.html, but URLs would be ugly
    byte[] bytes = new byte[value.length()];
    for (int i = 0; i < value.length(); i++) bytes[i] = (byte)value.charAt(i);
    return new String(bytes, "UTF-8");
  }

  static <T> List<T> limit(List<T> list, int limit) {
    return list.subList(0, min(list.size(), limit));
  }

  public void sitemap() {
    try {
      JPA.startTx(JPA.DEFAULT, true);
      WebPage root = rootForLocale();
      renderTemplate(ImmutableMap.of("root", root));
    }
    finally {
      JPA.closeTx(JPA.DEFAULT);
    }
  }

  public void robotsTxt() {
    renderText(WebPage.ROOT.dir.child("robots.txt").exists() ? WebPage.ROOT.loadFile("robots.txt") :
      "Sitemap: " + request.getBase() + Router.reverse("Web.sitemapXml") + "\n" +
        "User-Agent: *\n");
  }

  public void sitemapXml() {
    Document sitemap = XML.getDocument("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"/>");
    appendToSitemapRecursively(sitemap, WebPage.ROOT);
    renderXml(sitemap);
  }

  private void appendToSitemapRecursively(Document sitemap, WebPage page) {
    for (WebPage child : page.children()) {
      appendEntryToSitemap(sitemap, child.path, child.dir.lastModified(), "daily");
      appendToSitemapRecursively(sitemap, child);
    }
  }

  private void appendEntryToSitemap(Document sitemap, String path, long lastModified, String frequency) {
    Node url = sitemap.getDocumentElement().appendChild(sitemap.createElement("url"));
    url.appendChild(sitemap.createElement("loc")).setTextContent(request.getBase() + path);
    url.appendChild(sitemap.createElement("lastmod")).setTextContent(new SimpleDateFormat("yyyy-MM-dd").format(lastModified));
    url.appendChild(sitemap.createElement("changefreq")).setTextContent(frequency);
    url.appendChild(sitemap.createElement("priority")).setTextContent(Double.toString(1.0 / Math.max(countMatches(path, "/"), 0.1)));
  }

  public void redirectAlias(String path) {
    if (!path.startsWith("/")) forbidden();
    redirect(path, true);
  }

  public void postForm() throws Exception {
    checkAuthenticity();

    String path = new URL(request.headers.get("referer").value()).getPath();
    WebPage page = WebPage.forPath(path);

    SimpleEmail msg = new SimpleEmail();
    msg.setCharset("UTF-8");
    msg.setSubject(page.title);
    addTo(msg, page.metadata.getProperty("email"));

    StringBuilder body = new StringBuilder();

    String orderedParams = URLDecoder.decode(IOUtils.toString(request.body, "UTF-8"), "UTF-8");
    for (String keyVal : orderedParams.split("&")) {
      String[] kv = keyVal.split("=", 2);
      String key = kv[0];

      switch (key) {
        case "replyTo":
          try {
            if (isNotEmpty(kv[1])) msg.addReplyTo(kv[1]);
          }
          catch (EmailException ignore) {}
          continue;
        case "branch":
          addTo(msg, page.metadata.getProperty("email." + kv[1]));
          break;
        case "authenticityToken":
          continue;
      }

      body.append(key).append(": ").append(kv[1]).append("\n");
    }
    msg.setMsg(body.toString());

    if (msg.getToAddresses().isEmpty())
      addTo(msg, Play.configuration.getProperty("messages.to"));
    if (msg.getToAddresses().isEmpty())
      throw new IllegalStateException("Recipient address is not configured");

    msg.setFrom(Play.configuration.getProperty("email.from"), Play.configuration.getProperty("email.from.name"));

    logger.info("Sending web form to " + msg.getToAddresses() + ": " + body);
    if (await(Mail.send(msg))) {
      flash.success(Messages.get("web.form.sent"));
      redirect(page.path);
    }
    else {
      error("Sending email failed");
    }
  }

  public void thumbnail(String path, String name, int height) throws IOException {
    File imgFile = WebPage.forPath(path).dir.child(name).getRealFile();
    BufferedImage img = ImageIO.read(imgFile);
    BufferedImage resized = new BufferedImage(img.getWidth() * height / img.getHeight(), height, img.getType());
    Graphics2D g = resized.createGraphics();
    g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BILINEAR);
    g.setRenderingHint(KEY_RENDERING, VALUE_RENDER_QUALITY);
    g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
    g.drawImage(img, 0, 0, resized.getWidth(), resized.getHeight(), null);
    g.dispose();
    g.setComposite(AlphaComposite.Src);

    ByteArrayOutputStream out = new ByteArrayOutputStream((int) imgFile.length());
    ImageIO.write(resized, "png", out);
    Http.Response.current().cacheFor(String.valueOf(imgFile.lastModified()), "30d", imgFile.lastModified());
    renderBinary(new ByteArrayInputStream(out.toByteArray()), imgFile.getName(), "image/png", true);
  }

  private static void addTo(SimpleEmail msg, String addresses) throws EmailException {
    if (isEmpty(addresses)) return;
    for (String email : addresses.split("\\s*,\\s")) msg.addTo(email);
  }

  @Util
  public static void renderPage(WebPage page) {
    if (usePlayGroovyTemplates) {
      BaseTemplate.layoutData.set((Map) page.contentParts()); // init layoutData ourselves
      TagContext.init();
      renderArgs().put("_isLayout", true); // tell play not to reset layoutData itself
    }

    renderArgs().put("metaDescription", page.metadata.getProperty("description"));
    renderArgs().put("metaKeywords", page.metadata.getProperty("keywords"));
    renderArgs().put("page", page);

    render("Web/templates/" + page.template + ".html");
  }
}
