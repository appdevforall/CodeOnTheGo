/*
 * This file is part of Pebble. <p> Copyright (c) 2014 by Mitchell Bösecke <p> For the full
 * copyright and license information, please view the LICENSE file that was distributed with this
 * source code.
 */
package io.pebbletemplates.pebble.lexer;

import io.pebbletemplates.pebble.operator.BinaryOperator;
import io.pebbletemplates.pebble.operator.UnaryOperator;
import io.pebbletemplates.pebble.error.ParserException;
import io.pebbletemplates.pebble.lexer.Token.Type;
import io.pebbletemplates.pebble.utils.Pair;
import io.pebbletemplates.pebble.utils.StringLengthComparator;
import io.pebbletemplates.pebble.utils.StringUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class reads the template input and builds single items out of it.
 * <p>
 * This class is not thread safe.
 */
public final class LexerImpl implements Lexer {

  private final Logger logger = LoggerFactory.getLogger(LexerImpl.class);

  /**
   * Syntax
   */
  private final Syntax syntax;

  /**
   * Unary operators
   */
  private final Collection<UnaryOperator> unaryOperators;

  /**
   * Binary operators
   */
  private final Collection<BinaryOperator> binaryOperators;

  /**
   * As we progress through the source we maintain a string which is the text that has yet to be
   * tokenized.
   */
  TemplateSource source;

  /**
   * The list of tokens that we find and use to create a TokenStream
   */
  private ArrayList<Token> tokens;

  /**
   * Represents the brackets we are currently inside ordered by how recently we encountered them.
   * (i.e. peek() will return the most innermost bracket, getLast() will return the outermost).
   * Brackets in this case includes double quotes. The String value of the pair is the bracket
   * representation, and the Integer is the line number.
   */
  private LinkedList<Pair<String, Integer>> brackets;

  /**
   * The state of the lexer is important so that we know what to expect next and to help discover
   * errors in the template (ex. unclosed comments).
   */
  private Deque<State> lexerStateStack = new ArrayDeque<State>();

  private enum State {
    DATA, EXECUTE, PRINT, COMMENT, STRING, STRING_INTERPOLATION
  }

  /**
   * If we encountered an END delimiter that was preceded with a whitespace trim character (ex. {{
   * foo -}}) then this boolean is toggled to "true" which tells the lexData() method to trim
   * leading whitespace from the next text token.
   */
  private boolean trimLeadingWhitespaceFromNextData = false;

  /**
   * Static regular expressions for identifiers.
   */
  private static final Pattern REGEX_IDENTIFIER;
  
  static {
    if ("The Android Project".equals(System.getProperty("java.vendor"))) {
      REGEX_IDENTIFIER = Pattern.compile("^[\\p{Letter}_][\\p{Letter}\\p{Digit}_]*");
    } else {
      // Standard
      REGEX_IDENTIFIER = Pattern.compile("^[\\p{IsLetter}_][\\p{IsLetter}\\p{IsDigit}_]*");
    }
  }

  private static final Pattern REGEX_LONG = Pattern.compile("^[0-9]+L");

  private static final Pattern REGEX_NUMBER = Pattern.compile("^[0-9]+(\\.[0-9]+)?");

  /**
   * Matches a double quote
   */
  private static final Pattern REGEX_DOUBLEQUOTE = Pattern.compile("^\"");

  /**
   * Matches everything up to the first interpolation in a double quoted string
   */
  private static final Pattern REGEX_STRING_NON_INTERPOLATED_PART =
      Pattern.compile("^[^#\"\\\\]*(?:(?:\\\\.|#(?!\\{))[^#\"\\\\]*)*", Pattern.DOTALL);

  /**
   * Matches single quoted strings and double quoted strings without interpolation. Extra complexity
   * is due to ignoring escaped quotation marks.
   */
  private static final Pattern REGEX_STRING_PLAIN = Pattern.compile(
      "^\"([^#\"\\\\]*(?:\\\\.[^#\"\\\\]*)*)\"|'([^'\\\\]*(?:\\\\.[^'\\\\]*)*)'", Pattern.DOTALL);

