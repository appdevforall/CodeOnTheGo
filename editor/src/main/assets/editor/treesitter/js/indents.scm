; Indentation, in nvim-treesitter's capture names, which TreeSitterIndentProvider reads.

; format-ignore
[
  (class_body)                ; class X { ... }
  (statement_block)           ; { ... } of functions, if, for, while, try
  (switch_body)               ; switch (x) { ... }
  (switch_case)               ; case 1: ...
  (switch_default)            ; default: ...
  (object)                    ; { a: 1 }
  (object_pattern)            ; const { a } = x
  (array)                     ; [1, 2]
  (array_pattern)             ; const [a] = x
  (arguments)                 ; f(...)
  (formal_parameters)         ; function f(...)
  (named_imports)             ; import { a, b } from "m"
  (export_clause)             ; export { a, b }
  (parenthesized_expression)  ; ( ... )
  (template_substitution)     ; ${ ... }
  (jsx_element)               ; <div> ... </div>
] @indent.begin

[
  "("
  ")"
  "{"
  "}"
  "["
  "]"
] @indent.branch

[
  ")"
  "}"
  "]"
] @indent.end

(template_string) @indent.ignore

[
  (ERROR)
  (comment)
] @indent.auto
