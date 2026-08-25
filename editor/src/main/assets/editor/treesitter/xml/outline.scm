(end_tag_element
  (tag_start
    tag_name: (name) @name)) @symbol.element

(empty_element
  tag_name: (name) @name) @symbol.element

(empty_element
  tag_name: (name) @name
  (attribute
    (xml_attr
      attr_name: (name) @_a
      (attr_value) @detail))
  (#match? @_a "^id$")) @symbol.element

(end_tag_element
  (tag_start
    tag_name: (name) @name
    (attribute
      (xml_attr
        attr_name: (name) @_a
        (attr_value) @detail)))
  (#match? @_a "^id$")) @symbol.element