  private static final String PUNCTUATION = "()[]{}?:.,|=";

  /**
   * Regular expression to find operators
   */
  Pattern regexOperators;

  /**
   * The operator regex for each set of operators, shared by every lexer; see
   * {@link #buildOperatorRegex}.
   */
  private static final ConcurrentMap<String, Pattern> OPERATOR_REGEXES = new ConcurrentHashMap<>();

  /**
   * This lexer's Matcher for each pattern it has matched, and the window they are reset onto, with
   * the remaining length and window length it was cut at; see {@link #matcherOnWindow}.
   */
  private final Map<Pattern, Matcher> matchers = new IdentityHashMap<>();

  String windowText;

  private int windowRemaining = -1;

  private int windowLength = -1;

  /**
   * How many characters of the remaining source a match starts from; see {@link #match}.
   */
  static final int MATCH_WINDOW = 256;

  /**
   * {@link #MATCH_WINDOW}, settable by a test to compare small windows with the whole source.
   */
  int matchWindow = MATCH_WINDOW;

  /**
   * Calls of {@link #match}, the windows they matched on and those windows' total length, which a
   * test bounds to pin the cost {@link #match} exists to cut.
   */
  int matches;

  int windows;

  long windowChars;

  /**
   * Matchers created and windows cut, which a test bounds to pin the reuse in
   * {@link #matcherOnWindow}.
   */
  int matchersCreated;

  int windowsCut;

  /**
   * Constructor
   *
   * @param syntax The primary syntax
   * @param unaryOperators The available unary operators
   * @param binaryOperators The available binary operators
   */
  public LexerImpl(Syntax syntax, Collection<UnaryOperator> unaryOperators,
      Collection<BinaryOperator> binaryOperators) {
    this.syntax = syntax;
    this.unaryOperators = unaryOperators;
    this.binaryOperators = binaryOperators;
  }

  /**
   * This is the main method used to tokenize the raw contents of a template.
   *
   * @param reader The reader provided from the Loader
   * @param name The name of the template (used for meaningful error messages)
   */
  @Override
  public TokenStream tokenize(Reader reader, String name) {

    // operator regex
    this.buildOperatorRegex();

    // a window cut from the previous template must not stand in for one of this template's
    this.windowRemaining = -1;

    // standardize the character used for line breaks
    try {
      this.source = new TemplateSource(reader, name);
    } catch (IOException e) {
      throw new ParserException(e, "Can not convert template Reader into a String", 0, name);
    }


    this.tokens = new ArrayList<>();
    this.lexerStateStack = new ArrayDeque<>();
    this.brackets = new LinkedList<>();

    /*
     * Start in a DATA state by pushing it to the state stack. This state basically means that we
     * are NOT in between a pair of meaningful delimiters.
     */
    this.lexerStateStack.push(State.DATA);

    /*
     * loop through the entire source and apply different lexing methods depending on what kind of
     * state we are in at the time.
     *
     * This will always start on lexData();
     */
    while (this.source.length() > 0) {
      switch (this.lexerStateStack.peek()) {
        case DATA:
          this.tokenizeData();
          break;
        case EXECUTE:
          this.tokenizeBetweenExecuteDelimiters();
          break;
        case PRINT:
          this.tokenizeBetweenPrintDelimiters();
          break;
        case COMMENT:
          this.tokenizeComment();
          break;
        case STRING:
          this.tokenizeString();
          break;
        case STRING_INTERPOLATION:
          this.tokenizeStringInterpolation();
          break;
        default:
          break;
      }
    }

    // end of file token
    this.pushToken(Token.Type.EOF);
    this.popState();

    // make sure that all brackets have been closed, else throw an error
    if (!this.brackets.isEmpty()) {
      String expected = this.brackets.pop().getLeft();
      throw new ParserException(null, String.format("Unclosed \"%s\"", expected),
          this.source.getLineNumber(), this.source.getFilename());
    }

    return new TokenStream(this.tokens, this.source.getFilename());
  }

