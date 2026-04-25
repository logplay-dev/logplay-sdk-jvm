package org.zeplinko.logplay.client.codec

import java.lang.reflect.ParameterizedType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TypeTokenTest {

    @Test
    fun `captures generic type via anonymous subclass`() {
        val token = object : TypeToken<List<String>>() {}
        val type = token.type
        assertThat(type).isInstanceOf(ParameterizedType::class.java)
        type as ParameterizedType
        // Kotlin emits java.util.List for the read-only List interface in bytecode.
        assertThat((type.rawType as Class<*>).name).isEqualTo("java.util.List")
        // Kotlin's declaration-site variance emits the type arg as `? extends String`,
        // not `String`. The captured Type is still understood by Jackson — assert it stringifies
        // sensibly rather than match an exact reflection class.
        assertThat(type.actualTypeArguments[0].typeName).contains("String")
    }

    @Test
    fun `of() wraps a raw class`() {
        val token = TypeToken.of(String::class.java)
        assertThat(token.type).isEqualTo(String::class.java)
    }

    @Test
    fun `nested generics are captured`() {
        val token = object : TypeToken<Map<String, List<Int>>>() {}
        assertThat(token.type).isInstanceOf(ParameterizedType::class.java)
        val pt = token.type as ParameterizedType
        assertThat((pt.rawType as Class<*>).name).isEqualTo("java.util.Map")
        assertThat(pt.actualTypeArguments).hasSize(2)
        assertThat(pt.actualTypeArguments[0].typeName).contains("String")
        assertThat(pt.actualTypeArguments[1].typeName).contains("List")
    }
}
