(class_declaration
  (type_identifier) @name) @symbol.class

(object_declaration
  (type_identifier) @name) @symbol.object

(companion_object) @symbol.companion

(type_alias
  (type_identifier) @name) @symbol.typeAlias

(enum_entry
  (simple_identifier) @name) @symbol.enumMember

(function_declaration
  (simple_identifier) @name
  (function_value_parameters) @detail) @symbol.method

(secondary_constructor
  (function_value_parameters) @detail) @symbol.constructor

(property_declaration
  (variable_declaration
    (simple_identifier) @name)) @symbol.property

(class_parameter
  (simple_identifier) @name) @symbol.property
