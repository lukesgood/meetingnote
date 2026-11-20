package com.meetingnote.app

/**
 * 용어 사전 기반 녹취록 교정: 용어와 발음이 비슷하게 잘못 인식된 표기를 용어로 바꾼다.
 *  예) 삼촌리처치 → 삼성리서치, 윤관이는 → 윤관희는, 데이터브리스트 → 데이터브릭스, 삼성 카드 → 삼성카드
 *
 * LLM을 쓰지 않는 규칙 방식 (2026-11-20 PC 비교: 31분 회의에서 규칙 16곳 모두 정확·오교정 0,
 * Gemma 4 제안 방식은 1곳, Gemma 확인 방식은 15곳·오교정을 하나도 막지 못함, 시간 110초/30초 대 1초 미만).
 *  1) 낱말 1~3개 창마다 조사를 떼거나 붙인 형태를 용어와 자모 단위로 비교 (편집 거리 유사도)
 *  2) 용어의 앞부분만 같고 남은 2음절 이상이 전혀 다르면 제외 ('클라우드 아닌가' ≠ '클라우드프론트')
 *  3) 이미 용어를 포함한 낱말은 건드리지 않음 ('경쟁력인', '본부장님께')
 *  4) 겹치는 후보는 점수가 높은 것, 같으면 짧은 창 하나만 적용. 조사·문장부호는 그대로 둔다.
 * 바꾸는 결과는 항상 사용자가 입력한 용어이므로 사전에 없는 내용으로 바뀌지 않는다.
 */
object TermCorrector {
    data class Fix(val from: String, val to: String)
    data class Result(val lines: List<TranscriptLine>, val fixes: List<Fix>)

    private val PARTS = listOf("이하고", "이랑", "이는", "에서", "으로", "하고", "은", "는", "이", "가", "을", "를",
        "의", "에", "로", "와", "과", "도", "만", "님", "쪽", "께", "나")
    private val nonWord = Regex("[^\\p{L}\\p{N}]")

    fun parseTerms(vocabulary: String): List<String> =
        vocabulary.split(',', '\n').map { it.trim() }.filter { it.replace(" ", "").length >= 2 }.distinct()

    fun correct(lines: List<TranscriptLine>, terms: List<String>): Result {
        if (terms.isEmpty()) return Result(lines, emptyList())
        val fixes = ArrayList<Fix>()
        val out = lines.map { line ->
            val (text, f) = correctLine(line.text, terms)
            fixes += f
            if (f.isEmpty()) line else line.copy(text = text)
        }
        return Result(out, fixes)
    }

    private class Cand(val i: Int, val n: Int, val term: String, val score: Double, val raw: String, val part: String, val keep: Boolean)

    private fun correctLine(text: String, terms: List<String>): Pair<String, List<Fix>> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val cands = ArrayList<Cand>()
        for (i in words.indices) for (n in 1..3) {
            if (i + n > words.size) break
            val raw = words.subList(i, i + n).joinToString(" ")
            val full = raw.replace(nonWord, "")
            if (full.isEmpty()) continue
            val variants = listOf(full to "") + PARTS.filter { full.endsWith(it) && full.length > it.length }
                .map { full.dropLast(it.length) to it }
            for (t in terms) {
                val nt = t.replace(" ", "")
                if (full.contains(nt)) {
                    // 이미 맞게 쓴 낱말: 그대로 두고, 겹치는 다른 창이 이 자리를 고치지 못하게 막음
                    if (n == 1) { cands += Cand(i, n, t, 3.0, raw, "", keep = true); continue }
                    // 용어를 품은 넓은 창 ('그 삼성 카드'): 띄어쓰기만 다른 좁은 창이 따로 처리
                    if (variants.none { it.first == nt }) continue
                }
                var best: Triple<Double, String, String>? = null
                for ((core, part) in variants) {
                    if (core.length < 2 || kotlin.math.abs(core.length - nt.length) > 2) continue
                    val s = when {
                        core == nt -> if (raw.contains(' ') || raw.trimEnd('.', ',', '?', '!') != t + part) 2.0 else continue
                        core.equals(nt, ignoreCase = true) -> 2.0                        // 대소문자만 다름: msp → MSP
                        nt.length < 3 -> continue      // 두 글자 용어는 띄어쓰기·대소문자만 (상무 ↔ 사무 같은 흔한 낱말 보호)
                        else -> score(core, nt) ?: continue
                    }
                    val thr = if (nt.length >= 4) 0.6 else 0.7
                    if (s >= thr && (best == null || s > best.first || (s == best.first && core.length > best.second.length)))
                        best = Triple(s, core, part)
                }
                best?.let { cands += Cand(i, n, t, it.first, raw, it.third, keep = false) }
            }
        }
        // 겹치는 후보 중 하나만: 점수 높은 것, 같으면 짧은 창
        val chosen = ArrayList<Cand>()
        for (c in cands.sortedWith(compareByDescending<Cand> { it.score }.thenBy { it.n })) {
            if (chosen.none { o -> c.i < o.i + o.n && o.i < c.i + c.n }) chosen += c
        }
        val apply = chosen.filter { !it.keep }.sortedByDescending { it.i }
        if (apply.isEmpty()) return text to emptyList()
        val w = words.toMutableList()
        val fixes = ArrayList<Fix>()
        for (c in apply) {
            val punct = c.raw.takeLastWhile { it in ".,?!" }
            val rep = c.term + josa(c.term, c.part) + punct
            if (rep == c.raw) continue
            for (k in 0 until c.n) w.removeAt(c.i)
            w.add(c.i, rep)
            fixes += Fix(c.raw, rep)
        }
        return w.joinToString(" ") to fixes.reversed()
    }

    /** 받침 유무에 맞게 조사 형태를 바꿈: 김민숙은 → 김민수는, 박지형을 → 박지영을 */
    private val JOSA = listOf("은" to "는", "이" to "가", "을" to "를", "과" to "와", "으로" to "로", "이랑" to "랑")
    private fun josa(term: String, part: String): String {
        val c = term.last().code - 0xAC00
        if (part.isEmpty() || c !in 0 until 11172) return part     // 조사 없음, 영문 용어
        val batchim = c % 28 != 0
        val pair = JOSA.firstOrNull { part == it.first || part == it.second } ?: return part
        return if (batchim) pair.first else pair.second
    }

    /** 자모 유사도. 용어의 앞부분만 같고 남은 부분(2음절 이상)이 전혀 다르면 null */
    private fun score(core: String, term: String): Double? {
        val a = jamo(core); val b = jamo(term)
        val s = 1.0 - editDistance(a, b).toDouble() / maxOf(a.size, b.size)
        var l = 0
        while (l < minOf(a.size, b.size) && a[l] == b[l]) l++
        val tail = b.subList(l, b.size)
        if (l >= b.size / 2.0 && tail.size >= 4) {
            val rest = a.subList(l, a.size)
            if (1.0 - editDistance(rest, tail).toDouble() / maxOf(rest.size, tail.size) < 0.34) return null
        }
        return s
    }

    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    internal fun jamo(s: String): List<Char> {
        val out = ArrayList<Char>()
        for (ch in s.lowercase()) {
            if (ch.isWhitespace()) continue
            val c = ch.code - 0xAC00
            if (c in 0 until 11172) {
                out += CHO[c / 588]; out += JUNG[(c % 588) / 28]
                if (c % 28 != 0) out += JONG[c % 28]
            } else out += ch
        }
        return out
    }

    private fun editDistance(a: List<Char>, b: List<Char>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1); cur[0] = i
            for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.size]
    }
}
