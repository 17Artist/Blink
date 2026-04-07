/*
 * Copyright 2026 17Artist
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package priv.seventeen.artist.blink.gradle

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type

class BytecodeGenerator(private val targetPkg: String) {

    companion object {
        private const val BLINK_KT = "priv/seventeen/artist/blink/BlinkKt"
        private const val KOTLIN_BOOTSTRAP = "priv/seventeen/artist/blink/bootstrap/KotlinBootstrap"
        private const val LIFECYCLE_MANAGER = "priv/seventeen/artist/blink/lifecycle/LifeCycleManager"
        private const val LIFECYCLE_ENUM = "priv/seventeen/artist/blink/lifecycle/LifeCycle"
        private const val EVENT_MANAGER = "priv/seventeen/artist/blink/event/EventManager"
        private const val SCRIPT_MANAGER = "priv/seventeen/artist/blink/script/ScriptManager"
        private const val ARIA_SCRIPT_MANAGER = "priv/seventeen/artist/blink/script/AriaScriptManager"
        private const val DEP_LOADER = "priv/seventeen/artist/blink/loader/DependencyLoader"

        private const val JAVA_PLUGIN = "org/bukkit/plugin/java/JavaPlugin"
        private const val EVENT_PRIORITY = "org/bukkit/event/EventPriority"
        private const val EVENT_EXECUTOR = "org/bukkit/plugin/EventExecutor"
        private const val EVENT_CLASS = "org/bukkit/event/Event"
        private const val LISTENER = "org/bukkit/event/Listener"
        private const val SCHEDULER = "org/bukkit/scheduler/BukkitScheduler"
    }

    fun generateMainClass(enableScript: Boolean, enableAria: Boolean): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        val mainClass = "$targetPkg/BlinkGeneratedMain"

        cw.visit(V17, ACC_PUBLIC or ACC_SUPER, mainClass, null, JAVA_PLUGIN, null)
        generateConstructor(cw, JAVA_PLUGIN)
        generateOnLoad(cw, mainClass, enableScript, enableAria)
        generateOnEnable(cw, mainClass)
        generateOnDisable(cw, enableScript, enableAria)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun generateConstructor(cw: ClassWriter, superClass: String) {
        val mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKESPECIAL, superClass, "<init>", "()V", false)
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateOnLoad(cw: ClassWriter, @Suppress("UNUSED_PARAMETER") mainClass: String, enableScript: Boolean, enableAria: Boolean) {
        val mv = cw.visitMethod(ACC_PUBLIC, "onLoad", "()V", null, null)
        mv.visitCode()

        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKESTATIC, KOTLIN_BOOTSTRAP, "bootstrap", "(L$JAVA_PLUGIN;)V", false)

        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKESTATIC, BLINK_KT, "setBukkitPlugin", "(L$JAVA_PLUGIN;)V", false)

        mv.visitFieldInsn(GETSTATIC, DEP_LOADER, "INSTANCE", "L$DEP_LOADER;")
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKEVIRTUAL, DEP_LOADER, "loadAll", "(L$JAVA_PLUGIN;)V", false)

        if (enableScript) {
            mv.visitFieldInsn(GETSTATIC, DEP_LOADER, "INSTANCE", "L$DEP_LOADER;")
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKEVIRTUAL, DEP_LOADER, "loadNashorn", "(L$JAVA_PLUGIN;)Ljava/util/List;", false)
            mv.visitVarInsn(ASTORE, 1)

            mv.visitFieldInsn(GETSTATIC, SCRIPT_MANAGER, "INSTANCE", "L$SCRIPT_MANAGER;")
            mv.visitVarInsn(ALOAD, 1)
            mv.visitMethodInsn(INVOKEVIRTUAL, SCRIPT_MANAGER, "init", "(Ljava/util/List;)V", false)
        }

        if (enableAria) {
            mv.visitFieldInsn(GETSTATIC, DEP_LOADER, "INSTANCE", "L$DEP_LOADER;")
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKEVIRTUAL, DEP_LOADER, "loadAria", "(L$JAVA_PLUGIN;)V", false)

            mv.visitFieldInsn(GETSTATIC, ARIA_SCRIPT_MANAGER, "INSTANCE", "L$ARIA_SCRIPT_MANAGER;")
            mv.visitMethodInsn(INVOKEVIRTUAL, ARIA_SCRIPT_MANAGER, "init", "()V", false)
        }

        mv.visitMethodInsn(INVOKESTATIC, "$targetPkg/BlinkGeneratedLifeCycle", "registerAll", "()V", false)

        mv.visitFieldInsn(GETSTATIC, LIFECYCLE_MANAGER, "INSTANCE", "L$LIFECYCLE_MANAGER;")
        mv.visitFieldInsn(GETSTATIC, LIFECYCLE_ENUM, "LOAD", "L$LIFECYCLE_ENUM;")
        mv.visitMethodInsn(INVOKEVIRTUAL, LIFECYCLE_MANAGER, "trigger", "(L$LIFECYCLE_ENUM;)V", false)

        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateOnEnable(cw: ClassWriter, mainClass: String) {
        val mv = cw.visitMethod(ACC_PUBLIC, "onEnable", "()V", null, null)
        mv.visitCode()

        mv.visitMethodInsn(INVOKESTATIC, "$targetPkg/BlinkGeneratedEvents", "registerAll", "()V", false)

        mv.visitFieldInsn(GETSTATIC, LIFECYCLE_MANAGER, "INSTANCE", "L$LIFECYCLE_MANAGER;")
        mv.visitFieldInsn(GETSTATIC, LIFECYCLE_ENUM, "ENABLE", "L$LIFECYCLE_ENUM;")
        mv.visitMethodInsn(INVOKEVIRTUAL, LIFECYCLE_MANAGER, "trigger", "(L$LIFECYCLE_ENUM;)V", false)

        // 调度 ACTIVE 到第一个 tick
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKEVIRTUAL, mainClass, "getServer", "()Lorg/bukkit/Server;", false)
        mv.visitMethodInsn(INVOKEINTERFACE, "org/bukkit/Server", "getScheduler", "()L$SCHEDULER;", true)
        mv.visitVarInsn(ALOAD, 0)

        mv.visitInvokeDynamicInsn(
            "run",
            "()Ljava/lang/Runnable;",
            Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false
            ),
            Type.getType("()V"),
            Handle(
                H_INVOKESTATIC,
                "$targetPkg/BlinkGeneratedMain",
                "lambda\$onEnable\$0",
                "()V",
                false
            ),
            Type.getType("()V")
        )

        mv.visitMethodInsn(INVOKEINTERFACE, SCHEDULER, "runTask", "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask;", true)
        mv.visitInsn(POP)

        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        val lambdaMv = cw.visitMethod(ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC, "lambda\$onEnable\$0", "()V", null, null)
        lambdaMv.visitCode()
        lambdaMv.visitFieldInsn(GETSTATIC, LIFECYCLE_MANAGER, "INSTANCE", "L$LIFECYCLE_MANAGER;")
        lambdaMv.visitFieldInsn(GETSTATIC, LIFECYCLE_ENUM, "ACTIVE", "L$LIFECYCLE_ENUM;")
        lambdaMv.visitMethodInsn(INVOKEVIRTUAL, LIFECYCLE_MANAGER, "trigger", "(L$LIFECYCLE_ENUM;)V", false)
        lambdaMv.visitInsn(RETURN)
        lambdaMv.visitMaxs(0, 0)
        lambdaMv.visitEnd()
    }

    private fun generateOnDisable(cw: ClassWriter, enableScript: Boolean, enableAria: Boolean) {
        val mv = cw.visitMethod(ACC_PUBLIC, "onDisable", "()V", null, null)
        mv.visitCode()

        mv.visitFieldInsn(GETSTATIC, LIFECYCLE_MANAGER, "INSTANCE", "L$LIFECYCLE_MANAGER;")
        mv.visitFieldInsn(GETSTATIC, LIFECYCLE_ENUM, "DISABLE", "L$LIFECYCLE_ENUM;")
        mv.visitMethodInsn(INVOKEVIRTUAL, LIFECYCLE_MANAGER, "trigger", "(L$LIFECYCLE_ENUM;)V", false)

        if (enableScript) {
            mv.visitFieldInsn(GETSTATIC, SCRIPT_MANAGER, "INSTANCE", "L$SCRIPT_MANAGER;")
            mv.visitMethodInsn(INVOKEVIRTUAL, SCRIPT_MANAGER, "shutdown", "()V", false)
        }

        if (enableAria) {
            mv.visitFieldInsn(GETSTATIC, ARIA_SCRIPT_MANAGER, "INSTANCE", "L$ARIA_SCRIPT_MANAGER;")
            mv.visitMethodInsn(INVOKEVIRTUAL, ARIA_SCRIPT_MANAGER, "shutdown", "()V", false)
        }

        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    fun generateLifeCycleClass(entries: List<AnnotationScanner.AwakeEntry>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        val className = "$targetPkg/BlinkGeneratedLifeCycle"

        cw.visit(V17, ACC_PUBLIC or ACC_SUPER, className, null, "java/lang/Object", null)

        val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "registerAll", "()V", null, null)
        mv.visitCode()

        for ((index, entry) in entries.withIndex()) {
            if (!entry.isStatic && !entry.hasInstance) continue

            val lambdaName = "lambda\$lifecycle\$$index"

            mv.visitFieldInsn(GETSTATIC, LIFECYCLE_MANAGER, "INSTANCE", "L$LIFECYCLE_MANAGER;")
            mv.visitFieldInsn(GETSTATIC, LIFECYCLE_ENUM, entry.lifeCycle, "L$LIFECYCLE_ENUM;")
            pushInt(mv, entry.priority)

            mv.visitInvokeDynamicInsn(
                "run",
                "()Ljava/lang/Runnable;",
                Handle(
                    H_INVOKESTATIC,
                    "java/lang/invoke/LambdaMetafactory",
                    "metafactory",
                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                    false
                ),
                Type.getType("()V"),
                Handle(
                    H_INVOKESTATIC,
                    className,
                    lambdaName,
                    "()V",
                    false
                ),
                Type.getType("()V")
            )

            mv.visitLdcInsn("${entry.ownerInternal.substringAfterLast('/')}#${entry.methodName}")

            mv.visitMethodInsn(INVOKEVIRTUAL, LIFECYCLE_MANAGER, "register",
                "(L$LIFECYCLE_ENUM;ILjava/lang/Runnable;Ljava/lang/String;)V", false)

            val lambdaMv = cw.visitMethod(ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC, lambdaName, "()V", null, null)
            lambdaMv.visitCode()
            if (entry.isStatic) {
                lambdaMv.visitMethodInsn(INVOKESTATIC, entry.ownerInternal, entry.methodName, "()V", false)
            } else {
                lambdaMv.visitFieldInsn(GETSTATIC, entry.ownerInternal, "INSTANCE", "L${entry.ownerInternal};")
                lambdaMv.visitMethodInsn(INVOKEVIRTUAL, entry.ownerInternal, entry.methodName, "()V", false)
            }
            lambdaMv.visitInsn(RETURN)
            lambdaMv.visitMaxs(0, 0)
            lambdaMv.visitEnd()
        }

        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    fun generateEventsClass(entries: List<AnnotationScanner.ListenerEntry>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        val className = "$targetPkg/BlinkGeneratedEvents"

        cw.visit(V17, ACC_PUBLIC or ACC_SUPER, className, null, "java/lang/Object", null)

        val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "registerAll", "()V", null, null)
        mv.visitCode()

        for ((index, entry) in entries.withIndex()) {
            if (!entry.isStatic && !entry.hasInstance) continue

            val lambdaName = "lambda\$event\$$index"

            mv.visitFieldInsn(GETSTATIC, EVENT_MANAGER, "INSTANCE", "L$EVENT_MANAGER;")
            mv.visitLdcInsn(Type.getObjectType(entry.eventTypeInternal))
            mv.visitFieldInsn(GETSTATIC, EVENT_PRIORITY, entry.priority, "L$EVENT_PRIORITY;")
            mv.visitInsn(if (entry.ignoreCancelled) ICONST_1 else ICONST_0)

            mv.visitInvokeDynamicInsn(
                "execute",
                "()L$EVENT_EXECUTOR;",
                Handle(
                    H_INVOKESTATIC,
                    "java/lang/invoke/LambdaMetafactory",
                    "metafactory",
                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                    false
                ),
                Type.getType("(L$LISTENER;L$EVENT_CLASS;)V"),
                Handle(
                    H_INVOKESTATIC,
                    className,
                    lambdaName,
                    "(L$LISTENER;L$EVENT_CLASS;)V",
                    false
                ),
                Type.getType("(L$LISTENER;L$EVENT_CLASS;)V")
            )

            mv.visitMethodInsn(INVOKEVIRTUAL, EVENT_MANAGER, "register",
                "(Ljava/lang/Class;L$EVENT_PRIORITY;ZL$EVENT_EXECUTOR;)V", false)

            val lambdaMv = cw.visitMethod(ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC, lambdaName,
                "(L$LISTENER;L$EVENT_CLASS;)V", null, null)
            lambdaMv.visitCode()

            lambdaMv.visitVarInsn(ALOAD, 1)
            lambdaMv.visitTypeInsn(CHECKCAST, entry.eventTypeInternal)

            if (entry.isStatic) {
                lambdaMv.visitMethodInsn(INVOKESTATIC, entry.ownerInternal, entry.methodName,
                    "(L${entry.eventTypeInternal};)V", false)
            } else {
                lambdaMv.visitVarInsn(ASTORE, 2)
                lambdaMv.visitFieldInsn(GETSTATIC, entry.ownerInternal, "INSTANCE", "L${entry.ownerInternal};")
                lambdaMv.visitVarInsn(ALOAD, 2)
                lambdaMv.visitMethodInsn(INVOKEVIRTUAL, entry.ownerInternal, entry.methodName,
                    "(L${entry.eventTypeInternal};)V", false)
            }

            lambdaMv.visitInsn(RETURN)
            lambdaMv.visitMaxs(0, 0)
            lambdaMv.visitEnd()
        }

        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun pushInt(mv: MethodVisitor, value: Int) {
        when (value) {
            -1 -> mv.visitInsn(ICONST_M1)
            in 0..5 -> mv.visitInsn(ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }
}
