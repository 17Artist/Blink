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

import org.objectweb.asm.*
import java.io.File

class AnnotationScanner {

    /**
     * 描述如何在生成的字节码中获得方法接收者实例。
     *
     * <p>对于 Kotlin {@code companion object}，外层类（owner 的 EnclosingClass）有一个静态字段
     * （默认名为 {@code Companion}）持有 companion 单例；方法所在类（{@code Owner$Companion}）
     * 没有 {@code INSTANCE} 字段。生成 lambda 调用方法时需要从外层类拿这个字段。
     */
    data class CompanionAccess(
        /** 持有 companion 实例的外层类 internal name，如 {@code com/foo/Bar} */
        val outerInternal: String,
        /** 外层类中持有 companion 实例的静态字段名，通常为 {@code Companion}（用户可重命名） */
        val fieldName: String,
        /** 字段描述符，如 {@code Lcom/foo/Bar$Companion;} */
        val fieldDesc: String
    )

    data class AwakeEntry(
        val ownerInternal: String,
        val methodName: String,
        val methodDesc: String,
        val isStatic: Boolean,
        val hasInstance: Boolean,
        val companionAccess: CompanionAccess?,
        val lifeCycle: String,
        val priority: Int
    )

    data class ListenerEntry(
        val ownerInternal: String,
        val methodName: String,
        val methodDesc: String,
        val isStatic: Boolean,
        val hasInstance: Boolean,
        val companionAccess: CompanionAccess?,
        val eventTypeInternal: String,
        val priority: String,
        val ignoreCancelled: Boolean
    )

    val awakeEntries = mutableListOf<AwakeEntry>()
    val listenerEntries = mutableListOf<ListenerEntry>()

    /**
     * BlinkConfig / BlinkSection 子类的 internal name 列表（含传递继承）。
     *
     * <p>这些类按「字段名 → YAML key」反射映射配置，一旦被 Proteus 重命名字段，
     * 生成的 YAML key 会变成乱码、且每次构建可能不同。因此需要在混淆时整体排除它们。
     */
    val configClasses = mutableListOf<String>()

    /**
     * 第一遍扫描收集到的 companion 关系：key 为 companion 内部类的 internal name，
     * value 为如何从外层类访问到该 companion 实例。
     */
    private val companionAccessMap = mutableMapOf<String, CompanionAccess>()

    /** 每个类的直接父类 internal name（用于解析 BlinkConfig/BlinkSection 的传递继承）。 */
    private val classSupers = mutableMapOf<String, String?>()

    fun scan(classesRoot: File) {
        val classFiles = classesRoot.walk()
            .filter { it.isFile && it.extension == "class" }
            .filter { !it.nameWithoutExtension.startsWith("BlinkGenerated") }
            .toList()

        // 第一遍：发现所有 companion 类与外层类持有它的字段，并记录类继承关系
        classFiles.forEach { collectCompanionRelations(it) }
        // 第二遍：扫描 @Awake / @AutoListener
        classFiles.forEach { scanClassFile(it) }
        // 解析 BlinkConfig / BlinkSection 子类（含传递继承）
        resolveConfigClasses()
    }

    private fun resolveConfigClasses() {
        for (name in classSupers.keys) {
            var cur = classSupers[name]
            var depth = 0
            while (cur != null && depth++ < 30) {
                if (cur in CONFIG_BASE_CLASSES) {
                    configClasses.add(name)
                    break
                }
                cur = classSupers[cur]
            }
        }
    }