  private void tokenizeStringInterpolation() {
    this.logger.trace("Tokenizing String Interpolation");
    String lastBracket = this.brackets.peek().getLeft();
    Matcher matcher;
    if (this.syntax.getInterpolationOpenDelimiter().equals(lastBracket)
        && (matcher = this.lookingAt(this.syntax.getRegexInterpolationClose())) != null) {
      this.brackets.pop();
      this.pushToken(Token.Type.STRING_INTERPOLATION_END);
      this.source.advance(matcher.end());
      this.popState();
    } else {
      this.tokenizeExpression();
    }
  }

  private void tokenizeString() {
    this.logger.trace("Tokenizing String");
    // interpolation
    Matcher matcher = this.lookingAt(this.syntax.getRegexInterpolationOpen());
    if (matcher != null) {
      this.brackets.push(
          new Pair<>(this.syntax.getInterpolationOpenDelimiter(), this.source.getLineNumber()));
      this.pushToken(Token.Type.STRING_INTERPOLATION_START);
      this.source.advance(matcher.end());
      this.lexerStateStack.push(State.STRING_INTERPOLATION);
      return;
    }

    // regular string start (always full string if single quotes)
    matcher = this.lookingAt(REGEX_STRING_NON_INTERPOLATED_PART);
    if (matcher != null && matcher.end() > 0) {
      String token = this.source.substring(matcher.end());
      this.source.advance(matcher.end());
      this.pushToken(Token.Type.STRING, token);
      return;
    }

    // end of string (which may have contained interpolation)
    matcher = this.lookingAt(REGEX_DOUBLEQUOTE);
    if (matcher != null) {
      String expected = this.brackets.pop().getLeft();

      if (this.source.charAt(0) != '"') {
        throw new ParserException(null, String.format("Unclosed \"%s\"", expected),
            this.source.getLineNumber(), this.source.getFilename());
      }

      this.popState();
      this.source.advance(matcher.end());
    }
  }

  /**
   * The DATA state assumes that we are current NOT in between any pair of meaningful delimiters. We
   * are currently looking for the next "open" or "start" delimiter, ex. the opening comment
   * delimiter, or the opening variable delimiter.
   */
  private void tokenizeData() {
    this.logger.trace("Tokenizing Data");
    // find the next start delimiter
    Matcher matcher = this.find(this.syntax.getRegexStartDelimiters());
    boolean match = matcher != null;

    String text;
    String startDelimiter = null;

    // if we didn't find another start delimiter, the text
    // token goes all the way to the end of the template.
    if (!match) {
      this.logger
          .trace("Advancing to the end of the template because no start delimiter was found");
      text = this.source.toString();
      this.source.advance(this.source.length());
    } else {
      text = this.source.substring(matcher.start());
      startDelimiter = this.source.substring(matcher.start(), matcher.end());
      this.logger.trace("Start Deliminter Token string: {}", startDelimiter);
      // advance to after the start delimiter
      this.source.advance(matcher.end());
    }

    // trim leading whitespace from this text if we previously
    // encountered the appropriate whitespace trim character
    if (this.trimLeadingWhitespaceFromNextData) {
      this.logger.trace("Left Trimming text");
      text = StringUtils.ltrim(text);
      this.trimLeadingWhitespaceFromNextData = false;
    }
    Token textToken = this.pushToken(Type.TEXT, text);

    if (match) {
      this.checkForLeadingWhitespaceTrim(textToken);
      if (this.syntax.getCommentOpenDelimiter().equals(startDelimiter)) {
        // we don't actually push any tokens for comments
        this.lexerStateStack.push(State.COMMENT);
      } else if (this.syntax.getPrintOpenDelimiter().equals(startDelimiter)) {
        this.pushToken(Token.Type.PRINT_START);
        this.lexerStateStack.push(State.PRINT);
      } else if ((this.syntax.getExecuteOpenDelimiter().equals(startDelimiter))) {
        // check for verbatim tag
        Matcher verbatimStartMatcher = this.lookingAt(this.syntax.getRegexVerbatimStart());
        if (verbatimStartMatcher != null) {
          this.lexVerbatimData(verbatimStartMatcher);
          this.lexerStateStack.push(State.DATA);
        } else {
          this.pushToken(Token.Type.EXECUTE_START);
          this.lexerStateStack.push(State.EXECUTE);
        }
      }
    }
  }

