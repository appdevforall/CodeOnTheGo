(class_declaration
  "interface"
  (type_identifier) @name) @symbol.interface

(class_declaration
  (type_identifier) @name
  (enum_class_body)) @symbol.enum

(class_declaration
  (type_identifier) @name) @symbol.class

(object_declaration
  (type_identifier) @name) @symbol.object

(companion_object
  (type_identifier)? @name) @symbol.companion

(type_alias
  (type_identifier) @name) @symbol.typeAlias

(enum_entry
  (simple_identifier) @name) @symbol.enumMember

(class_body
  (function_declaration
    (simple_identifier) @name
    (function_value_parameters) @detail) @symbol.method)

(enum_class_body
  (function_declaration
    (simple_identifier) @name
    (function_value_parameters) @detail) @symbol.method)

(source_file
  (function_declaration
    (simple_identifier) @name
    (function_value_parameters) @detail) @symbol.method)

(secondary_constructor
  (function_value_parameters) @detail) @symbol.constructor

(class_body
  (property_declaration
    (variable_declaration
      (simple_identifier) @name)) @symbol.property)

(enum_class_body
  (property_declaration
    (variable_declaration
      (simple_identifier) @name)) @symbol.property)

(source_file
  (property_declaration
    (variable_declaration
      (simple_identifier) @name)) @symbol.property)

(class_parameter
  ["val" "var"]
  (simple_identifier) @name) @symbol.property
