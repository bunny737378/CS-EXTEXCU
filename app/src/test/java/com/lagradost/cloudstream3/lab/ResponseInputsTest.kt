package com.lagradost.cloudstream3.lab

import org.junit.Assert.*
import org.junit.Test
import com.lagradost.cloudstream3.lab.ManualProviderRunner.Method

class ResponseInputsTest {
    @Test fun searchPreservesOpaqueJsonAndIgnoresPosterHeaders() {
        val json = """{"items":[{"name":"Slime","url":"{\"session\":\"abc\",\"sessionDate\":0}","posterUrl":"image","posterHeaders":{"Cookie":"private"}}]}"""
        val choice = ResponseInputs.choices(json, Method.PAGED_SEARCH).single()
        assertEquals("{\"session\":\"abc\",\"sessionDate\":0}", choice.value)
        assertEquals(Method.LOAD, choice.method)
    }
    @Test fun movieAndEpisodesUseOnlyPlaybackData() {
        assertEquals("opaque movie", ResponseInputs.choices("""{"dataUrl":"opaque movie","url":"wrong"}""", Method.LOAD).single().value)
        val choices = ResponseInputs.choices("""{"episodes":{"Subbed":[{"name":"One","data":"opaque episode"} ]},"url":"wrong"}""", Method.LOAD)
        assertEquals("opaque episode", choices.single().value)
        assertEquals(Method.LOAD_LINKS, choices.single().method)
        assertTrue(ResponseInputs.choices("null", Method.LOAD).isEmpty())
    }
}
