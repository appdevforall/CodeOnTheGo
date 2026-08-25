(class_declaration
  name: (identifier) @name) @symbol.class

(interface_declaration
  name: (identifier) @name) @symbol.interface

(enum_declaration
  name: (identifier) @name) @symbol.enum

(record_declaration
  name: (identifier) @name) @symbol.record

(annotation_type_declaration
  name: (identifier) @name) @symbol.annotation

(enum_constant
  name: (identifier) @name) @symbol.enumMember

(constructor_declaration
  name: (identifier) @name
  parameters: (formal_parameters) @detail) @symbol.constructor

(method_declaration
  name: (identifier) @name
  parameters: (formal_parameters) @detail) @symbol.method

(field_declaration
  declarator: (variable_declarator
    name: (identifier) @name)) @symbol.field
