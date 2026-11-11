; Bracket pairs. Each pattern captures the open and the close node together.

(statement_block
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(class_body
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(switch_body
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(object
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(object_pattern
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(array
  "[" @editor.brackets.open
  "]" @editor.brackets.close)

(array_pattern
  "[" @editor.brackets.open
  "]" @editor.brackets.close)

(arguments
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(formal_parameters
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(parenthesized_expression
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(template_substitution
  "${" @editor.brackets.open
  "}" @editor.brackets.close)
