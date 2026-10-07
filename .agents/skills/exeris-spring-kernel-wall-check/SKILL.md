---
name: exeris-spring-kernel-wall-check
description: Prevent Spring leakage into kernel boundaries and preserve The Wall. Verifies no Spring imports/types/annotations in kernel SPI/Core touchpoints, no framework-coupled contracts in Exeris kernel abstractions, no bypass of canonical Exeris provider discovery, and no kernel API changes that assume servlet/reactive/JDBC framework runtime.
---

# exeris-spring-kernel-wall-check

## Purpose
Prevent Spring leakage into kernel boundaries and preserve The Wall.

## Checks
1. No Spring imports/types/annotations in kernel SPI/Core touchpoints.
2. No framework-coupled contracts pushed into Exeris kernel abstractions.
3. No bypass of canonical Exeris provider discovery ownership.
4. No kernel API changes that assume servlet/reactive/JDBC framework runtime.

## Output
- wall compliance status (`PASS | RISK | FAIL`)
- violating symbols/files (if any)
- required isolation/refactor action
