/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.tools

import kotlinx.metadata.*
import kotlinx.metadata.jvm.*
import org.objectweb.asm.tree.ClassNode

val ClassNode.kotlinMetadata: KotlinClassMetadata?
    get() {
        val metadata = findAnnotation("kotlin/Metadata", false) ?: return null
        @Suppress("UNCHECKED_CAST")
        val header = with(metadata) {
            KotlinClassHeader(
                kind = get("k") as Int?,
                metadataVersion = (get("mv") as List<Int>?)?.toIntArray(),
                bytecodeVersion = (get("bv") as List<Int>?)?.toIntArray(),
                data1 = (get("d1") as List<String>?)?.toTypedArray(),
                data2 = (get("d2") as List<String>?)?.toTypedArray(),
                extraString = get("xs") as String?,
                packageName = get("pn") as String?,
                extraInt = get("xi") as Int?
            )
        }
        return KotlinClassMetadata.read(header)
    }


fun KotlinClassMetadata?.isFileOrMultipartFacade() =
    this is KotlinClassMetadata.FileFacade || this is KotlinClassMetadata.MultiFileClassFacade

fun KotlinClassMetadata?.isSyntheticClass() = this is KotlinClassMetadata.SyntheticClass

fun KotlinClassMetadata.toClassVisibility(classNode: ClassNode): ClassVisibility? {
    var flags: Flags? = null
    var _facadeClassName: String? = null
    val members = mutableListOf<MemberVisibility>()

    fun addMember(signature: JvmMemberSignature?, flags: Flags) {
        if (signature != null) {
            members.add(MemberVisibility(signature, flags))
        }
    }

    val container: KmDeclarationContainer? = when (this) {
        is KotlinClassMetadata.Class ->
            KmClass().apply(this::accept).also { klass ->
                flags = klass.flags

                for (constructor in klass.constructors) {
                    addMember(constructor.signature, constructor.flags)
                }
            }
        is KotlinClassMetadata.FileFacade ->
            KmPackage().apply(this::accept)
        is KotlinClassMetadata.MultiFileClassPart ->
            KmPackage().apply(this::accept).also { _facadeClassName = this.facadeClassName }
        else -> null
    }

    if (container != null) {
        for (function in container.functions) {
            addMember(function.signature, function.flags)
        }

        for (property in container.properties) {
            addMember(property.getterSignature, property.getterFlags)
            addMember(property.setterSignature, property.setterFlags)

            val fieldVisibility = when {
                Flag.Property.IS_LATEINIT(property.flags) -> property.setterFlags
                property.getterSignature == null && property.setterSignature == null -> property.flags // JvmField or const case
                else -> flagsOf(Flag.IS_PRIVATE)
            }
            addMember(property.fieldSignature, fieldVisibility)
        }
    }

    return ClassVisibility(classNode.name, flags, members.associateBy { it.member }, _facadeClassName)
}

fun ClassNode.toClassVisibility() = kotlinMetadata?.toClassVisibility(this)

fun Sequence<ClassNode>.readKotlinVisibilities(): Map<String, ClassVisibility> =
    mapNotNull { it.toClassVisibility() }
        .associateBy { it.name }
        .apply {
            values.asSequence().filter { it.isCompanion }.forEach {
                val containingClassName = it.name.substringBeforeLast('$')
                getValue(containingClassName).companionVisibilities = it
            }

            values.asSequence().filter { it.facadeClassName != null }.forEach {
                getValue(it.facadeClassName!!).partVisibilities.add(it)
            }
        }
