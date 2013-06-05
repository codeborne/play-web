package models;

import java.io.Serializable;
import java.util.List;

import static java.util.Arrays.asList;

public class MetadataField implements Serializable {
  public static List<MetadataField> ALL = asList(
      new MetadataField("title"),
      new MetadataField("template"),
      new MetadataField("order", "number", null),
      new MetadataField("description"),
      new MetadataField("keywords"),
      new MetadataField("tags"),
      new MetadataField("alias"),
      new MetadataField("mainMenu", "checkbox", false),
      new MetadataField("hidden", "checkbox", false),
      new MetadataField("submenuColumns", "number", 5),
      new MetadataField("redirect"),
      new MetadataField("contentFrom"),
      new MetadataField("contentFromNewestChild"),
      new MetadataField("sitemap", "checkbox", true)
  );

  public String name;
  public String type = "text";
  public Object defaultValue;

  public MetadataField(String name) {
    this.name = name;
  }

  public MetadataField(String name, String type, Object defaultValue) {
    this.name = name;
    this.type = type;
    this.defaultValue = defaultValue;
  }
}
