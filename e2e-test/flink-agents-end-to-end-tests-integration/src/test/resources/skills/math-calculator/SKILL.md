---
name: math-calculator
description: Calculate simple mathematical expressions using shell commands. Use when the user asks to perform arithmetic calculations like addition, subtraction, multiplication, division, or more complex math expressions.
license: Apache-2.0
compatibility: Requires bash with bc (basic calculator)
---

# Math Calculator Skill

This skill provides the ability to calculate mathematical expressions using shell commands.

## When to Use

Use this skill when:
- Performing arithmetic calculations (add, subtract, multiply, divide)
- Evaluating mathematical expressions with parentheses
- Computing percentages or powers
- Any numeric computation requested by the user

## Methods

### Using `bc` (Basic Calculator)

The `bc` command is a powerful calculator that supports:
- Basic arithmetic: `+`, `-`, `*`, `/`
- Power: `^`
- Parentheses for grouping
- Scale for decimal precision

**Example:**
```bash
echo "2 + 3 * 4" | bc
# Output: 14

echo "scale=2; 10 / 3" | bc
# Output: 3.33

echo "(2 + 3) * 4" | bc
# Output: 20
```

## Supported Operations

| Operation | Symbol | Example |
|-----------|--------|---------|
| Addition | `+` | `5 + 3 = 8` |
| Subtraction | `-` | `10 - 4 = 6` |
| Multiplication | `*` | `6 * 7 = 42` |
| Division | `/` | `15 / 3 = 5` |
| Power | `^` (bc) or `**` (Python) | `2 ^ 3 = 8` |
| Modulo | `%` | `17 % 5 = 2` |
| Square Root | `sqrt()` (bc) | `sqrt(16) = 4` |

## Notes

- Use `scale=N` in `bc` to set decimal precision (default is 0, integer only)
- For floating-point division, always set `scale` in `bc`
