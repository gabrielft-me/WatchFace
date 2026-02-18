package com.example.myapplication.data.util

private val urlRegex = Regex("""https?://[^\s)]+""")

fun extractMeetingLink(location: String?, description: String?): String? {
    val text = listOfNotNull(location, description).joinToString(" ").trim()
    if (text.isEmpty()) return null
    return urlRegex.find(text)?.value
}
