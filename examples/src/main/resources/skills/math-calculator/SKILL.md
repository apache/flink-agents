---
name: math-calculator
description: Calculate mathematical expressions using shell commands. Use when the user asks to perform arithmetic calculations like addition, subtraction, multiplication, division, or powers.
license: Apache-2.0
compatibility: Requires bash with bc (basic calculator)
---

# Math Calculator Skill

This skill calculates mathematical expressions using shell commands.

## When to Use

Use this skill when the user asks to evaluate a numeric expression, such as:

- Basic arithmetic (add, subtract, multiply, divide)
- Expressions with parentheses
- Powers and square roots

## Method

Pipe the expression into the `bc` (basic calculator) command:

```bash
echo "(2 + 3) * 4" | bc
# Output: 20

echo "2 ^ 10" | bc
# Output: 1024
```

For decimal results, set the scale first:

```bash
echo "scale=2; 10 / 3" | bc
# Output: 3.33
```

## Instructions

1. Translate the user's question into a single `bc` expression.
2. Run it with the `bash` tool: `echo "<expression>" | bc`.
3. Report the number printed by `bc` as the answer.
