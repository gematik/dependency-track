/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.notification;

import alpine.notification.Notification;
import alpine.notification.NotificationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;

import static org.mockito.Mockito.mock;

class NotificationSubsystemInitializerTest {

    @Test
    void testInitialization() {
        NotificationSubsystemInitializer listener = new NotificationSubsystemInitializer();
        listener.contextInitialized(new ServletContextEvent(mock(ServletContext.class)));
        Assertions.assertTrue(NotificationService.getInstance().hasSubscriptions(new Notification()));
    }
}
