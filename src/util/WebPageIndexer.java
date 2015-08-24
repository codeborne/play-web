package util;

import models.WebPage;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.unmodifiableMap;
import static models.WebPage.removeTags;
import static org.apache.commons.lang.StringUtils.join;
import static org.apache.commons.lang.StringUtils.split;

public class WebPageIndexer {
  private static final Logger logger = LoggerFactory.getLogger(WebPageIndexer.class);

  private static final Version version = Version.LUCENE_43;
  private Directory dir;
  public IndexReader reader;
  public IndexSearcher searcher;
  public QueryParser queryParser;
  private Analyzer analyzer;
  public Map<String, Map<String, AtomicInteger>> tagsFreqByTopPage = new HashMap<>();

  private static final WebPageIndexer instance = new WebPageIndexer();
  public static WebPageIndexer getInstance() {
    return instance;
  }

  WebPageIndexer() {
    if (!shouldIndex()) return;
    try {
      dir = FSDirectory.open(new File(Play.tmpDir, "web-index"));
      analyzer = new RussianAnalyzer(version);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }

    try {
      reopenIndex();
    }
    catch (IOException ignore) {}
  }

  public final boolean shouldIndex() {
    return WebPage.ROOT.dir.exists() && "true".equals(Play.configuration.getProperty("web.enabled", "true"));
  }

  public synchronized void indexWebPages() throws IOException {
    logger.info("Indexing web pages...");
    long start = System.currentTimeMillis();

    IndexWriterConfig conf = new IndexWriterConfig(version, analyzer);
    try (final IndexWriter writer = new IndexWriter(dir, conf)) {
      writer.deleteAll();

      Map<String, Map<String, AtomicInteger>> tagsFreqByTopPage = new HashMap<>();
      for (WebPage page : WebPage.ROOT.childrenRecursively()) {
        if (page instanceof WebPage.News && !((WebPage.News) page).isStory()) continue;
        if ("true".equals(page.metadata.getProperty("hidden", "false"))) continue;

        float boost = 3600 * 24 * 1000f / (System.currentTimeMillis() - page.date().getTime());

        Document doc = new Document();
        doc.add(withBoost(boost, new TextField("path", page.path, Field.Store.YES)));
        doc.add(withBoost(boost, new TextField("title", page.title, Field.Store.NO)));
        doc.add(withBoost(boost, new TextField("keywords", page.metadata.getProperty("description", "") + " " + page.metadata.getProperty("keywords", ""), Field.Store.NO)));

        String tags = page.metadata.getProperty("tags", "");
        calcTagFreq(tagsFreqByTopPage, page, tags);
        doc.add(withBoost(boost, new TextField("tags", tags, Field.Store.YES)));

        doc.add(withBoost(boost, new TextField("text", removeTags(join(page.contentParts().values(), ' ')), Field.Store.NO)));
        writer.addDocument(doc);
      }
      this.tagsFreqByTopPage = unmodifiableMap(tagsFreqByTopPage);
      logger.info("Indexed " + writer.numDocs() + " pages in " + ((System.currentTimeMillis() - start) / 1000) + " sec");
    }

    reopenIndex();
  }

  private void calcTagFreq(Map<String, Map<String, AtomicInteger>> tagsFreqByTopPage, WebPage page, String tags) {
    for (String tag : split(tags, ",")) {
      tag = tag.trim();
      String topLevelPage = page.topParentName();
      Map<String, AtomicInteger> freq = tagsFreqByTopPage.get(topLevelPage);
      if (freq == null) tagsFreqByTopPage.put(topLevelPage, freq = new HashMap<>());
      AtomicInteger tagFreq = freq.get(tag);
      if (tagFreq == null) freq.put(tag, tagFreq = new AtomicInteger());
      tagFreq.incrementAndGet();
    }
  }

  private IndexableField withBoost(float boost, TextField field) {
    field.setBoost(boost);
    return field;
  }

  private void reopenIndex() throws IOException {
    if (reader != null) reader.close();
    reader = DirectoryReader.open(dir);
    searcher = new IndexSearcher(reader);
    queryParser = new QueryParser(version, "text", analyzer);
  }
}
