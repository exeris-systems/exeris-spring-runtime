/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import eu.exeris.spring.runtime.events.ExerisEventAutoConfiguration;
import eu.exeris.spring.runtime.events.ExerisEventPublisher;
import eu.exeris.spring.runtime.flow.ExerisFlowAutoConfiguration;
import eu.exeris.spring.runtime.flow.ExerisFlowTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Both bridges stay off until their flag is set. */
class DefaultOffTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ExerisEventAutoConfiguration.class, ExerisFlowAutoConfiguration.class));

    @Test
    void neitherBridgeIsWiredWithoutItsFlag() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ExerisEventPublisher.class);
            assertThat(context).doesNotHaveBean(ExerisFlowTemplate.class);
        });
    }
}