  /**
   * Tokenizes between execute delimiters.
   */
  private void tokenizeBetweenExecuteDelimiters() {
    this.logger.trace("Tokenize between execute delimiters");
    // check for the trailing whitespace trim character
    this.checkForTrailingWhitespaceTrim();

    Matcher matcher;

    // check if we are at the execute closing delimiter
    if (this.brackets.isEmpty()
        && (matcher = this.lookingAt(this.syntax.getRegexExecuteClose())) != null) {
      this.pushToken(Token.Type.EXECUTE_END, this.syntax.getExecuteCloseDelimiter());
      this.source.advance(matcher.end());
      this.popState();
    } else {
      this.tokenizeExpression();
    }
  }

  /**
   * Tokenizes between print delimiters.
   */
  private void tokenizeBetweenPrintDelimiters() {

    // check for the trailing whitespace trim character
    this.checkForTrailingWhitespaceTrim();

    Matcher matcher;

    // check if we are at the print closing delimiter
    if (this.brackets.isEmpty()
        && (matcher = this.lookingAt(this.syntax.getRegexPrintClose())) != null) {
      this.pushToken(Token.Type.PRINT_END, this.syntax.getPrintCloseDelimiter());
      this.source.advance(matcher.end());
      this.popState();
    } else {
      this.tokenizeExpression();
    }
  }

  /**
   * Tokenizes between comment delimiters.
   * <p>
   * Simply find the closing delimiter for the comment and move the cursor to that point.
   */
  private void tokenizeComment() {

    // all we need to do is find the end of the comment.
    Matcher matcher = this.find(this.syntax.getRegexCommentClose());

    boolean match = matcher != null;
    if (!match) {
      throw new ParserException(null, "Unclosed comment.", this.source.getLineNumber(),
          this.source.getFilename());
    }

    /*
     * check if the commented ended with the whitespace trim character by reversing the comment and
     * performing a regular forward regex search.
     */
    String comment = this.source.substring(matcher.start());
    String reversedComment = new StringBuilder(comment).reverse().toString();
    Matcher whitespaceTrimMatcher =
        this.syntax.getRegexLeadingWhitespaceTrim().matcher(reversedComment);
    if (whitespaceTrimMatcher.lookingAt()) {
      this.trimLeadingWhitespaceFromNextData = true;
    }

    // move cursor to end of comment (and closing delimiter)
    this.source.advance(matcher.end());
    this.popState();
  }

