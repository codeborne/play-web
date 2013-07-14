package controllers;

import models.WebPage;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import play.Play;
import play.cache.CacheFor;
import play.db.jpa.JPAPlugin;
import play.db.jpa.NoTransaction;
import play.i18n.Lang;
import play.libs.Mail;
import play.libs.XML;
import play.mvc.After;
import play.mvc.Controller;
import play.mvc.Router;
import play.mvc.With;
import play.templates.BaseTemplate;
import play.templates.TagContext;
import play.vfs.VirtualFile;
import util.WebPageIndexer;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static ext.CustomExtensions.safeUrlEncode;
import static java.lang.Math.min;
import static java.util.Arrays.asList;
import static java.util.Collections.sort;
import static models.WebPage.ALLOWED_FILE_TYPES;
import static org.apache.commons.lang.StringUtils.*;

@With(Security.class) @NoTransaction
public class Web extends Controller {
  @Inject static WebPageIndexer indexer;

  @After public static void setHeaders() {
    BaseController.addGlobalHeaders();
  }

  @CacheFor("5mn")
  public static void serveCachedContent() throws UnsupportedEncodingException {
    serveContentInternal();
  }

  public static void serveContent() throws UnsupportedEncodingException {
    serveContentInternal();
  }

  private static void serveContentInternal() throws UnsupportedEncodingException {
    VirtualFile file = WebPage.toVirtualFile(URLDecoder.decode(request.path, "UTF-8"));
    if (!file.exists()) notFound();

    if (file.isDirectory()) {
      if (!request.path.endsWith("/")) redirect(request.path + "/");
      WebPage page = WebPage.forPath(request.path);
      String redirectUrl = page.metadata.getProperty("redirect");
      if (isNotEmpty(redirectUrl)) redirect((redirectUrl.startsWith("/") ? "" : request.path) + safeUrlEncode(redirectUrl));
      if ("prod".equals(Play.id)) response.cacheFor(Long.toString(page.dir.lastModified()), "12h", page.dir.lastModified());
      renderPage(page);
    }
    else if (isAllowed(file)) {
      response.cacheFor("30d");
      renderBinary(file.getRealFile());
    }
    else notFound();
  }

  static boolean isAllowed(VirtualFile file) {
    String name = file.getName();
    String ext = name.substring(name.lastIndexOf('.')+1);
    return ALLOWED_FILE_TYPES.contains(ext);
  }

  public static void search(String q) throws ParseException, IOException {
    Query query = indexer.queryParser.parse("title:\"" + q + "\"^3 text:\"" + q + "\"");
    TopDocs topDocs = indexer.searcher.search(query, 50);
    List<WebPage> results = new ArrayList<>();
    for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
      WebPage page = WebPage.forPath(indexer.reader.document(scoreDoc.doc).get("path"));
      results.add(page);
    }

