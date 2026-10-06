package org.appdevforall.codeonthego.lsp.xml.providers;

import org.appdevforall.codeonthego.lsp.models.CodeFormatResult;
import org.appdevforall.codeonthego.lsp.models.FormatCodeParams;
import org.appdevforall.codeonthego.lsp.xml.providers.format.XMLFormatter;
import org.appdevforall.codeonthego.utils.StopWatch;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeFormatProvider {

  private static final Logger LOG = LoggerFactory.getLogger(CodeFormatProvider.class);

  public CodeFormatResult format(FormatCodeParams params) {
    final CharSequence input = params.getContent();
    final var watch = new StopWatch("Formatting XML code");
    try {
      final var document =
          DOMParser.getInstance()
              .parse(input.toString(), "UTF-8", new URIResolverExtensionManager());
      final var edits = new XMLFormatter().format(document, params.getRange());
      return new CodeFormatResult(false, edits);
    } catch (Throwable error) {
      LOG.error("Error formatting code using DOM formatter", error);
      return CodeFormatResult.NONE;
    } finally {
      watch.log();
    }
  }
}
