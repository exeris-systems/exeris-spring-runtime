/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.compat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

// tag::request[]
public record CreateOrder(@NotBlank String product, @Positive int quantity) {
}
// end::request[]
