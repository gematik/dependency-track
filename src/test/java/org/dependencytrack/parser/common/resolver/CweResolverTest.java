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
package org.dependencytrack.parser.common.resolver;

import org.dependencytrack.PersistenceCapableTest;
import org.dependencytrack.model.Cwe;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CweResolverTest extends PersistenceCapableTest {

    @Test
    void testPositiveResolutionByCweId() {
        Cwe cwe = CweResolver.getInstance().lookup("CWE-79");
        Assertions.assertNotNull(cwe);
        Assertions.assertEquals(79, cwe.getCweId());
    }

    @Test
    void testPositiveResolutionByCweIdIntegerOnly() {
        Cwe cwe = CweResolver.getInstance().lookup("79");
        Assertions.assertNotNull(cwe);
        Assertions.assertEquals(79, cwe.getCweId());
    }

    @Test
    void testPositiveResolutionByCweIdAndName() {
        Cwe cwe = CweResolver.getInstance().lookup("CWE-79 Improper Neutralization of Input During Web Page Generation ('Cross-site Scripting')");
        Assertions.assertNotNull(cwe);
        Assertions.assertEquals(79, cwe.getCweId());
    }

    @Test
    void testNegativeResolutionByCweId() {
        Cwe cwe = CweResolver.getInstance().lookup("CWE-9999");
        Assertions.assertNull(cwe);
    }

    @Test
    void testNegativeResolutionByInvalidCweId() {
        Cwe cwe = CweResolver.getInstance().lookup("CWE-A");
        Assertions.assertNull(cwe);
    }
}