  /**
   * Tokenizing an expression which can be found within both execute and print regions.
   */
  private void tokenizeExpression() {
    this.logger.trace("Tokenizing Expression");
    String token;
    this.source.advanceThroughWhitespace();
    /*
     * Matcher matcher = REGEX_WHITESPACE.matcher(source); if (matcher.lookingAt()) {
     * source.advance(matcher.end()); }
     */

    // operators
    Matcher matcher = this.lookingAt(this.regexOperators);
    if (matcher != null) {
      token = this.source.substring(matcher.end());
      this.pushToken(Token.Type.OPERATOR, token);
      this.source.advance(matcher.end());
      return;
    }

    // names
    matcher = this.lookingAt(REGEX_IDENTIFIER);
    if (matcher != null) {
      token = this.source.substring(matcher.end());
      this.pushToken(Token.Type.NAME, token);
      this.source.advance(matcher.end());
      return;
    }

    // long
    matcher = this.lookingAt(REGEX_LONG);
    if (matcher != null) {
      token = this.source.substring(matcher.end() - 1);
      this.pushToken(Token.Type.LONG, token);
      this.source.advance(matcher.end());
      return;
    }

    // numbers
    matcher = this.lookingAt(REGEX_NUMBER);
    if (matcher != null) {
      token = this.source.substring(matcher.end());
      this.pushToken(Token.Type.NUMBER, token);
      this.source.advance(matcher.end());
      return;
    }

    // punctuation
    if (PUNCTUATION.indexOf(this.source.charAt(0)) >= 0) {
      String character = String.valueOf(this.source.charAt(0));

      // opening bracket
      if ("([{".contains(character)) {
        this.brackets.push(new Pair<>(character, this.source.getLineNumber()));
      }

      // closing bracket
      else if (")]}".contains(character)) {
        if (this.brackets.isEmpty()) {
          throw new ParserException(null, "Unexpected \"" + character + "\"",
              this.source.getLineNumber(), this.source.getFilename());
        } else {
          HashMap<String, String> validPairs = new HashMap<>();
          validPairs.put("(", ")");
          validPairs.put("[", "]");
          validPairs.put("{", "}");
          String lastBracket = this.brackets.pop().getLeft();
          String expected = validPairs.get(lastBracket);
          if (!expected.equals(character)) {
            throw new ParserException(null, "Unclosed \"" + expected + "\"",
                this.source.getLineNumber(), this.source.getFilename());
          }
        }
      }

      this.pushToken(Token.Type.PUNCTUATION, character);
      this.source.advance(1);
      return;
    }

    // Plain (non-interpolated) string
    matcher = this.lookingAt(REGEX_STRING_PLAIN);
    if (matcher != null) {
      token = this.source.substring(matcher.end());
      this.source.advance(matcher.end());
      token = this.unquoteAndUnescape(token);
      this.pushToken(Token.Type.STRING, token);
      return;
    }

    // Interpolated strings
    matcher = this.lookingAt(REGEX_DOUBLEQUOTE);
    if (matcher != null) {
      this.brackets.push(new Pair<>("\"", this.source.getLineNumber()));
      this.lexerStateStack.push(State.STRING);
      this.source.advance(matcher.end());
      return;
    }

    // we should have found something and returned by this point
    throw new ParserException(null,
        String.format("Unexpected character [%s]", this.source.charAt(0)),
        this.source.getLineNumber(), this.source.getFilename());
  }

  /**
   * This method assumes the provided {@code str} starts with a single or double quote. It removes
   * the wrapping quotes, and un-escapes any quotes within the string.
   */
  private String unquoteAndUnescape(String str) {
    char quotationType = str.charAt(0);

    // remove first and last quotation marks
    str = str.substring(1, str.length() - 1);

    // remove backslashes used to escape inner quotation marks
    if (quotationType == '\'') {
      str = str.replaceAll("\\\\(')", "$1");
    } else if (quotationType == '"') {
      str = str.replaceAll("\\\\(\")", "$1");
    }
    return str;
  }

  private void checkForLeadingWhitespaceTrim(Token leadingToken) {

    Matcher whitespaceTrimMatcher = this.lookingAt(this.syntax.getRegexLeadingWhitespaceTrim());

    if (whitespaceTrimMatcher != null) {
      this.logger.trace("Found Leading Whitespace Trim Character");
      if (leadingToken != null) {
        this.logger.trace("Right trimming leading token: {}", leadingToken);
        leadingToken.setValue(StringUtils.rtrim(leadingToken.getValue()));
      }
      this.source.advance(whitespaceTrimMatcher.end());
    }

  }

