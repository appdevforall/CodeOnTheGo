package com.google.googlejavaformat.java;

import java.nio.file.Path;
import javax.annotation.processing.Generated;
import org.checkerframework.checker.nullness.qual.Nullable;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_FormatFileCallable_Result extends FormatFileCallable.Result {

  private final @Nullable Path path;

  private final String input;

  private final @Nullable String output;

  private final @Nullable FormatterException exception;

  AutoValue_FormatFileCallable_Result(
      @Nullable Path path,
      String input,
      @Nullable String output,
      @Nullable FormatterException exception) {
    this.path = path;
    if (input == null) {
      throw new NullPointerException("Null input");
    }
    this.input = input;
    this.output = output;
    this.exception = exception;
  }

  @Override
  @Nullable Path path() {
    return path;
  }

  @Override
  String input() {
    return input;
  }

  @Override
  @Nullable String output() {
    return output;
  }

  @Override
  @Nullable FormatterException exception() {
    return exception;
  }

  @Override
  public String toString() {
    return "Result{"
        + "path=" + path + ", "
        + "input=" + input + ", "
        + "output=" + output + ", "
        + "exception=" + exception
        + "}";
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof FormatFileCallable.Result) {
      FormatFileCallable.Result that = (FormatFileCallable.Result) o;
      return (this.path == null ? that.path() == null : this.path.equals(that.path()))
          && this.input.equals(that.input())
          && (this.output == null ? that.output() == null : this.output.equals(that.output()))
          && (this.exception == null ? that.exception() == null : this.exception.equals(that.exception()));
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= (path == null) ? 0 : path.hashCode();
    h$ *= 1000003;
    h$ ^= input.hashCode();
    h$ *= 1000003;
    h$ ^= (output == null) ? 0 : output.hashCode();
    h$ *= 1000003;
    h$ ^= (exception == null) ? 0 : exception.hashCode();
    return h$;
  }

}
