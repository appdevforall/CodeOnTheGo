package com.google.googlejavaformat.java;

import javax.annotation.processing.Generated;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_JavaFormatterOptions extends JavaFormatterOptions {

  private final boolean formatJavadoc;

  private final boolean reorderModifiers;

  private final boolean useJava17AstVisitor;

  private final JavaFormatterOptions.Style style;

  private AutoValue_JavaFormatterOptions(
      boolean formatJavadoc,
      boolean reorderModifiers,
      boolean useJava17AstVisitor,
      JavaFormatterOptions.Style style) {
    this.formatJavadoc = formatJavadoc;
    this.reorderModifiers = reorderModifiers;
    this.useJava17AstVisitor = useJava17AstVisitor;
    this.style = style;
  }

  @Override
  public boolean formatJavadoc() {
    return formatJavadoc;
  }

  @Override
  public boolean reorderModifiers() {
    return reorderModifiers;
  }

  @Override
  public boolean useJava17AstVisitor() {
    return useJava17AstVisitor;
  }

  @Override
  public JavaFormatterOptions.Style style() {
    return style;
  }

  @Override
  public String toString() {
    return "JavaFormatterOptions{"
        + "formatJavadoc=" + formatJavadoc + ", "
        + "reorderModifiers=" + reorderModifiers + ", "
        + "useJava17AstVisitor=" + useJava17AstVisitor + ", "
        + "style=" + style
        + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof JavaFormatterOptions) {
      JavaFormatterOptions that = (JavaFormatterOptions) o;
      return this.formatJavadoc == that.formatJavadoc()
          && this.reorderModifiers == that.reorderModifiers()
          && this.useJava17AstVisitor == that.useJava17AstVisitor()
          && this.style.equals(that.style());
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= formatJavadoc ? 1231 : 1237;
    h$ *= 1000003;
    h$ ^= reorderModifiers ? 1231 : 1237;
    h$ *= 1000003;
    h$ ^= useJava17AstVisitor ? 1231 : 1237;
    h$ *= 1000003;
    h$ ^= style.hashCode();
    return h$;
  }

  static final class Builder extends JavaFormatterOptions.Builder {
    private boolean formatJavadoc;
    private boolean reorderModifiers;
    private boolean useJava17AstVisitor;
    private JavaFormatterOptions.Style style;
    private byte set$0;
    Builder() {
    }
    @Override
    public JavaFormatterOptions.Builder formatJavadoc(boolean formatJavadoc) {
      this.formatJavadoc = formatJavadoc;
      set$0 |= (byte) 1;
      return this;
    }
    @Override
    public JavaFormatterOptions.Builder reorderModifiers(boolean reorderModifiers) {
      this.reorderModifiers = reorderModifiers;
      set$0 |= (byte) 2;
      return this;
    }
    @Override
    public JavaFormatterOptions.Builder useJava17AstVisitor(boolean useJava17AstVisitor) {
      this.useJava17AstVisitor = useJava17AstVisitor;
      set$0 |= (byte) 4;
      return this;
    }
    @Override
    public JavaFormatterOptions.Builder style(JavaFormatterOptions.Style style) {
      if (style == null) {
        throw new NullPointerException("Null style");
      }
      this.style = style;
      return this;
    }
    @Override
    public JavaFormatterOptions build() {
      if (set$0 != 7
          || this.style == null) {
        StringBuilder missing = new StringBuilder();
        if ((set$0 & 1) == 0) {
          missing.append(" formatJavadoc");
        }
        if ((set$0 & 2) == 0) {
          missing.append(" reorderModifiers");
        }
        if ((set$0 & 4) == 0) {
          missing.append(" useJava17AstVisitor");
        }
        if (this.style == null) {
          missing.append(" style");
        }
        throw new IllegalStateException("Missing required properties:" + missing);
      }
      return new AutoValue_JavaFormatterOptions(
          this.formatJavadoc,
          this.reorderModifiers,
          this.useJava17AstVisitor,
          this.style);
    }
  }

}
