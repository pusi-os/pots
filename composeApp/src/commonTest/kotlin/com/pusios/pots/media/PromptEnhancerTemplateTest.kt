package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Prompt enhancement is content-type aware: image, video, music and TTS each
 * get their own enhancement template, and the raw user text always survives.
 */
class PromptEnhancerTemplateTest {

    @Test
    fun imageTargetKeepsImageTemplate() {
        val prompt = PromptEnhancer.buildPrompt("кот на крыше", PromptTarget.Image)
        assertTrue(prompt.contains("изображения"), prompt)
        assertTrue(prompt.contains("Запрос: кот на крыше"), prompt)
    }

    @Test
    fun videoTargetUsesVideoTemplate() {
        val prompt = PromptEnhancer.buildPrompt("машина едет", PromptTarget.Video)
        assertTrue(prompt.contains("видео"), prompt)
        assertTrue(prompt.contains("движение"), prompt)
        assertTrue(prompt.contains("Запрос: машина едет"), prompt)
    }

    @Test
    fun musicTargetUsesMusicTemplate() {
        val prompt = PromptEnhancer.buildPrompt("лоуфай бит", PromptTarget.Music)
        assertTrue(prompt.contains("музык"), prompt)
        assertTrue(prompt.contains("Запрос: лоуфай бит"), prompt)
    }

    @Test
    fun ttsTargetUsesVoiceoverTemplate() {
        val prompt = PromptEnhancer.buildPrompt("Привет, запятая, мир", PromptTarget.Tts)
        assertTrue(prompt.contains("озвуч"), prompt)
        assertTrue(prompt.contains("пунктуаци"), prompt)
        // Stage directions like «запятая» must be stripped from the spoken text.
        assertTrue(prompt.contains("ремарк"), prompt)
        assertTrue(prompt.contains("Запрос: Привет, запятая, мир"), prompt)
    }

    @Test
    fun eachTargetHasItsOwnTemplate() {
        val targets = PromptTarget.entries.toList()
        val templates = targets.map { PromptEnhancer.buildPrompt("x", it) }.toSet()
        assertEquals(targets.size, templates.size)
    }

    @Test
    fun defaultTargetIsImageForBackwardCompatibility() {
        assertEquals(PromptEnhancer.buildPrompt("x", PromptTarget.Image), PromptEnhancer.buildPrompt("x"))
    }
}