    private fun collectCompanionRelations(file: File) {
        val reader = ClassReader(file.readBytes())
        reader.accept(CompanionRelationVisitor(), ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
    }

    private fun scanClassFile(file: File) {
        val reader = ClassReader(file.readBytes())
        val visitor = BlinkClassVisitor()
        reader.accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
    }

    /**
     * 第一遍 visitor：记录每个外层类的 static final 字段中类型为内部类（{@code $}） 的引用。
     * Kotlin companion 编译为：{@code static final Foo$Companion Companion} 字段（字段名可由
     * 用户重命名，如 {@code companion object MyCompanion} 会得到 {@code static final
     * Foo$MyCompanion MyCompanion}）。
     */
    private inner class CompanionRelationVisitor : ClassVisitor(Opcodes.ASM9) {
        private var ownerClassName = ""

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
            ownerClassName = name
            classSupers[name] = superName
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            val isStatic = (access and Opcodes.ACC_STATIC) != 0
            val isFinal = (access and Opcodes.ACC_FINAL) != 0
            // 只考虑 public/private static final 字段；类型必须是引用类型且形如 LOuter$X;
            if (!isStatic || !isFinal) return null
            if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) return null
            val fieldTypeInternal = descriptor.substring(1, descriptor.length - 1)
            // 字段类型必须是当前类的内部类（companion 总是 nested）
            if (!fieldTypeInternal.startsWith("$ownerClassName$")) return null
            // 不要覆盖已记录的（按文件遍历顺序优先保留第一个，正常情况下只会有一个 companion）
            companionAccessMap.putIfAbsent(
                fieldTypeInternal,
                CompanionAccess(ownerClassName, name, descriptor)
            )
            return null
        }
    }

    private inner class BlinkClassVisitor : ClassVisitor(Opcodes.ASM9) {
        private var className = ""
        private var hasInstanceField = false

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
            className = name
            hasInstanceField = false
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            if (name == "INSTANCE" && (access and Opcodes.ACC_STATIC) != 0 && descriptor == "L$className;") {
                hasInstanceField = true
            }
            return null
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
            val isStatic = (access and Opcodes.ACC_STATIC) != 0
            return BlinkMethodVisitor(className, name, descriptor, isStatic, this)
        }

        fun isObjectSingleton() = hasInstanceField
    }

    private inner class BlinkMethodVisitor(
        private val ownerInternal: String,
        private val methodName: String,
        private val methodDesc: String,
        private val isStatic: Boolean,
        private val classVisitor: BlinkClassVisitor
    ) : MethodVisitor(Opcodes.ASM9) {

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            return when (descriptor) {
                AWAKE_DESC -> AwakeAnnotationVisitor()
                AUTO_LISTENER_DESC -> AutoListenerAnnotationVisitor()
                else -> null
            }
        }

        private inner class AwakeAnnotationVisitor : AnnotationVisitor(Opcodes.ASM9) {
            private var lifeCycle = "ENABLE"
            private var priority = 0

            override fun visitEnum(name: String, descriptor: String, value: String) {
                if (name == "value") lifeCycle = value
            }

            override fun visit(name: String, value: Any) {
                if (name == "priority" && value is Int) priority = value
            }

            override fun visitEnd() {
                if (methodDesc != "()V") {
                    System.err.println("[Blink] WARNING: @Awake method $ownerInternal#$methodName has descriptor $methodDesc, expected ()V — skipping")
                    return
                }
                val companion = companionAccessMap[ownerInternal]
                awakeEntries.add(AwakeEntry(
                    ownerInternal, methodName, methodDesc, isStatic,
                    classVisitor.isObjectSingleton(), companion, lifeCycle, priority
                ))
            }
        }

        private inner class AutoListenerAnnotationVisitor : AnnotationVisitor(Opcodes.ASM9) {
            private var priority = "NORMAL"
            private var ignoreCancelled = false

            override fun visitEnum(name: String, descriptor: String, value: String) {
                if (name == "priority") priority = value
            }

            override fun visit(name: String, value: Any) {
                if (name == "ignoreCancelled" && value is Boolean) ignoreCancelled = value
            }

            override fun visitEnd() {
                val eventType = extractFirstParamType(methodDesc)
                if (eventType == null) {
                    System.err.println("[Blink] WARNING: @AutoListener method $ownerInternal#$methodName has no valid Event parameter — skipping")
                    return
                }
                val companion = companionAccessMap[ownerInternal]
                listenerEntries.add(ListenerEntry(
                    ownerInternal, methodName, methodDesc, isStatic,
                    classVisitor.isObjectSingleton(), companion, eventType, priority, ignoreCancelled
                ))
            }
        }
    }

    companion object {
        private const val AWAKE_DESC = "Lpriv/seventeen/artist/blink/lifecycle/Awake;"
        private const val AUTO_LISTENER_DESC = "Lpriv/seventeen/artist/blink/event/AutoListener;"

        /** BlinkConfig / BlinkSection 的 internal name（编译期、relocate 之前为规范包名）。 */
        private val CONFIG_BASE_CLASSES = setOf(
            "priv/seventeen/artist/blink/config/BlinkConfig",
            "priv/seventeen/artist/blink/config/BlinkSection"
        )

        fun extractFirstParamType(desc: String): String? {
            val start = desc.indexOf('(')
            val end = desc.indexOf(')')
            if (start < 0 || end < 0 || end <= start + 1) return null
            val params = desc.substring(start + 1, end)
            if (params.startsWith('L')) {
                val semi = params.indexOf(';')
                if (semi > 0) return params.substring(1, semi)
            }
            return null
        }
    }
}
