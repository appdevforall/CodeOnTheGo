; JavaScript highlights, adapted from tree-sitter-javascript v0.23.1
; (queries/highlights.scm, highlights-params.scm, highlights-jsx.scm; MIT).
;
; The editor colors a range by the first capture that starts there. Matches come
; ordered by the depth of the pattern's root node, then by their order in this file,
; so a pattern rooted at a parent (call_expression, formal_parameters, ...) wins over
; a bare (identifier) pattern wherever it appears. Strings are captured piece by
; piece so that escapes and template substitutions keep their own colors. Capture
; names are the ones the generic color scheme styles.

(comment) @comment
(hash_bang_line) @comment

(string ["\"" "'"] @string)
(string (string_fragment) @string)
(template_string "`" @string)
(template_string (string_fragment) @string)
(escape_sequence) @string.escape
(template_substitution ["${" "}"] @operator)
(regex) @string
(number) @number

[(true) (false)] @boolean
[(null) (undefined)] @constant.builtin
[(this) (super)] @variable.builtin

(jsx_opening_element name: (_) @type)
(jsx_closing_element name: (_) @type)
(jsx_self_closing_element name: (_) @type)
(jsx_attribute (property_identifier) @attribute)

(class_declaration name: (identifier) @type)
(class_heritage (identifier) @type)
(new_expression constructor: (identifier) @type)

(function_declaration name: (identifier) @function)
(function_expression name: (identifier) @function)
(generator_function_declaration name: (identifier) @function)
(generator_function name: (identifier) @function)
(method_definition
  name: [(property_identifier) (private_property_identifier)] @function)
(pair
  key: (property_identifier) @function
  value: [(function_expression) (arrow_function)])
(variable_declarator
  name: (identifier) @function
  value: [(function_expression) (arrow_function)])
(assignment_expression
  left: (identifier) @function
  right: [(function_expression) (arrow_function)])
(assignment_expression
  left: (member_expression property: (property_identifier) @function)
  right: [(function_expression) (arrow_function)])

(formal_parameters
  [
    (identifier) @variable.parameter
    (assignment_pattern left: (identifier) @variable.parameter)
    (rest_pattern (identifier) @variable.parameter)
    (array_pattern (identifier) @variable.parameter)
    (object_pattern
      [
        (pair_pattern value: (identifier) @variable.parameter)
        (shorthand_property_identifier_pattern) @variable.parameter
      ])
  ])
(arrow_function parameter: (identifier) @variable.parameter)

(call_expression
  function: ((identifier) @function.builtin
    (#eq? @function.builtin "require")))
(call_expression function: (identifier) @function.call)
(call_expression
  function: (member_expression
    property: [(property_identifier) (private_property_identifier)] @function.call))

(field_definition
  property: [(property_identifier) (private_property_identifier)] @property)
(labeled_statement label: (statement_identifier) @label)
(break_statement label: (statement_identifier) @label)
(continue_statement label: (statement_identifier) @label)

((identifier) @variable.builtin
  (#match? @variable.builtin "^(arguments|console|document|globalThis|module|window)$"))
([(identifier) (shorthand_property_identifier) (shorthand_property_identifier_pattern)] @constant
  (#match? @constant "^[A-Z_][A-Z0-9_]+$"))
((identifier) @type
  (#match? @type "^[A-Z]"))

[(property_identifier) (private_property_identifier)] @property

[
  "-"
  "--"
  "-="
  "+"
  "++"
  "+="
  "*"
  "*="
  "**"
  "**="
  "/"
  "/="
  "%"
  "%="
  "<"
  "<="
  "<<"
  "<<="
  "="
  "=="
  "==="
  "!"
  "!="
  "!=="
  "=>"
  ">"
  ">="
  ">>"
  ">>="
  ">>>"
  ">>>="
  "~"
  "^"
  "&"
  "|"
  "^="
  "&="
  "|="
  "&&"
  "||"
  "??"
  "&&="
  "||="
  "??="
] @operator

[
  "as"
  "async"
  "await"
  "break"
  "case"
  "catch"
  "class"
  "const"
  "continue"
  "debugger"
  "default"
  "delete"
  "do"
  "else"
  "export"
  "extends"
  "finally"
  "for"
  "from"
  "function"
  "get"
  "if"
  "import"
  "in"
  "instanceof"
  "let"
  "new"
  "of"
  "return"
  "set"
  "static"
  "switch"
  "target"
  "throw"
  "try"
  "typeof"
  "var"
  "void"
  "while"
  "with"
  "yield"
] @keyword
