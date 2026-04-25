package org.zeplinko.logplay.client.codec

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Captures a generic [Type] at compile time so payloads with generic type parameters can be
 * deserialized correctly.
 *
 * Usage from Kotlin:
 * ```
 * val token = object : TypeToken<List<MyDto>>() {}
 * ```
 *
 * Usage from Java:
 * ```
 * TypeToken<List<MyDto>> token = new TypeToken<List<MyDto>>() {};
 * ```
 *
 * For non-generic types prefer [TypeToken.of].
 */
public abstract class TypeToken<T> {
    public val type: Type

    protected constructor() {
        val superclass = javaClass.genericSuperclass
        require(superclass is ParameterizedType) {
            "TypeToken must be created as an anonymous subclass with a parameterized type"
        }
        type = superclass.actualTypeArguments[0]
    }

    private constructor(rawType: Type) {
        type = rawType
    }

    public companion object {
        /** Wraps a raw [Class] as a [TypeToken]. */
        @JvmStatic public fun <T> of(clazz: Class<T>): TypeToken<T> = ClassTypeToken(clazz)
    }

    private class ClassTypeToken<T>(rawType: Class<T>) : TypeToken<T>(rawType as Type)
}
