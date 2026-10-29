package com.example.flac2mp3

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class CueException(message: String) : Exception(message)

data class CueTrack(
    val number: Int,
    val title: String?,
    val performer: String?,
    val startSec: Double,
    /** null = runs to the end of the audio file. */
    val endSec: Double?,
)

data class CueSheet(
    val album: String?,
    val performer: String?,
    val date: String?,
    val genre: String?,
    val tracks: List<CueTrack>,
)

/** Minimal CUE sheet parser for single-file albums (one FILE, many TRACKs). Pure Kotlin, no Android deps. */
object CueParser {

    private class TrackBuilder(val number: Int, val audio: Boolean) {
        var title: String? = null
        var performer: String? = null
        var start: Double? = null
    }

    fun parse(text: String): CueSheet {
        var album: String? = null
        var albumPerformer: String? = null
        var date: String? = null
        var genre: String? = null
        var files = 0
        val builders = mutableListOf<TrackBuilder>()
        var current: TrackBuilder? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('﻿')
            if (line.isEmpty()) continue
            val cmd = line.substringBefore(' ').uppercase()
            val rest = line.substringAfter(' ', "").trim()
            when (cmd) {
                "FILE" -> files++
                "TITLE" -> {
                    val v = unquote(rest)
                    val c = current
                    if (c == null) album = v else c.title = v
                }
                "PERFORMER" -> {
                    val v = unquote(rest)
                    val c = current
                    if (c == null) albumPerformer = v else c.performer = v
                }
                "TRACK" -> {
                    val parts = rest.split(Regex("\\s+"))
                    val number = parts.firstOrNull()?.toIntOrNull() ?: (builders.size + 1)
                    val type = parts.getOrNull(1)?.uppercase()
                    val tb = TrackBuilder(number, audio = type == null || type == "AUDIO")
                    builders += tb
                    current = tb
                }
                "INDEX" -> {
                    val parts = rest.split(Regex("\\s+"))
                    val c = current
                    if (c != null && parts.size >= 2 && parts[0].toIntOrNull() == 1) {
                        c.start = parseTime(parts[1])
                    }
                }
                "REM" -> if (current == null) {
                    val key = rest.substringBefore(' ').uppercase()
                    val value = unquote(rest.substringAfter(' ', ""))
                    if (key == "DATE") date = value
                    if (key == "GENRE") genre = value
                }
            }
        }

        if (files > 1) {
            throw CueException("This CUE sheet lists several files. Only single-file albums can be split.")
        }
        val usable = builders.filter { it.audio && it.start != null }
        if (usable.isEmpty()) throw CueException("No tracks found in the CUE sheet.")

        val tracks = usable.mapIndexed { i, b ->
            val start = b.start!!
            val end = usable.getOrNull(i + 1)?.start
            if (end != null && end <= start) {
                throw CueException("Track ${b.number} does not start before the next track in the CUE sheet.")
            }
            CueTrack(b.number, b.title, b.performer, start, end)
        }
        return CueSheet(album, albumPerformer, date, genre, tracks)
    }

    /** mm:ss:ff, where ff is 1/75 s. */
    private fun parseTime(s: String): Double? {
        val m = Regex("^(\\d+):(\\d{1,2}):(\\d{1,2})$").matchEntire(s) ?: return null
        val (mm, ss, ff) = m.destructured
        return mm.toInt() * 60.0 + ss.toInt() + ff.toInt() / 75.0
    }

    private fun unquote(s: String): String? {
        val t = s.trim()
        val v = if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t.substring(1, t.length - 1) else t
        return v.takeIf { it.isNotBlank() }
    }

    /** CUE files come as UTF-8 (with or without BOM), UTF-16, or legacy Windows-1252. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            b = b.copyOfRange(3, b.size)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) {
            String(b, charset("windows-1252"))
        }
    }
}