    renderArgs.put("numResults", topDocs.totalHits);
    render(results);
  }

  public static void contacts() {
    render();
  }

  public static void map() {
    render();
  }

  public static void locale(String locale) {
    redirect(Play.configuration.getProperty("web." + locale + ".home"));
  }

  public static void news(String tag) throws Exception {
    tag = fixEncodingForIE(tag);
    WebPage.News page = WebPage.forPath(request.path);
    List<WebPage> allNews = page.findNews(tag);
    if (isNotEmpty(tag) && allNews.isEmpty() && page.level >= 2) redirect(page.parent().path + "?" + request.querystring);
    List<WebPage> news = page.isStory() ? asList((WebPage)page) : limit(allNews, 30);
    renderArgs.put("tagFreq", tagFreq(page));
    int total = allNews.size();
    render(page, news, tag, total);
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
    sort(tags, new Comparator<Map.Entry<String, AtomicInteger>>() {
      @Override public int compare(Map.Entry<String, AtomicInteger> tag1, Map.Entry<String, AtomicInteger> tag2) {
        return tag2.getValue().intValue() - tag1.getValue().intValue();
      }
    });

    LinkedList<Map.Entry<String, Float>> result = new LinkedList<>();
    boolean even = false;
    for (Map.Entry<String, AtomicInteger> tag : tags) {
      Map.Entry<String, Float> tagFreq = new HashMap.SimpleEntry<>(tag.getKey(), tag.getValue().floatValue()/total);
      if (even) result.addFirst(tagFreq); else result.add(tagFreq);
      even = !even;
    }
    return result;
  }

  private static String fixEncodingForIE(String value) throws UnsupportedEncodingException {
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

  public static void sitemap() {
    WebPage root = Lang.get().equals("en") ? WebPage.ROOT_EN : WebPage.ROOT;
    render(root);
  }

  public static void robotsTxt() throws IOException {
    renderText(WebPage.ROOT.loadFile("robots.txt"));
  }

  public static void sitemapXml() {
    Document sitemap = XML.getDocument("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"/>");
    appendToSitemapRecursively(sitemap, WebPage.ROOT);
    appendToSitemapRecursively(sitemap, WebPage.ROOT_EN);
    appendEntryToSitemap(sitemap, Router.reverse("Application.home").url, new File(".").lastModified(), "weekly");
    appendEntryToSitemap(sitemap, Router.reverse("Application.wallet").url, new File(".").lastModified(), "weekly");
    renderXml(sitemap);
  }

  private static void appendToSitemapRecursively(Document sitemap, WebPage page) {
    for (WebPage child : page.children()) {
      appendEntryToSitemap(sitemap, child.path, child.dir.lastModified(), "daily");
      appendToSitemapRecursively(sitemap, child);
    }
  }

  private static void appendEntryToSitemap(Document sitemap, String path, long lastModified, String frequency) {
    Node url = sitemap.getDocumentElement().appendChild(sitemap.createElement("url"));
    url.appendChild(sitemap.createElement("loc")).setTextContent(Play.configuration.getProperty("application.baseUrl") + path);
    url.appendChild(sitemap.createElement("lastmod")).setTextContent(new SimpleDateFormat("yyyy-MM-dd").format(lastModified));
    url.appendChild(sitemap.createElement("changefreq")).setTextContent(frequency);
    url.appendChild(sitemap.createElement("priority")).setTextContent(Double.toString(1.0 / Math.max(countMatches(path, "/"), 0.1)));
  }

  public static void redirectAlias(String path) {
    redirect(path, true);
  }

  public static void postForm() throws MalformedURLException, EmailException {
    checkAuthenticity();
    Map<String, String[]> data = params.all();
    data.remove("body"); data.remove("authenticityToken"); data.remove("controller"); data.remove("action");
    String replyTo = data.containsKey("replyTo") ? data.remove("replyTo")[0] : null;

    String path = new URL(request.headers.get("referer").value()).getPath();
    WebPage page = WebPage.forPath(path);

    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, String[]> e : data.entrySet()) {
      body.append(e.getKey()).append(": ").append(join(e.getValue(), ", ")).append("\n");
    }

    SimpleEmail msg = new SimpleEmail();
    msg.setSubject(page.title);
    msg.setMsg(body.toString());

    String branch = params.get("branch");
    addTo(msg, page.metadata.getProperty("email", Play.configuration.getProperty("messages.to")));
    if (isNotEmpty(branch)) addTo(msg, page.metadata.getProperty("email." + branch));
    if (msg.getToAddresses().isEmpty())
      throw new IllegalStateException("Recipient address is not configured");

    msg.setFrom(Play.configuration.getProperty("messages.to"));
    try {
      if (isNotEmpty(replyTo)) msg.addReplyTo(replyTo);
    }
    catch (EmailException ignore) {}

    Mail.send(msg);
    flash.success(play.i18n.Messages.get("web.form.sent"));
    redirect(page.path);
  }

  private static void addTo(SimpleEmail msg, String addresses) throws EmailException {
    if (isEmpty(addresses)) return;
    for (String email : addresses.split("\\s*,\\s")) msg.addTo(email);
  }

  @SuppressWarnings("unchecked")
  private static void renderPage(WebPage page) {
    BaseTemplate.layoutData.set((Map) page.contentParts()); // init layoutData ourselves
    TagContext.init();
    renderArgs.put("_isLayout", true); // tell play not to reset layoutData itself

    renderArgs.put("metaDescription", page.metadata.getProperty("description"));
    renderArgs.put("metaKeywords", page.metadata.getProperty("keywords"));

    try {
      if ("demo".equals(Play.id)) JPAPlugin.startTx(true);

      renderTemplate("Web/templates/" + page.template + ".html", page);
    }
    finally {
      if ("demo".equals(Play.id)) JPAPlugin.closeTx(true);
    }
  }
}