  private void checkForTrailingWhitespaceTrim() {
    Matcher whitespaceTrimMatcher = this.lookingAt(this.syntax.getRegexTrailingWhitespaceTrim());

    if (whitespaceTrimMatcher != null) {
      this.trimLeadingWhitespaceFromNextData = true;
    }
  }

  /**
   * Implementation of the "verbatim" tag
   */
  private void lexVerbatimData(Matcher verbatimStartMatcher) {

    // move cursor past the opening verbatim tag
    this.source.advance(verbatimStartMatcher.end());

    // look for the "endverbatim" tag and storing everything between
    // now and then into a TEXT node
    Matcher verbatimEndMatcher = this.find(this.syntax.getRegexVerbatimEnd());

    // check for EOF
    if (verbatimEndMatcher == null) {
      throw new ParserException(null, "Unclosed verbatim tag.", this.source.getLineNumber(),
          this.source.getFilename());
    }
    String verbatimText = this.source.substring(verbatimEndMatcher.start());

    // check if the verbatim start tag has a trailing whitespace trim
    if (verbatimStartMatcher.group(0) != null) {
      verbatimText = StringUtils.ltrim(verbatimText);
    }

    // check if the verbatim end tag had a leading whitespace trim
    if (verbatimEndMatcher.group(1) != null) {
      verbatimText = StringUtils.rtrim(verbatimText);
    }

    // check if the verbatim end tag had a trailing whitespace trim
    if (verbatimEndMatcher.group(2) != null) {
      this.trimLeadingWhitespaceFromNextData = true;
    }

    // move cursor past the verbatim text and end delimiter
    this.source.advance(verbatimEndMatcher.end());

    this.pushToken(Type.TEXT, verbatimText);
  }

  /**
   * Matches {@code pattern} at the start of the remaining source.
   *
   * @return the matcher, or null if the pattern does not match there
   */
  private Matcher lookingAt(Pattern pattern) {
    return this.match(pattern, false);
  }

  /**
   * Finds the first match of {@code pattern} in the remaining source.
   *
   * @return the matcher, or null if there is no match
   */
  private Matcher find(Pattern pattern) {
    return this.match(pattern, true);
  }

  /*
   * Code On The Go (ADFA-3305): on Android, java.util.regex runs on ICU, and creating a Matcher
   * copies its whole input twice: TemplateSource.toString(), then ICU's own buffer (libcore
   * Matcher.reset, ICU MatcherState::updateInput). Matching each token against the rest of the
   * template made lexing quadratic: an 807 KB template took 93-114 s to compile on a Pixel 6 Pro.
   * So a match runs on a window at the start of the remaining source, and again on one twice as
   * long whenever the engine read the window's end, which is what hitEnd() reports: only then could
   * more input change the result, even for a match that stops short of the end, such as an
   * operator after its lookahead. A result reached with hitEnd() false is the one the whole
   * remaining source gives, provided the window does not split a surrogate pair (see below).
   */
  private Matcher match(Pattern pattern, boolean find) {
    int remaining = this.source.length();
    int window = Math.min(Math.max(this.matchWindow, 1), remaining);
    this.matches++;
    while (true) {
      // Both engines read a lone high surrogate at the window's end as a whole code point, without
      // reporting the end, so a window must not split a surrogate pair.
      if (window < remaining && Character.isHighSurrogate(this.source.charAt(window - 1))) {
        window++;
      }
      this.windows++;
      this.windowChars += window;
      Matcher matcher = this.matcherOnWindow(pattern, remaining, window);
      boolean matched = find ? matcher.find() : matcher.lookingAt();
      if (window == remaining || !matcher.hitEnd()) {
        return matched ? matcher : null;
      }
      window = (int) Math.min((long) window * 2, remaining);
    }
  }

