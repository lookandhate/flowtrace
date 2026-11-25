# Changelog

## [Unreleased]

## [0.4.0]

- Every use of the traced value is a separate row with its kind: assignments, call arguments, returns, method calls, struct fields, reassignments of the variable, declarations, other uses.
- Direct uses are grouped by kind and can be filtered to chosen kinds; the subtree of each kept use stays complete.
- Dataflow-style tree: line number, the code line with syntax highlighting and the traced occurrence in bold, and the enclosing function.
- Reassignments are found in all forms: `x = …`, `x += …`, `x++` and redeclaration `a, x := …`.
- Two calls with the same argument are now two rows instead of one.
- Toolbar: group by kind, kind filter, expand and collapse all, autoscroll to source, show or hide the graph.

## [0.3.0]

First public release.

- Trace Value Origins: finds the topmost sources of a value (literals, constructors, external calls) and the path through arguments, assignments, struct fields, returns and interface calls.
- Trace Value Destinations: follows a value through parameters, constructors, fields and method receivers to the places where it is finally used.
- Flow Trace tool window with a tree of sources or end points, the full trace tree and a layered graph; double-click opens the code.
- English UI by default, Russian with the Russian language pack.
