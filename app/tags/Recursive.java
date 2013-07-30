package tags;

import groovy.lang.Closure;
import models.WebPage;
import play.templates.FastTags;
import play.templates.GroovyTemplate;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

@SuppressWarnings("UnusedDeclaration")
public class Recursive extends FastTags {
  public static void _sitemap(Map<?, ?> args, Closure body, PrintWriter out, GroovyTemplate.ExecutableTemplate template, int fromLine) {
    WebPage current = (WebPage) args.get("arg");
    String name = (String) args.get("as");

    if (args.containsKey("child")) {
      body.setProperty(name, current);
      body.call();
    }

    List<WebPage> children = current.children();
    if (!children.isEmpty()) {
      out.write("<ul>");
    }

    for (WebPage child : children) {
      if ("true".equals(child.metadata.getProperty("hidden", "false"))) continue;
      ((Map)args).put("arg", child);
      ((Map)args).put("child", true);
      _sitemap(args, body, out, template, fromLine);
    }

    if (!children.isEmpty()) {
      out.write("</ul>");
    }
  }
}
