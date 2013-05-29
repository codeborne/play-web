package controllers;

import com.google.common.base.Predicate;
import models.Config;
import models.WebPage;
import org.apache.commons.mail.EmailException;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import play.Play;
import play.i18n.Lang;
import play.libs.XML;
import play.mvc.After;
import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Router;
import play.templates.BaseTemplate;
import play.templates.TagContext;
import play.vfs.VirtualFile;
import services.CurrencyService;
import services.MessageService;
import services.NewsService;
import util.WebPageIndexer;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.collect.Collections2.filter;
import static com.google.common.collect.Lists.newArrayList;
import static controllers.BaseController.checkAuthenticPost;
import static ext.CustomExtensions.safeUrlEncode;
import static java.lang.Math.min;
import static java.util.Arrays.asList;
import static java.util.Collections.sort;
import static models.WebPage.ALLOWED_FILE_TYPES;
import static org.apache.commons.lang.StringUtils.*;

public class Web extends Controller {
  @Inject static CurrencyService currencyService;
  @Inject static NewsService newsService;
  @Inject static MessageService messageService;
  @Inject static WebPageIndexer indexer;

  @Before public static void checkNotProd() {
    if (Config.isProdEnv()) notFound();
  }

  @After public static void setHeaders() {
    BaseController.addGlobalHeaders();
  }

  public static void serveContent() throws UnsupportedEncodingException {
    VirtualFile file = WebPage.toVirtualFile(URLDecoder.decode(request.path, "UTF-8"));
    if (!file.exists()) notFound();

    if (file.isDirectory()) {
      if (!request.path.endsWith("/")) redirect(request.path + "/");
      WebPage page = WebPage.forPath(request.path);
      fixLocale(page);
      String redirectUrl = page.metadata.getProperty("redirect");
      if (isNotEmpty(redirectUrl)) redirect((redirectUrl.startsWith("/") ? "" : request.path) + safeUrlEncode(redirectUrl));
      if (page.level == 1 || page.equals(WebPage.ROOT_EN)) loadCurrenciesAndNews(page); // only in case of front pages and english front page
      renderPage(page);
    }
    else if (isAllowed(file)) {
      renderBinary(file.getRealFile());
    }
    else notFound();
  }

  private static void fixLocale(WebPage page) {
    String locale = Lang.get();
    String expectedLocale = page.path.startsWith("/en") ? "en" : "ru";
    if (!expectedLocale.equals(locale)) locale(expectedLocale);
  }

  static boolean isAllowed(VirtualFile file) {
    String name = file.getName();
    String ext = name.substring(name.lastIndexOf('.')+1);
    return ALLOWED_FILE_TYPES.contains(ext);
  }

  private static void loadCurrenciesAndNews(WebPage page) {
    renderArgs.put("currencyRates", currencyService.rates());
    List<WebPage> news;
    if (page.equals(WebPage.ROOT_EN)) news = limit(findNews(WebPage.forPath("/en/news"), null), 3);
    else news = limit(findNews(WebPage.forPath("/news"), page.title), 2);
    renderArgs.put("latestNews", news);
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
    Lang.change(locale);
    // play puts only session cookie, let's have a longer one
    response.setCookie(Play.configuration.getProperty("application.lang.cookie", "PLAY_LANG"), locale, "10000d");
    redirect(Play.configuration.getProperty("web." + locale + ".home"));
  }

  public static void news(String tag) throws Exception {
    tag = fixEncodingForIE(tag);
    WebPage.News page = WebPage.forPath(request.path);
    List<WebPage> allNews = findNews(page, tag);
    if (isNotEmpty(tag) && allNews.isEmpty()) redirect(request.url.replace(page.dir.getName() + '/', ""));
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

  private static <T> List<T> limit(List<T> list, int limit) {
    return list.subList(0, min(list.size(), limit));
  }

  private static List<WebPage> findNews(WebPage page, final String tag) {
    // TODO: need more effective implementation
    List<WebPage> news = newArrayList(filter(page.childrenRecursively(), new Predicate<WebPage>() {
      @Override public boolean apply(WebPage page) {
        return !page.metadata.isEmpty() && (tag == null || page.metadata.getProperty("tags","").contains(tag));
      }
    }));
    Collections.reverse(news);
    return news;
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

  public static void sendMessage(String name, String email, String subject, String text, File[] attachment) throws EmailException {
    checkAuthenticPost();
    messageService.sendAnonymously(name, email, play.i18n.Messages.get("web.email.subject") + ": " + subject, text, attachment);
    renderText("OK");
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

  @SuppressWarnings("unchecked")
  private static void renderPage(WebPage page) {
    BaseTemplate.layoutData.set((Map) page.contentParts()); // init layoutData ourselves
    TagContext.init();
    renderArgs.put("_isLayout", true); // tell play not to reset layoutData itself

    renderArgs.put("title", page.title);
    renderArgs.put("metaDescription", page.metadata.getProperty("description"));
    renderArgs.put("metaKeywords", page.metadata.getProperty("keywords"));

    String template = page.metadata.getProperty("template", "custom");
    renderTemplate("Web/templates/" + template + ".html", page);
  }
}
