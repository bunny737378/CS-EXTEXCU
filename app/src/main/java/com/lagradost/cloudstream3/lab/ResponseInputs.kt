package com.lagradost.cloudstream3.lab

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.lab.ManualProviderRunner.Method

/** Extracts only public provider input fields, never poster URLs, headers or other metadata. */
object ResponseInputs {
    data class Choice(val label: String, val value: String, val method: Method)

    fun choices(json: String, method: Method): List<Choice> {
        val root = mapper.readTree(json) ?: return emptyList()
        fun JsonNode.string(key: String) = get(key)?.takeIf { it.isTextual }?.asText()
        fun results(node: JsonNode): List<Choice> = when {
            node.isArray -> node.flatMap { results(it) }
            node.isObject && node.has("items") -> results(node["items"])
            node.isObject && node.has("list") -> results(node["list"])
            else -> node.string("url")?.takeIf { it.isNotBlank() }?.let {
                listOf(Choice(node.string("name") ?: "Result", it, Method.LOAD))
            }.orEmpty()
        }
        if (method in listOf(Method.SEARCH, Method.PAGED_SEARCH, Method.QUICK_SEARCH, Method.MAIN_PAGE)) {
            return results(root)
        }
        if (method != Method.LOAD) return emptyList()
        root.string("dataUrl")?.takeIf { it.isNotBlank() }?.let {
            return listOf(Choice(root.string("name") ?: "Movie / live stream", it, Method.LOAD_LINKS))
        }
        fun episodes(node: JsonNode, path: String): List<Choice> = when {
            node.isArray -> node.flatMapIndexed { index, item -> episodes(item, "$path · ${index + 1}") }
            node.isObject && node.has("data") -> node.string("data")?.takeIf { it.isNotBlank() }?.let {
                listOf(Choice(node.string("name") ?: path, it, Method.LOAD_LINKS))
            }.orEmpty()
            node.isObject -> node.fields().asSequence().flatMap { (key, value) -> episodes(value, "$path · $key") }.toList()
            else -> emptyList()
        }
        return root.get("episodes")?.let { episodes(it, "Episode") }.orEmpty()
    }
}
