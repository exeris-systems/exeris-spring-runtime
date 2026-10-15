---
title: "Licensing"
type: reference
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-10-15
---
# Licensing

This file is the answer to "what licence is this, exactly". `LICENSE` is the operative
text; this is the map.

## This repository

| What | Licence | SPDX |
|---|---|---|
| Everything in `exeris-systems/exeris-spring-runtime` — all reactor modules, tests, docs | Apache License, Version 2.0 | `Apache-2.0` |

The text in `LICENSE` is the unmodified Apache License 2.0, identical to the text
published at <https://www.apache.org/licenses/LICENSE-2.0.txt>. There is no addendum,
no additional condition, and no field-of-use restriction. Every Java source file carries
`SPDX-License-Identifier: Apache-2.0`.

Published Maven coordinates under the `eu.exeris` namespace that originate from this
repository carry the same licence, declared once in the root `pom.xml`
(`eu.exeris:exeris-spring-runtime-parent`) and inherited by every module.

## Relationship to the kernel

This repository bridges Spring applications onto the Exeris kernel, which is licensed
under the same Apache License 2.0 (`exeris-systems/exeris-kernel`). The closed
enterprise tier is a separate distribution and is not part of this repository.

## Trademarks

Apache-2.0 section 6 grants no rights in trade names, trademarks, service marks, or
product names. See `NOTICE`.

## Third-party dependencies

The only vendored tree is `.agents/vendor/exeris-agents-2.1.0`, the organisation's agent
bundle, licensed Apache-2.0 and not part of any published artifact. Dependencies resolve
through Maven; consult the dependency tree of the artifact you consume.

## Inquiries

legal@exeris.eu
