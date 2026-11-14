package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract drift protection: primitive-only fields may receive objects or
 * arrays as the provider evolves the payload. A non-primitive element is
 * skipped — the model stays in the list (a whole-model loss is what the
 * v3.12.0 «Element class … is not JSONPrimitive» crash did), primitives
 * around it keep their meaning.
 */
class MediaNonPrimitiveSkipTest {

    private val provider = PlusVibeMediaProvider()

    @Test
    fun nonPrimitiveInputTypesElementIsSkippedModelSurvives() {
        val catalog = """
            {
              "models": [
                {
                  "model": "drift-input", "kind": "image",
                  "inputTypes": ["image", {"odd": true}, ["nested"]]
                }
              ]
            }
        """.trimIndent()
        val model = provider.parseCatalog(catalog).single { it.id == "drift-input" }
        assertTrue(model.acceptsImageReference, "primitive «image» survives the drift")
        assertFalse(model.acceptsVideoReference)
        assertFalse(model.acceptsAudioReference)
    }

    @Test
    fun nonPrimitiveParamOptionsAreSkippedModelSurvives() {
        val catalog = """
            {
              "models": [
                {
                  "model": "drift-params", "kind": "image",
                  "params": [
                    {
                      "key": "mode", "label": "Режим",
                      "options": ["t2i", {"odd": 1}, "i2i"],
                      "optionLabels": ["Текст", ["нет"], "Картинка"]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val model = provider.parseCatalog(catalog).single { it.id == "drift-params" }
        val param = model.params.single { it.key == "mode" }
        assertEquals(
            listOf(MediaCapValue("t2i", "Текст"), MediaCapValue("i2i", "Картинка")),
            param.options,
        )
    }
}
