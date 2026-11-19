---
file: javascript-intro.html
title: JavaScript basics
description: A short introduction to JavaScript for writing sqlite-js functions.
parent: sqlite-js-top.html
prev: sqlite-js-javascript.html
---

This page teaches enough JavaScript to write [sqlite-js functions](sqlite-js-top.html). Each example runs in `sqlite3` after you `.load sqlite-js`. The `js_eval` function runs JavaScript and returns the value of the last expression.

A name you declare with `const`, `let` or `class` lasts until you close `sqlite3`, and declaring it again fails with `redeclaration of`. That is why the longer examples put their code in braces, `{ ... }`: the names then last for one run, so you can run the example again.

## Values and variables

`const` makes a variable that you never reassign. `let` makes one that you can. Text goes in quotes, and a template string, in backticks, can include values with `${...}`.

```sql
SELECT js_eval('{
  const name = "Code on the Go";
  let count = 3;
  count = count + 1;
  `${name} has ${count} tools`;
}');
-- Code on the Go has 4 tools
```

## Numbers

JavaScript uses one kind of number for whole numbers and fractions alike. `Math` has the usual functions.

```sql
SELECT js_eval('Math.max(3, 9, 4)'), js_eval('Math.round(2.5)'), js_eval('10 % 3'), js_eval('2 ** 10');
-- 9|3|1|1024
```

## Text

A test such as `includes` gives `true` or `false`, which SQL shows as 1 or 0.

```sql
SELECT js_eval('"  Hello, World  ".trim().toUpperCase()'), js_eval('"a,b,c".split(",").length'), js_eval('"abcdef".slice(1, 4)'), js_eval('"hello".includes("ell")');
-- HELLO, WORLD|3|bcd|1
```

## Arrays

An array is an ordered list. Its `map`, `filter` and `reduce` methods take a small function, written as an arrow function `x => ...`.

```sql
SELECT js_eval('[1, 2, 3, 4].map(x => x * x).join(",")'),
       js_eval('[1, 2, 3, 4].filter(x => x % 2 === 0).join(",")'),
       js_eval('[1, 2, 3, 4].reduce((sum, x) => sum + x, 0)');
-- 1,4,9,16|2,4|10
```

## Objects and JSON

An object holds named values. `JSON.stringify` turns it into text, and `JSON.parse` turns text back into an object. This is how a function can work with JSON stored in a column.

```sql
SELECT js_eval('JSON.stringify({ name: "ada", langs: ["js", "sql"] })'),
       js_eval('JSON.parse(`{"price": 12, "qty": 3}`).price * 3');
-- {"name":"ada","langs":["js","sql"]}|36
```

## Decisions and loops

```sql
SELECT js_eval('{
  let total = 0;
  for (let i = 1; i <= 5; i++) {
    if (i % 2 === 1) total += i;
  }
  total;
}');
-- 9
```

## Your own functions

A function takes values and returns one. An arrow function is a short way to write one.

```sql
SELECT js_eval('{
  function area(w, h) { return w * h; }
  const half = n => n / 2;
  half(area(3, 4));
}');
-- 6
```

## Dates

Months count from 0, so 10 is November.

```sql
SELECT js_eval('new Date(Date.UTC(2026, 10, 11)).toISOString().slice(0, 10)');
-- 2026-11-11
```

## Handling errors

`try` and `catch` let your code recover when something throws an exception.

```sql
SELECT js_eval('{
  let result;
  try { result = JSON.parse("not json"); } catch (e) { result = "bad input"; }
  result;
}');
-- bad input
```

## Matching patterns

A regular expression finds text that matches a pattern.

```sql
SELECT js_eval('"order 66 shipped on 2026-11-11".match(/\d{4}-\d{2}-\d{2}/)[0]');
-- 2026-11-11
```

## Keep learning

The JavaScript Guide on MDN, at developer.mozilla.org, teaches the whole language. In sqlite-js, ignore the parts about web pages, such as the DOM, `fetch` and timers: see [JavaScript in sqlite-js](sqlite-js-javascript.html) for what is available.
