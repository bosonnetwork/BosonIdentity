/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.Component
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes

/**
 * Lets Vert.x 5.1 start on Android.
 *
 * Every Vert.x 5.1 release initialises `io.vertx.core.impl.VertxImpl` with
 * `MethodHandles.lookup().findVarHandle(InternalTimerHandler.class, "disposed", boolean.class)`.
 * The field is private to the nested class `VertxImpl$InternalTimerHandler`. The JVM allows the
 * lookup because the two classes are nestmates; Android's runtime has no nestmate access for
 * method handles and throws IllegalAccessException. The class then fails to initialise, and the
 * first `Vertx.vertx()` dies with NoClassDefFoundError, on every Android version.
 *
 * This drops `private` from that one field as the classes are packaged, so the lookup becomes an
 * ordinary same-package access. Nothing else is touched. Remove it once a Vert.x release no longer
 * makes that lookup.
 *
 * Applied to each app variant and to every test component, app or library, so the test APKs that
 * create a Vertx get it too. A library's own variant is left alone: it does not package its
 * dependencies, and AGP refuses to instrument them there.
 */
class VertxAndroidFixPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
            project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
                .onVariants { variant -> instrument(listOf(variant) + variant.nestedComponents) }
        }
        project.pluginManager.withPlugin("com.android.library") {
            project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
                .onVariants { variant -> instrument(variant.nestedComponents) }
        }
    }

    private fun instrument(components: List<Component>) {
        components.forEach { component ->
            component.instrumentation.transformClassesWith(
                VertxTimerFieldAccess::class.java,
                InstrumentationScope.ALL,
            ) {}
        }
    }
}

/** Removes `private` from `VertxImpl$InternalTimerHandler.disposed`; see [VertxAndroidFixPlugin]. */
abstract class VertxTimerFieldAccess : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == "io.vertx.core.impl.VertxImpl\$InternalTimerHandler"

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(instrumentationContext.apiVersion.get(), nextClassVisitor) {
            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                val opened = if (name == "disposed") access and Opcodes.ACC_PRIVATE.inv() else access
                return super.visitField(opened, name, descriptor, signature, value)
            }
        }
}