  /*
   * Code On The Go (ADFA-3305): on Android a new Matcher allocates native ICU state, which the GC
   * counts as a native allocation. A Matcher per pattern tried at every position, over a new String
   * copy of the window each time, was most of lexing's cost there. So each pattern keeps one
   * Matcher, reset onto the window, and the patterns tried at one position share one String copy of
   * it. Each reset still copies that String into the Matcher's ICU buffer, which is cheap at 256
   * characters. That is safe because a lexer is used by one thread,
   * as its source and tokens already require, and every caller reads the Matcher it is handed before
   * matching that pattern again. The source only shrinks, so its remaining length and the window's
   * length name one window of one template.
   */
  Matcher matcherOnWindow(Pattern pattern, int remaining, int window) {
    if (remaining != this.windowRemaining || window != this.windowLength) {
      this.windowText = this.source.subSequence(0, window).toString();
      this.windowRemaining = remaining;
      this.windowLength = window;
      this.windowsCut++;
    }
    Matcher matcher = this.matchers.get(pattern);
    if (matcher == null) {
      matcher = pattern.matcher(this.windowText);
      this.matchers.put(pattern, matcher);
      this.matchersCreated++;
    } else {
      matcher.reset(this.windowText);
    }
    return matcher;
  }

  /**
   * Create a Token with a Token Type but without no value onto the list of tokens that we are
   * maintaining.
   *
   * @param type The type of Token we are creating
   */
  private Token pushToken(Token.Type type) {
    Token token = this.pushToken(type, null);
    return token;
  }

  /**
   * Create a Token of a certain type and value and push it into the list of tokens that we are
   * maintaining. `
   *
   * @param type The type of token we are creating
   * @param value The value of the new token
   */
  private Token pushToken(Token.Type type, String value) {
    // ignore empty text tokens
    if (type.equals(Token.Type.TEXT) && (value == null || "".equals(value))) {
      this.logger.trace("Skipping empty text token");
      return null;
    }

    Token token = new Token(type, value, this.source.getLineNumber());
    this.tokens.add(token);
    this.logger.trace("Pushing Token: {}", token);

    return token;
  }

  /**
   * Pop state from the stack
   */
  private void popState() {
    this.lexerStateStack.pop();
  }

  /**
   * Retrieves the operators (both unary and binary) from the PebbleEngine and then dynamically
   * creates one giant regular expression to detect for the existence of one of these operators.
   */
  private void buildOperatorRegex() {

    List<String> operators = new ArrayList<>();

    for (UnaryOperator operator : this.unaryOperators) {
      operators.add(operator.getSymbol());
    }

    for (BinaryOperator operator : this.binaryOperators) {
      operators.add(operator.getSymbol());
    }

    /*
     * Since java's matcher doesn't conform with the posix standard of matching the longest
     * alternative (it matches the first alternative), we must first sort all of the operators by
     * length before creating the regex. This is to help match "is not" over "is".
     */
    operators.sort(StringLengthComparator.INSTANCE);

    StringBuilder regex = new StringBuilder("^");

    boolean isFirst = true;
    for (String operator : operators) {
      if (isFirst) {
        isFirst = false;
      } else {
        regex.append("|");
      }
      regex.append(Pattern.quote(operator));

      /*
       * If the operator ends in an alpha character we use a negative lookahead assertion to make
       * sure the next character in the stream is NOT an alpha character. This ensures user can type
       * "organization" without the "or" being parsed as an operator.
       */
      char nextChar = operator.charAt(operator.length() - 1);
      if (Character.isLetter(nextChar) || Character.getType(nextChar) == Character.LETTER_NUMBER) {
        regex.append("(?![a-zA-Z0-9_])");
      }
    }

    // Code On The Go (ADFA-3305): compiled once per set of operators instead of once per template,
    // since building an ICU Pattern is costly on Android. A Pattern is immutable, so lexers on any
    // thread may share it. The regex is built from the operators' symbols alone and is its own key,
    // so lexers with different operators never share one.
    this.regexOperators = OPERATOR_REGEXES.computeIfAbsent(regex.toString(), Pattern::compile);
  }

}
