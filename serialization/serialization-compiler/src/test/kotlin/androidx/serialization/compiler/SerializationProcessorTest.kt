/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.serialization.compiler

import com.google.testing.compile.Compilation
import com.google.testing.compile.CompilationSubject.assertThat
import com.google.testing.compile.Compiler.javac
import com.google.testing.compile.JavaFileObjects
import org.junit.Test
import javax.tools.JavaFileObject

/** Unit tests for [SerializationProcessor]. */
class SerializationProcessorTest {
    @Test
    fun testSucceedsWithoutWarnings() {
        val testClass = JavaFileObjects.forSourceString("Test", "public class Test {}")
        assertThat(compile(testClass)).succeededWithoutWarnings()
    }

    private fun compile(vararg sources: JavaFileObject): Compilation {
        return javac().withProcessors(SerializationProcessor()).compile(*sources)
    }
}
